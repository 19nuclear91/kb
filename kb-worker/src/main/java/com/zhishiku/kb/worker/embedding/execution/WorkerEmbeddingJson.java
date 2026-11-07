package com.zhishiku.kb.worker.embedding.execution;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.common.embedding.CallBudget;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 计数与发送共用同一编码器，计数过程不保留请求字节。 */
public final class WorkerEmbeddingJson {
    private final ObjectMapper mapper;
    private final int maximum;

    public WorkerEmbeddingJson(WorkerEmbeddingSettings settings) {
        maximum = settings.maxRequestBytes();
        mapper = new ObjectMapper(JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(settings.maxJsonDepth()).build())
                .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public int count(Object value, CallBudget budget) {
        var output = new LimitedOutput(budget, false);
        write(value, output);
        return output.count;
    }

    public byte[] encode(Object value, CallBudget budget) {
        var output = new LimitedOutput(budget, true);
        write(value, output);
        return output.bytes.toByteArray();
    }

    public Map<String, Object> decode(byte[] bytes, CallBudget budget) {
        budget.check();
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            Map<String, Object> result = mapper.readValue(text, new TypeReference<>() { });
            budget.check();
            if (result == null) {
                throw new WorkerEmbeddingFailure(WorkerEmbeddingFailure.Reason.INVALID_RESPONSE);
            }
            return result;
        } catch (IOException error) {
            throw new WorkerEmbeddingFailure(WorkerEmbeddingFailure.Reason.INVALID_RESPONSE);
        }
    }

    private void write(Object value, LimitedOutput output) {
        output.budget.check();
        try {
            mapper.writeValue(output, value);
            output.budget.check();
        } catch (IOException error) {
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof WorkerEmbeddingFailure failure) {
                    throw failure;
                }
            }
            throw new IllegalStateException("embedding request encoding failed");
        }
    }

    private final class LimitedOutput extends OutputStream {
        private final CallBudget budget;
        private final ByteArrayOutputStream bytes;
        private int count;

        private LimitedOutput(CallBudget budget, boolean retain) {
            this.budget = budget;
            bytes = retain ? new ByteArrayOutputStream() : null;
        }

        @Override
        public void write(int value) {
            reserve(1);
            if (bytes != null) {
                bytes.write(value);
            }
        }

        @Override
        public void write(byte[] buffer, int offset, int length) {
            reserve(length);
            if (bytes != null) {
                bytes.write(buffer, offset, length);
            }
        }

        private void reserve(int length) {
            budget.check();
            if (length > maximum - count) {
                throw new WorkerEmbeddingFailure(WorkerEmbeddingFailure.Reason.REQUEST_LIMIT);
            }
            count += length;
        }
    }
}
