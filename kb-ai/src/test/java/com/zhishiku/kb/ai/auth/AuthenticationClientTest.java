package com.zhishiku.kb.ai.auth;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.reactor.context.SaReactorSyncHolder;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.AuthenticationSignature;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

class AuthenticationClientTest {
    @Test
    void stopReportSendsBoundBytesWithFreshSignatureAndNoUserCredentials() {
        byte[] body = "{\"executionId\":\"opaque-test\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var nonces = new HashSet<String>();
        WebClient wire = WebClient.builder().baseUrl("http://app.invalid").exchangeFunction(request -> {
            assertThat(request.url().getPath()).isEqualTo(AuthenticationSignature.STOP_PATH);
            assertThat(request.headers()).doesNotContainKeys("satoken", "X-User-Id", "traceparent", "baggage");
            var headers = new AuthenticationSignature.Headers(request.headers().getFirst(AuthenticationSignature.CALLER),
                    Long.parseLong(request.headers().getFirst(AuthenticationSignature.TIMESTAMP)),
                    request.headers().getFirst(AuthenticationSignature.NONCE), request.headers().getFirst(AuthenticationSignature.SIGNATURE));
            var target = new org.springframework.mock.http.client.reactive.MockClientHttpRequest(request.method(), request.url());
            return request.body().insert(target, new org.springframework.web.reactive.function.BodyInserter.Context() {
                @Override
                public java.util.List<org.springframework.http.codec.HttpMessageWriter<?>> messageWriters() {
                    return org.springframework.web.reactive.function.client.ExchangeStrategies.withDefaults().messageWriters();
                }
                @Override
                public java.util.Optional<org.springframework.http.server.reactive.ServerHttpRequest> serverRequest() {
                    return java.util.Optional.empty();
                }
                @Override
                public java.util.Map<String, Object> hints() {
                    return java.util.Map.of();
                }
            }).then(Mono.defer(target::getBodyAsString)).map(sent -> {
                assertThat(sent.getBytes(java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(body);
                AuthenticationSignature.verifyStop(SECRET, "POST", request.url().getPath(), body, headers,
                        java.time.Clock.systemUTC(), (caller, nonce, ttl) -> nonces.add(nonce));
                return ClientResponse.create(HttpStatus.NO_CONTENT).build();
            });
        }).build();
        var client = new AuthenticationClient(wire, SECRET);
        client.reportStop(body).block();
        client.reportStop(body).block();
        assertThat(nonces).hasSize(2);
    }
    private static final String SECRET = "test-auth-secret-32-bytes-minimum";
    private static final String IDENTITY = "{\"userId\":7,\"authEpoch\":3,\"mustChangePassword\":false,\"checkedAt\":\"2026-11-06T00:00:00Z\"}";

    @Test
    void everyRequestGetsFreshSignatureWithoutUserOrTracingHeadersOrPositiveCache() {
        AtomicInteger calls = new AtomicInteger();
        Set<String> nonces = new HashSet<>();
        WebClient wire = WebClient.builder().baseUrl("http://app.invalid").exchangeFunction(request -> {
            calls.incrementAndGet();
            assertThat(request.headers().getFirst("satoken")).isEqualTo("token");
            assertThat(request.headers()).doesNotContainKeys("X-User-Id", "traceparent", "baggage");
            assertThat(nonces.add(request.headers().getFirst(AuthenticationSignature.NONCE))).isTrue();
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body(IDENTITY).build());
        }).build();
        var client = new AuthenticationClient(wire, SECRET);
        assertThat(client.check("token").block().authEpoch()).isEqualTo(3);
        assertThat(client.check("token").block().userId()).isEqualTo(7);
        assertThat(calls).hasValue(2);
    }

    @Test
    void timeoutUsesVirtualTimeAndCancelsTheOutstandingCheck() {
        AtomicInteger cancellations = new AtomicInteger();
        WebClient wire = WebClient.builder().baseUrl("http://app.invalid")
                .exchangeFunction(request -> Mono.<ClientResponse>never().doOnCancel(cancellations::incrementAndGet)).build();
        StepVerifier.withVirtualTime(() -> new AuthenticationClient(wire, SECRET).check("token"))
                .thenAwait(Duration.ofSeconds(2))
                .expectErrorSatisfies(error -> assertThat(((ApiException) error).httpStatus()).isEqualTo(503)).verify();
        assertThat(cancellations).hasValue(1);
    }

    @Test
    void directServiceAccessRejectsUnknownIdentityBeforeInvokingHandler() {
        WebClient wire = WebClient.builder().baseUrl("http://app.invalid")
                .exchangeFunction(request -> Mono.error(new IllegalStateException("private upstream details"))).build();
        var filter = new CurrentIdentityFilter(new AuthenticationClient(wire, SECRET));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/ai/chat")
                .header("X-User-Id", "999").build());
        AtomicInteger handlers = new AtomicInteger();
        try (var holder = mockStatic(SaReactorSyncHolder.class); var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getTokenValue).thenReturn("token");
            filter.filter(exchange, ignored -> {
                handlers.incrementAndGet();
                return Mono.empty();
            }).block();
        }
        assertThat(handlers).hasValue(0);
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(exchange.getResponse().getBodyAsString().block()).doesNotContain("private", "999");
    }

    @Test
    void onlyConfiguredSurfacePublicRoutesSkipTheIndependentIdentityCheck() {
        AtomicInteger calls = new AtomicInteger();
        WebClient wire = WebClient.builder().baseUrl("http://app.invalid").exchangeFunction(request -> {
            calls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.UNAUTHORIZED).build());
        }).build();
        var filter = new CurrentIdentityFilter(new AuthenticationClient(wire, SECRET));
        try (var holder = mockStatic(SaReactorSyncHolder.class); var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getTokenValue).thenReturn("token");
            var publicRoute = MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/login").build());
            filter.filter(publicRoute, ignored -> Mono.empty()).block();
            assertThat(calls).hasValue(1);
            var protectedRoute = MockServerWebExchange.from(MockServerHttpRequest.get("/api/auth/login").build());
            filter.filter(protectedRoute, ignored -> Mono.empty()).block();
            assertThat(protectedRoute.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void malformedSuccessAndRedirectsAreUnavailableInsteadOfAuthorized() {
        for (ClientResponse response : new ClientResponse[]{
                ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body("{\"userId\":7}").build(),
                ClientResponse.create(HttpStatus.FOUND).header("Location", "https://external.invalid").build()}) {
            WebClient wire = WebClient.builder().baseUrl("http://app.invalid")
                    .exchangeFunction(request -> Mono.just(response)).build();
            StepVerifier.create(new AuthenticationClient(wire, SECRET).check("token"))
                    .expectErrorSatisfies(error -> assertThat(((ApiException) error).httpStatus()).isEqualTo(503)).verify();
        }
    }
}
