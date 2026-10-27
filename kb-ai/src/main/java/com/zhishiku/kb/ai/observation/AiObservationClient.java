package com.zhishiku.kb.ai.observation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.ai.chat.AiProperties;
import com.zhishiku.kb.common.aiusage.AiObservation;
import com.zhishiku.kb.common.conversation.RequestSignature;
import com.zhishiku.kb.common.security.QuotaPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/**
 * 每次重试生成新 nonce，但复用固定事件身份和原始载荷。
 */
@Service
public class AiObservationClient {
    private final WebClient client;
    private final AiObservationProperties properties;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public AiObservationClient(WebClient.Builder builder, AiProperties ai, AiObservationProperties properties, ObjectMapper json) {
        this(builder.baseUrl(ai.appBaseUrl()).codecs(c -> c.defaultCodecs().maxInMemorySize(32768)).build(), properties, json, Clock.systemUTC());
    }

    public AiObservationClient(WebClient client, AiObservationProperties properties, ObjectMapper json, Clock clock) {
        this.client = client; this.properties = properties; this.json = json; this.clock = clock;
    }

    public boolean enabled() { return properties.isEnabled(); }

    public Mono<Session> open(String token) {
        return Mono.defer(() -> {
            UUID id = UUID.randomUUID();
            var registration = new AiObservation.Registration(1, id, clock.instant(), "SINGLE", null);
            return exchange("register", registration, token).map(data -> {
                if (!id.toString().equals(data.path("callId").asText())) throw unavailable();
                long epoch = positive(data.path("epoch"));
                var principal = new QuotaPrincipal(positive(data.path("userId")), positive(data.path("departmentId")));
                if (principal.userId() <= 0 || principal.departmentId() <= 0
                        || !"RUNNING".equals(data.path("generationStatus").asText())) throw unavailable();
                return new Session(id, epoch, principal);
            });
        });
    }

    public Mono<Void> rejectedMulti(UUID turnId) {
        if (!enabled()) return Mono.empty();
        var event = new AiObservation.Event(1, turnId, AiObservation.eventId(turnId, "QUOTA_REJECTED"), 1,
                "QUOTA_REJECTED", clock.instant(), null, null, null, null, null, null, null, null, null);
        return exchange("events", event, null).then();
    }

    private Mono<JsonNode> exchange(String operation, Object value, String token) {
        final byte[] body;
        try { body = json.writeValueAsBytes(value); }
        catch (Exception error) { return Mono.error(unavailable()); }
        if (body.length > 32768) return Mono.error(unavailable());
        String path = "/internal/ai-observability/" + operation;
        return Mono.defer(() -> {
            var signed = RequestSignature.sign(properties.getSecret(), "kb-ai", "POST", path, body, token, clock);
            return client.post().uri(path).contentType(MediaType.APPLICATION_JSON).headers(headers -> {
                headers.set("X-Service-Caller", signed.caller()); headers.set("X-Service-Timestamp", Long.toString(signed.timestamp()));
                headers.set("X-Service-Nonce", signed.nonce()); headers.set("X-Service-Signature", signed.signature());
                if (token != null) headers.set("satoken", token);
            }).bodyValue(body).retrieve().bodyToMono(JsonNode.class)
                    .switchIfEmpty(Mono.error(unavailable())).map(response -> {
                        if (!response.path("code").isIntegralNumber() || response.path("code").asInt() != 200 || !response.path("data").isObject()) throw unavailable();
                        return response.path("data");
                    }).timeout(Duration.ofSeconds(properties.getTimeoutSeconds()));
        }).retryWhen(Retry.max(properties.getRetryAttempts() - 1).filter(error -> error instanceof TimeoutException
                || error instanceof WebClientRequestException || (error instanceof WebClientResponseException response
                && response.getStatusCode().is5xxServerError())).onRetryExhaustedThrow((spec, signal) -> unavailable()))
                .onErrorMap(error -> unavailable());
    }

    public static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 观测暂不可用");
    }

    private static long positive(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1) throw unavailable();
        return value.longValue();
    }

    /**
     * 本次订阅的固定元数据身份，不保存问题、答案或会话令牌。
     */
    public final class Session {
        private final UUID id;
        private final long epoch;
        private final QuotaPrincipal principal;
        private final Map<String, Mono<Void>> operations = new ConcurrentHashMap<>();

        private Session(UUID id, long epoch, QuotaPrincipal principal) { this.id = id; this.epoch = epoch; this.principal = principal; }
        public UUID id() { return id; }
        public QuotaPrincipal principal() { return principal; }
        public Mono<Void> simple(String kind) { return send(kind, null, null, null, null, null, null, null, null, null); }
        public Mono<Void> reserved(String lease, long amount) { return send("RESERVED", UUID.fromString(lease), amount, null, null, null, null, null, null, null); }
        public Mono<Void> finalized(String generation, String result, AiObservation.Usage usage, Long amount, String settlement) {
            return send("FINALIZED", null, null, generation, result, usage.status(), usage.prompt(), usage.completion(), amount, settlement);
        }
        public Mono<Void> settled(String lease, long amount) { return send("SETTLED", UUID.fromString(lease), null, null, null, null, null, null, amount, null); }
        public Mono<Void> renew() {
            return exchange("renew", Map.of("callId", id, "epoch", epoch), null).doOnNext(data -> {
                if (!data.path("accepted").isBoolean() || !data.path("accepted").booleanValue()) throw unavailable();
            }).then();
        }

        private Mono<Void> send(String kind, UUID lease, Long reserved, String generation, String result, String usage,
                                Long prompt, Long completion, Long amount, String settlement) {
            return operations.computeIfAbsent(kind, key -> {
                var event = new AiObservation.Event(1, id, AiObservation.eventId(id, kind), epoch, kind, clock.instant(),
                        lease, reserved, generation, result, usage, prompt, completion, amount, settlement);
                return exchange("events", event, null).doOnNext(data -> {
                    if (!id.toString().equals(data.path("callId").asText()) || positive(data.path("epoch")) != epoch
                            || !event.eventId().toString().equals(data.path("acceptedEventId").asText())
                            || !event.digest().equals(data.path("acceptedDigest").asText())) throw unavailable();
                }).then().cache();
            });
        }
    }
}
