package com.zhishiku.kb.app.search.service;

import com.zhishiku.kb.app.embedding.service.EmbeddingControlService;
import com.zhishiku.kb.app.search.mapper.SearchMapper;
import com.zhishiku.kb.app.search.model.ChunkHit;
import com.zhishiku.kb.common.embedding.EmbeddingMath;
import com.zhishiku.kb.common.embedding.PreparedEmbeddingQuery;
import com.zhishiku.kb.common.security.PermissionScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class QueryRetrievalService {
    private final EmbeddingControlService control;
    private final SearchMapper mapper;

    public QueryRetrievalService(EmbeddingControlService control, SearchMapper mapper) {
        this.control = control;
        this.mapper = mapper;
    }

    @Transactional(timeout = 10)
    public List<ChunkHit> retrieve(String text, PreparedEmbeddingQuery query, PermissionScope scope, int candidates) {
        var current = control.lockShared();
        boolean useVector = query != null && query.matches(current);
        return List.copyOf(mapper.hybridSearch(text,
                useVector ? EmbeddingMath.toPgVector(query.result().vector(0)) : null,
                scope.visibleSpaceIdList(), List.of(scope.principalKeys()), scope.clearanceLevel(), candidates,
                useVector ? query.result().profileId() : null));
    }
}
