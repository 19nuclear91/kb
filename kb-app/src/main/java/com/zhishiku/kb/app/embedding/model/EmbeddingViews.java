package com.zhishiku.kb.app.embedding.model;

import com.zhishiku.kb.app.search.embedding.EmbeddingInstanceReadiness.Snapshot;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class EmbeddingViews {
    private EmbeddingViews() {
    }

    /**
     * 管理接口返回的嵌入配置及就绪状态。
     *
     * @param profile     不可变嵌入配置
     * @param fingerprint 嵌入配置的语义指纹
     * @param ready       当前配置是否已通过就绪检查
     */
    @Schema(description = "管理接口返回的嵌入配置及就绪状态。")
    public record Profile(
            @Schema(description = "不可变嵌入配置") EmbeddingProfileView profile,
            @Schema(description = "嵌入配置的语义指纹") String fingerprint,
            @Schema(description = "当前配置是否已通过就绪检查") boolean ready) {
    }

    /**
     * 嵌入全局控制状态与当前实例就绪信息。
     *
     * @param state           当前生命周期状态
     * @param activeProfileId 当前已激活的嵌入配置标识，未初始化时为空
     * @param targetProfileId 重建目标配置标识，无重建任务时为空
     * @param epoch           嵌入控制代次，用于隔离旧任务与旧查询
     * @param runId           重建运行标识
     * @param waitingReason   等待或维护原因，无等待时为空
     * @param localEmbedding  当前应用实例的嵌入就绪快照
     */
    @Schema(description = "嵌入全局控制状态与当前实例就绪信息。")
    public record Status(
            @Schema(description = "当前生命周期状态") String state,
            @Schema(description = "当前已激活的嵌入配置标识，未初始化时为空") String activeProfileId,
            @Schema(description = "重建目标配置标识，无重建任务时为空") String targetProfileId,
            @Schema(description = "嵌入控制代次，用于隔离旧任务与旧查询") long epoch,
            @Schema(description = "重建运行标识") UUID runId,
            @Schema(description = "等待或维护原因，无等待时为空") String waitingReason,
            @Schema(description = "当前应用实例的嵌入就绪快照") Snapshot localEmbedding) {
    }

    /**
     * 管理接口返回的重建运行进度。
     *
     * @param runId           重建运行标识
     * @param targetProfileId 重建目标配置标识，无重建任务时为空
     * @param state           当前生命周期状态
     * @param version         乐观锁版本号
     * @param totalCount      重建目标总数
     * @param succeededCount  已成功重建数量
     * @param skippedCount    已跳过的目标数量
     * @param nextAttemptAt   下次允许重试的时间
     * @param errorCode       最近一次失败的错误码
     */
    @Schema(description = "管理接口返回的重建运行进度。")
    public record Run(
            @Schema(description = "重建运行标识") UUID runId,
            @Schema(description = "重建目标配置标识，无重建任务时为空") String targetProfileId,
            @Schema(description = "当前生命周期状态") String state,
            @Schema(description = "乐观锁版本号") long version,
            @Schema(description = "重建目标总数") long totalCount,
            @Schema(description = "已成功重建数量") long succeededCount,
            @Schema(description = "已跳过的目标数量") long skippedCount,
            @Schema(description = "下次允许重试的时间") OffsetDateTime nextAttemptAt,
            @Schema(description = "最近一次失败的错误码") String errorCode) {
    }
}
