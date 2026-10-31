package com.zhishiku.kb.app.search;

import com.zhishiku.kb.app.config.GlobalExceptionHandler;
import com.zhishiku.kb.app.search.model.SearchFailure;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchFailureTest {
    @Test
    void errorsHaveFixedStatusesAndCapacityHasRetryAdvice() {
        var handler = new GlobalExceptionHandler();
        for (var kind : SearchFailure.Kind.values()) {
            var failure = new SearchFailure(kind,
                    kind == SearchFailure.Kind.USER_LIMIT || kind == SearchFailure.Kind.CAPACITY ? 2 : 0);
            var response = handler.handleSearch(failure);
            assertThat(response.getStatusCode().value()).isEqualTo(failure.status());
            assertThat(response.getBody().message()).isEqualTo(failure.safeMessage());
            assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
            assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo(failure.retryAfter() > 0 ? "2" : null);
        }
        assertThat(new SearchFailure(SearchFailure.Kind.INVALID_INPUT).status()).isEqualTo(400);
        assertThat(new SearchFailure(SearchFailure.Kind.REQUEST_TOO_LARGE).status()).isEqualTo(413);
        assertThat(new SearchFailure(SearchFailure.Kind.USER_LIMIT).status()).isEqualTo(429);
        assertThat(new SearchFailure(SearchFailure.Kind.DEPENDENCY).status()).isEqualTo(503);
    }
}
