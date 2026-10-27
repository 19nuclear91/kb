package com.zhishiku.kb.common.embedding;

/**
 * 与控制代次绑定的查询向量，供检索一致性检查使用。
 *
 * @param epoch  嵌入控制代次，用于隔离旧任务与旧查询
 * @param result 携带配置身份的查询向量结果
 */
public record PreparedEmbeddingQuery(long epoch, EmbeddingResult result) {
    public PreparedEmbeddingQuery {
        if (epoch < 0 || result == null || result.role() != EmbeddingRole.QUERY || result.size() != 1) {
            throw new IllegalArgumentException("query requires one identified vector and a valid epoch");
        }
    }

    public boolean matches(EmbeddingControl control) {
        return control.active() && control.epoch() == epoch
                && result.profile().hasIdentity(control.activeProfileId(), control.activeFingerprint());
    }
}
