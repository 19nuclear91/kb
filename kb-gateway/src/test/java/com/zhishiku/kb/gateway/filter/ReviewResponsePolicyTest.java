package com.zhishiku.kb.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ReviewResponsePolicyTest {
    @Test
    void earlyAuthenticationDenialIsPrivateAndDoesNotCallDownstream() {
        var downstream = mock(WebFilterChain.class);
        WebFilter authentication = (exchange, chain) -> {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        };
        for (String path : paths()) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
            new ReviewResponsePolicy().filter(exchange, current -> authentication.filter(current, downstream)).block(Duration.ofSeconds(1));
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(exchange.getResponse().getHeaders().getCacheControl()).isEqualTo("no-store");
        }
        verifyNoInteractions(downstream);
    }

    @Test
    void downstreamSuccessFailureAndOverriddenCacheHeadersRemainPrivate() {
        for (HttpStatus status : List.of(HttpStatus.OK, HttpStatus.BAD_REQUEST, HttpStatus.FORBIDDEN,
                HttpStatus.NOT_FOUND, HttpStatus.CONFLICT, HttpStatus.SERVICE_UNAVAILABLE)) {
            for (String path : paths()) {
                var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
                new ReviewResponsePolicy().filter(exchange, current -> {
                    current.getResponse().setStatusCode(status);
                    current.getResponse().getHeaders().setCacheControl("public");
                    return current.getResponse().setComplete();
                }).block(Duration.ofSeconds(1));
                assertThat(exchange.getResponse().getHeaders().getCacheControl()).isEqualTo("no-store");
            }
        }
        var stream = MockServerWebExchange.from(MockServerHttpRequest.post("/api/ai/conversations/1/turns"));
        new ReviewResponsePolicy().filter(stream, current -> Mono.empty()).block(Duration.ofSeconds(1));
        assertThat(stream.getResponse().getHeaders().getCacheControl()).isNull();
    }

    private static List<String> paths() {
        return List.of("/api/reviews", "/api/reviews/invalid", "/api/documents", "/api/documents/7",
                "/api/documents/7/versions/8/review", "/api/documents/7/versions/8/reviews/bad/approve",
                "/api/documents/7/progress", "/api/admin/spaces/7/publication-policy",
                "/api/documents/7/versions/8/comments", "/api/documents/x/versions/y/comments/bad/replies",
                "/api/documents/7/versions/8/comments/00000000-0000-0000-0000-000000000001",
                "/api/documents/query", "/api/documents/query/categories", "/api/documents/7/metadata",
                "/api/admin/document-tags/query", "/api/admin/spaces/7/categories/8",
                "/api/admin/documents/7/relocation-context");
    }
}
