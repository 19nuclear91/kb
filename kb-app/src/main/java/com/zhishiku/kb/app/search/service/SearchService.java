package com.zhishiku.kb.app.search.service;

import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.embedding.service.EmbeddingControlService;
import com.zhishiku.kb.app.search.embedding.EmbeddingInstanceReadiness;
import com.zhishiku.kb.app.search.model.ChunkHit;
import com.zhishiku.kb.app.search.rerank.RerankClient;
import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.common.embedding.PreparedEmbeddingQuery;
import com.zhishiku.kb.common.security.PermissionScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    public SearchService(PermissionService permissionService, EmbeddingClientRegistry clients,
                         EmbeddingControlService control, QueryRetrievalService retrieval, RerankClient rerankClient,
                         EmbeddingInstanceReadiness instanceReadiness) {
        this.permissionService = permissionService;
        this.clients = clients;
        this.control = control;
        this.retrieval = retrieval;
        this.rerankClient = rerankClient;
        this.instanceReadiness = instanceReadiness;
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
        if (queryVector != null) {
            throw new IllegalArgumentException("bare query vectors are not supported");
        }
        if (candidates <= 0) {
            throw new IllegalArgumentException("candidates 必须为正数");
        }
        if (topK <= 0) {
            return List.of();
        }
        PermissionScope scope = permissionService.resolve(userId, PermissionService.PERM_VIEW);

        // 没有任何可见范围时直接短路，省一次必然返回空的数据库往返。
        // 注意：这是性能优化，不是安全边界——安全边界在 SQL 的 aclPredicate 里。
        if (scope.seesNothing()) {
            log.debug("用户无任何可见范围，跳过检索: userId={}", userId);
            return List.of();
        }

        EmbeddingControl current;
        try {
            current = control.current();
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
                    var result = client.get().embed(List.of(queryText));
                    result.require(client.get().profile(), EmbeddingRole.QUERY, 1);
                    query = new PreparedEmbeddingQuery(current.epoch(), result);
                } catch (RuntimeException e) {
                    log.warn("query embedding unavailable; using BM25");
                }
            }
        }

        List<ChunkHit> hits = retrieval.retrieve(queryText, query, scope, candidates);

        List<ChunkHit> ranked = hits;
        if (rerankClient != null && !hits.isEmpty()) {
            try {
                ranked = rerankClient.rerank(queryText, hits, Math.min(topK, candidates));
            } catch (RuntimeException e) {
                log.warn("rerank unavailable; retaining RRF order");
            }
        }
        if (ranked.size() > topK) {
            ranked = ranked.subList(0, topK);
        }

        log.debug("检索完成: userId={} 命中={}", userId, ranked.size());
        return ranked;
    }
}
