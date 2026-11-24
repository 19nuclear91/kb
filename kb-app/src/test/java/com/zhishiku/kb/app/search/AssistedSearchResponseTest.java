package com.zhishiku.kb.app.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.service.EpochSessionService;
import com.zhishiku.kb.app.config.EpochAuthenticationInterceptor;
import com.zhishiku.kb.app.search.config.SearchLimitsProperties;
import com.zhishiku.kb.app.search.execution.SearchResponseEncoder;
import com.zhishiku.kb.app.search.expansion.SearchQueryPlan;
import com.zhishiku.kb.app.search.expansion.SearchQueryPlanner;
import com.zhishiku.kb.app.search.model.ChunkHit;
import com.zhishiku.kb.app.search.model.SearchFailure;
import com.zhishiku.kb.app.search.service.SearchService;
import com.zhishiku.kb.app.search.web.AssistedSearchFailure;
import com.zhishiku.kb.app.search.web.AssistedSearchRequest;
import com.zhishiku.kb.app.search.web.SearchController;
import com.zhishiku.kb.app.search.web.SearchRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 完整编码及直接控制器投影，不组装 MVC。 */
class AssistedSearchResponseTest {
    private final ObjectMapper json = new ObjectMapper();
    private final SearchLimitsProperties limits = SearchLimitsProperties.defaults();

    @Test
    void originalHitsKeepExactIdentityAndBothServiceVersions() throws Exception {
        var encoder = new SearchResponseEncoder(json, limits);
        var plan = original();
        byte[] encoded = encoder.encodeAssisted(List.of(hit("正文")), plan.assistance(null));
        var data = json.readTree(encoded).get("data");
        assertThat(data.get("hits").get(0).get("docId").textValue()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(data.get("hits").get(0).get("versionNo").textValue()).isEqualTo("2");
        assertThat(data.get("assistance").get("effectiveQuery").textValue()).isEqualTo("OA");
        var service = mock(SearchService.class);
        when(service.assistedResponse(any(), any())).thenReturn(new SearchService.AssistedResponse(encoded));
        var request = new MockHttpServletRequest();
        request.setAttribute(EpochAuthenticationInterceptor.VERIFIED, new EpochSessionService.Session(7, 3, EpochSessionService.Method.LOCAL, false));
        var response = new SearchController(service, limits).assisted(new AssistedSearchRequest(new SearchRequest("OA", null, null),
                SearchQueryPlan.Mode.ORIGINAL, null, List.of()), request);
        assertThat(response.getHeaders().getFirst("X-Search-Assistance-Version")).isEqualTo("1");
        assertThat(response.getHeaders().getFirst("X-Search-Filter-Version")).isEqualTo("1");
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getBody()).isEqualTo(encoded);
    }

    @Test
    void incompleteHitAndCombinedResponseLimitNeverReturnPartialBytes() {
        var encoder = new SearchResponseEncoder(json, limits);
        var plan = original();
        var incomplete = new ChunkHit(1L, 2L, "文档", 3L, (short) 1, 0, "正文", null, 0.1);
        assertThatThrownBy(() -> encoder.encodeAssisted(List.of(incomplete), plan.assistance(null))).isInstanceOf(IllegalStateException.class);
        String content = "a".repeat(limits.maxResponseBytes());
        assertThatThrownBy(() -> encoder.encodeAssisted(List.of(hit(content)), plan.assistance(null)))
                .isInstanceOfSatisfying(SearchFailure.class, failure -> assertThat(failure.kind()).isEqualTo(SearchFailure.Kind.RESPONSE_TOO_LARGE));
        var small = new SearchLimitsProperties(8000, 131072, 1048576, 262144, 2097152, 700);
        var bounded = new SearchResponseEncoder(json, small);
        assertThat(bounded.encode(List.of(hit("a".repeat(150))))).hasSizeLessThan(700);
        assertThatThrownBy(() -> bounded.encodeAssisted(List.of(hit("a".repeat(150))), plan.assistance(null))).isInstanceOf(SearchFailure.class);
    }

    @Test
    void stableFailureKindsHaveNoSuccessHeadersAndOnlyCapacityRetryAfter() {
        var kinds = List.of(SearchFailure.Kind.INVALID_INPUT, SearchFailure.Kind.ASSISTANCE_LIMIT_EXCEEDED,
                SearchFailure.Kind.ASSISTANCE_STALE, SearchFailure.Kind.USER_LIMIT, SearchFailure.Kind.CAPACITY,
                SearchFailure.Kind.ASSISTANCE_UNAVAILABLE, SearchFailure.Kind.DEPENDENCY, SearchFailure.Kind.RESPONSE_TOO_LARGE);
        for (var kind : kinds) {
            var failure = new SearchFailure(kind, 2);
            var response = AssistedSearchFailure.project(failure);
            assertThat(response.getStatusCode().value()).isEqualTo(failure.status());
            assertThat(response.getBody().data()).isNull();
            assertThat(response.getBody().reasonCode()).isNotBlank();
            assertThat(response.getHeaders()).doesNotContainKeys("X-Search-Assistance-Version", "X-Search-Filter-Version");
            assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo(kind == SearchFailure.Kind.USER_LIMIT || kind == SearchFailure.Kind.CAPACITY ? "2" : null);
        }
        assertThat(AssistedSearchFailure.project(new IllegalStateException("private-probe")).getStatusCode().value()).isEqualTo(500);
    }

    private static SearchQueryPlan original() {
        return new SearchQueryPlanner().plan(SearchQueryPlan.Mode.ORIGINAL, "OA", 8000, List.of(), null, () -> {
        });
    }

    private static ChunkHit hit(String content) {
        return new ChunkHit(1L, Long.MAX_VALUE, 20L, 2, "文档", 3L, (short) 1, 0, content, null,
                null, null, null, null, null, null, null, 0.1);
    }
}
