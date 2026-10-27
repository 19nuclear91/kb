package com.zhishiku.kb.common.embedding;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 持久化的重建运行状态及租约、重试和进度信息。
 *
 * @param runId           重建运行标识
 * @param targetProfileId 重建目标配置标识，无重建任务时为空
 * @param createdBy       创建重建任务的用户 ID
 * @param state           当前生命周期状态
 * @param version         乐观锁版本号
 * @param resumePhase     故障恢复后继续执行的阶段
 * @param claimToken      领取时分配的栅栏令牌，用于拒绝迟到写入
 * @param leaseOwner      当前租约持有者
 * @param leaseUntil      租约到期时间
 * @param attempts        已尝试执行次数
 * @param maxAttempts     最大允许尝试次数
 * @param nextAttemptAt   下次允许重试的时间
 * @param totalCount      重建目标总数
 * @param succeededCount  已成功重建数量
 * @param skippedCount    已跳过的目标数量
 * @param errorCode       最近一次失败的错误码
 */
public record EmbeddingRun(UUID runId, String targetProfileId, long createdBy, String state, long version,
                           String resumePhase, long claimToken, UUID leaseOwner, OffsetDateTime leaseUntil,
                           int attempts, int maxAttempts, OffsetDateTime nextAttemptAt, long totalCount,
                           long succeededCount, long skippedCount, String errorCode) {
}
