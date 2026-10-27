package com.zhishiku.kb.ai.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;

/**
 * 将不含正文和令牌的审计摘要持久化到 Redis 有界列表。
 */
@Component
@ConditionalOnBean(ReactiveStringRedisTemplate.class)
public class RedisChatAuditSink implements ChatAuditSink {

    private static final Logger log = LoggerFactory.getLogger(RedisChatAuditSink.class);

    /**
     * 事件去重、追加和裁剪必须在同一个 Lua 脚本内完成。
     * Redis 响应丢失后，外层可以安全重试而不会在审计列表里产生重复事件。
     */
    private static final RedisScript<Long> RECORD_SCRIPT = RedisScript.of("""
            if redis.call('SETNX', KEYS[2], '1') == 1 then
                redis.call('EXPIRE', KEYS[2], ARGV[1])
                redis.call('RPUSH', KEYS[1], ARGV[2])
                redis.call('LTRIM', KEYS[1], -tonumber(ARGV[3]), -1)
            end
            return 1
            """, Long.class);

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AiProperties props;

    public RedisChatAuditSink(ReactiveStringRedisTemplate redis,
                              ObjectMapper objectMapper,
                              AiProperties props) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    @Override
    public void record(ChatAuditEvent event) {
        recordAsync(event)
                .retryWhen(redisRetry())
                .subscribe(null, error -> SafeChatLog.error(log, "audit_write", error));
    }

    @Override
    public Mono<Void> recordAsync(ChatAuditEvent event) {
        final String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            return Mono.error(new IllegalArgumentException("序列化 AI 审计事件失败", e));
        }

        String deduplicationKey = props.auditKey() + ":dedupe:" + event.eventId();
        return execute(payload, deduplicationKey)
                .next()
                .switchIfEmpty(Mono.error(new IllegalStateException("审计 Redis 未返回结果")))
                .then();
    }

    private Flux<Long> execute(String payload, String deduplicationKey) {
        return Flux.defer(() -> {
            var command = redis.execute(RECORD_SCRIPT,
                    List.of(props.auditKey(), deduplicationKey),
                    List.of(Integer.toString(props.auditDeduplicationSeconds()),
                            payload, Integer.toString(props.auditMaxEvents())));
            if (command == null) {
                return Flux.error(new IllegalStateException("审计 Redis 命令未创建"));
            }
            return command;
        });
    }

    private Retry redisRetry() {
        if (props.redisRetryAttempts() <= 1) return Retry.max(0);
        return Retry.backoff(props.redisRetryAttempts() - 1,
                        Duration.ofMillis(props.redisRetryBackoffMs()))
                .jitter(0.0)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }
}
