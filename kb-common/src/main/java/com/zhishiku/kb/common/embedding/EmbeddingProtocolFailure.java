package com.zhishiku.kb.common.embedding;

/** 远程响应身份或向量不满足约定，不含供应商正文。 */
public final class EmbeddingProtocolFailure extends IllegalStateException {
    public EmbeddingProtocolFailure() {
        super("invalid embedding response");
    }
}
