package com.zhishiku.kb.worker.parse;

import org.springframework.dao.DataAccessException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import software.amazon.awssdk.core.exception.SdkException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * 将 Worker 异常归一为可持久化、可告警的有限分类和错误代码。
 * 未知异常默认可重试，但不会把异常正文原样写入数据库或告警。
 */
public final class FailureClassifier {

    public static final int MAX_SUMMARY_LENGTH = 2000;

    public Classification classify(Throwable error) {
        if (error == null) {
            return new Classification(FailureClass.UNKNOWN, "UNKNOWN_ERROR",
                    "Unknown failure", false);
        }
        if (error instanceof LeaseLostException) {
            return new Classification(null, "LEASE_LOST", "解析任务租约已失守", true);
        }
        if (error instanceof ParseFailureException) {
            return classification(FailureClass.PERMANENT, "PARSER_ERROR", error);
        }
        if (error instanceof IllegalArgumentException) {
            return classification(FailureClass.PERMANENT, "INVALID_INPUT", error);
        }
        if (error instanceof ParseTaskRunner.TaskTimeoutException
                || error instanceof TimeoutException
                || error instanceof SocketTimeoutException
                || error instanceof ConnectException) {
            return classification(FailureClass.RETRYABLE, "DEPENDENCY_TIMEOUT", error);
        }
        if (error instanceof DataAccessException) {
            return classification(FailureClass.RETRYABLE, "DATABASE_UNAVAILABLE", error);
        }
        if (error instanceof ResourceAccessException) {
            return classification(FailureClass.RETRYABLE, "REMOTE_UNAVAILABLE", error);
        }
        if (error instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            return classification(status == 429 || status >= 500
                            ? FailureClass.RETRYABLE : FailureClass.PERMANENT,
                    status == 429 || status >= 500 ? "REMOTE_UNAVAILABLE" : "REMOTE_CLIENT_ERROR",
                    error);
        }
        if (error instanceof SdkException) {
            return classification(FailureClass.RETRYABLE, "OBJECT_STORAGE_UNAVAILABLE", error);
        }
        return classification(FailureClass.UNKNOWN, "UNKNOWN_ERROR", error);
    }

    private static Classification classification(FailureClass failureClass, String code,
                                                 Throwable error) {
        return new Classification(failureClass, code, summarize(error), false);
    }

    static String summarize(Throwable error) {
        String type = error == null ? "Unknown" : error.getClass().getSimpleName();
        String message = error == null ? "Unknown failure" : error.getMessage();
        String value = type + ": " + (message == null || message.isBlank() ? "未提供错误详情" : message);
        value = value.replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("(?i)(bearer\\s+)[^\\s]+", "$1[REDACTED]")
                .replaceAll("(?i)((?:password|token|secret|api[-_]?key)\\s*[=:]\\s*)[^,;\\s]+",
                        "$1[REDACTED]")
                .trim();
        return value.length() <= MAX_SUMMARY_LENGTH
                ? value : value.substring(0, MAX_SUMMARY_LENGTH);
    }

    public enum FailureClass {
        PERMANENT, RETRYABLE, UNKNOWN
    }

    /**
     * 异常归一化结果。
     *
     * @param failureClass 失败分类；租约失守时为空并由 {@code leaseLost} 表示
     * @param code         稳定失败代码
     * @param summary      脱敏后的有限长度错误摘要
     * @param leaseLost    是否检测到任务租约失守
     */
    public record Classification(FailureClass failureClass, String code,
                                 String summary, boolean leaseLost) {

        public boolean retryable() {
            return failureClass == FailureClass.RETRYABLE || failureClass == FailureClass.UNKNOWN;
        }

        public boolean permanent() {
            return failureClass == FailureClass.PERMANENT;
        }

        public String metricClass() {
            return failureClass == null ? "LEASE_LOST" : failureClass.name().toUpperCase(Locale.ROOT);
        }
    }
}
