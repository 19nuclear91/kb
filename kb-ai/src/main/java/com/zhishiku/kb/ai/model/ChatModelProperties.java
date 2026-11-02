package com.zhishiku.kb.ai.model;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

/** 模型秘密不参与 toString；仅显式启用时要求配置完整。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "kb.ai.chat-model", ignoreUnknownFields = false)
public class ChatModelProperties {
    private boolean enabled;
    private String baseUrl;
    private String apiKey;
    private String model;
    private String outputTokenField = "max_tokens";
    private boolean requestUsage = true;
    private int singleMaxOutputTokens = 2048;
    private int connectTimeoutMs = 5000;
    private int idleTimeoutMs = 30000;
    private int maxRequestBytes = 1048576;
    private int maxEventBytes = 1048576;
    private int maxResponseBytes = 4194304;
    private int maxAnswerCodepoints = 64000;
    private Budget budget = new Budget();

    public void validate(boolean production, int totalSeconds, int reservationTokens) {
        validate(production, totalSeconds, reservationTokens, 2048);
    }

    public void validate(boolean production, int totalSeconds, int reservationTokens, int multiTurnOutputTokens) {
        if (!enabled) {
            return;
        }
        if (invalidText(apiKey) || invalidText(model)
                || (!"max_tokens".equals(outputTokenField) && !"max_completion_tokens".equals(outputTokenField))
                || totalSeconds <= 0 || singleMaxOutputTokens <= 0 || singleMaxOutputTokens > reservationTokens
                || connectTimeoutMs <= 0 || idleTimeoutMs <= 0
                || connectTimeoutMs > totalSeconds * 1000L || idleTimeoutMs > totalSeconds * 1000L
                || maxRequestBytes <= 0 || maxEventBytes <= 0 || maxResponseBytes < maxEventBytes
                || maxAnswerCodepoints <= 0) {
            throw invalid();
        }
        URI root = rootUri();
        if (production && !"https".equals(root.getScheme())) {
            throw invalid();
        }
        if (budget == null) {
            throw invalid();
        }
        budget.validate(reservationTokens, multiTurnOutputTokens);
    }

    /** 仅本地部署配置；不包含秘密，不参与实际 usage。 */
    @Getter
    @Setter
    public static class Budget {
        private String mode = "verified";
        private int contextWindowTokens;
        private int tokensPerKib = 1024;
        private int perMessageTokens = 16;
        private int requestOverheadTokens = 64;
        private int safetyMarginTokens = 512;

        void validate(long reservationTokens, long outputTokens) {
            if (!"verified".equals(mode) && !"estimated".equals(mode)) {
                throw invalid();
            }
            if (!"estimated".equals(mode)) {
                return;
            }
            validateEstimateParameters();
            try {
                if (reservationTokens <= 0 || outputTokens <= 0
                        || Math.min(contextWindowTokens, reservationTokens)
                        <= Math.addExact(outputTokens, safetyMarginTokens)) {
                    throw invalid();
                }
            } catch (ArithmeticException error) {
                throw invalid();
            }
        }

        void validateEstimateParameters() {
            if (contextWindowTokens <= 0 || tokensPerKib < 1 || tokensPerKib > 65536
                    || perMessageTokens < 0 || perMessageTokens > 65536
                    || requestOverheadTokens < 0 || requestOverheadTokens > 65536 || safetyMarginTokens < 0) {
                throw invalid();
            }
        }
    }

    public URI rootUri() {
        try {
            if (invalidText(baseUrl)) {
                throw invalid();
            }
            String value = baseUrl;
            while (value.endsWith("/")) {
                value = value.substring(0, value.length() - 1);
            }
            URI uri = URI.create(value);
            String path = uri.getPath();
            if ((!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !uri.normalize().equals(uri) || path.endsWith("/completions")
                    || path.contains("\\")) {
                throw invalid();
            }
            return uri;
        } catch (RuntimeException error) {
            throw invalid();
        }
    }

    public URI endpoint() {
        return URI.create(rootUri().toASCIIString() + "/chat/completions");
    }

    private static boolean invalidText(String value) {
        return value == null || value.isBlank() || value.codePoints().anyMatch(Character::isISOControl);
    }

    static IllegalArgumentException invalid() {
        return new IllegalArgumentException("聊天模型配置无效");
    }
}
