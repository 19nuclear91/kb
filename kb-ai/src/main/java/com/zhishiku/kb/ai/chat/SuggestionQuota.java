package com.zhishiku.kb.ai.chat;

import com.zhishiku.kb.common.suggestion.SuggestionProtocol;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
public class SuggestionQuota {
    private static final long EVIDENCE_SECONDS = 86400;
    private static final RedisScript<Long> SETTLE = RedisScript.of("""
            local marker = redis.call('GET', KEYS[5])
            local done = 'DONE:' .. ARGV[2] .. ':' .. ARGV[3]
            if marker == done then return 1 end
            if marker == 'REJECTED:' .. ARGV[2] and ARGV[3] == '0' then return 1 end
            if marker ~= 'RESERVED:' .. ARGV[2] then return -1 end
            redis.call('SET', KEYS[5], 'SETTLING', 'EX', ARGV[4])
            local reserved = tonumber(redis.call('HGET', KEYS[3], ARGV[1]) or '0')
            if reserved > 0 then
              local remaining = redis.call('DECRBY', KEYS[2], reserved)
              if remaining <= 0 then redis.call('DEL', KEYS[2]) end
            end
            redis.call('HDEL', KEYS[3], ARGV[1])
            redis.call('ZREM', KEYS[1], ARGV[1])
            if ARGV[3] ~= '0' then
              local existed = redis.call('EXISTS', KEYS[4])
              redis.call('INCRBY', KEYS[4], ARGV[3])
              if existed == 0 then redis.call('EXPIRE', KEYS[4], 60) end
            end
            redis.call('SET', KEYS[5], done, 'EX', ARGV[4])
            return 1
            """, Long.class);
    private final ReactiveStringRedisTemplate redis;
    private final AiProperties config;

    public SuggestionQuota(ReactiveStringRedisTemplate redis, AiProperties config) {
        this.redis = redis;
        this.config = config;
    }

    public boolean reserve(SuggestionProtocol.Key key, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("预留额度必须大于零");
        }
        if (amount > config.maxTokensPerRequest()) {
            throw new IllegalArgumentException("INPUT_TOO_LARGE");
        }
        long now = System.currentTimeMillis();
        String prefix = prefix(key);
        long result = execute(ChatQuotaAuditService.ACQUIRE_SCRIPT, List.of(prefix + ":requests", prefix + ":active",
                prefix + ":tokens:used", prefix + ":tokens:reserved", prefix + ":tokens:reservations", marker(key)),
                List.of(Long.toString(now), Integer.toString(config.requestsPerMinute()), Long.toString(leaseUntil(now)),
                        "60", Integer.toString(config.maxConcurrent()), lease(key), Integer.toString(config.quotaLeaseSeconds()),
                        Integer.toString(config.tokenBudgetPerMinute()), Long.toString(amount), Long.toString(EVIDENCE_SECONDS),
                        Long.toString(key.deadlineMillis())));
        if (result == 0 || result == -2) {
            return false;
        }
        if (result != 1) {
            throw new IllegalStateException("配额预留未确认");
        }
        return true;
    }

    public boolean renew(SuggestionProtocol.Key key) {
        long now = System.currentTimeMillis();
        String prefix = prefix(key);
        return execute(ChatQuotaAuditService.RENEW_SCRIPT,
                List.of(prefix + ":active", prefix + ":tokens:reserved", prefix + ":tokens:reservations"),
                List.of(lease(key), Long.toString(leaseUntil(now)), Integer.toString(config.quotaLeaseSeconds()),
                        Long.toString(now))) == 1;
    }

    public boolean settle(SuggestionProtocol.Charge charge) {
        var key = charge.key();
        if (charge.charged() < 0 || charge.reservation() <= 0) {
            return false;
        }
        String prefix = prefix(key);
        return execute(SETTLE, List.of(prefix + ":active", prefix + ":tokens:reserved", prefix + ":tokens:reservations",
                prefix + ":tokens:used", marker(key)), List.of(lease(key), Long.toString(charge.reservation()),
                Long.toString(charge.charged()), Long.toString(EVIDENCE_SECONDS))) == 1;
    }

    private long execute(RedisScript<Long> script, List<String> keys, List<String> args) {
        Long result = redis.execute(script, keys, args).next().block(Duration.ofSeconds(3));
        if (result == null) {
            throw new IllegalStateException("配额结果未确认");
        }
        return result;
    }

    private long leaseUntil(long now) {
        return now + config.quotaLeaseSeconds() * 1000L;
    }

    private String prefix(SuggestionProtocol.Key key) {
        return config.quotaKeyPrefix() + ":dept:" + key.departmentId();
    }

    private String marker(SuggestionProtocol.Key key) {
        return prefix(key) + ":metadata-suggestion:" + key.executionId();
    }

    private String lease(SuggestionProtocol.Key key) {
        return "metadata-suggestion:" + key.executionId();
    }
}
