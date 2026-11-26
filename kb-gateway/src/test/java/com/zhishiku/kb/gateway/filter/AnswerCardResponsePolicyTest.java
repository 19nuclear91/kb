package com.zhishiku.kb.gateway.filter;

import com.zhishiku.kb.common.security.AnswerCardEndpoints;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerCardResponsePolicyTest {
    @Test
    void exactPathBoundaryAndErrorsArePrivateWithoutClaimingProtocolSuccess() {
        var policy = new AnswerCardResponsePolicy();
        for (String path : List.of("/api/answer-cards", "/api/answer-cards/1/workspace", "/api/answer%2Dcards/search")) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.POST, URI.create(path)));
            policy.filter(exchange, current -> {
                current.getResponse().getHeaders().set("X-Answer-Card-Version", "1");
                current.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                return current.getResponse().setComplete();
            }).block();
            assertThat(exchange.getResponse().getHeaders().getCacheControl()).isEqualTo("no-store");
            assertThat(exchange.getResponse().getHeaders().containsKey("X-Answer-Card-Version")).isFalse();
        }
        for (String path : List.of("/api/answer-cardsx", "/internal/answer-cards", "/api/ai/chat")) {
            assertThat(AnswerCardEndpoints.matches(path)).isFalse();
        }
    }

    @Test
    void chunkedAndDeclaredBodiesEnforceSearchLimitBeforeDownstream() {
        var policy = new AnswerCardResponsePolicy();
        var called = new AtomicBoolean();
        var factory = new DefaultDataBufferFactory();
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/answer-cards/search")
                .body(Flux.just(factory.wrap(new byte[8192]), factory.wrap(new byte[8193]))));
        policy.filter(exchange, current -> {
            called.set(true);
            return Mono.empty();
        }).block();
        assertThat(called).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(exchange.getResponse().getHeaders().getCacheControl()).isEqualTo("no-store");
        var declared = MockServerWebExchange.from(MockServerHttpRequest.post("/api/answer-cards/commands/query").contentLength(131073));
        policy.filter(declared, current -> {
            called.set(true);
            return Mono.empty();
        }).block();
        assertThat(called).isFalse();
        assertThat(declared.getResponse().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }
}
