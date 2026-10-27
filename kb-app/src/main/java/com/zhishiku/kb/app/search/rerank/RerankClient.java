package com.zhishiku.kb.app.search.rerank;

import com.zhishiku.kb.app.search.model.ChunkHit;

import java.util.List;

/**
 * RRF 之后的重排适配接口。实现可以是本地规则或外部 rerank 服务。
 */
public interface RerankClient {

    List<ChunkHit> rerank(String query, List<ChunkHit> candidates, int topK);

    String model();
}
