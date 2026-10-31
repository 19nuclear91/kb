package com.zhishiku.kb.app.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.search.config.SearchLimitsProperties;
import com.zhishiku.kb.app.search.execution.SearchMetrics;
import com.zhishiku.kb.app.search.execution.SearchResponseEncoder;
import com.zhishiku.kb.app.search.execution.SearchTransaction;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

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
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final SearchMetrics metrics = new SearchMetrics(meters);
    private final PermissionScope scope = PermissionScope.of(7L, (short) 2, List.of(3L), List.of("1:7"));

    @Test
    void locksAndRechecksEpochProfileAndStateBeforeVectorSql() {
        float[] vector = new float[1024];
        vector[0] = 1f;
        var prepared = new PreparedEmbeddingQuery(7, new EmbeddingResult(profile, EmbeddingRole.QUERY,
                List.of(vector)));
        var transaction = mock(SearchTransaction.class);
        when(transaction.execute(any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
        var retrieval = new QueryRetrievalService(control, mapper, transaction, metrics);
        when(control.lockShared()).thenReturn(new EmbeddingControl("ACTIVE", "p", profile.fingerprint(), null, null,
                7, null));
        retrieval.retrieve("q", prepared, scope, 5);
        var order = inOrder(control, mapper);
        order.verify(control).lockShared();
        order.verify(mapper).hybridSearch(eq("q"), startsWith("[1.0,"), eq(List.of(3L)), anyList(), eq((short) 2),
                eq(5), eq("p"));
        assertThat(meters.find("kb.search.degradation").counters()).isEmpty();
        int degraded = 0;
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
            assertThat(meters.get("kb.search.degradation").tags("stage", "RETRIEVAL", "reason", "PROFILE_UNAVAILABLE")
                    .counter().count()).isEqualTo(++degraded);
        }
        retrieval.retrieve("q", null, scope, 5);
        assertThat(meters.get("kb.search.degradation").counter().count()).isEqualTo(degraded);
        when(control.lockShared()).thenThrow(new IllegalStateException("control unavailable"));
        clearInvocations(mapper);
        assertThatThrownBy(() -> retrieval.retrieve("q", prepared, scope, 5)).hasMessage("control unavailable");
        verifyNoInteractions(mapper);
        assertThat(meters.get("kb.search.degradation").counter().count()).isEqualTo(degraded);
        assertThat(meters.get("kb.search.degradation").counter().getId().getTags()).hasSize(2);
    }

    @Test
    void publicServiceRejectsBareVectorsAndDoesNotFallBackOnControlFailure() {
        var permissions = mock(PermissionService.class);
        var retrieval = mock(QueryRetrievalService.class);
        var definition = new EmbeddingProfileDefinition(profile, new EmbeddingConnection(null, null, 30, 16));
        var registry = new EmbeddingClientRegistry(List.of(definition), EmbeddingRole.QUERY,
                d -> new LocalProfileEmbeddingClient(d, EmbeddingRole.QUERY));
        var instance = new EmbeddingInstanceReadiness(registry);
        var service = service(permissions, registry, retrieval, instance);
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
    void sharedLockAndRecallStayInsideOneTransactionAndFailureEscapes() {
        var transaction = mock(SearchTransaction.class);
        var active = new AtomicBoolean();
        when(transaction.execute(any())).thenAnswer(call -> {
            active.set(true);
            try {
                return ((Supplier<?>) call.getArgument(0)).get();
            } finally {
                active.set(false);
            }
        });
        when(control.lockShared()).thenAnswer(call -> {
            assertThat(active).isTrue();
            return new EmbeddingControl("UNINITIALIZED", null, null, null, null, 0, null);
        });
        when(mapper.hybridSearch(any(), isNull(), anyList(), anyList(), eq((short) 2), eq(5), isNull()))
                .thenAnswer(call -> {
                    assertThat(active).isTrue();
                    throw new IllegalStateException("sql failure");
                });
        var service = new QueryRetrievalService(control, mapper, transaction, metrics);
        assertThatThrownBy(() -> service.retrieve("q", null, scope, 5)).hasMessage("sql failure");
        assertThat(active).isFalse();
        var order = inOrder(transaction, control, mapper);
        order.verify(transaction).execute(any());
        order.verify(control).lockShared();
        order.verify(mapper).hybridSearch(eq("q"), isNull(), anyList(), anyList(), eq((short) 2), eq(5), isNull());
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
        service(permissions, lagging, retrieval, instance).search(7, "query", null);
        verify(retrieval).retrieve(eq("query"), isNull(), eq(scope), eq(50));
        verify(oldClient, never()).embed(any());
        assertThat(instance.snapshot().ready()).isFalse();
        assertThat(instance.snapshot().reason()).isEqualTo("MISSING_PROFILE");
        assertThat(instance.snapshot().profileId()).isEqualTo("next");
        assertThat(instance.snapshot().epoch()).isEqualTo(9);
        clearInvocations(retrieval);
        service(permissions, upgraded, retrieval, new EmbeddingInstanceReadiness(upgraded)).search(7, "query", null);
        var prepared = ArgumentCaptor.forClass(PreparedEmbeddingQuery.class);
        verify(retrieval).retrieve(eq("query"), prepared.capture(), eq(scope), eq(50));
        assertThat(prepared.getValue().epoch()).isEqualTo(9);
        assertThat(prepared.getValue().result().profileId()).isEqualTo("next");
    }

    private SearchService service(PermissionService permissions, EmbeddingClientRegistry registry,
                                  QueryRetrievalService retrieval, EmbeddingInstanceReadiness readiness) {
        return new SearchService(permissions, registry, control, retrieval, null, readiness,
                SearchExecutionStub.immediate(), new SearchResponseEncoder(new ObjectMapper(), SearchLimitsProperties.defaults()),
                SearchLimitsProperties.defaults());
    }
}
