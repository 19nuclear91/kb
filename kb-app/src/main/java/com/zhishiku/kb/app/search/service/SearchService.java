package com.zhishiku.kb.app.search.service;

import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.embedding.service.EmbeddingControlService;
import com.zhishiku.kb.app.search.config.SearchLimitsProperties;
import com.zhishiku.kb.app.search.embedding.EmbeddingInstanceReadiness;
import com.zhishiku.kb.app.search.execution.SearchBudget;
import com.zhishiku.kb.app.search.execution.SearchCallBudget;
import com.zhishiku.kb.app.search.execution.SearchContext;
import com.zhishiku.kb.app.search.execution.SearchExecutor;
import com.zhishiku.kb.app.search.execution.SearchResponseEncoder;
import com.zhishiku.kb.app.search.execution.SearchStageFailure;
import com.zhishiku.kb.app.search.model.ChunkHit;
import com.zhishiku.kb.app.search.model.SearchFailure;
import com.zhishiku.kb.app.search.model.SearchFilters;
import com.zhishiku.kb.app.search.model.SearchInput;
import com.zhishiku.kb.app.search.rerank.RerankClient;
import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.embedding.EmbeddingProtocolFailure;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.common.embedding.PreparedEmbeddingQuery;
import com.zhishiku.kb.common.security.PermissionScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.List;

/**
 * 检索服务。对外<b>唯一</b>的检索入口——AI 服务也必须走这里，不得自行查库。
 *
 * <p>见《架构决策记录.md》2.4：AI 服务不得绕过授权读取全库。
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    /**
     * 每路召回的候选数。偏大是有意的——融合后还要交给 rerank 重排。
     */
    private static final int DEFAULT_CANDIDATES = 50;

    private final PermissionService permissionService;
    private final EmbeddingClientRegistry clients;
    private final EmbeddingControlService control;
    private final QueryRetrievalService retrieval;
    private final RerankClient rerankClient;
    private final EmbeddingInstanceReadiness instanceReadiness;
    private final SearchExecutor executor;
    private final SearchResponseEncoder encoder;
    private final SearchLimitsProperties limits;

    public SearchService(PermissionService permissionService, EmbeddingClientRegistry clients,
                         EmbeddingControlService control, QueryRetrievalService retrieval, RerankClient rerankClient,
                          EmbeddingInstanceReadiness instanceReadiness, SearchExecutor executor,
                          SearchResponseEncoder encoder, SearchLimitsProperties limits) {
        this.permissionService = permissionService;
        this.clients = clients;
        this.control = control;
        this.retrieval = retrieval;
        this.rerankClient = rerankClient;
        this.instanceReadiness = instanceReadiness;
        this.executor = executor;
        this.encoder = encoder;
        this.limits = limits;
    }

    /**
     * 带权限过滤的混合检索。
     *
     * @param userId      服务端已验证的用户身份
     * @param queryText   查询原文
     * @param queryVector 旧参数仅允许 null；无身份裸向量一律拒绝
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<ChunkHit> search(long userId, String queryText, String queryVector) {
        return search(userId, queryText, queryVector, DEFAULT_CANDIDATES);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<ChunkHit> search(long userId, String queryText, String queryVector, int candidates) {
        return search(userId, queryText, queryVector, candidates, candidates);
    }

    /**
     * 先召回 candidates，再重排并截取 topK。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<ChunkHit> search(long userId, String queryText, String queryVector,
                                   int candidates, int topK) {
        return search(userId, queryText, queryVector, candidates, topK, SearchFilters.empty());
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<ChunkHit> search(long userId, String queryText, String queryVector,
                                 int candidates, int topK, SearchFilters filters) {
        return execute(userId, queryText, queryVector, candidates, topK, filters).hits();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public byte[] searchResponse(long userId, String queryText, String queryVector, int candidates, int topK) {
        return searchResponse(userId, queryText, queryVector, candidates, topK, SearchFilters.empty());
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public byte[] searchResponse(long userId, String queryText, String queryVector, int candidates, int topK,
                                 SearchFilters filters) {
        return execute(userId, queryText, queryVector, candidates, topK, filters).json();
    }

    private Result execute(long userId, String queryText, String queryVector, int candidates, int topK,
                           SearchFilters filters) {
        if (filters == null) {
            throw new SearchFailure(SearchFailure.Kind.INVALID_INPUT);
        }
        if (queryVector != null) {
            throw new IllegalArgumentException("bare query vectors are not supported");
        }
        SearchInput.validate(queryText, candidates, topK, limits.maxQueryCodepoints());
        return executor.execute(userId, execution -> {
            try {
                List<ChunkHit> hits = List.copyOf(retrieve(execution, userId, queryText, candidates, topK, filters));
                execution.resultCount(hits.size());
                byte[] bytes = executor.required(execution, SearchBudget.Stage.RESPONSE, () -> encoder.encode(hits));
                return new Result(hits, bytes);
            } catch (DataAccessException | TransactionException unavailable) {
                throw databaseFailure(unavailable);
            }
        });
    }

    static RuntimeException databaseFailure(RuntimeException failure) {
        boolean sqlFailure = false;
        boolean timeout = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SearchFailure known) {
                return known;
            }
            sqlFailure |= cause instanceof SQLException;
            timeout |= cause instanceof TransactionTimedOutException;
        }
        if (timeout) {
            return new SearchFailure(SearchFailure.Kind.TIMEOUT);
        }
        if (failure instanceof DataAccessException || failure instanceof CannotCreateTransactionException
                || (failure instanceof TransactionSystemException && sqlFailure)) {
            return new SearchFailure(SearchFailure.Kind.DEPENDENCY);
        }
        // 事务使用错误等程序问题仍由通用 500 处理，不冒充依赖不可用。
        return failure;
    }

    private List<ChunkHit> retrieve(SearchExecutor.Execution execution, long userId, String queryText,
                                   int candidates, int topK, SearchFilters filters) {
        PermissionScope scope = executor.required(execution, SearchBudget.Stage.AUTHORIZATION,
                () -> permissionService.resolve(userId, PermissionService.PERM_VIEW));

        // 没有任何可见范围时直接短路，省一次必然返回空的数据库往返。
        // 注意：这是性能优化，不是安全边界——安全边界在 SQL 的 aclPredicate 里。
        if (scope.seesNothing()) {
            log.debug("搜索结束 category=EMPTY");
            return List.of();
        }

        EmbeddingControl current;
        try {
            current = executor.required(execution, SearchBudget.Stage.CONTROL, control::current);
        } catch (RuntimeException unavailable) {
            instanceReadiness.controlUnavailable();
            throw unavailable;
        }
        instanceReadiness.observe(current);
        PreparedEmbeddingQuery query = null;
        if (current.active()) {
            var client = clients.find(current.activeProfileId(), current.activeFingerprint());
            if (client.isPresent()) {
                try {
                    var result = executor.optional(execution, SearchBudget.Stage.EMBEDDING, () -> {
                        var embedded = client.get().embed(List.of(queryText),
                                new SearchCallBudget(SearchContext.current(), limits.maxEmbeddingResponseBytes()));
                        if (embedded == null || !embedded.profile().compatibleWith(client.get().profile())
                                || embedded.role() != EmbeddingRole.QUERY || embedded.size() != 1) {
                            throw new SearchStageFailure(SearchStageFailure.Reason.INVALID_RESPONSE);
                        }
                        return embedded;
                    });
                    query = new PreparedEmbeddingQuery(current.epoch(), result);
                } catch (EmbeddingProtocolFailure e) {
                    executor.degraded(SearchBudget.Stage.EMBEDDING, SearchStageFailure.Reason.INVALID_RESPONSE);
                    log.warn("搜索降级 stage=EMBEDDING category=INVALID_RESPONSE");
                } catch (SearchStageFailure e) {
                    executor.degraded(SearchBudget.Stage.EMBEDDING, e.reason());
                    log.warn("搜索降级 stage=EMBEDDING category={}", e.reason());
                }
            } else {
                executor.degraded(SearchBudget.Stage.EMBEDDING, SearchStageFailure.Reason.PROFILE_UNAVAILABLE);
            }
        } else {
            executor.degraded(SearchBudget.Stage.EMBEDDING, SearchStageFailure.Reason.PROFILE_UNAVAILABLE);
        }

        PreparedEmbeddingQuery prepared = query;
        List<ChunkHit> hits = executor.required(execution, SearchBudget.Stage.RETRIEVAL,
                () -> filters.restricted() ? retrieval.retrieve(queryText, prepared, scope, candidates, filters)
                        : retrieval.retrieve(queryText, prepared, scope, candidates));

        List<ChunkHit> ranked = hits;
        if (rerankClient != null && !hits.isEmpty()) {
            try {
                ranked = executor.optional(execution, SearchBudget.Stage.RERANK,
                        () -> rerankClient.rerank(queryText, hits, Math.min(topK, candidates)));
            } catch (SearchStageFailure e) {
                executor.degraded(SearchBudget.Stage.RERANK, e.reason());
                log.warn("搜索降级 stage=RERANK category={}", e.reason());
            }
        }
        if (ranked.size() > topK) {
            ranked = ranked.subList(0, topK);
        }

        log.debug("搜索结束 category={}", ranked.isEmpty() ? "EMPTY" : "SUCCESS");
        return ranked;
    }

    /**
     * 同一次执行的领域结果及完整响应。
     * @param hits 已授权的分块
     * @param json 已完成限界编码的响应字节
     */
    private record Result(List<ChunkHit> hits, byte[] json) {
    }
}
