package com.zhishiku.kb.common.review;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * 不含原因原文的命令绑定；规范化载荷只参与摘要，不进入通用回执和审计。
 *
 * @param domain 操作域
 * @param requestId 请求标识
 * @param actorUserId 当前操作者
 * @param spaceId 策略操作目标空间
 * @param docId 版本操作目标文档
 * @param versionId 版本操作目标版本
 * @param reviewId 决定操作目标轮次
 * @param expectedVersion 原请求预期并发版本
 * @param inputDigest 长度前缀编码后 SHA-256 摘要
 */
public record ReviewCommandBinding(ReviewCommandDomain domain, String requestId, long actorUserId,
                                    Long spaceId, Long docId, Long versionId, UUID reviewId,
                                    long expectedVersion, String inputDigest) {
    public ReviewCommandBinding {
        Objects.requireNonNull(domain, "domain");
        requireRequestId(requestId);
        if (actorUserId <= 0 || expectedVersion < 0 || inputDigest == null
                || !inputDigest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid command binding");
        if (domain == ReviewCommandDomain.POLICY) {
            if (spaceId == null || spaceId <= 0 || docId != null || versionId != null || reviewId != null)
                throw new IllegalArgumentException("Invalid policy target");
        } else {
            if (spaceId != null || docId == null || docId <= 0 || versionId == null || versionId <= 0)
                throw new IllegalArgumentException("Invalid version target");
            if ((domain == ReviewCommandDomain.APPROVE || domain == ReviewCommandDomain.REJECT
                    || domain == ReviewCommandDomain.WITHDRAW) && reviewId == null)
                throw new IllegalArgumentException("Missing review target");
            if ((domain == ReviewCommandDomain.SUBMIT || domain == ReviewCommandDomain.INDEX_REFRESH) && reviewId != null)
                throw new IllegalArgumentException("Unexpected review target");
        }
    }

    public static ReviewCommandBinding policy(String requestId, long actor, long spaceId,
                                               long expectedVersion, PublicationPolicy policy) {
        return new ReviewCommandBinding(ReviewCommandDomain.POLICY, requestId, actor, spaceId, null, null,
                null, expectedVersion, digest(Objects.requireNonNull(policy, "policy").name()));
    }

    public static ReviewCommandBinding version(ReviewCommandDomain domain, String requestId, long actor,
                                                long documentId, long versionId, UUID reviewId, long expectedVersion,
                                                String reason) {
        if (domain == ReviewCommandDomain.POLICY || (domain != ReviewCommandDomain.REJECT && reason != null))
            throw new IllegalArgumentException("Invalid version command payload");
        String digest = domain == ReviewCommandDomain.REJECT ? digest(ReviewReason.normalize(reason)) : digest();
        return new ReviewCommandBinding(domain, requestId, actor, null, documentId, versionId, reviewId,
                expectedVersion, digest);
    }

    public static String requireRequestId(String value) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > 128
                || value.codePoints().anyMatch(code -> Character.isISOControl(code)
                || (code >= Character.MIN_SURROGATE && code <= Character.MAX_SURROGATE))) {
            throw new IllegalArgumentException("Invalid request identifier");
        }
        return value;
    }

    public static String digest(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = Objects.requireNonNull(value, "digest value").getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
