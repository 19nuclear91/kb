package com.zhishiku.kb.app.document.preview;

/**
 * 仅供服务端短期存储；不得作为 Controller 响应或供应商配置序列化。
 * 原登录令牌只用于再次查询权威登录存储，toString 不输出任何字段。
 */
public record OfficePreviewSession(String id, long userId, String originalToken, long documentId, long versionId,
                                   String documentKey, String deploymentId, String state, String launchDigest,
                                   String cookieDigest, long createdAt, long absoluteExpiresAt, long expiresAt) {
    public OfficePreviewSession active(String cookieDigest, long expiresAt) {
        return new OfficePreviewSession(id, userId, originalToken, documentId, versionId, documentKey, deploymentId,
                "ACTIVE", "", cookieDigest, createdAt, absoluteExpiresAt, Math.min(expiresAt, absoluteExpiresAt));
    }

    @Override
    public String toString() {
        return "OfficePreviewSession[redacted]";
    }
}
