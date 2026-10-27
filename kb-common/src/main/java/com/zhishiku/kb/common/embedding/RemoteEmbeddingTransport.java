package com.zhishiku.kb.common.embedding;

import java.util.Map;

@FunctionalInterface
public interface RemoteEmbeddingTransport {
    Map<String, Object> post(Map<String, Object> request);
}
