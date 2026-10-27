package com.zhishiku.kb.app.embedding.model;

import com.zhishiku.kb.common.api.ApiException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 经接口校验后交给重建服务的内部命令。
 *
 * @param domain          命令类型，START 表示启动，RESUME 表示续建
 * @param profileId       不可变嵌入配置标识
 * @param runId           重建运行标识
 * @param expectedVersion 调用方提交的预期版本，用于并发校验
 * @param reason          操作或状态原因
 * @param requestId       请求幂等标识
 */
public record EmbeddingCommand(String domain, String profileId, UUID runId, long expectedVersion,
                               String reason, String requestId) {
    public EmbeddingCommand {
        if (!("START".equals(domain) || "RESUME".equals(domain)) || expectedVersion < 0) {
            throw ApiException.badRequest("invalid embedding command");
        }
        if ("START".equals(domain) && (profileId == null || profileId.isBlank()
                || !profileId.equals(profileId.strip()) || runId != null)) {
            throw ApiException.badRequest("profileId is required");
        }
        if ("RESUME".equals(domain) && (runId == null || profileId != null)) {
            throw ApiException.badRequest("runId is required");
        }
        if (reason == null) throw ApiException.badRequest("reason is required");
        reason = reason.strip();
        if (reason.isEmpty() || reason.codePointCount(0, reason.length()) > 500) {
            throw ApiException.badRequest("reason must contain 1-500 characters");
        }
        validateRequestId(requestId);
    }

    public static String requestId(String body, String header) {
        if (body != null && header != null && !body.equals(header)) {
            throw ApiException.badRequest("request identifiers do not match");
        }
        String id = body == null ? header : body;
        validateRequestId(id);
        return id;
    }

    private static void validateRequestId(String id) {
        if (id == null || id.isBlank() || id.codePointCount(0, id.length()) > 128) {
            throw ApiException.badRequest("requestId must contain 1-128 characters");
        }
    }

    public String digest(long actor) {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            for (String field : List.of("embedding-command-v1", domain, Long.toString(actor),
                    profileId == null ? runId.toString() : profileId, Long.toString(expectedVersion), reason)) {
                byte[] value = field.getBytes(StandardCharsets.UTF_8);
                hash.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
                hash.update(value);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    @Override
    public String toString() {
        return "EmbeddingCommand[domain=" + domain + "]";
    }
}
