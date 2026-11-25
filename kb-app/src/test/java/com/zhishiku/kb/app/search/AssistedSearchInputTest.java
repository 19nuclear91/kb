package com.zhishiku.kb.app.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.search.config.SearchLimitsProperties;
import com.zhishiku.kb.app.search.expansion.SearchQueryPlan;
import com.zhishiku.kb.app.search.model.SearchFailure;
import com.zhishiku.kb.app.search.web.AssistedSearchRequest;
import com.zhishiku.kb.app.search.web.SearchInputAdvice;
import com.zhishiku.kb.app.search.web.SearchRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 直接解码及正文 advice 单测，不组装 MVC 或监听服务。 */
class AssistedSearchInputTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void defaultsExactIdentityAndCompleteBaseFiltersRemainCompatible() throws Exception {
        var empty = json.readValue("{\"query\":\"OA\"}", AssistedSearchRequest.class);
        assertThat(empty.mode()).isEqualTo(SearchQueryPlan.Mode.ASSISTED);
        assertThat(empty.candidates()).isEqualTo(50);
        assertThat(empty.topK()).isEqualTo(50);
        assertThat(empty.selectionContext()).isNull();
        var selected = json.readValue("""
                {"query":"😀PM", "candidates":20, "topK":5, "tagIds":[9,1], "spaceId":9223372036854775807,
                 "selectionContext":"opaque", "selections":[{"kind":"DISAMBIGUATION","start":1,"end":3,"termId":9223372036854775807}]}
                """, AssistedSearchRequest.class);
        assertThat(selected.selections().getFirst().termId()).isEqualTo(Long.MAX_VALUE);
        assertThat(selected.base().spaceId()).isEqualTo(Long.MAX_VALUE);
        assertThat(selected.base().tagIds()).containsExactly(1L, 9L);
        var serviceInput = selected.toInput();
        assertThat(serviceInput.query()).isEqualTo("😀PM");
        assertThat(serviceInput.candidates()).isEqualTo(20);
        assertThat(serviceInput.topK()).isEqualTo(5);
        assertThat(serviceInput.filters()).isEqualTo(selected.base().filters());
        assertThat(serviceInput.selections()).isEqualTo(selected.selections());
        assertThat(serviceInput.selectionContext()).isEqualTo("opaque");
        assertThat(serviceInput.toString()).doesNotContain("opaque", "😀PM");
        assertThat(selected.toString()).doesNotContain("opaque", "😀PM");
        assertThat(json.readValue("{\"query\":\"OA\"}", SearchRequest.class).query()).isEqualTo("OA");
    }

    @Test
    void nullUnknownDuplicateTrailingAndInvalidSelectionsAreRejectedAsAWhole() {
        for (String extra : List.of("\"mode\":null", "\"mode\":\"OTHER\"", "\"selections\":null", "\"selectionContext\":\"unexpected\"",
                "\"userId\":1", "\"mode\":\"ASSISTED\",\"mode\":\"ORIGINAL\"")) {
            assertThatThrownBy(() -> json.readValue("{\"query\":\"PM\"," + extra + "}", AssistedSearchRequest.class)).isInstanceOf(Exception.class);
        }
        for (String item : List.of("{\"kind\":\"OTHER\",\"start\":0,\"end\":2,\"termId\":1}",
                "{\"kind\":\"CORRECTION\",\"start\":0,\"end\":3,\"termId\":1}",
                "{\"kind\":\"CORRECTION\",\"start\":0,\"end\":2,\"termId\":\"1\"}",
                "{\"kind\":\"CORRECTION\",\"start\":0,\"end\":2,\"termId\":9223372036854775808}",
                "{\"kind\":\"CORRECTION\",\"start\":0.0,\"end\":2,\"termId\":1}")) {
            assertThatThrownBy(() -> json.readValue("{\"query\":\"PM\",\"selectionContext\":\"opaque\",\"selections\":[" + item + "]}", AssistedSearchRequest.class))
                    .isInstanceOf(Exception.class);
        }
        String item = "{\"kind\":\"CORRECTION\",\"start\":0,\"end\":2,\"termId\":1}";
        assertThatThrownBy(() -> json.readValue("{\"query\":\"PM\",\"selectionContext\":\"opaque\",\"selections\":[" + item + "," + item + "]}", AssistedSearchRequest.class))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> json.readValue("{\"query\":\"PM\"} {}", AssistedSearchRequest.class)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> json.readValue("{\"query\":\"PM\",\"mode\":\"ORIGINAL\",\"selectionContext\":\"opaque\",\"selections\":[" + item + "]}", AssistedSearchRequest.class))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> json.readValue("{\"query\":\"PM\",\"mode\":\"ASSISTED\"}", SearchRequest.class)).isInstanceOf(Exception.class);
    }

    @Test
    void actualBodyLimitAndConfiguredCodepointLimitApplyBeforeWork() {
        var limits = SearchLimitsProperties.defaults();
        var advice = new SearchInputAdvice(limits);
        var body = new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(new byte[limits.maxRequestBytes() + 1]);
            }

            @Override
            public HttpHeaders getHeaders() {
                return new HttpHeaders();
            }
        };
        assertThatThrownBy(() -> advice.beforeBodyRead(body, null, AssistedSearchRequest.class, null))
                .isInstanceOfSatisfying(SearchFailure.class, error -> assertThat(error.kind()).isEqualTo(SearchFailure.Kind.REQUEST_TOO_LARGE));
        var request = new AssistedSearchRequest(new SearchRequest("😀".repeat(8001), null, null), SearchQueryPlan.Mode.ASSISTED, null, List.of());
        assertThatThrownBy(() -> request.validate(8000)).isInstanceOf(SearchFailure.class);
    }
}
