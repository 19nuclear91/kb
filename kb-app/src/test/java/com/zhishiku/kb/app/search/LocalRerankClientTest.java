package com.zhishiku.kb.app.search;

import com.zhishiku.kb.app.search.model.ChunkHit;
import com.zhishiku.kb.app.search.rerank.LocalRerankClient;
import com.zhishiku.kb.app.search.rerank.RerankProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalRerankClientTest {

    @Test
    void tokenOverlapMovesRelevantChunkBeforeRrfTie() {
        LocalRerankClient client = new LocalRerankClient(
                new RerankProperties("local", "", "", "local", 30));
        ChunkHit unrelated = hit(1L, "权限申请流程");
        ChunkHit relevant = hit(2L, "微服务部署流程");

        assertThat(client.rerank("微服务", List.of(unrelated, relevant), 1))
                .containsExactly(relevant);
    }

    private static ChunkHit hit(long id, String content) {
        return new ChunkHit(id, 10L, "文档", 3L, (short) 1, 0, content, 1, 0.1);
    }
}
