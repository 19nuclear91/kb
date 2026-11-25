package com.zhishiku.kb.app.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.embedding.service.EmbeddingControlService;
import com.zhishiku.kb.app.search.assistance.AssistedCaller;
import com.zhishiku.kb.app.search.assistance.SearchAssistanceContextProperties;
import com.zhishiku.kb.app.search.assistance.SearchContextKeys;
import com.zhishiku.kb.app.search.assistance.SearchSelectionContext;
import com.zhishiku.kb.app.search.assistance.SignedSearchContext;
import com.zhishiku.kb.app.search.config.SearchLimitsProperties;
import com.zhishiku.kb.app.search.embedding.EmbeddingInstanceReadiness;
import com.zhishiku.kb.app.search.execution.SearchBudget;
import com.zhishiku.kb.app.search.execution.SearchContext;
import com.zhishiku.kb.app.search.execution.SearchExecutor;
import com.zhishiku.kb.app.search.execution.SearchMetrics;
import com.zhishiku.kb.app.search.execution.SearchResponseEncoder;
import com.zhishiku.kb.app.search.execution.SearchStageFailure;
import com.zhishiku.kb.app.search.expansion.SearchQueryPlan;
import com.zhishiku.kb.app.search.expansion.SyntheticTermDictionary;
import com.zhishiku.kb.app.search.model.ChunkHit;
import com.zhishiku.kb.app.search.model.SearchFailure;
import com.zhishiku.kb.app.search.model.SearchFilters;
import com.zhishiku.kb.app.search.model.AssistedSearchInput;
import com.zhishiku.kb.app.search.rerank.RerankClient;
import com.zhishiku.kb.app.search.service.QueryRetrievalService;
import com.zhishiku.kb.app.search.service.SearchAssistanceService;
import com.zhishiku.kb.app.search.service.SearchService;
import com.zhishiku.kb.app.search.term.TermSnapshot;
import com.zhishiku.kb.app.search.term.TermSnapshotReader;
import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingConnection;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import com.zhishiku.kb.common.embedding.EmbeddingProfileDefinition;
import com.zhishiku.kb.common.embedding.EmbeddingResult;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.common.embedding.ProfileEmbeddingClient;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.PermissionScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 被测搜索服务配合身份、词典事务及模型替身，不组装应用或跨服务链路。 */
class AssistedSearchServiceTest {
    @Test
    void assistanceMetricsDistinguishPlansAndFailureKindsWithoutInputLabels() {
        var f = new Fixture();
        for (var entry : java.util.Map.of("unregistered", SearchMetrics.AssistanceOutcome.NO_TERMS, "OA", SearchMetrics.AssistanceOutcome.APPLIED,
                "PM", SearchMetrics.AssistanceOutcome.SUGGESTIONS, "OA 报消", SearchMetrics.AssistanceOutcome.MIXED).entrySet()) {
            f.service.assistedResponse(caller(), body(entry.getKey(), SearchQueryPlan.Mode.ASSISTED));
            verify(f.metrics).assistance(SearchQueryPlan.Mode.ASSISTED, entry.getValue());
        }
        f.service.assistedResponse(caller(), body("OA", SearchQueryPlan.Mode.ORIGINAL));
        verify(f.metrics).assistance(SearchQueryPlan.Mode.ORIGINAL, SearchMetrics.AssistanceOutcome.ORIGINAL);
        for (var entry : java.util.Map.of(SearchFailure.Kind.ASSISTANCE_LIMIT_EXCEEDED, SearchMetrics.AssistanceOutcome.LIMIT_EXCEEDED,
                SearchFailure.Kind.ASSISTANCE_STALE, SearchMetrics.AssistanceOutcome.STALE,
                SearchFailure.Kind.ASSISTANCE_UNAVAILABLE, SearchMetrics.AssistanceOutcome.UNAVAILABLE).entrySet()) {
            doThrow(new SearchFailure(entry.getKey())).when(f.reader).read(any());
            assertThatThrownBy(() -> f.service.assistedResponse(caller(), body("OA", SearchQueryPlan.Mode.ASSISTED))).isInstanceOf(SearchFailure.class);
            verify(f.metrics).assistance(SearchQueryPlan.Mode.ASSISTED, entry.getValue());
        }
    }

    @Test
    void noDocumentScopeStillReturnsPublicAssistanceWithOneAdmissionAndReleasesVerifiedToken() throws Exception {
        var f = new Fixture();
        var caller = caller();
        var result = f.service.assistedResponse(caller, body("PM", SearchQueryPlan.Mode.ASSISTED));
        var data = f.json.readTree(result.json()).get("data");
        assertThat(data.get("hits").size()).isZero();
        assertThat(data.get("assistance").get("suggestions").size()).isEqualTo(1);
        assertThat(data.get("assistance").get("selectionContext").isTextual()).isTrue();
        assertThat(result.assistanceVersion()).isEqualTo("1");
        assertThat(result.filterVersion()).isEqualTo("1");
        verify(f.executor).execute(eq(7L), any());
        verify(f.permissions).resolve(7, PermissionService.PERM_VIEW);
        verify(f.reader).read(any());
        verifyNoInteractions(f.control, f.retrieval, f.rerank, f.embedding);
        assertThatThrownBy(caller::consumeToken).isInstanceOf(IdentityException.class);
        assertThat(f.inSnapshot.get()).isFalse();
    }

    @Test
    void legacyAndOriginalNeverReadDictionaryOrCreateCredentialsEvenWhenDictionaryFails() throws Exception {
        var f = new Fixture();
        doThrow(new SearchFailure(SearchFailure.Kind.ASSISTANCE_UNAVAILABLE)).when(f.reader).read(any());
        var original = f.service.assistedResponse(caller(), body("OA", SearchQueryPlan.Mode.ORIGINAL));
        var data = f.json.readTree(original.json()).get("data");
        assertThat(data.get("assistance").get("mode").textValue()).isEqualTo("ORIGINAL");
        assertThat(data.get("assistance").get("dictionaryRevision").isNull()).isTrue();
        assertThat(data.get("assistance").get("appliedTerms").size()).isZero();
        assertThat(f.json.readTree(f.service.searchResponse(7, "OA", null, 50, 50)).get("data").isArray()).isTrue();
        verifyNoInteractions(f.reader);
    }

    @Test
    void queryEmbeddingKeywordAndRerankShareOneExecutionTextAfterSnapshotEndsAndKeepFilters() throws Exception {
        var f = new Fixture();
        var scope = PermissionScope.of(7, (short) 2, List.of(3L), List.of("1:7"));
        when(f.permissions.resolve(7, PermissionService.PERM_VIEW)).thenReturn(scope);
        var profile = f.embedding.profile();
        when(f.control.current()).thenReturn(new EmbeddingControl("ACTIVE", "p", profile.fingerprint(), null, null, 7, null));
        String execution = "OA\n办公自动化\n旧办公系统";
        when(f.embedding.embed(eq(List.of(execution)), any())).thenAnswer(invocation -> {
            assertThat(f.inSnapshot.get()).isFalse();
            var vector = new float[1024];
            vector[0] = 1;
            return new EmbeddingResult(profile, EmbeddingRole.QUERY, List.of(vector));
        });
        var request = new AssistedSearchInput("OA", 20, 5, new SearchFilters(3L, null, false, List.of(9L, 1L),
                SearchFilters.FileType.PDF, null, null), SearchQueryPlan.Mode.ASSISTED, null, List.of());
        var hit = hit();
        when(f.retrieval.retrieve(eq(execution), any(), eq(scope), eq(20), eq(request.filters()))).thenReturn(List.of(hit));
        when(f.rerank.rerank(execution, List.of(hit), 5)).thenReturn(List.of(hit));
        var response = f.service.assistedResponse(caller(), request);
        verify(f.embedding).embed(eq(List.of(execution)), any());
        verify(f.retrieval).retrieve(eq(execution), any(), eq(scope), eq(20), eq(request.filters()));
        verify(f.rerank).rerank(execution, List.of(hit), 5);
        var data = f.json.readTree(response.json()).get("data");
        assertThat(data.get("hits").get(0).get("chunkId").textValue()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(data.get("assistance").get("originalQuery").textValue()).isEqualTo("OA");
    }

    @Test
    void validCumulativeChoiceRechecksBindingAndMismatchedTokenOrConditionsRejectBeforeRetrieval() throws Exception {
        var f = new Fixture();
        var response = f.service.assistedResponse(caller(), body("PM", SearchQueryPlan.Mode.ASSISTED));
        String context = f.json.readTree(response.json()).get("data").get("assistance").get("selectionContext").textValue();
        var chosen = new AssistedSearchInput("PM", 50, 50, SearchFilters.empty(), SearchQueryPlan.Mode.ASSISTED, context,
                List.of(new SearchQueryPlan.Selection(SearchQueryPlan.SelectionKind.DISAMBIGUATION, 0, 2, 3)));
        var adopted = f.json.readTree(f.service.assistedResponse(caller(), chosen).json()).get("data").get("assistance");
        assertThat(adopted.get("effectiveQuery").textValue()).isEqualTo("项目管理");
        assertThat(adopted.get("suggestions").size()).isZero();
        assertThatThrownBy(() -> f.service.assistedResponse(new AssistedCaller(7, 3, "another-token"), chosen))
                .isInstanceOfSatisfying(SearchFailure.class, error -> assertThat(error.kind()).isEqualTo(SearchFailure.Kind.INVALID_INPUT));
        verifyNoInteractions(f.retrieval, f.embedding, f.rerank);
    }

    @Test
    void rejectionAndDictionaryFailureCannotMasqueradeAsSuccessfulEmptyAssistance() {
        var rejected = new Fixture();
        doThrow(new SearchFailure(SearchFailure.Kind.USER_LIMIT, 1)).when(rejected.executor).execute(anyLong(), any());
        var caller = caller();
        assertThatThrownBy(() -> rejected.service.assistedResponse(caller, body("OA", SearchQueryPlan.Mode.ASSISTED))).isInstanceOf(SearchFailure.class);
        verifyNoInteractions(rejected.reader);
        assertThatThrownBy(caller::consumeToken).isInstanceOf(IdentityException.class);
        var failed = new Fixture();
        doThrow(new SearchFailure(SearchFailure.Kind.ASSISTANCE_UNAVAILABLE)).when(failed.reader).read(any());
        assertThatThrownBy(() -> failed.service.assistedResponse(caller(), body("OA", SearchQueryPlan.Mode.ASSISTED)))
                .isInstanceOfSatisfying(SearchFailure.class, error -> assertThat(error.kind()).isEqualTo(SearchFailure.Kind.ASSISTANCE_UNAVAILABLE));
        verifyNoInteractions(failed.control, failed.embedding, failed.retrieval, failed.rerank);
    }

    @Test
    void rerankFailureKeepsAuthorizedCandidatesAndExpandedKeywordQuery() {
        var f = new Fixture();
        var scope = PermissionScope.of(7, (short) 2, List.of(3L), List.of("1:7"));
        when(f.permissions.resolve(7, PermissionService.PERM_VIEW)).thenReturn(scope);
        when(f.control.current()).thenReturn(new EmbeddingControl("UNINITIALIZED", null, null, null, null, 7, null));
        String text = "OA\n办公自动化\n旧办公系统";
        when(f.retrieval.retrieve(text, null, scope, 50)).thenReturn(List.of(hit()));
        when(f.rerank.rerank(eq(text), any(), eq(50))).thenThrow(new SearchStageFailure(SearchStageFailure.Reason.UPSTREAM));
        assertThat(f.service.assistedResponse(caller(), body("OA", SearchQueryPlan.Mode.ASSISTED)).json()).isNotEmpty();
        verify(f.retrieval).retrieve(text, null, scope, 50);
        verifyNoInteractions(f.embedding);
    }

    private static AssistedCaller caller() {
        return new AssistedCaller(7, 3, "verified-session-token");
    }

    private static AssistedSearchInput body(String query, SearchQueryPlan.Mode mode) {
        return new AssistedSearchInput(query, 50, 50, SearchFilters.empty(), mode, null, List.of());
    }

    private static ChunkHit hit() {
        return new ChunkHit(Long.MAX_VALUE, 10L, 20L, 2, "文档", 3L, (short) 1, 0, "内容", 1,
                null, null, null, null, null, null, null, 0.1);
    }

    private static final class Fixture {
        private final PermissionService permissions = mock(PermissionService.class);
        private final EmbeddingControlService control = mock(EmbeddingControlService.class);
        private final QueryRetrievalService retrieval = mock(QueryRetrievalService.class);
        private final ProfileEmbeddingClient embedding = mock(ProfileEmbeddingClient.class);
        private final RerankClient rerank = mock(RerankClient.class);
        private final TermSnapshotReader reader = mock(TermSnapshotReader.class);
        private final SearchExecutor executor = mock(SearchExecutor.class);
        private final SearchExecutor.Execution execution = mock(SearchExecutor.Execution.class);
        private final SearchContext context = mock(SearchContext.class);
        private final SearchBudget.Window window = mock(SearchBudget.Window.class);
        private final SearchMetrics metrics = mock(SearchMetrics.class);
        private final AtomicBoolean inSnapshot = new AtomicBoolean();
        private final ObjectMapper json = new ObjectMapper();
        private final SearchService service;

        private Fixture() {
            when(permissions.resolve(7, PermissionService.PERM_VIEW)).thenReturn(PermissionScope.denyAll(7));
            when(context.window()).thenReturn(window);
            when(window.remainingNanos()).thenReturn(1_000_000_000L);
            when(window.queryTimeoutSeconds()).thenReturn(1);
            when(executor.execute(anyLong(), any())).thenAnswer(invocation -> invocation.<Function<SearchExecutor.Execution, ?>>getArgument(1).apply(execution));
            when(executor.required(any(), any(), any())).thenAnswer(invocation -> run(invocation.getArgument(2)));
            when(executor.optional(any(), any(), any())).thenAnswer(invocation -> run(invocation.getArgument(2)));
            when(reader.read(any())).thenAnswer(invocation -> {
                inSnapshot.set(true);
                try {
                    return invocation.<Function<TermSnapshot, ?>>getArgument(0).apply(SyntheticTermDictionary.examples());
                } finally {
                    inSnapshot.set(false);
                }
            });
            var definition = new EmbeddingProfileDefinition(new EmbeddingProfile("p", "local", "kb/local", "local-hash-v1", "1", 1024,
                    "cosine", "identity-v1", "identity-v1", "query", "document", "local-hash-v1"), new EmbeddingConnection(null, null, 30, 16));
            when(embedding.profile()).thenReturn(definition.profile());
            when(embedding.role()).thenReturn(EmbeddingRole.QUERY);
            var registry = new EmbeddingClientRegistry(List.of(definition), EmbeddingRole.QUERY, ignored -> embedding);
            var keys = new SearchContextKeys(new SearchAssistanceContextProperties("current", "x".repeat(32), null, null));
            var contexts = new SearchSelectionContext(keys, new SignedSearchContext(keys));
            var limits = SearchLimitsProperties.defaults();
            service = new SearchService(permissions, registry, control, retrieval, rerank, mock(EmbeddingInstanceReadiness.class), executor,
                    new SearchResponseEncoder(json, limits), limits, new SearchAssistanceService(reader, contexts), metrics);
            clearInvocations(embedding);
        }

        private Object run(Supplier<?> action) {
            try (var installed = mockStatic(SearchContext.class)) {
                installed.when(SearchContext::current).thenReturn(context);
                return action.get();
            }
        }
    }
}
