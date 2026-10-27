package com.zhishiku.kb.ai.conversation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.ai.conversation.config.ConversationProperties;
import com.zhishiku.kb.common.conversation.RequestSignature;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/**
 * 纯结算只验证 app 服务身份，用户令牌不能授权此入口。
 */
@RestController
@Hidden
@ConditionalOnProperty(prefix = "kb.conversation", name = "enabled", havingValue = "true")
public class InternalSettlementController {
    private final ConversationSettlementService settlement;
    private final ConversationProperties properties;
    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper json;

    public InternalSettlementController(ConversationSettlementService settlement, ConversationProperties properties,
                                        ReactiveStringRedisTemplate redis, ObjectMapper json) {
        this.settlement = settlement;
        this.properties = properties;
        this.redis = redis;
        this.json = json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @PostMapping(path = "/internal/conversations/settle", consumes = "application/json", produces = "application/json")
    public Mono<Map<String, Boolean>> settle(@RequestBody byte[] body, @RequestHeader HttpHeaders headers) {
        return Mono.defer(() -> {
            try {
                if (body.length > 8192) throw denied();
                var signed = new RequestSignature.Headers(headers.getFirst("X-Service-Caller"),
                        Long.parseLong(headers.getFirst("X-Service-Timestamp")), headers.getFirst("X-Service-Nonce"),
                        headers.getFirst("X-Service-Signature"));
                RequestSignature.verify(properties.getSecret(), "kb-app", "POST", "/internal/conversations/settle",
                        body,
                        headers.getFirst("satoken"), signed, Clock.systemUTC(), properties.getSignatureWindowSeconds(),
                        properties.getNonceTtlSeconds(), (a, b, c) -> true);
                return redis.opsForValue().setIfAbsent("kb:conversation:nonce:" + signed.caller() + ":" + signed.nonce(), "1",
                                Duration.ofSeconds(properties.getNonceTtlSeconds()))
                        .flatMap(claimed -> {
                            if (!Boolean.TRUE.equals(claimed)) return Mono.error(denied());
                            SettlementIntent intent;
                            try {
                                intent = json.readValue(body, SettlementIntent.class);
                            } catch (Exception error) {
                                return Mono.error(denied());
                            }
                            return settlement.settle(intent).thenReturn(Map.of("settled", true));
                        }).switchIfEmpty(Mono.error(denied())).onErrorMap(error -> error instanceof ResponseStatusException ? error : denied());
            } catch (Exception error) {
                return Mono.error(denied());
            }
        });
    }

    private static ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "内部请求身份无效");
    }
}

