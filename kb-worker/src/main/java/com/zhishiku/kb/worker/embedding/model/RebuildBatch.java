package com.zhishiku.kb.worker.embedding.model;

import com.zhishiku.kb.common.embedding.EmbeddingProfile;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 已领取的重建批次，携带运行和分块两级栅栏身份。
 *
 * @param runId    重建运行标识
 * @param epoch    嵌入控制代次，用于隔离旧任务与旧查询
 * @param runToken 重建运行的租约栅栏令牌
 * @param owner    当前执行实例的租约所有者标识
 * @param profile  不可变嵌入配置
 * @param items    当前批次领取的分块条目
 */
public record RebuildBatch(UUID runId, long epoch, long runToken, UUID owner,
                           EmbeddingProfile profile, List<Item> items) {
    public RebuildBatch {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(profile, "profile");
        if (epoch < 0 || runToken <= 0) throw new IllegalArgumentException("invalid embedding execution identity");
        items = items.stream().sorted(Comparator.comparingLong(Item::chunkId)).toList();
        if (items.stream().map(Item::chunkId).distinct().count() != items.size()) {
            throw new IllegalArgumentException("duplicate embedding item");
        }
    }

    /**
     * 重建批次内单个分块的领取身份与正文摘要。
     *
     * @param chunkId       分块 ID
     * @param docId         文档 ID
     * @param versionId     文档版本 ID
     * @param contentDigest 领取时的正文摘要，用于防止内容变化后提交旧向量
     * @param claimToken    领取时分配的栅栏令牌，用于拒绝迟到写入
     * @param attempts      已尝试执行次数
     */
    public record Item(long chunkId, long docId, long versionId, String contentDigest, long claimToken, int attempts) {
        public Item {
            if (chunkId <= 0 || docId <= 0 || versionId <= 0 || claimToken <= 0 || attempts <= 0
                    || contentDigest == null || contentDigest.isBlank()) {
                throw new IllegalArgumentException("invalid embedding item identity");
            }
        }
    }
}
