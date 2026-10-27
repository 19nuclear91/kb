package com.zhishiku.kb.worker.embedding.model;

import com.zhishiku.kb.common.embedding.EmbeddingResult;
import com.zhishiku.kb.common.embedding.EmbeddingRole;

import java.util.List;

/**
 * 待事务提交的批次向量及对应输入摘要。
 *
 * @param batch      已领取的重建批次
 * @param inputs     向量生成输入及其正文摘要
 * @param embeddings 携带配置身份的批次向量结果
 */
public record GeneratedRebuildBatch(RebuildBatch batch, List<Input> inputs, EmbeddingResult embeddings) {
    public GeneratedRebuildBatch {
        inputs = List.copyOf(inputs);
        if (!inputs.stream().map(Input::item).toList().equals(batch.items())) {
            throw new IllegalArgumentException("embedding batch inputs mismatch");
        }
        embeddings.require(batch.profile(), EmbeddingRole.DOCUMENT,
                Math.toIntExact(inputs.stream().filter(Input::generated).count()));
    }

    /**
     * 向量生成阶段对应的分块与正文摘要。
     *
     * @param item   对应的已领取分块条目
     * @param digest 生成向量时使用的正文摘要
     */
    public record Input(RebuildBatch.Item item, String digest) {
        public Input {
            if (digest != null && !digest.equals(item.contentDigest())) {
                throw new IllegalArgumentException("embedding input digest mismatch");
            }
        }

        public boolean generated() {
            return digest != null;
        }
    }
}
