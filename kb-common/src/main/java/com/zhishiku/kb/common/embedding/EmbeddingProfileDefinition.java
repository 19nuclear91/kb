package com.zhishiku.kb.common.embedding;

import java.util.List;
import java.util.Objects;

/**
 * 部署连接与不可变模型配置的组合。
 *
 * @param profile    不可变嵌入配置
 * @param connection 部署环境的连接与认证参数
 */
public record EmbeddingProfileDefinition(EmbeddingProfile profile, EmbeddingConnection connection) {

    public EmbeddingProfileDefinition {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(connection, "connection");
        if (!List.of("local", "remote").contains(profile.provider())) {
            throw new IllegalArgumentException("unsupported embedding provider");
        }
        if ("local".equals(profile.provider())) {
            if (!"local-hash-v1".equals(profile.model()) || !"local-hash-v1".equals(profile.adapterVersion())) {
                throw new IllegalArgumentException("local embedding requires local-hash-v1 model and adapter");
            }
        } else if (!"embeddings-v1".equals(profile.adapterVersion()) || connection.endpoint() == null) {
            throw new IllegalArgumentException("remote embedding requires embeddings-v1 adapter and endpoint");
        }
        if (!"query".equals(profile.queryTaskType()) || !"document".equals(profile.documentTaskType())) {
            throw new IllegalArgumentException("unsupported embedding task types");
        }
        boolean identity = "identity-v1".equals(profile.queryTransformVersion())
                && "identity-v1".equals(profile.documentTransformVersion());
        boolean e5 = "e5-query-v1".equals(profile.queryTransformVersion())
                && "e5-document-v1".equals(profile.documentTransformVersion());
        if (!identity && !e5) {
            throw new IllegalArgumentException("unsupported embedding transform pair");
        }
    }

    public List<String> transform(EmbeddingRole role, List<String> texts) {
        String version = profile.transformVersion(role);
        String prefix = switch (version) {
            case "identity-v1" -> "";
            case "e5-query-v1" -> "query: ";
            case "e5-document-v1" -> "passage: ";
            default -> throw new IllegalStateException("unsupported embedding transform");
        };
        return texts.stream().map(text -> {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("embedding input must not be blank");
            }
            return prefix + text;
        }).toList();
    }
}
