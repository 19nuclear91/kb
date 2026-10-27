package com.zhishiku.kb.app.search;

import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.embedding.service.EmbeddingControlService;
import com.zhishiku.kb.app.search.embedding.EmbeddingInstanceReadiness;
import com.zhishiku.kb.app.search.model.ChunkHit;
import com.zhishiku.kb.app.search.rerank.RerankClient;
import com.zhishiku.kb.app.search.service.QueryRetrievalService;
import com.zhishiku.kb.app.search.service.SearchService;
import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingConnection;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import com.zhishiku.kb.common.embedding.EmbeddingProfileDefinition;
import com.zhishiku.kb.common.embedding.EmbeddingResult;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.common.embedding.ProfileEmbeddingClient;
import com.zhishiku.kb.common.security.PermissionScope;
import com.zhishiku.kb.common.security.ResolvedPrincipal;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchServiceTest {

    @Test
    void queryEmbeddingIsPassedToHybridSearchAndRerankIsTopKBounded() {
        QueryRetrievalService retrieval = mock(QueryRetrievalService.class);
        EmbeddingControlService control = mock(EmbeddingControlService.class);
        PermissionService permissions = mock(PermissionService.class);
        ProfileEmbeddingClient embedding = mock(ProfileEmbeddingClient.class);
        var registry = registry(embedding);
        var profile = embedding.profile();
        when(control.current()).thenReturn(new EmbeddingControl("ACTIVE", "p", profile.fingerprint(), null, null, 7,
                null));
        RerankClient rerank = mock(RerankClient.class);
        PermissionScope scope = PermissionScope.of(7L, (short) 2, List.of(3L), List.of("1:7"));
        when(permissions.resolve(7L, PermissionService.PERM_VIEW)).thenReturn(scope);
        float[] vector = new float[1024];
        vector[0] = 1f;
        when(embedding.embed(List.of("微服务"))).thenReturn(new EmbeddingResult(profile, EmbeddingRole.QUERY,
                List.of(vector)));
        ChunkHit first = hit(1L, "普通内容");
        ChunkHit second = hit(2L, "命中内容");
        when(retrieval.retrieve(eq("微服务"), argThat(query -> query != null && query.epoch() == 7
                && query.result().profileId().equals("p")), eq(scope), eq(20))).thenReturn(List.of(first, second));
        when(rerank.rerank(eq("微服务"), eq(List.of(first, second)), eq(1)))
                .thenReturn(List.of(second));

        SearchService service = new SearchService(permissions, registry, control, retrieval, rerank,
                new EmbeddingInstanceReadiness(registry));

        List<ChunkHit> result = service.search(7L, "微服务", null, 20, 1);
        assertThat(result).containsExactly(second);
        assertThat(result.getFirst()).isSameAs(second);
        assertThat(result.getFirst().versionId()).isEqualTo(22L);
        assertThat(result.getFirst().versionNo()).isEqualTo(2);
        assertThat(result.getFirst().sourceLocator()).isEqualTo("source-2");
        verify(rerank).rerank("微服务", List.of(first, second), 1);
        var order = inOrder(control, embedding, retrieval, rerank);
        order.verify(control).current();
        order.verify(embedding).embed(List.of("微服务"));
        order.verify(retrieval).retrieve(eq("微服务"), any(), eq(scope), eq(20));
        order.verify(rerank).rerank("微服务", List.of(first, second), 1);
    }

    @Test
    void embeddingFailureFallsBackToBm25() {
        QueryRetrievalService retrieval = mock(QueryRetrievalService.class);
        EmbeddingControlService control = mock(EmbeddingControlService.class);
        PermissionService permissions = mock(PermissionService.class);
        ProfileEmbeddingClient embedding = mock(ProfileEmbeddingClient.class);
        var registry = registry(embedding);
        var profile = embedding.profile();
        when(control.current()).thenReturn(new EmbeddingControl("ACTIVE", "p", profile.fingerprint(), null, null, 7,
                null));
        PermissionScope scope = PermissionScope.of(7L, (short) 2, List.of(3L), List.of("1:7"));
        when(permissions.resolve(7L, PermissionService.PERM_VIEW)).thenReturn(scope);
        when(embedding.embed(List.of("问题"))).thenThrow(new IllegalStateException("unavailable"));
        ChunkHit hit = hit(1L, "内容");
        when(retrieval.retrieve("问题", null, scope, 5)).thenReturn(List.of(hit));

        SearchService service = new SearchService(permissions, registry, control, retrieval, null,
                new EmbeddingInstanceReadiness(registry));

        assertThat(service.search(7L, "问题", null, 5)).containsExactly(hit);
    }

    @Test
    void searchResolvesPermissionFactsOncePerRequest() {
        var permissionMapper = mock(PermissionMapper.class);
        var identities = mock(IdentityService.class);
        when(identities.resolvePrincipal(7L))
                .thenReturn(new ResolvedPrincipal(7L, 5L, (short) 2, List.of(), List.of()));
        when(permissionMapper.findVisibleSpaceIds(anyList(), eq("view"))).thenReturn(List.of(3L));
        var permissions = new PermissionService(permissionMapper, identities);

        QueryRetrievalService retrieval = mock(QueryRetrievalService.class);
        EmbeddingControlService control = mock(EmbeddingControlService.class);
        ProfileEmbeddingClient embedding = mock(ProfileEmbeddingClient.class);
        var registry = registry(embedding);
        var profile = embedding.profile();
        when(control.current()).thenReturn(new EmbeddingControl("ACTIVE", "p", profile.fingerprint(), null, null, 7,
                null));
        when(embedding.embed(List.of("问题"))).thenThrow(new IllegalStateException("unavailable"));
        ChunkHit hit = hit(1L, "内容");
        when(retrieval.retrieve(eq("问题"), any(), any(), eq(5))).thenReturn(List.of(hit));

        SearchService service = new SearchService(permissions, registry, control, retrieval, null,
                new EmbeddingInstanceReadiness(registry));

        assertThat(service.search(7L, "问题", null, 5)).containsExactly(hit);
        verify(identities, times(1)).resolvePrincipal(7L);
        verify(permissionMapper, never()).findClearanceLevel(anyLong());
        verify(permissionMapper, never()).findRoleIds(anyLong());
        verify(permissionMapper, never()).findDeptChainIds(anyLong());
        verify(permissionMapper, times(1)).findVisibleSpaceIds(anyList(), eq("view"));
    }

    private static EmbeddingClientRegistry registry(ProfileEmbeddingClient client) {
        var definition = new EmbeddingProfileDefinition(new EmbeddingProfile("p", "local", "kb/local", "local-hash-v1",
                "1", 1024, "cosine", "identity-v1", "identity-v1", "query", "document", "local-hash-v1"),
                new EmbeddingConnection(null, null, 30, 16));
        when(client.profile()).thenReturn(definition.profile());
        when(client.role()).thenReturn(EmbeddingRole.QUERY);
        return new EmbeddingClientRegistry(List.of(definition), EmbeddingRole.QUERY, ignored -> client);
    }

    private static ChunkHit hit(long id, String content) {
        return new ChunkHit(id, 10L, 20L + id, 2, "文档", 3L, (short) 1, 0, content,
                1, "章节", "PARAGRAPH", 1, 3, 0, 20, "source-" + id, 0.1);
    }
}
