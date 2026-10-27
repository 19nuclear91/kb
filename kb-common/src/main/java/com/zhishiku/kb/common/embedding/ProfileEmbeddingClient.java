package com.zhishiku.kb.common.embedding;

import java.util.List;

public interface ProfileEmbeddingClient {
    EmbeddingProfile profile();

    EmbeddingRole role();

    EmbeddingResult embed(List<String> texts);
}
