package com.zhishiku.kb.ai.model;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.publisher.Flux;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 每次订阅拥有独立状态；只在完整 SSE 终结链后正常完成。 */
public class OpenAiSseDecoder {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final int eventLimit;
    private final int responseLimit;
    private final int answerLimit;

    public OpenAiSseDecoder(ChatModelProperties properties) {
        eventLimit = properties.getMaxEventBytes();
        responseLimit = properties.getMaxResponseBytes();
        answerLimit = properties.getMaxAnswerCodepoints();
    }

    public Flux<ModelEvent> decode(Flux<DataBuffer> buffers) {
        return Flux.defer(() -> {
            var state = new State();
            return buffers.concatMapIterable(state::feed, 1)
                    .<ModelEvent>handle((event, sink) -> {
                        if (event instanceof ModelEvent.Failure failure) {
                            sink.error(failure.error());
                        } else {
                            sink.next(event);
                        }
                    })
                    .takeUntil(event -> event == ModelEvent.Signal.END)
                    .concatWith(Flux.defer(() -> state.done ? Flux.empty()
                            : Flux.error(new ChatModelFailure(ChatModelFailure.Kind.INCOMPLETE_STREAM))))
                    .doFinally(signal -> state.clear())
                    .doOnDiscard(DataBuffer.class, DataBufferUtils::release);
        });
    }

    private final class State {
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private final StringBuilder data = new StringBuilder();
        private boolean hasData;
        private boolean skipLf;
        private boolean eventBoundary;
        private boolean firstLine = true;
        private boolean stop;
        private boolean hasText;
        private boolean done;
        private int eventBytes;
        private long responseBytes;
        private long answerCodepoints;
        private ModelEvent.FinalUsage candidate;

        private synchronized List<ModelEvent> feed(DataBuffer buffer) {
            var output = new ArrayList<ModelEvent>();
            try {
                responseBytes += buffer.readableByteCount();
                if (responseBytes > responseLimit) {
                    throw failure(ChatModelFailure.Kind.PAYLOAD_LIMIT);
                }
                while (buffer.readableByteCount() > 0 && !done) {
                    int value = buffer.read() & 0xff;
                    if (skipLf && value == '\n') {
                        countEventByte();
                        skipLf = false;
                        continue;
                    }
                    skipLf = false;
                    if (eventBoundary) {
                        eventBytes = 0;
                        eventBoundary = false;
                    }
                    countEventByte();
                    if (value == '\r' || value == '\n') {
                        processLine(output, value == '\r');
                        skipLf = value == '\r';
                    } else {
                        line.write(value);
                    }
                }
                return output;
            } catch (ChatModelFailure error) {
                // 同一网络分包内先前已完成的合法事件仍按序交付，再报告后续错误。
                output.add(new ModelEvent.Failure(error));
                return output;
            } finally {
                DataBufferUtils.release(buffer);
            }
        }

        private void countEventByte() {
            eventBytes++;
            if (eventBytes > eventLimit) {
                throw failure(ChatModelFailure.Kind.PAYLOAD_LIMIT);
            }
        }

        private void processLine(List<ModelEvent> output, boolean carriageReturn) {
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(line.toByteArray())).toString();
            } catch (CharacterCodingException error) {
                throw failure(ChatModelFailure.Kind.PROTOCOL_INVALID);
            }
            line.reset();
            ChatJson.text(text);
            if (firstLine && text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            firstLine = false;
            if (text.isEmpty()) {
                // CR 可立即分隔事件；发布前先为可能的 LF 留出空间，实际 LF 到达时再计数一次。
                if (carriageReturn && eventBytes >= eventLimit) {
                    throw failure(ChatModelFailure.Kind.PAYLOAD_LIMIT);
                }
                if (hasData && !data.toString().isBlank()) {
                    processEvent(data.toString(), output);
                }
                data.setLength(0);
                hasData = false;
                // CR 已能分隔事件；若后续还有 LF，先归入原事件再重置，分包方式不影响计数。
                eventBoundary = true;
                return;
            }
            int colon = text.indexOf(':');
            String name = colon < 0 ? text : text.substring(0, colon);
            if ("data".equals(name)) {
                String value = colon < 0 ? "" : text.substring(colon + 1);
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                if (hasData) {
                    data.append('\n');
                }
                data.append(value);
                hasData = true;
            }
        }

        private void processEvent(String payload, List<ModelEvent> output) {
            if ("[DONE]".equals(payload.trim())) {
                if (!stop || !hasText) {
                    throw failure(ChatModelFailure.Kind.INCOMPLETE_STREAM);
                }
                done = true;
                if (candidate != null) {
                    output.add(candidate);
                }
                output.add(ModelEvent.Signal.END);
                return;
            }
            JsonNode root;
            try {
                root = JSON.readTree(payload);
            } catch (IOException | RuntimeException error) {
                throw failure(ChatModelFailure.Kind.PROTOCOL_INVALID);
            }
            if (root == null || !root.isObject() || root.has("error")) {
                throw failure(ChatModelFailure.Kind.PROTOCOL_INVALID);
            }
            unsupported(root);
            var choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.size() > 1) {
                throw failure(ChatModelFailure.Kind.PROTOCOL_INVALID);
            }
            String text = null;
            String finish = null;
            if (!choices.isEmpty()) {
                var choice = choices.get(0);
                unsupported(choice);
                var index = choice.get("index");
                var delta = choice.get("delta");
                if (index == null || !index.isIntegralNumber() || !index.canConvertToInt() || index.intValue() != 0
                        || delta == null || !delta.isObject()) {
                    throw failure(ChatModelFailure.Kind.PROTOCOL_INVALID);
                }
                unsupported(delta);
                var role = delta.get("role");
                if (role != null && !role.isNull() && (!role.isTextual() || !"assistant".equals(role.textValue()))) {
                    throw failure(ChatModelFailure.Kind.UNSUPPORTED_OUTPUT);
                }
                var content = delta.get("content");
                if (content != null && !content.isNull()) {
                    if (!content.isTextual()) {
                        throw failure(ChatModelFailure.Kind.UNSUPPORTED_OUTPUT);
                    }
                    text = content.textValue();
                    ChatJson.text(text);
                }
                var reason = choice.get("finish_reason");
                if (reason != null && !reason.isNull()) {
                    if (!reason.isTextual()) {
                        throw failure(ChatModelFailure.Kind.PROTOCOL_INVALID);
                    }
                    finish = reason.textValue();
                    if (!"stop".equals(finish)) {
                        throw failure("length".equals(finish) ? ChatModelFailure.Kind.OUTPUT_TRUNCATED
                                : ChatModelFailure.Kind.UNSUPPORTED_OUTPUT);
                    }
                    if (stop) {
                        throw failure(ChatModelFailure.Kind.PROTOCOL_INVALID);
                    }
                }
            }
            if (text != null && !text.isEmpty()) {
                if (stop) {
                    throw failure(ChatModelFailure.Kind.PROTOCOL_INVALID);
                }
                answerCodepoints += text.codePointCount(0, text.length());
                if (answerCodepoints > answerLimit) {
                    throw failure(ChatModelFailure.Kind.PAYLOAD_LIMIT);
                }
                candidate = null;
                hasText = true;
            }
            var usage = root.get("usage");
            if (usage != null && !usage.isNull()) {
                if (!usage.isObject()) {
                    throw failure(ChatModelFailure.Kind.PROTOCOL_INVALID);
                }
                candidate = new ModelEvent.FinalUsage(number(usage.get("prompt_tokens")),
                        number(usage.get("completion_tokens")));
            }
            if (choices.isEmpty() && (usage == null || usage.isNull())) {
                throw failure(ChatModelFailure.Kind.PROTOCOL_INVALID);
            }
            if (text != null && !text.isEmpty()) {
                output.add(new ModelEvent.Delta(text));
            }
            if (finish != null) {
                stop = true;
            }
            output.add(ModelEvent.Signal.ACTIVITY);
        }

        private synchronized void clear() {
            // 总期限或客户端取消可能来自另一线程，不能在解析中途清空共享缓冲。
            line.reset();
            data.setLength(0);
            candidate = null;
        }
    }

    private static long number(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 0) {
            throw failure(ChatModelFailure.Kind.PROTOCOL_INVALID);
        }
        return node.longValue();
    }

    private static void unsupported(JsonNode node) {
        for (String field : List.of("tool_calls", "function_call", "refusal", "reasoning_content", "reasoning",
                "thinking", "audio", "images")) {
            var value = node.get(field);
            if (value != null && !value.isNull() && !(value.isTextual() && value.textValue().isEmpty())
                    && !((value.isArray() || value.isObject()) && value.isEmpty())) {
                throw failure(ChatModelFailure.Kind.UNSUPPORTED_OUTPUT);
            }
        }
    }

    private static ChatModelFailure failure(ChatModelFailure.Kind kind) {
        return new ChatModelFailure(kind);
    }
}
