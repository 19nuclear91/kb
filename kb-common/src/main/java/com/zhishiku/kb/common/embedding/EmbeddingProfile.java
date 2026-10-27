package com.zhishiku.kb.common.embedding;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 嵌入模型的不可变语义配置，不包含端点与密钥。
 *
 * @param profileId                不可变嵌入配置标识
 * @param provider                 模型服务提供方类型
 * @param modelSource              模型来源标识
 * @param model                    模型名称
 * @param modelRevision            模型版本标识
 * @param dimension                向量维度
 * @param distance                 向量距离度量
 * @param queryTransformVersion    查询文本变换版本
 * @param documentTransformVersion 文档文本变换版本
 * @param queryTaskType            查询向量任务类型
 * @param documentTaskType         文档向量任务类型
 * @param adapterVersion           模型适配器版本
 */
public record EmbeddingProfile(
        String profileId,
        String provider,
        String modelSource,
        String model,
        String modelRevision,
        int dimension,
        String distance,
        String queryTransformVersion,
        String documentTransformVersion,
        String queryTaskType,
        String documentTaskType,
        String adapterVersion) {

    public static final int DIMENSION = 1024;

    public EmbeddingProfile {
        requireText(profileId, "profileId");
        requireText(provider, "provider");
        requireText(modelSource, "modelSource");
        requireText(model, "model");
        requireText(modelRevision, "modelRevision");
        requireText(queryTransformVersion, "queryTransformVersion");
        requireText(documentTransformVersion, "documentTransformVersion");
        requireText(queryTaskType, "queryTaskType");
        requireText(documentTaskType, "documentTaskType");
        requireText(adapterVersion, "adapterVersion");
        if (dimension != DIMENSION) {
            throw new IllegalArgumentException("embedding dimension must be 1024");
        }
        if (!"cosine".equals(distance)) {
            throw new IllegalArgumentException("embedding distance must be cosine");
        }
    }

    public String fingerprint() {
        Map<String, String> fields = new TreeMap<>();
        fields.put("adapterVersion", adapterVersion);
        fields.put("dimension", Integer.toString(dimension));
        fields.put("distance", distance);
        fields.put("documentTaskType", documentTaskType);
        fields.put("documentTransformVersion", documentTransformVersion);
        fields.put("model", model);
        fields.put("modelRevision", modelRevision);
        fields.put("modelSource", modelSource);
        fields.put("provider", provider);
        fields.put("queryTaskType", queryTaskType);
        fields.put("queryTransformVersion", queryTransformVersion);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            addField(digest, "kb-embedding-profile-v1");
            // 长度前缀与固定排序避免字段分隔符碰撞；连接配置不属于语义字段。
            fields.forEach((key, value) -> {
                addField(digest, key);
                addField(digest, value);
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public boolean hasIdentity(String expectedId, String expectedFingerprint) {
        return profileId.equals(expectedId) && fingerprint().equals(expectedFingerprint);
    }

    public boolean compatibleWith(EmbeddingProfile other) {
        return other != null && hasIdentity(other.profileId(), other.fingerprint());
    }

    public String transformVersion(EmbeddingRole role) {
        return switch (Objects.requireNonNull(role, "role")) {
            case QUERY -> queryTransformVersion;
            case DOCUMENT -> documentTransformVersion;
        };
    }

    public String taskType(EmbeddingRole role) {
        return switch (Objects.requireNonNull(role, "role")) {
            case QUERY -> queryTaskType;
            case DOCUMENT -> documentTaskType;
        };
    }

    private static void addField(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException("embedding " + name + " must be explicit and non-blank");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IllegalArgumentException("embedding " + name + " contains invalid Unicode");
                }
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("embedding " + name + " contains invalid Unicode");
            }
        }
    }
}
