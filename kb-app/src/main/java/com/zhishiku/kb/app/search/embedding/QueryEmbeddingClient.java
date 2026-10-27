package com.zhishiku.kb.app.search.embedding;

/**
 * 查询侧 embedding 的最小适配接口。写入侧与查询侧必须使用相同模型和维度。
 */
public interface QueryEmbeddingClient {

    float[] embed(String text);

    String model();

    int dimension();
}
