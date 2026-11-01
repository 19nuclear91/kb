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

    public void validate(boolean production, int totalSeconds, int reservationTokens) {
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
