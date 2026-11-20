package com.zhishiku.kb.ai.suggestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.ai.chat.SuggestionQuota;
import com.zhishiku.kb.common.conversation.RequestSignature;
import com.zhishiku.kb.common.suggestion.SuggestionProtocol;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.Set;

@RestController
public class SuggestionInternalController {
    private final String secret;
    private final ObjectMapper json;
    private final SuggestionInference inference;
    private final SuggestionQuota quota;
    private final ReactiveStringRedisTemplate redis;
    private final boolean enabled;
    private final Semaphore executions = new Semaphore(4);

    public SuggestionInternalController(@Value("${kb.metadata-suggestions.secret:}") String secret, ObjectMapper json,
                                        SuggestionInference inference, SuggestionQuota quota, ReactiveStringRedisTemplate redis,
                                        @Value("${kb.metadata-suggestions.enabled:false}") boolean enabled) {
        this.secret = secret;
        this.json = json;
        this.inference = inference;
        this.quota = quota;
        this.redis = redis;
        this.enabled = enabled;
    }

    @PostMapping("/internal/metadata-suggestions/{operation}")
    public Mono<ResponseEntity<Object>> handle(@PathVariable String operation, ServerHttpRequest request) {
        if (!Set.of("run", "settle", "stop").contains(operation) || request.getURI().getRawQuery() != null) {
            return Mono.just(ResponseEntity.badRequest().header("Cache-Control", "no-store").build());
        }
        if ("run".equals(operation)) {
            return Mono.defer(() -> {
                if (!executions.tryAcquire()) {
                    return Mono.just(ResponseEntity.status(503).header("Cache-Control", "no-store").build());
                }
                return read(operation, request, SuggestionProtocol.MATERIAL_LIMIT).doFinally(signal -> executions.release());
            });
        }
        return read(operation, request, 4096);
    }

    private Mono<ResponseEntity<Object>> read(String operation, ServerHttpRequest request, int limit) {
        return DataBufferUtils.join(request.getBody(), limit).map(buffer -> {
            try {
                byte[] bytes = new byte[buffer.readableByteCount()];
                buffer.read(bytes);
                return bytes;
            } finally {
                DataBufferUtils.release(buffer);
            }
        }).flatMap(bytes -> Mono.fromCallable(() -> handle(operation, bytes, request.getHeaders()))
                .subscribeOn(Schedulers.boundedElastic())).switchIfEmpty(Mono.just(ResponseEntity.badRequest().header("Cache-Control", "no-store").build()))
                .onErrorReturn(ResponseEntity.badRequest().header("Cache-Control", "no-store").build());
    }

    private ResponseEntity<Object> handle(String operation, byte[] body, HttpHeaders h) {
        try {
            String token = h.getFirst("satoken");
            var signature = new RequestSignature.Headers(h.getFirst("X-Service-Caller"),
                    Long.parseLong(h.getFirst("X-Service-Timestamp")), h.getFirst("X-Service-Nonce"), h.getFirst("X-Service-Signature"));
            SuggestionProtocol.verify(secret, "kb-app", operation, body, token, signature, Clock.systemUTC(),
                    (caller, nonce, ttl) -> Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(
                            "kb:metadata-suggestions:nonce:" + caller + ":" + nonce, "1", Duration.ofSeconds(ttl))
                            .block(Duration.ofSeconds(3))));
            Object result;
            if ("settle".equals(operation)) {
                result = quota.settle(json.readValue(body, SuggestionProtocol.Charge.class));
            } else if ("stop".equals(operation)) {
                result = inference.stop(json.readValue(body, SuggestionProtocol.Key.class));
            } else if (enabled) {
                result = inference.run(json.readValue(body, SuggestionProtocol.Run.class), token);
            } else {
                result = SuggestionProtocol.Outcome.failure("MODEL_UNAVAILABLE");
            }
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(result);
        } catch (SecurityException | NumberFormatException denied) {
            return ResponseEntity.status(403).header("Cache-Control", "no-store").build();
        } catch (Exception failed) {
            return ResponseEntity.status(503).header("Cache-Control", "no-store").build();
        }
    }
}
