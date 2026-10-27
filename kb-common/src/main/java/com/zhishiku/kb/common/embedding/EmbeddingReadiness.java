package com.zhishiku.kb.common.embedding;

import java.time.Instant;
import java.util.UUID;

/**
 * 应用实例对指定模型配置的就绪探测结果。
 *
 * @param instanceId      当前应用实例标识
 * @param role            执行角色
 * @param profileId       不可变嵌入配置标识
 * @param fingerprint     嵌入配置的语义指纹
 * @param protocolVersion 就绪探测协议版本
 * @param ready           当前配置是否已通过就绪检查
 * @param checkedAt       最近一次探测时间
 * @param validUntil      探测结果失效时间
 */
public record EmbeddingReadiness(UUID instanceId, EmbeddingRole role, String profileId, String fingerprint,
                                 int protocolVersion, boolean ready, Instant checkedAt, Instant validUntil) {
    public static final int PROTOCOL_VERSION = 1;

    public boolean usable(EmbeddingProfile profile, EmbeddingRole expectedRole, Instant now) {
        return usable(profile.profileId(), profile.fingerprint(), expectedRole, now);
    }

    public boolean usable(String expectedId, String expectedFingerprint, EmbeddingRole expectedRole, Instant now) {
        return ready && role == expectedRole && protocolVersion == PROTOCOL_VERSION
                && expectedId != null && expectedId.equals(profileId)
                && expectedFingerprint != null && expectedFingerprint.equals(fingerprint)
                && !checkedAt.isAfter(now) && validUntil.isAfter(now);
    }
}
