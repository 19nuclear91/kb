package com.zhishiku.kb.common.embedding;

import java.util.List;

public interface ProfileEmbeddingClient {
    EmbeddingProfile profile();

    EmbeddingRole role();

    EmbeddingResult embed(List<String> texts);

    default EmbeddingResult embed(List<String> texts, CallBudget budget) {
        budget.check();
        EmbeddingResult result = embed(texts);
        budget.check();
        return result;
    }
}
