package com.zhishiku.kb.ai.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 预算与发送共用的不可变消息视图，不含 API Key。 */
public final class PreparedChatRequest {
    public record Message(String role, String content) {
        @Override
        public String toString() {
            return "ChatMessage[role=" + role + "]";
        }
    }

    private final String model;
    private final List<Message> messages;
    private final int maxOutputTokens;
    private final String outputTokenField;
    private final boolean requestUsage;
    private final int maxRequestBytes;

    PreparedChatRequest(String model, List<Message> messages, int maxOutputTokens,
                        String outputTokenField, boolean requestUsage, int maxRequestBytes) {
        this.model = model;
        this.messages = List.copyOf(messages);
        this.maxOutputTokens = maxOutputTokens;
        this.outputTokenField = outputTokenField;
        this.requestUsage = requestUsage;
        this.maxRequestBytes = maxRequestBytes;
    }

    public String model() {
        return model;
    }

    public List<Message> messages() {
        return messages;
    }

    public String formatVersion() {
        return ChatPromptRenderer.FORMAT_VERSION;
    }

    public int maxOutputTokens() {
        return maxOutputTokens;
    }

    public byte[] encode() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", true);
        body.put("n", 1);
        body.put(outputTokenField, maxOutputTokens);
        if (requestUsage) {
            body.put("stream_options", Map.of("include_usage", true));
        }
        return ChatJson.encode(body, maxRequestBytes);
    }

    @Override
    public String toString() {
        return "PreparedChatRequest[format=" + formatVersion() + "]";
    }
}
