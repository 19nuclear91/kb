package com.zhishiku.kb.worker.parse.model;

import org.apache.ibatis.annotations.AutomapConstructor;

import java.time.OffsetDateTime;

/**
 * 一条待解析任务（已被本 Worker 领取）。
 *
 * @param attempts             已尝试次数，<b>在领取时就已自增</b>。
 *                             详见 {@code ParseTaskMapper#claim} 的说明——
 *                             这个选择决定了 Worker 崩溃时任务不会无限重投。
 * @param id                   解析任务 ID
 * @param versionId            文档版本 ID
 * @param maxAttempts          最大尝试次数
 * @param leaseUntil           当前租约截止时间
 * @param leaseOwner           当前租约持有者
 * @param nextAttemptAt        下一次允许领取时间
 * @param failureClass         最近失败分类
 * @param failureCode          最近稳定失败代码
 * @param failureSummary       最近脱敏失败摘要
 * @param lastFailureAt        最近失败时间
 * @param executionGeneration  当前执行周期
 * @param objectKey            原始文件对象键，仅供 Worker 内部使用
 * @param fileName             原始文件名
 * @param fileSize             原始文件大小
 * @param mimeType             原始文件 MIME 类型
 * @param embeddingProfileId   任务领取时绑定的嵌入配置标识
 * @param embeddingFingerprint 任务领取时绑定的配置语义指纹
 * @param embeddingEpoch       任务领取时绑定的嵌入控制代次
 * @param claimToken           领取时分配的栅栏令牌，用于拒绝迟到写入
 */
public record ParseTask(
        Long id,
        Long versionId,
        Integer attempts,
        Integer maxAttempts,
        OffsetDateTime leaseUntil,
        String leaseOwner,
        OffsetDateTime nextAttemptAt,
        String failureClass,
        String failureCode,
        String failureSummary,
        OffsetDateTime lastFailureAt,
        Long executionGeneration,
        // 以下来自 kb_document_version，领取时一并带出，省一次查询
        String objectKey,
        String fileName,
        Long fileSize,
        String mimeType,
        String embeddingProfileId,
        String embeddingFingerprint,
        Long embeddingEpoch,
        Long claimToken) {

    @AutomapConstructor
    public ParseTask {
    }

    public boolean hasEmbeddingIdentity() {
        return embeddingProfileId != null && embeddingFingerprint != null
                && embeddingEpoch != null && embeddingEpoch >= 0 && claimToken != null && claimToken > 0;
    }

    /**
     * 旧构造结果没有发布资格，不能按当前默认配置补标身份。
     */
    public ParseTask(Long id, Long versionId, Integer attempts, Integer maxAttempts,
                     OffsetDateTime leaseUntil, String leaseOwner, OffsetDateTime nextAttemptAt,
                     String failureClass, String failureCode, String failureSummary,
                     OffsetDateTime lastFailureAt, Long executionGeneration,
                     String objectKey, String fileName, Long fileSize, String mimeType) {
        this(id, versionId, attempts, maxAttempts, leaseUntil, leaseOwner, nextAttemptAt,
                failureClass, failureCode, failureSummary, lastFailureAt, executionGeneration,
                objectKey, fileName, fileSize, mimeType, null, null, null, null);
    }

    /**
     * 兼容已有流水线测试和直接构造方。
     */
    public ParseTask(Long id, Long versionId, Integer attempts, Integer maxAttempts,
                     OffsetDateTime leaseUntil, String leaseOwner,
                     String objectKey, String fileName, Long fileSize, String mimeType) {
        this(id, versionId, attempts, maxAttempts, leaseUntil, leaseOwner,
                null, null, null, null, null, 0L,
                objectKey, fileName, fileSize, mimeType);
    }
}
