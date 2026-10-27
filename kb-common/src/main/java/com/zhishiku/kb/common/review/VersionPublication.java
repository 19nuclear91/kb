package com.zhishiku.kb.common.review;

import java.time.Instant;
import java.util.Objects;

/**
 * 不含框架或持久层的版本发布事实。索引完成和审核轮次不存为发布证据。
 *
 * @param policy 版本创建时固定的策略
 * @param protocol 行的协议来源
 * @param uploaderUserId 服务端确认的真实上传者；旧行允许未知
 * @param evidence 已核验的发布证据
 * @param publishedAt 实际发布时间；观察到的旧发布时间保持未知
 */
public record VersionPublication(PublicationPolicy policy, PublicationProtocol protocol,
                                 Long uploaderUserId, PublicationEvidence evidence, Instant publishedAt) {
    public VersionPublication {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(evidence, "evidence");
        if (uploaderUserId != null && uploaderUserId <= 0) {
            throw new IllegalArgumentException("Invalid uploader");
        }
        if (protocol == PublicationProtocol.VERSIONED_REVIEW && uploaderUserId == null) {
            throw new IllegalArgumentException("New versions require an uploader");
        }
        if ((evidence == PublicationEvidence.PUBLISHED) != (publishedAt != null)) {
            throw new IllegalArgumentException("Publication time requires an actual publication");
        }
        if (evidence == PublicationEvidence.OBSERVED_CURRENT && protocol != PublicationProtocol.LEGACY_COMPAT) {
            throw new IllegalArgumentException("Observed evidence is upgrade-only");
        }
    }

    public static VersionPublication create(PublicationPolicy policy, long uploaderUserId) {
        return new VersionPublication(policy, PublicationProtocol.VERSIONED_REVIEW,
                uploaderUserId, PublicationEvidence.NONE, null);
    }

    public boolean permitsModelSource() {
        return evidence.isPublished() || protocol == PublicationProtocol.LEGACY_COMPAT;
    }

    public boolean permitsCommentTarget() {
        return evidence.isPublished();
    }

    /** 仅判定双轴业务前置条件；调用方仍须在事务内校验索引身份、归属和并发门禁。 */
    public boolean permitsPublication(String indexStatus, ReviewState decision) {
        return !evidence.isPublished() && "indexed".equals(indexStatus)
                && (policy == PublicationPolicy.AUTO || decision == ReviewState.APPROVED);
    }

    public VersionPublication publish(String indexStatus, ReviewState decision, Instant now) {
        if (!permitsPublication(indexStatus, decision)) {
            throw new IllegalStateException("STATE_CONFLICT");
        }
        return new VersionPublication(policy, protocol, uploaderUserId,
                PublicationEvidence.PUBLISHED, Objects.requireNonNull(now, "now"));
    }
}
