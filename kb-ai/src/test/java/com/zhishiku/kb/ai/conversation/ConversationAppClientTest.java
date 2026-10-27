package com.zhishiku.kb.ai.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.ai.chat.AiProperties;
import com.zhishiku.kb.ai.conversation.config.ConversationProperties;
import com.zhishiku.kb.common.conversation.RequestSignature;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationAppClientTest {
    @Test
    void signsActualMethodPathUnicodeBodyAndTokenAndRetriesWithFreshNonce() {
        var properties = new ConversationProperties();
        properties.setSecret("0123456789abcdef0123456789abcdef");
        AtomicInteger calls = new AtomicInteger();
        var seen = new HashSet<String>();
        var client = new ConversationAppClient(WebClient.builder().exchangeFunction(request -> {
            var target = new MockClientHttpRequest(request.method(), request.url());
            BodyInserter.Context context = new BodyInserter.Context() {
                public List<HttpMessageWriter<?>> messageWriters() {
                    return ExchangeStrategies.withDefaults().messageWriters();
                }

                public Optional<ServerHttpRequest> serverRequest() {
                    return Optional.empty();
                }

                public Map<String, Object> hints() {
                    return Map.of();
                }
            };
            return request.body().insert(target, context).then(Mono.defer(target::getBodyAsString)).map(body -> {
                assertThat(request.method()).isEqualTo(HttpMethod.POST);
                assertThat(request.url().getPath()).isEqualTo("/internal/conversations/append");
                assertThat(body).containsAnyOf("😀", "\\uD83D\\uDE00", "\\ud83d\\ude00");
                var headers = request.headers();
                RequestSignature.verify(properties.getSecret(), "kb-ai", request.method().name(),
                        request.url().getRawPath(),
                        body.getBytes(StandardCharsets.UTF_8), headers.getFirst("satoken"),
                        new RequestSignature.Headers(headers.getFirst("X-Service-Caller"),
                                Long.parseLong(headers.getFirst("X-Service-Timestamp")),
                                headers.getFirst("X-Service-Nonce"), headers.getFirst("X-Service-Signature")),
                        Clock.systemUTC(), 60, 180, (a, b, c) -> seen.add(b));
                return ClientResponse.create(calls.incrementAndGet() == 1 ? HttpStatus.SERVICE_UNAVAILABLE :
                                HttpStatus.OK)
                        .header("Content-Type", "application/json").body("{\"lastBatchSeq\":1}").build();
            });
        }), new AiProperties("http://app", 5, 1000, 30), properties, new ObjectMapper());
        assertThat(client.call("append", Map.of("text", "😀"), "owner-token", true).block().get("lastBatchSeq").asLong()).isEqualTo(1);
        assertThat(calls).hasValue(2);
        assertThat(seen).hasSize(2);
    }

    @Test
    void refusalDoesNotExposeBodyAndDoesNotRetryConflict() {
        var properties = new ConversationProperties();
        properties.setSecret("0123456789abcdef0123456789abcdef");
        AtomicInteger calls = new AtomicInteger();
        var client = new ConversationAppClient(WebClient.builder().exchangeFunction(request -> {
            calls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.CONFLICT).body("private-ticket-question").build());
        }), new AiProperties("http://app", 5, 1000, 30), properties, new ObjectMapper());
        StepVerifier.create(client.call("append", Map.of("text", "a"), null, true))
                .expectErrorMatches(e -> e instanceof ResponseStatusException s && s.getStatusCode().value() == 409 && !e.toString().contains("private")).verify();
        assertThat(calls).hasValue(1);
    }
}

