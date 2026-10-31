package com.zhishiku.kb.common.embedding;

import java.util.Map;

@FunctionalInterface
public interface RemoteEmbeddingTransport {
    Map<String, Object> post(Map<String, Object> request);

    default Map<String, Object> post(Map<String, Object> request, CallBudget budget) {
        budget.check();
        Map<String, Object> response = post(request);
        budget.check();
        return response;
    }
}
