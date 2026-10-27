package com.zhishiku.kb.worker.parse.model;

import com.zhishiku.kb.common.embedding.EmbeddingProfile;

/**
 * 解析任务的租约身份和领取时绑定的嵌入配置。
 *
 * @param taskId     解析任务 ID
 * @param versionId  文档版本 ID
 * @param owner      当前执行实例的租约所有者标识
 * @param epoch      嵌入控制代次，用于隔离旧任务与旧查询
 * @param claimToken 领取时分配的栅栏令牌，用于拒绝迟到写入
 * @param profile    不可变嵌入配置
 */
public record ParseExecution(long taskId, long versionId, String owner, long epoch,
                             long claimToken, EmbeddingProfile profile) {
    public ParseExecution {
        if (taskId <= 0 || versionId <= 0 || owner == null || owner.isBlank()
                || epoch < 0 || claimToken <= 0 || profile == null) {
            throw new IllegalArgumentException("invalid parse execution identity");
        }
    }

    public boolean matches(ParseTask task, String expectedOwner) {
        return task.hasEmbeddingIdentity() && taskId == task.id() && versionId == task.versionId()
                && owner.equals(expectedOwner) && owner.equals(task.leaseOwner())
                && epoch == task.embeddingEpoch() && claimToken == task.claimToken()
                && profile.hasIdentity(task.embeddingProfileId(), task.embeddingFingerprint());
    }
}
