package com.zhishiku.kb.ai.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.common.aiusage.AiObservation;
import com.zhishiku.kb.common.conversation.RequestSignature;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class AiObservationClientTest {
    private final AiObservationProperties props = new AiObservationProperties(new MockEnvironment());
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC);
    AiObservationClientTest() { props.setSecret("0123456789abcdef0123456789abcdef"); props.setEnabled(true); }

    @Test
    void lostResponsesRetrySameIdentityPayloadWithFreshNoncesAndCheckAcknowledgement() {
        var bodies = new ArrayList<String>(); var seen = new HashSet<String>(); var attempts = new AtomicInteger();
        WebClient http = WebClient.builder().exchangeFunction(request -> body(request).map(body -> {
            var headers = request.headers();
            RequestSignature.verify(props.getSecret(), "kb-ai", "POST", request.url().getRawPath(), body.getBytes(StandardCharsets.UTF_8),
                    headers.getFirst("satoken"), new RequestSignature.Headers(headers.getFirst("X-Service-Caller"),
                            Long.parseLong(headers.getFirst("X-Service-Timestamp")), headers.getFirst("X-Service-Nonce"),
                            headers.getFirst("X-Service-Signature")), clock, 60, 180, (a, b, c) -> seen.add(b));
            try {
                var input = json.readTree(body);
                if (request.url().getPath().endsWith("register")) return ok(Map.of("callId", input.path("callId").asText(), "epoch", 1,
                        "userId", 9007199254740993L, "departmentId", 2, "generationStatus", "RUNNING"));
                bodies.add(body);
                if (attempts.incrementAndGet() == 1) return ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).body("private-upstream").build();
                var event = json.readValue(body, AiObservation.Event.class);
                return ok(Map.of("callId", event.callId().toString(), "epoch", 1, "acceptedEventId", event.eventId().toString(), "acceptedDigest", event.digest()));
            } catch (Exception error) { throw new AssertionError(error); }
        })).build();
        var client = new AiObservationClient(http, props, json, clock);
        var session = client.open("private-token").block();
        assertThat(session.principal().userId()).isEqualTo(9007199254740993L);
        session.simple("ACQUIRE_INTENDED").block(); session.simple("ACQUIRE_INTENDED").block();
        assertThat(bodies).hasSize(2); assertThat(bodies.get(0)).isEqualTo(bodies.get(1)); assertThat(seen).hasSize(3);
        assertThat(bodies.get(0)).doesNotContain("private-token", "question", "answer");
    }

    @Test
    void refusalOrWrongIdentityFailsSafelyWithoutRetryingBusinessWork() {
        var attempts = new AtomicInteger();
        var http = WebClient.builder().exchangeFunction(request -> {
            attempts.incrementAndGet(); return Mono.just(ClientResponse.create(HttpStatus.FORBIDDEN).body("private-token-body").build());
        }).build();
        StepVerifier.create(new AiObservationClient(http, props, json, clock).open("token"))
                .expectErrorMatches(error -> error instanceof ResponseStatusException status && status.getStatusCode().value() == 503
                        && !error.toString().contains("private")).verify();
        assertThat(attempts).hasValue(1);
        var wrong = WebClient.builder().exchangeFunction(request -> Mono.just(ok(Map.of("callId", UUID.randomUUID().toString(),
                "epoch", 1, "userId", 1, "departmentId", 2, "generationStatus", "RUNNING")))).build();
        StepVerifier.create(new AiObservationClient(wrong, props, json, clock).open("token")).expectError(ResponseStatusException.class).verify();
    }

    @Test
    void timeoutCancelsEachAttemptAndHasBoundedRetries() {
        props.setTimeoutSeconds(1); props.setRetryAttempts(2);
        var attempts = new AtomicInteger(); var cancelled = new AtomicInteger();
        var http = WebClient.builder().exchangeFunction(request -> Mono.<ClientResponse>never()
                .doOnSubscribe(ignored -> attempts.incrementAndGet()).doOnCancel(cancelled::incrementAndGet)).build();
        StepVerifier.withVirtualTime(() -> new AiObservationClient(http, props, json, clock).open("token"))
                .thenAwait(Duration.ofSeconds(3)).expectError(ResponseStatusException.class).verify();
        assertThat(attempts).hasValue(2); assertThat(cancelled).hasValue(2);
    }

    private ClientResponse ok(Map<String, Object> data) {
        try { return ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                .body(json.writeValueAsString(Map.of("code", 200, "data", data))).build(); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    private Mono<String> body(ClientRequest request) {
        var target = new MockClientHttpRequest(request.method(), request.url());
        return request.body().insert(target, new BodyInserter.Context() {
            public List<HttpMessageWriter<?>> messageWriters() { return ExchangeStrategies.withDefaults().messageWriters(); }
            public Optional<ServerHttpRequest> serverRequest() { return Optional.empty(); }
            public Map<String, Object> hints() { return Map.of(); }
        }).then(Mono.defer(target::getBodyAsString));
    }
}
