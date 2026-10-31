package com.zhishiku.kb.app.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.embedding.service.EmbeddingControlService;
import com.zhishiku.kb.app.search.config.SearchLimitsProperties;
import com.zhishiku.kb.app.search.embedding.EmbeddingInstanceReadiness;
import com.zhishiku.kb.app.search.execution.SearchBudget;
import com.zhishiku.kb.app.search.execution.SearchResponseEncoder;
import com.zhishiku.kb.app.search.execution.SearchStageFailure;
import com.zhishiku.kb.app.search.model.ChunkHit;
import com.zhishiku.kb.app.search.model.SearchFailure;
import com.zhishiku.kb.app.search.rerank.RerankClient;
import com.zhishiku.kb.app.search.service.QueryRetrievalService;
import com.zhishiku.kb.app.search.service.SearchService;
import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import com.zhishiku.kb.common.embedding.ProfileEmbeddingClient;
import com.zhishiku.kb.common.security.PermissionScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionTimedOutException;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SearchFallbackTest {
    private final PermissionService permissions = mock(PermissionService.class);
    private final EmbeddingControlService control = mock(EmbeddingControlService.class);
    private final EmbeddingClientRegistry registry = mock(EmbeddingClientRegistry.class);
    private final ProfileEmbeddingClient embedding = mock(ProfileEmbeddingClient.class);
    private final QueryRetrievalService retrieval = mock(QueryRetrievalService.class);
    private final RerankClient rerank = mock(RerankClient.class);
    private final PermissionScope scope = PermissionScope.of(7, (short) 0, List.of(1L), List.of("1:7"));
    private final List<ChunkHit> hits = List.of(hit(1), hit(2));
    private final com.zhishiku.kb.app.search.execution.SearchExecutor executor = SearchExecutionStub.immediate();

    private SearchService service() {
        var profile = new EmbeddingProfile("p", "local", "source", "local-hash-v1", "1", 1024,
                "cosine", "identity-v1", "identity-v1", "query", "document", "local-hash-v1");
        when(embedding.profile()).thenReturn(profile);
        when(registry.find("p", profile.fingerprint())).thenReturn(Optional.of(embedding));
        when(permissions.resolve(7, "view")).thenReturn(scope);
        when(control.current()).thenReturn(new EmbeddingControl("ACTIVE", "p", profile.fingerprint(), null, null, 1, null));
        when(retrieval.retrieve(eq("q"), any(), eq(scope), eq(50))).thenReturn(hits);
        return new SearchService(permissions, registry, control, retrieval, rerank,
                mock(EmbeddingInstanceReadiness.class), executor,
                new SearchResponseEncoder(new ObjectMapper(), SearchLimitsProperties.defaults()), SearchLimitsProperties.defaults());
    }

    @ParameterizedTest
    @EnumSource(SearchStageFailure.Reason.class)
    void onlyKnownOptionalFailuresFallbackOnceAndKeepOriginalAuthorizedOrder(SearchStageFailure.Reason reason) {
        var service = service();
        when(embedding.embed(any(), any())).thenThrow(new SearchStageFailure(reason));
        when(rerank.rerank(any(), any(), anyInt())).thenThrow(new SearchStageFailure(reason));
        assertThat(service.search(7, "q", null, 50, 1)).containsExactly(hits.getFirst());
        verify(embedding).embed(any(), any());
        verify(retrieval).retrieve("q", null, scope, 50);
        verify(rerank).rerank("q", hits, 1);
    }

    @Test
    void degradationLogsOnlyFixedStageAndReason() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SearchService.class);
        var capture = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        capture.start();
        logger.addAppender(capture);
        try {
            var service = service();
            when(embedding.embed(any(), any())).thenThrow(new SearchStageFailure(SearchStageFailure.Reason.UPSTREAM));
            service.search(7, "QUERY_CANARY_SECRET", null);
            assertThat(capture.list).isNotEmpty().allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("QUERY_CANARY_SECRET", "userId", "token");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(capture);
            capture.stop();
        }
    }

    @Test
    void insufficientEmbeddingBudgetSkipsTheClientAndStillRecalls() {
        var service = service();
        doThrow(new SearchStageFailure(SearchStageFailure.Reason.TIME_BUDGET))
                .when(executor).optional(any(), eq(SearchBudget.Stage.EMBEDDING), any());
        when(rerank.rerank(any(), any(), anyInt())).thenReturn(hits);
        assertThat(service.search(7, "q", null)).containsExactlyElementsOf(hits);
        verify(embedding, never()).embed(any(), any());
        verify(retrieval).retrieve(eq("q"), isNull(), eq(scope), eq(50));
    }

    @Test
    void cancellationAndUnknownProgrammingErrorsAreNotSwallowed() {
        var service = service();
        when(embedding.embed(any(), any())).thenThrow(new SearchFailure(SearchFailure.Kind.CANCELLED));
        assertThatThrownBy(() -> service.search(7, "q", null)).isInstanceOf(SearchFailure.class);
        doThrow(new NullPointerException("internal")).when(embedding).embed(any(), any());
        assertThatThrownBy(() -> service.search(7, "q", null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(retrieval, rerank);
    }

    @Test
    void knownTransactionFailuresAcrossRequiredStagesAreUnavailableAndPreserveCancellation() {
        var service = service();
        var cancelled = new SearchFailure(SearchFailure.Kind.CANCELLED);
        var timedOut = new SearchFailure(SearchFailure.Kind.TIMEOUT);
        for (var stage : List.of(SearchBudget.Stage.AUTHORIZATION, SearchBudget.Stage.CONTROL, SearchBudget.Stage.RETRIEVAL)) {
            for (var failure : List.of(new CannotCreateTransactionException("private", new SQLException("private")),
                    new TransactionSystemException("private", new SQLException("private")),
                    new TransactionTimedOutException("private"),
                    new CannotCreateTransactionException("private", cancelled),
                    new CannotCreateTransactionException("private", timedOut))) {
                doReturn(scope).when(permissions).resolve(7, "view");
                doReturn(new EmbeddingControl("UNINITIALIZED", null, null, null, null, 0, null)).when(control).current();
                doReturn(hits).when(retrieval).retrieve(any(), any(), any(), anyInt());
                switch (stage) {
                    case AUTHORIZATION -> doThrow(failure).when(permissions).resolve(7, "view");
                    case CONTROL -> doThrow(failure).when(control).current();
                    case RETRIEVAL -> doThrow(failure).when(retrieval).retrieve(any(), any(), any(), anyInt());
                    default -> throw new AssertionError(stage);
                }
                assertThatThrownBy(() -> service.search(7, "q", null)).isInstanceOfSatisfying(SearchFailure.class, error -> {
                    assertThat(error.status()).isEqualTo(503);
                    if (failure.getCause() instanceof SearchFailure original) {
                        assertThat(error).isSameAs(original);
                    } else {
                        assertThat(error.kind()).isEqualTo(failure instanceof TransactionTimedOutException
                                ? SearchFailure.Kind.TIMEOUT : SearchFailure.Kind.DEPENDENCY);
                    }
                    assertThat(error.getMessage()).doesNotContain("private");
                });
            }
        }
        verify(embedding, never()).embed(any(), any());
        verifyNoInteractions(rerank);
    }

    @Test
    void unknownTransactionProgrammingFailuresRemainUnclassified() {
        var service = service();
        for (var failure : List.of(new IllegalTransactionStateException("programming"),
                new TransactionSystemException("programming", new NullPointerException()))) {
            doThrow(failure).when(permissions).resolve(7, "view");
            assertThatThrownBy(() -> service.search(7, "q", null)).isSameAs(failure);
        }
        verifyNoInteractions(control, retrieval, rerank);
    }

    @Test
    void permissionsControlAndSqlFailuresAreCoreErrors() {
        var service = service();
        var failure = new DataAccessResourceFailureException("sensitive dependency");
        doThrow(failure).when(permissions).resolve(7, "view");
        assertThatThrownBy(() -> service.search(7, "q", null)).hasMessage("DEPENDENCY");
        verifyNoInteractions(control, retrieval, rerank);
        when(permissions.resolve(8, "view")).thenReturn(scope);
        doThrow(failure).when(control).current();
        assertThatThrownBy(() -> service.search(8, "q", null)).hasMessage("DEPENDENCY");
        verifyNoInteractions(retrieval, rerank);
        doThrow(failure).when(retrieval).retrieve(any(), any(), any(), anyInt());
        doReturn(new EmbeddingControl("UNINITIALIZED", null, null, null, null, 0, null)).when(control).current();
        assertThatThrownBy(() -> service.search(8, "q", null)).hasMessage("DEPENDENCY");
        verifyNoInteractions(rerank);
    }

    private static ChunkHit hit(long id) {
        return new ChunkHit(id, 10L, 20L, 1, "title", 1L, (short) 0, 0, "text", null, null, null,
                null, null, null, null, null, 0.5);
    }
}
