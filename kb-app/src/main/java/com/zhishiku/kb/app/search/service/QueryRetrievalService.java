package com.zhishiku.kb.app.search.service;

import com.zhishiku.kb.app.embedding.service.EmbeddingControlService;
import com.zhishiku.kb.app.search.execution.SearchBudget;
import com.zhishiku.kb.app.search.execution.SearchMetrics;
import com.zhishiku.kb.app.search.execution.SearchStageFailure;
import com.zhishiku.kb.app.search.execution.SearchTransaction;
import com.zhishiku.kb.app.search.mapper.SearchMapper;
import com.zhishiku.kb.app.search.model.ChunkHit;
import com.zhishiku.kb.common.embedding.EmbeddingMath;
import com.zhishiku.kb.common.embedding.PreparedEmbeddingQuery;
import com.zhishiku.kb.common.security.PermissionScope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class QueryRetrievalService {
    private final EmbeddingControlService control;
    private final SearchMapper mapper;
    private final SearchTransaction transaction;
    private final SearchMetrics metrics;

    public QueryRetrievalService(EmbeddingControlService control, SearchMapper mapper, SearchTransaction transaction,
                                 SearchMetrics metrics) {
        this.control = control;
        this.mapper = mapper;
        this.transaction = transaction;
        this.metrics = metrics;
    }

    public List<ChunkHit> retrieve(String text, PreparedEmbeddingQuery query, PermissionScope scope, int candidates) {
        return transaction.execute(() -> {
            var current = control.lockShared();
            boolean useVector = query != null && query.matches(current);
            if (query != null && !useVector) {
                metrics.degradation(SearchBudget.Stage.RETRIEVAL, SearchStageFailure.Reason.PROFILE_UNAVAILABLE);
            }
            return List.copyOf(mapper.hybridSearch(text,
                    useVector ? EmbeddingMath.toPgVector(query.result().vector(0)) : null,
                    scope.visibleSpaceIdList(), List.of(scope.principalKeys()), scope.clearanceLevel(), candidates,
                    useVector ? query.result().profileId() : null));
        });
    }
}
