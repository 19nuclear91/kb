package com.zhishiku.kb.app.search;

import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.embedding.service.EmbeddingControlService;
import com.zhishiku.kb.app.search.embedding.EmbeddingInstanceReadiness;
import com.zhishiku.kb.app.search.mapper.SearchMapper;
import com.zhishiku.kb.app.search.service.QueryRetrievalService;
import com.zhishiku.kb.app.search.service.SearchService;
import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingConnection;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import com.zhishiku.kb.common.embedding.EmbeddingProfileDefinition;
import com.zhishiku.kb.common.embedding.EmbeddingResult;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.common.embedding.LocalProfileEmbeddingClient;
import com.zhishiku.kb.common.embedding.PreparedEmbeddingQuery;
import com.zhishiku.kb.common.security.PermissionScope;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QueryConsistencyTest {
    private final EmbeddingProfile profile = new EmbeddingProfile("p", "local", "kb/local", "local-hash-v1", "1",
            1024, "cosine", "identity-v1", "identity-v1", "query", "document", "local-hash-v1");
    private final EmbeddingControlService control = mock(EmbeddingControlService.class);
    private final SearchMapper mapper = mock(SearchMapper.class);
    private final PermissionScope scope = PermissionScope.of(7L, (short) 2, List.of(3L), List.of("1:7"));

    @Test
    void locksAndRechecksEpochProfileAndStateBeforeVectorSql() {
        float[] vector = new float[1024];
        vector[0] = 1f;
        var prepared = new PreparedEmbeddingQuery(7, new EmbeddingResult(profile, EmbeddingRole.QUERY,
                List.of(vector)));
        var retrieval = new QueryRetrievalService(control, mapper);
        when(control.lockShared()).thenReturn(new EmbeddingControl("ACTIVE", "p", profile.fingerprint(), null, null,
                7, null));
        retrieval.retrieve("q", prepared, scope, 5);
        var order = inOrder(control, mapper);
        order.verify(control).lockShared();
        order.verify(mapper).hybridSearch(eq("q"), startsWith("[1.0,"), eq(List.of(3L)), anyList(), eq((short) 2),
                eq(5), eq("p"));
        for (EmbeddingControl changed : List.of(
                new EmbeddingControl("ACTIVE", "p", profile.fingerprint(), null, null, 8, null),
                new EmbeddingControl("ACTIVE", "other", "other-fingerprint", null, null, 7, null),
                new EmbeddingControl("REBUILDING", "p", profile.fingerprint(), "next", "next-fingerprint", 7,
                        UUID.randomUUID()),
                new EmbeddingControl("BLOCKED", "p", profile.fingerprint(), "next", "next-fingerprint", 7,
                        UUID.randomUUID()))) {
            clearInvocations(mapper);
            when(control.lockShared()).thenReturn(changed);
            retrieval.retrieve("q", prepared, scope, 5);
            verify(mapper).hybridSearch(eq("q"), isNull(), anyList(), anyList(), eq((short) 2), eq(5), isNull());
        }
        when(control.lockShared()).thenThrow(new IllegalStateException("control unavailable"));
        clearInvocations(mapper);
        assertThatThrownBy(() -> retrieval.retrieve("q", prepared, scope, 5)).hasMessage("control unavailable");
        verifyNoInteractions(mapper);
    }

    @Test
    void publicServiceRejectsBareVectorsAndDoesNotFallBackOnControlFailure() {
        var permissions = mock(PermissionService.class);
        var retrieval = mock(QueryRetrievalService.class);
        var definition = new EmbeddingProfileDefinition(profile, new EmbeddingConnection(null, null, 30, 16));
        var registry = new EmbeddingClientRegistry(List.of(definition), EmbeddingRole.QUERY,
                d -> new LocalProfileEmbeddingClient(d, EmbeddingRole.QUERY));
        var instance = new EmbeddingInstanceReadiness(registry);
        var service = new SearchService(permissions, registry, control, retrieval, null, instance);
        assertThatThrownBy(() -> service.search(7, "q", "[1,0]"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(permissions, control, retrieval);
        when(permissions.resolve(7, "view")).thenReturn(scope);
        when(control.current()).thenThrow(new IllegalStateException("control unavailable"));
        assertThatThrownBy(() -> service.search(7, "q", null)).hasMessage("control unavailable");
        assertThat(instance.snapshot().ready()).isFalse();
        assertThat(instance.snapshot().reason()).isEqualTo("CONTROL_UNAVAILABLE");
        verifyNoInteractions(retrieval);
    }

    @Test
    void transactionBoundariesExcludeModelAndRerankAndRequireSharedLockTransaction() throws Exception {
        for (var method : SearchService.class.getDeclaredMethods()) {
            if (method.getName().equals("search")) {
                assertThat(method.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
            }
        }
        assertThat(QueryRetrievalService.class.getMethod("retrieve", String.class, PreparedEmbeddingQuery.class,
                PermissionScope.class, int.class).getAnnotation(Transactional.class).timeout()).isEqualTo(10);
        assertThat(EmbeddingControlService.class.getMethod("lockShared").getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.MANDATORY);
    }

    @Test
    void activatedProfileIsSelectedWhileLaggingInstanceUsesBm25() {
        var next = new EmbeddingProfile("next", "local", "kb/local", "local-hash-v1", "2", 1024,
                "cosine", "identity-v1", "identity-v1", "query", "document", "local-hash-v1");
        var oldDefinition = new EmbeddingProfileDefinition(profile, new EmbeddingConnection(null, null, 30, 16));
        var nextDefinition = new EmbeddingProfileDefinition(next, new EmbeddingConnection(null, null, 30, 16));
        var oldClient = spy(new LocalProfileEmbeddingClient(oldDefinition, EmbeddingRole.QUERY));
        var lagging = new EmbeddingClientRegistry(List.of(oldDefinition), EmbeddingRole.QUERY, d -> oldClient);
        var upgraded = new EmbeddingClientRegistry(List.of(oldDefinition, nextDefinition), EmbeddingRole.QUERY,
                d -> new LocalProfileEmbeddingClient(d, EmbeddingRole.QUERY));
        var permissions = mock(PermissionService.class);
        when(permissions.resolve(7, "view")).thenReturn(scope);
        when(control.current()).thenReturn(new EmbeddingControl("ACTIVE", "next", next.fingerprint(), null, null, 9,
                null));
        var retrieval = mock(QueryRetrievalService.class);
        var instance = new EmbeddingInstanceReadiness(lagging);
        new SearchService(permissions, lagging, control, retrieval, null, instance).search(7, "query", null);
        verify(retrieval).retrieve(eq("query"), isNull(), eq(scope), eq(50));
        verify(oldClient, never()).embed(any());
        assertThat(instance.snapshot().ready()).isFalse();
        assertThat(instance.snapshot().reason()).isEqualTo("MISSING_PROFILE");
        assertThat(instance.snapshot().profileId()).isEqualTo("next");
        assertThat(instance.snapshot().epoch()).isEqualTo(9);
        clearInvocations(retrieval);
        new SearchService(permissions, upgraded, control, retrieval, null, new EmbeddingInstanceReadiness(upgraded)).search(7, "query", null);
        var prepared = ArgumentCaptor.forClass(PreparedEmbeddingQuery.class);
        verify(retrieval).retrieve(eq("query"), prepared.capture(), eq(scope), eq(50));
        assertThat(prepared.getValue().epoch()).isEqualTo(9);
        assertThat(prepared.getValue().result().profileId()).isEqualTo("next");
    }
}
