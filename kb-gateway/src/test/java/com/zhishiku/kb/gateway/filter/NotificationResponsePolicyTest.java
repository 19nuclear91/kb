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

/** 内存交换对象与 Mock 下游；不启动网关或真实下游。 */
class NotificationResponsePolicyTest {
    private static final List<String> PATHS = List.of("/api/notifications", "/api/notifications/unread-count",
            "/api/notifications/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/read", "/api/notifications/bad/read");

    @Test
    void earlyAuthenticationDenialIsNoStoreAndNeverReachesDownstream() {
        var downstream = mock(WebFilterChain.class);
        WebFilter authentication = (exchange, chain) -> {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        };
        for (String path : PATHS) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.put(path));
            new NotificationResponsePolicy().filter(exchange, current -> authentication.filter(current, downstream))
                    .block(Duration.ofSeconds(1));
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(exchange.getResponse().getHeaders().getCacheControl()).isEqualTo("no-store");
        }
        verifyNoInteractions(downstream);
    }

    @Test
    void downstreamSuccessErrorsAndOverriddenHeadersStayNoStoreWhileOtherRoutesAreUntouched() {
        for (HttpStatus status : List.of(HttpStatus.OK, HttpStatus.BAD_REQUEST, HttpStatus.FORBIDDEN,
                HttpStatus.NOT_FOUND, HttpStatus.SERVICE_UNAVAILABLE)) {
            for (String path : PATHS) {
                var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
                new NotificationResponsePolicy().filter(exchange, current -> {
                    current.getResponse().setStatusCode(status);
                    current.getResponse().getHeaders().setCacheControl("public, max-age=600");
                    return current.getResponse().setComplete();
                }).block(Duration.ofSeconds(1));
                assertThat(exchange.getResponse().getHeaders().getCacheControl()).isEqualTo("no-store");
            }
        }
        for (String other : List.of("/api/ai/conversations/1/turns", "/api/notificationsx", "/notifications")) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(other));
            new NotificationResponsePolicy().filter(exchange, current -> Mono.empty()).block(Duration.ofSeconds(1));
            assertThat(exchange.getResponse().getHeaders().getCacheControl()).as(other).isNull();
        }
    }
}
