package com.zhishiku.kb.common.embedding;

import java.util.Set;
import java.util.UUID;

/**
 * 嵌入控制状态，用于查询、解析与重建之间的代次隔离。
 *
 * @param state             当前生命周期状态
 * @param activeProfileId   当前已激活的嵌入配置标识，未初始化时为空
 * @param activeFingerprint 当前已激活配置的语义指纹，未初始化时为空
 * @param targetProfileId   重建目标配置标识，无重建任务时为空
 * @param targetFingerprint 重建目标配置的语义指纹，无重建任务时为空
 * @param epoch             嵌入控制代次，用于隔离旧任务与旧查询
 * @param runId             重建运行标识
 */
public record EmbeddingControl(String state, String activeProfileId, String activeFingerprint,
                               String targetProfileId, String targetFingerprint, long epoch, UUID runId) {
    public EmbeddingControl {
        if (epoch < 0 || state == null
                || !Set.of("UNINITIALIZED", "QUIESCING", "REBUILDING", "VERIFYING", "BLOCKED", "ACTIVE").contains(state)) {
            throw new IllegalArgumentException("invalid embedding control state");
        }
        if ("ACTIVE".equals(state) && (activeProfileId == null || activeFingerprint == null
                || targetProfileId != null || runId != null)) {
            throw new IllegalArgumentException("invalid active embedding control");
        }
        if ("UNINITIALIZED".equals(state) && (activeProfileId != null || targetProfileId != null || runId != null)) {
            throw new IllegalArgumentException("invalid uninitialized embedding control");
        }
        if (!"UNINITIALIZED".equals(state) && !"ACTIVE".equals(state)
                && (targetProfileId == null || targetFingerprint == null || runId == null)) {
            throw new IllegalArgumentException("invalid maintenance embedding control");
        }
    }

    public boolean active() {
        return "ACTIVE".equals(state);
    }
}
