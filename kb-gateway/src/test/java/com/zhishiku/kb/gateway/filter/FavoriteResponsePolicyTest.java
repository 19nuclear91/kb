package com.zhishiku.kb.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FavoriteResponsePolicyTest {
    @Test
    void favoritesArePrivateEvenWhenTheGatewayRejectsBeforeRouting() {
        var policy = new ConversationResponsePolicy();
        for (String path : List.of("/api/documents/favorites", "/api/documents/favorites/status", "/api/documents/7/favorite")) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
            policy.filter(exchange, current -> {
                assertThat(current.getResponse().getHeaders().getCacheControl()).isEqualTo("no-store");
                current.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return Mono.empty();
            }).block();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        var ordinary = MockServerWebExchange.from(MockServerHttpRequest.get("/api/documents/7"));
        policy.filter(ordinary, current -> Mono.empty()).block();
        assertThat(ordinary.getResponse().getHeaders().getCacheControl()).isNull();
    }
}
