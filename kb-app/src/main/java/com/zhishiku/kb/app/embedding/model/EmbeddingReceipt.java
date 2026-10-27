package com.zhishiku.kb.app.embedding.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * 嵌入重建命令的幂等回执。
 *
 * @param operationId 幂等操作标识
 * @param runId       重建运行标识
 * @param profileId   不可变嵌入配置标识
 * @param state       当前生命周期状态
 * @param version     乐观锁版本号
 * @param epoch       嵌入控制代次，用于隔离旧任务与旧查询
 * @param replayed    是否重放此前成功操作的回执
 */
@Schema(description = "嵌入重建命令的幂等回执。")
public record EmbeddingReceipt(
        @Schema(description = "幂等操作标识") UUID operationId,
        @Schema(description = "重建运行标识") UUID runId,
        @Schema(description = "不可变嵌入配置标识") String profileId,
        @Schema(description = "当前生命周期状态") String state,
        @Schema(description = "乐观锁版本号") long version,
        @Schema(description = "嵌入控制代次，用于隔离旧任务与旧查询") long epoch,
        @Schema(description = "是否重放此前成功操作的回执") boolean replayed) {
    public EmbeddingReceipt replay() {
        return new EmbeddingReceipt(operationId, runId, profileId, state, version, epoch, true);
    }
}
