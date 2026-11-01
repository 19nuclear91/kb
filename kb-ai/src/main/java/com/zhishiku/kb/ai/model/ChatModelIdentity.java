package com.zhishiku.kb.ai.model;

import java.net.URI;

/** 预算绑定的部署身份；模型别名本身不证明 tokenizer 或实际权重。 */
public record ChatModelIdentity(URI baseUrl, String model, String formatVersion) {
    public ChatModelIdentity {
        if (baseUrl == null || model == null || model.isBlank() || formatVersion == null || formatVersion.isBlank()) {
            throw ChatModelProperties.invalid();
        }
    }

    public static ChatModelIdentity of(ChatModelProperties properties) {
        return new ChatModelIdentity(properties.rootUri(), properties.getModel(), ChatPromptRenderer.FORMAT_VERSION);
    }

    @Override
    public String toString() {
        return "ChatModelIdentity[redacted]";
    }
}
