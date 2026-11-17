package com.zhishiku.kb.answereval;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/** 单候选普通文本裁判，不自动修复协议、转换工具输出或重试。 */
public final class JudgeClient {
    public static final int BODY_LIMIT = 2 * 1024 * 1024;
    private final JudgeConfig config;
    private final String key;
    private final HttpExchange http;
    private final LongSupplier clock;
    private final Deadline batchDeadline;
    private final List<JudgmentModel.Call> calls = new ArrayList<>();
    private int submitted;
    private long estimatedTokens;

    public JudgeClient(JudgeConfig config, String key, Transport transport, LongSupplier clock) {
        if (key == null || key.isBlank() || key.length() > 8192 || !key.equals(key.strip())
                || key.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new EvaluationFailure(EvaluationFailure.Code.CONFIGURATION);
        }
        this.config = config;
        this.key = key;
        this.clock = clock;
        http = new HttpExchange(transport);
        batchDeadline = new Deadline(clock, config.batchTimeoutMs());
    }

    public <T> T assess(String view, List<DataModel.Message> messages, Class<T> type) {
        batchDeadline.check();
        byte[] request = JudgeViews.request(config, messages);
        long estimate = (long) Math.ceil(StrictJson.bytes(messages).length / config.inputBytesPerToken()) + 16L * messages.size();
        if (request.length > BODY_LIMIT || estimate + config.maxOutputTokens() + config.safetyMarginTokens() > config.windowTokens()
                || submitted >= config.maxRequests() || estimate > config.maxEstimatedInputTokens() - estimatedTokens) {
            throw new EvaluationFailure(EvaluationFailure.Code.LIMIT);
        }
        String digest = StrictJson.sha(request);
        submitted++;
        estimatedTokens += estimate;
        int index = calls.size();
        calls.add(new JudgmentModel.Call(view, digest, "SUBMITTED", new DataModel.Usage(null, null)));
        int millis = (int) Math.max(1, Math.min(config.requestTimeoutMs(), batchDeadline.remainingNanos() / 1_000_000));
        try {
            return http.execute(new Transport.Request(Endpoint.api(Endpoint.validate(config.baseUrl(), config.allowHttpLoopback()),
                            "/chat/completions"), "POST", Map.of("Authorization", "Bearer " + key,
                            "Content-Type", "application/json; charset=UTF-8", "Accept", "application/json",
                            "Accept-Encoding", "identity"), request),
                    new Deadline(clock, millis), (response, deadline) -> {
                        HttpExchange.contentType(response, Set.of(200), Set.of("application/json"));
                        JsonNode root = StrictJson.parse(StrictJson.bounded(response.body(), BODY_LIMIT, deadline::check));
                        DataModel.Usage usage = usage(root.path("usage"));
                        calls.set(index, new JudgmentModel.Call(view, digest, "SUBMITTED", usage));
                        JsonNode choices = root.path("choices");
                        if (!choices.isArray() || choices.size() != 1 || !"stop".equals(choices.get(0).path("finish_reason").asText())) {
                            throw protocol();
                        }
                        JsonNode message = choices.get(0).path("message");
                        if (!"assistant".equals(message.path("role").asText()) || !message.path("content").isTextual()) {
                            throw protocol();
                        }
                        for (String unsupported : List.of("tool_calls", "function_call", "refusal", "reasoning_content", "reasoning", "thinking", "audio")) {
                            if (message.hasNonNull(unsupported)) {
                                throw protocol();
                            }
                        }
                        T result = StrictJson.read(message.get("content").textValue().getBytes(java.nio.charset.StandardCharsets.UTF_8), type);
                        calls.set(index, new JudgmentModel.Call(view, digest, "COMPLETE", usage));
                        return result;
                    });
        } catch (EvaluationFailure failure) {
            calls.set(index, new JudgmentModel.Call(view, digest, "FAILED", calls.get(index).usage()));
            throw failure;
        }
    }

    public int submitted() {
        return submitted;
    }

    public long estimatedTokens() {
        return estimatedTokens;
    }

    public List<JudgmentModel.Call> callsSince(int start) {
        return List.copyOf(calls.subList(start, calls.size()));
    }

    private static DataModel.Usage usage(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return new DataModel.Usage(null, null);
        }
        return new DataModel.Usage(BusinessClient.integer(node, "prompt_tokens", 0),
                BusinessClient.integer(node, "completion_tokens", 0));
    }

    private static EvaluationFailure protocol() {
        return new EvaluationFailure(EvaluationFailure.Code.PROTOCOL);
    }
}
