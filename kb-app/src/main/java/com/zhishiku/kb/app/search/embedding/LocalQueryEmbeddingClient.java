package com.zhishiku.kb.app.search.embedding;

import com.zhishiku.kb.common.embedding.EmbeddingMath;

/**
 * 旧直接构造接口；生产通过 profile 注册表选择客户端。
 */
public class LocalQueryEmbeddingClient implements QueryEmbeddingClient {

    private final QueryEmbeddingProperties props;

    public LocalQueryEmbeddingClient(QueryEmbeddingProperties props) {
        this.props = props;
    }

    @Override
    public float[] embed(String text) {
        return EmbeddingMath.localHash(text, dimension());
    }

    @Override
    public String model() {
        return "local-hash-v1";
    }

    @Override
    public int dimension() {
        return props.dimension();
    }
}
