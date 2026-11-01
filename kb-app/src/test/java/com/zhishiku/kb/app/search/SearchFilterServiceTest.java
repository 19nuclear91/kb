package com.zhishiku.kb.app.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.search.web.SearchController;
import com.zhishiku.kb.app.search.web.SearchRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.metadata.model.DocumentFileTypes;
import com.zhishiku.kb.app.embedding.service.EmbeddingControlService;
import com.zhishiku.kb.app.search.config.SearchLimitsProperties;
import com.zhishiku.kb.app.search.embedding.EmbeddingInstanceReadiness;
import com.zhishiku.kb.app.search.execution.SearchMetrics;
import com.zhishiku.kb.app.search.execution.SearchResponseEncoder;
import com.zhishiku.kb.app.search.execution.SearchStageFailure;
import com.zhishiku.kb.app.search.execution.SearchTransaction;
import com.zhishiku.kb.app.search.mapper.SearchMapper;
import com.zhishiku.kb.app.search.model.ChunkHit;
import com.zhishiku.kb.app.search.model.SearchFilters;
import com.zhishiku.kb.app.search.rerank.RerankClient;
import com.zhishiku.kb.app.search.service.QueryRetrievalService;
import com.zhishiku.kb.app.search.service.SearchService;
import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import com.zhishiku.kb.common.embedding.EmbeddingResult;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.common.embedding.PreparedEmbeddingQuery;
import com.zhishiku.kb.common.embedding.ProfileEmbeddingClient;
import com.zhishiku.kb.common.security.PermissionScope;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchFilterServiceTest {
    private final SearchFilters filters = new SearchFilters(99L, 4L, false, List.of(8L), SearchFilters.FileType.PDF,
            OffsetDateTime.parse("2026-10-01T00:00:00Z"), null);
    private final PermissionScope scope = PermissionScope.of(7, (short) 2, List.of(1L), List.of("1:7"));
    private final EmbeddingProfile profile = new EmbeddingProfile("p", "local", "source", "local-hash-v1", "1", 1024,
            "cosine", "identity-v1", "identity-v1", "query", "document", "local-hash-v1");

    @Test
    void bothOptionalFailuresKeepSameFiltersAndAuthorizedCandidates() throws Exception {
        var permissions = mock(PermissionService.class);
        var control = mock(EmbeddingControlService.class);
        var registry = mock(EmbeddingClientRegistry.class);
        var embedding = mock(ProfileEmbeddingClient.class);
        var retrieval = mock(QueryRetrievalService.class);
        var rerank = mock(RerankClient.class);
        when(permissions.resolve(7, "view")).thenReturn(scope);
        when(control.current()).thenReturn(new EmbeddingControl("ACTIVE", "p", profile.fingerprint(), null, null, 1, null));
        when(registry.find("p", profile.fingerprint())).thenReturn(Optional.of(embedding));
        when(embedding.embed(any(), any())).thenThrow(new SearchStageFailure(SearchStageFailure.Reason.TIMEOUT));
        var hit = new ChunkHit(1L, 10L, 20L, 1, "title", 99L, (short) 1, 0, "text", null, null, null,
                null, null, null, null, null, 0.5);
        when(retrieval.retrieve("q", null, scope, 50, filters)).thenReturn(List.of(hit));
        when(rerank.rerank("q", List.of(hit), 20)).thenThrow(new SearchStageFailure(SearchStageFailure.Reason.UPSTREAM));
        var service = new SearchService(permissions, registry, control, retrieval, rerank,
                mock(EmbeddingInstanceReadiness.class), SearchExecutionStub.immediate(),
                new SearchResponseEncoder(new ObjectMapper(), SearchLimitsProperties.defaults()), SearchLimitsProperties.defaults());
        try (var identity = mockStatic(StpUtil.class)) {
            identity.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            var response = new SearchController(service).search(new SearchRequest("q", 50, 20,
                    filters.spaceId(), filters.categoryId(), filters.uncategorized(), filters.tagIds(),
                    filters.fileType(), filters.updatedFrom(), filters.updatedTo()), new MockHttpServletRequest());
            assertThat(response.getHeaders().getFirst("X-Search-Filter-Version")).isEqualTo("1");
            var data = new ObjectMapper().readTree((byte[]) response.getBody()).path("data");
            assertThat(data.isArray()).isTrue();
            assertThat(data.size()).isEqualTo(1);
            assertThat(data.get(0).path("chunkId").asLong()).isEqualTo(hit.chunkId());
        }
        verify(retrieval).retrieve("q", null, scope, 50, filters);
        verify(retrieval, never()).retrieve(any(), any(), any(), anyInt());
        assertThat(scope.visibleSpaceIdList()).containsExactly(1L);
    }

    @Test
    void profileChangeDropsOnlyVectorAndKeepsSelectionSeparateFromAcl() {
        var control = mock(EmbeddingControlService.class);
        var mapper = mock(SearchMapper.class);
        var transaction = mock(SearchTransaction.class);
        when(transaction.execute(any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
        when(control.lockShared()).thenReturn(new EmbeddingControl("UNINITIALIZED", null, null, null, null, 2, null));
        var vector = new float[1024];
        vector[0] = 1;
        var query = new PreparedEmbeddingQuery(1, new EmbeddingResult(profile, EmbeddingRole.QUERY, List.of(vector)));
        var service = new QueryRetrievalService(control, mapper, transaction, new SearchMetrics(null));
        assertThat(service.retrieve("q", query, scope, 50, filters)).isEmpty();
        verify(mapper).filteredSearch("q", null, List.of(1L), List.of("1:7"), (short) 2, 50, null, filters, DocumentFileTypes.RULES);
        verify(mapper, never()).hybridSearch(any(), any(), any(), any(), eq((short) 2), anyInt(), any());
    }
}
