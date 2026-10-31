package com.zhishiku.kb.app.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.embedding.service.EmbeddingControlService;
import com.zhishiku.kb.app.search.config.SearchLimitsProperties;
import com.zhishiku.kb.app.search.embedding.EmbeddingInstanceReadiness;
import com.zhishiku.kb.app.search.execution.SearchBudget;
import com.zhishiku.kb.app.search.execution.SearchExecutor;
import com.zhishiku.kb.app.search.execution.SearchResponseEncoder;
import com.zhishiku.kb.app.search.model.ChunkHit;
import com.zhishiku.kb.app.search.model.SearchFailure;
import com.zhishiku.kb.app.search.service.QueryRetrievalService;
import com.zhishiku.kb.app.search.service.SearchService;
import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.security.PermissionScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SearchFacadeTest {
    private final PermissionService permissions = mock(PermissionService.class);
    private final EmbeddingControlService control = mock(EmbeddingControlService.class);
    private final QueryRetrievalService retrieval = mock(QueryRetrievalService.class);
    private final EmbeddingClientRegistry clients = mock(EmbeddingClientRegistry.class);
    private final EmbeddingInstanceReadiness readiness = mock(EmbeddingInstanceReadiness.class);

    @Test
    void allEntryPointsUseAdmissionBeforeResolvingPermissions() throws Exception {
        var executor = SearchExecutionStub.immediate();
        var service = service(executor, SearchLimitsProperties.defaults());
        when(permissions.resolve(7, "view")).thenReturn(PermissionScope.of(7, (short) 0, List.of(1L), List.of("1:7")));
        when(control.current()).thenReturn(new EmbeddingControl("UNINITIALIZED", null, null, null, null, 0, null));
        when(retrieval.retrieve(eq("q"), any(), any(), anyInt())).thenReturn(List.of(hit("正文")));
        assertThat(service.search(7, "q", null)).hasSize(1);
        assertThat(service.search(7, "q", null, 20)).hasSize(1);
        assertThat(service.search(7, "q", null, 20, 50)).hasSize(1);
        byte[] bytes = service.searchResponse(7, "q", null, 20, 50);
        var body = new ObjectMapper().readTree(bytes);
        assertThat(body.get("code").intValue()).isEqualTo(200);
        assertThat(body.get("data").get(0).get("docId").longValue()).isEqualTo(Long.MAX_VALUE);
        verify(executor, times(4)).execute(eq(7L), any());
        verify(executor, times(4)).required(any(), eq(SearchBudget.Stage.AUTHORIZATION), any());
        var order = inOrder(executor, permissions);
        order.verify(executor).execute(eq(7L), any());
        order.verify(executor).required(any(), eq(SearchBudget.Stage.AUTHORIZATION), any());
        order.verify(permissions).resolve(7, "view");
    }

    @Test
    void identityResolutionAndRetrievalRunInsideTheDispatchedCall() throws Exception {
        var executor = SearchExecutionStub.immediate();
        Thread caller = Thread.currentThread();
        try (var worker = Executors.newSingleThreadExecutor()) {
            doAnswer(call -> {
                Function<SearchExecutor.Execution, Object> action = call.getArgument(1);
                return worker.submit(() -> action.apply(mock(SearchExecutor.Execution.class))).get();
            }).when(executor).execute(anyLong(), any());
            when(permissions.resolve(7, "view")).thenAnswer(call -> {
                assertThat(Thread.currentThread()).isNotSameAs(caller);
                return PermissionScope.of(7, (short) 0, List.of(1L), List.of("1:7"));
            });
            when(control.current()).thenReturn(new EmbeddingControl("UNINITIALIZED", null, null, null, null, 0, null));
            when(retrieval.retrieve(eq("q"), any(), any(), anyInt())).thenAnswer(call -> {
                assertThat(Thread.currentThread()).isNotSameAs(caller);
                return List.of();
            });
            assertThat(service(executor, SearchLimitsProperties.defaults()).search(7, "q", null)).isEmpty();
        }
    }

    @Test
    void capacityDenialNeverReachesAuthorizationOrRetrieval() {
        var executor = mock(SearchExecutor.class);
        when(executor.execute(anyLong(), any())).thenThrow(new SearchFailure(SearchFailure.Kind.CAPACITY, 1));
        var service = service(executor, SearchLimitsProperties.defaults());
        assertThatThrownBy(() -> service.search(7, "q", null)).isInstanceOf(SearchFailure.class);
        verifyNoInteractions(permissions, control, retrieval, clients);
    }

    @Test
    void largeResultsFailBeforeAnIncompleteSuccessCanBeReturned() {
        var limits = new SearchLimitsProperties(8000, 131072, 100, 100, 100, 128);
        var service = service(SearchExecutionStub.immediate(), limits);
        when(permissions.resolve(7, "view")).thenReturn(PermissionScope.of(7, (short) 0, List.of(1L), List.of("1:7")));
        when(control.current()).thenReturn(new EmbeddingControl("UNINITIALIZED", null, null, null, null, 0, null));
        when(retrieval.retrieve(eq("q"), any(), any(), anyInt())).thenReturn(List.of(hit("内容".repeat(100))));
        assertThatThrownBy(() -> service.searchResponse(7, "q", null, 50, 8))
                .isInstanceOfSatisfying(SearchFailure.class,
                        e -> assertThat(e.kind()).isEqualTo(SearchFailure.Kind.RESPONSE_TOO_LARGE));
        assertThatThrownBy(() -> service.search(7, "q", null)).isInstanceOf(SearchFailure.class);
    }

    private SearchService service(SearchExecutor executor, SearchLimitsProperties limits) {
        return new SearchService(permissions, clients, control, retrieval, null, readiness, executor,
                new SearchResponseEncoder(new ObjectMapper(), limits), limits);
    }

    private static ChunkHit hit(String text) {
        return new ChunkHit(3L, Long.MAX_VALUE, 2L, 1, "标题", 1L, (short) 0, 0, text,
                null, null, null, null, null, null, null, null, 0.5);
    }
}
