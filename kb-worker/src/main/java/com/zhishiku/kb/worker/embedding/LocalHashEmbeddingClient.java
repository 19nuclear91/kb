package com.zhishiku.kb.worker.embedding;

import com.zhishiku.kb.common.embedding.EmbeddingMath;

import java.util.ArrayList;
import java.util.List;

/**
 * 离线开发实现。它保证维度、确定性和归一化，生产应切换为 remote。
 */
public class LocalHashEmbeddingClient implements EmbeddingClient {

    private final EmbeddingProperties props;

    public LocalHashEmbeddingClient(EmbeddingProperties props) {
        this.props = props;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> result = new ArrayList<>(texts.size());
        for (String text : texts) {
            result.add(EmbeddingMath.localHash(text, dimension()));
        }
        return result;
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
