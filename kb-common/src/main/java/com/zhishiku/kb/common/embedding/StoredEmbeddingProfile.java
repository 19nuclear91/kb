package com.zhishiku.kb.common.embedding;

/**
 * 数据库登记的嵌入配置身份摘要。
 *
 * @param profileId   不可变嵌入配置标识
 * @param fingerprint 嵌入配置的语义指纹
 * @param dimension   向量维度
 */
public record StoredEmbeddingProfile(String profileId, String fingerprint, int dimension) {
    public void require(EmbeddingProfile profile) {
        if (!profile.hasIdentity(profileId, fingerprint) || dimension != profile.dimension()) {
            throw new IllegalStateException("registered embedding profile conflicts with deployment");
        }
    }
}
