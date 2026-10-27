package com.zhishiku.kb.ai.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.ai.chat.AiProperties;
import com.zhishiku.kb.ai.conversation.config.ConversationProperties;
import com.zhishiku.kb.common.conversation.UsageSettlement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 同一租约只接受固定结算意图，账本与审计可以各自重试。
 */
@Service
@ConditionalOnProperty(prefix = "kb.conversation", name = "enabled", havingValue = "true")
public class ConversationSettlementService {
    static final RedisScript<Long> SETTLE = RedisScript.of("""
            local marker = redis.call('GET', KEYS[5])
            if marker then
              if marker == 'DONE|' .. ARGV[2] then return 1 end
              if marker == 'INFLIGHT|' .. ARGV[2] then return -2 end
              return -1
            end
            redis.call('SET', KEYS[5], 'INFLIGHT|' .. ARGV[2], 'EX', ARGV[4])
            local reserved = tonumber(redis.call('HGET', KEYS[3], ARGV[1]) or '0')
            if reserved > 0 then
              local remaining = redis.call('DECRBY', KEYS[2], reserved)
              if remaining <= 0 then redis.call('DEL', KEYS[2]) end
              redis.call('HDEL', KEYS[3], ARGV[1])
            end
            redis.call('ZREM', KEYS[1], ARGV[1])
            if ARGV[3] ~= '0' then
              local existed = redis.call('EXISTS', KEYS[4])
              redis.call('INCRBY', KEYS[4], ARGV[3])
              if existed == 0 then redis.call('EXPIRE', KEYS[4], 60) end
            end
            redis.call('SET', KEYS[5], 'DONE|' .. ARGV[2], 'EX', ARGV[4])
            return 1
            """, Long.class);
    static final RedisScript<Long> AUDIT = RedisScript.of("""
            local marker = redis.call('GET', KEYS[2])
            if marker then
              if marker == 'DONE|' .. ARGV[1] then return 1 end
              if marker == 'INFLIGHT|' .. ARGV[1] then return -2 end
              return -1
            end
            redis.call('SET', KEYS[2], 'INFLIGHT|' .. ARGV[1], 'EX', ARGV[3])
            redis.call('RPUSH', KEYS[1], ARGV[2])
            redis.call('LTRIM', KEYS[1], -tonumber(ARGV[4]), -1)
            redis.call('SET', KEYS[2], 'DONE|' .. ARGV[1], 'EX', ARGV[3])
            return 1
            """, Long.class);
    private final ReactiveStringRedisTemplate redis;
    private final AiProperties ai;
    private final ConversationProperties properties;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public ConversationSettlementService(ReactiveStringRedisTemplate redis, AiProperties ai,
                                         ConversationProperties properties, ObjectMapper json) {
        this(redis, ai, properties, json, Clock.systemUTC());
    }

    public ConversationSettlementService(ReactiveStringRedisTemplate redis, AiProperties ai,
                                         ConversationProperties properties, ObjectMapper json, Clock clock) {
        this.redis = redis;
        this.ai = ai;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    public Mono<Void> settle(SettlementIntent intent) {
        return Mono.defer(() -> {
            validate(intent);
            String prefix = ai.quotaKeyPrefix() + ":dept:" + intent.departmentId();
            List<String> keys = List.of(prefix + ":active", prefix + ":tokens:reserved", prefix + ":tokens" +
                            ":reservations",
                    prefix + ":tokens:used", ai.quotaKeyPrefix() + ":settlement:" + intent.leaseId());
            return accepted(redis.execute(SETTLE, keys, List.of(intent.leaseId().toString(), intent.intentDigest(),
                    Long.toString(intent.chargedTokens()), Long.toString(properties.getSettlementDedupSeconds()))).next())
                    .then(Mono.defer(() -> {
                        String payload;
                        try {
                            payload = json.writeValueAsString(intent);
                        } catch (Exception error) {
                            return Mono.error(unavailable());
                        }
                        return accepted(redis.execute(AUDIT, List.of(ai.auditKey(),
                                        ai.auditKey() + ":turn-event:" + intent.eventId()),
                                List.of(intent.intentDigest(), payload,
                                        Long.toString(properties.getSettlementDedupSeconds()),
                                        Integer.toString(ai.auditMaxEvents()))).next());
                    }));
        }).timeout(Duration.ofSeconds(5)).onErrorMap(error -> error instanceof ResponseStatusException ? error :
                unavailable());
    }

    public void validate(SettlementIntent intent) {
        if (intent == null || intent.turnId() == null || intent.leaseId() == null || intent.eventId() == null
                || intent.userId() <= 0 || intent.departmentId() <= 0 || intent.reservedTokens() != properties.getReservationTokens()
                || !Set.of("COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED").contains(intent.generationStatus())
                || intent.finalizedAt() == null || intent.finalizedAt().isAfter(clock.instant().plusSeconds(60))
                || !clock.instant().isBefore(intent.finalizedAt().plusSeconds(properties.getSettlementRetrySeconds())))
            throw conflict();
        UsageSettlement.Value usage;
        if ("KNOWN".equals(intent.usageStatus()))
            usage = UsageSettlement.calculate(true, true, intent.promptTokens(), intent.completionTokens(),
                    intent.reservedTokens());
        else if ("UNKNOWN".equals(intent.usageStatus()) && intent.promptTokens() == null && intent.completionTokens() == null)
            usage = UsageSettlement.calculate(true, false, null, null, intent.reservedTokens());
        else if ("NOT_INVOKED".equals(intent.usageStatus()) && Long.valueOf(0).equals(intent.promptTokens()) && Long.valueOf(0).equals(intent.completionTokens()))
            usage = UsageSettlement.calculate(false, false, null, null, intent.reservedTokens());
        else throw conflict();
        String digest = UsageSettlement.digest(intent.turnId().toString(), intent.finalizedAt().toString(),
                intent.leaseId().toString(), intent.eventId().toString(), intent.userId(),
                intent.departmentId(), intent.reservedTokens(), intent.generationStatus(), usage);
        if (usage.chargedTokens() != intent.chargedTokens() || !digest.equals(intent.intentDigest())) throw conflict();
    }

    private Mono<Void> accepted(Mono<Long> result) {
        return result.switchIfEmpty(Mono.error(unavailable())).flatMap(code -> code == 1 ? Mono.empty() :
                Mono.error(conflict()));
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "固定结算意图无效");
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "费用结算暂未确认");
    }
}

