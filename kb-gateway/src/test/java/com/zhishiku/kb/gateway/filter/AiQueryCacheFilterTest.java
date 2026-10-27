package com.zhishiku.kb.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

class AiQueryCacheFilterTest {
    @Test
    void operationsDenialsAndDownstreamFailuresCannotBeCached() {
        for (var status : java.util.List.of(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.SERVICE_UNAVAILABLE)) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/admin/reports/operations"));
            new AiQueryCacheFilter().filter(exchange, current -> {
                current.getResponse().setStatusCode(status);
                current.getResponse().getHeaders().setCacheControl("public");
                return current.getResponse().setComplete();
            }).block(java.time.Duration.ofSeconds(1));
            assertThat(exchange.getResponse().getHeaders().getCacheControl()).isEqualTo("no-store");
        }
    }

    @Test
    void earlyGatewayDenialCannotBeCached() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/admin/ai/usage"));
        new AiQueryCacheFilter().filter(exchange, current -> {
            current.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            current.getResponse().getHeaders().setCacheControl("public");
            return current.getResponse().setComplete();
        }).block(java.time.Duration.ofSeconds(1));
        assertThat(exchange.getResponse().getHeaders().getCacheControl()).isEqualTo("no-store");
    }
}
