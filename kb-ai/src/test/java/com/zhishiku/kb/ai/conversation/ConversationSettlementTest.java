package com.zhishiku.kb.ai.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.ai.chat.AiProperties;
import com.zhishiku.kb.ai.conversation.config.ConversationProperties;
import com.zhishiku.kb.common.conversation.RequestSignature;
import com.zhishiku.kb.common.conversation.UsageSettlement;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationSettlementTest {
    final ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    final ConversationProperties properties = new ConversationProperties();
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final Instant now = Instant.now();
    final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    final ConversationSettlementService service = new ConversationSettlementService(redis, new AiProperties("http" +
            "://app", 5, 1000, 30), properties, json, clock);

    ConversationSettlementTest() {
        properties.setSecret("0123456789abcdef0123456789abcdef");
    }

    SettlementIntent intent() {
        UUID lease = UUID.randomUUID(), event = UUID.randomUUID();
        UUID turn = UUID.randomUUID();
        var usage = UsageSettlement.calculate(true, false, null, null, 12000);
        return new SettlementIntent(turn, 1, 10, lease, event, 12000, "UNKNOWN", null, null, 12000, "CANCELLED",
                UsageSettlement.digest(turn.toString(), now.toString(), lease.toString(), event.toString(), 1, 10,
                        12000, "CANCELLED", usage), now);
    }

    @Test
    void fixedIntentRetryReleasesAndAuditsUsingSameStableIdsAndRejectsConflicts() {
        var intent = intent();
        when(redis.execute(any(), any(), any())).thenReturn(Flux.just(1L));
        StepVerifier.create(service.settle(intent)).verifyComplete();
        StepVerifier.create(service.settle(intent)).verifyComplete();
        verify(redis, times(2)).execute(eq(ConversationSettlementService.SETTLE),
                eq(List.of("kb:ai:quota:dept:10:active", "kb:ai:quota:dept:10:tokens:reserved", "kb:ai:quota:dept:10" +
                                ":tokens:reservations",
                        "kb:ai:quota:dept:10:tokens:used", "kb:ai:quota:settlement:" + intent.leaseId())), any());
        when(redis.execute(eq(ConversationSettlementService.SETTLE), any(), any())).thenReturn(Flux.just(-1L));
        StepVerifier.create(service.settle(intent)).expectErrorMatches(e -> e instanceof ResponseStatusException s && s.getStatusCode().value() == 409).verify();
        var changed = new SettlementIntent(intent.turnId(), 1, 10, intent.leaseId(), intent.eventId(), 12000,
                "UNKNOWN", null, null, 1, "CANCELLED", intent.intentDigest(), now);
        StepVerifier.create(service.settle(changed)).expectError(ResponseStatusException.class).verify();
    }

    @Test
    void replayAfterSafeWindowStopsAndStorageFailuresAreSafe() {
        var intent = intent();
        var expired = new SettlementIntent(intent.turnId(), 1, 10, intent.leaseId(), intent.eventId(), 12000,
                "UNKNOWN", null, null, 12000, "CANCELLED", intent.intentDigest(), now.minusSeconds(3600));
        StepVerifier.create(service.settle(expired)).expectError(ResponseStatusException.class).verify();
        verify(redis, never()).execute(any(), any(), any());
        when(redis.execute(any(), any(), any())).thenReturn(Flux.error(new IllegalStateException("secret")));
        StepVerifier.create(service.settle(intent)).expectErrorMatches(e -> e instanceof ResponseStatusException && !e.toString().contains("secret")).verify();
    }

    @Test
    void pureSettlementRequiresAppSignatureAndNonceButNoUserSession() throws Exception {
        ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(any(), any(), any())).thenReturn(Mono.just(true), Mono.just(false));
        when(redis.execute(any(), any(), any())).thenReturn(Flux.just(1L));
        var controller = new InternalSettlementController(service, properties, redis, json);
        byte[] body = json.writeValueAsBytes(intent());
        StepVerifier.create(controller.settle(body, new HttpHeaders())).expectErrorMatches(e -> e instanceof ResponseStatusException s && s.getStatusCode().value() == 403).verify();
        var signed = RequestSignature.sign(properties.getSecret(), "kb-app", "POST", "/internal/conversations/settle"
                , body, null, Clock.systemUTC());
        var headers = new HttpHeaders();
        headers.set("X-Service-Caller", signed.caller());
        headers.set("X-Service-Timestamp", Long.toString(signed.timestamp()));
        headers.set("X-Service-Nonce", signed.nonce());
        headers.set("X-Service-Signature", signed.signature());
        StepVerifier.create(controller.settle(body, headers)).assertNext(result -> assertThat(result.get("settled")).isTrue()).verifyComplete();
        StepVerifier.create(controller.settle(body, headers)).expectError(ResponseStatusException.class).verify();
        byte[] changed = "{}".getBytes(StandardCharsets.UTF_8);
        StepVerifier.create(controller.settle(changed, headers)).expectError(ResponseStatusException.class).verify();
    }
}

