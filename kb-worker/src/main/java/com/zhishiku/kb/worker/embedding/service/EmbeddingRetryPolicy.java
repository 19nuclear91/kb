package com.zhishiku.kb.worker.embedding.service;

import com.zhishiku.kb.worker.embedding.model.RebuildContentChangedException;
import org.springframework.dao.DataAccessException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

public final class EmbeddingRetryPolicy {
    private EmbeddingRetryPolicy() {
    }

    /**
     * 重建失败的稳定错误码与可重试判断。
     *
     * @param code      业务编码
     * @param retryable 是否允许自动重试
     */
    public record Failure(String code, boolean retryable) {
    }

    public static Failure classify(RuntimeException error) {
        if (error instanceof RebuildContentChangedException) return new Failure("CONTENT_CHANGED", false);
        if (error instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            return new Failure("MODEL_UNAVAILABLE", status == 429 || status == 408 || status >= 500);
        }
        if (error instanceof ResourceAccessException) return new Failure("MODEL_UNAVAILABLE", true);
        if (error instanceof DataAccessException) return new Failure("DATABASE_ERROR", true);
        if ("embedding document client unavailable".equals(error.getMessage()))
            return new Failure("MODEL_UNAVAILABLE", false);
        return new Failure("INVALID_RESPONSE", false);
    }

    public static int delaySeconds(int attempt) {
        if (attempt < 1) throw new IllegalArgumentException("invalid embedding attempt");
        return (int) Math.min(900L, 30L << Math.min(attempt - 1, 5));
    }
}
