package com.zhishiku.kb.ai.chat;

import com.zhishiku.kb.common.security.QuotaPrincipal;
import com.zhishiku.kb.ai.telemetry.DependencyTelemetry;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 按部门执行持久化 AI 配额，并写入不含正文的审计摘要。
 *
 * <p>AI 服务不连接业务数据库：部门主体由 kb-app 根据 Sa-Token 会话解析，
 * 配额计数、并发租约和审计列表存放在 Redis。Lua 脚本保证请求计数与并发槽位
 * 在多实例部署下原子更新；Redis 不可用时请求失败关闭，绝不退化成无限制。
 */
@Service
public class ChatQuotaAuditService {

    private static final Logger log = LoggerFactory.getLogger(ChatQuotaAuditService.class);

    private static final RedisScript<Long> ACQUIRE_SCRIPT = RedisScript.of("""
            local now = tonumber(ARGV[1])
            local request_limit = tonumber(ARGV[2])
            local lease_until = tonumber(ARGV[3])
            local window_seconds = tonumber(ARGV[4])
            local concurrent_limit = tonumber(ARGV[5])
            local lease_id = ARGV[6]
            local lease_seconds = tonumber(ARGV[7])
            local token_budget = tonumber(ARGV[8])
            local token_reservation = tonumber(ARGV[9])
            
            local expired = redis.call('ZRANGE', KEYS[2], '-inf', now, 'BYSCORE')
            for _, expired_id in ipairs(expired) do
                local old_reservation = tonumber(redis.call('HGET', KEYS[5], expired_id) or '0')
                if old_reservation > 0 then
                    local remaining = redis.call('DECRBY', KEYS[4], old_reservation)
                    if remaining <= 0 then redis.call('DEL', KEYS[4]) end
                end
                redis.call('HDEL', KEYS[5], expired_id)
            end
            redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now)
            if redis.call('ZCARD', KEYS[2]) >= concurrent_limit then
                return 0
            end
            
            local request_count = tonumber(redis.call('GET', KEYS[1]) or '0')
            if request_count >= request_limit then
                return 0
            end
            
            local token_used = tonumber(redis.call('GET', KEYS[3]) or '0')
            local token_reserved = tonumber(redis.call('GET', KEYS[4]) or '0')
            if token_used + token_reserved + token_reservation > token_budget then
                return -2
            end
            
            request_count = redis.call('INCR', KEYS[1])
            if request_count == 1 then
                redis.call('EXPIRE', KEYS[1], window_seconds)
            end
            redis.call('INCRBY', KEYS[4], token_reservation)
            redis.call('HSET', KEYS[5], lease_id, token_reservation)
            redis.call('EXPIRE', KEYS[4], lease_seconds)
            redis.call('EXPIRE', KEYS[5], lease_seconds)
            redis.call('ZADD', KEYS[2], lease_until, lease_id)
            redis.call('EXPIRE', KEYS[2], lease_seconds)
            return 1
            """, Long.class);

    private static final RedisScript<Long> RELEASE_SCRIPT = RedisScript.of("""
            local lease_id = ARGV[1]
            local actual_tokens = tonumber(ARGV[2])
            local window_seconds = tonumber(ARGV[3])
            local reserved = tonumber(redis.call('HGET', KEYS[3], lease_id) or '0')
            if reserved > 0 then
                local remaining = redis.call('DECRBY', KEYS[2], reserved)
                if remaining <= 0 then redis.call('DEL', KEYS[2]) end
                redis.call('HDEL', KEYS[3], lease_id)
            end
            redis.call('ZREM', KEYS[1], lease_id)
            if actual_tokens > 0 then
                local used = redis.call('INCRBY', KEYS[4], actual_tokens)
                if used == actual_tokens then redis.call('EXPIRE', KEYS[4], window_seconds) end
            end
            return reserved
            """, Long.class);

    private static final RedisScript<Long> RENEW_SCRIPT = RedisScript.of("""
            local lease_id = ARGV[1]
            local lease_until = tonumber(ARGV[2])
            local lease_seconds = tonumber(ARGV[3])
            local now = tonumber(ARGV[4])
            local current_until = redis.call('ZSCORE', KEYS[1], lease_id)
            if current_until == false or tonumber(current_until) <= now then
                return 0
            end
            redis.call('ZADD', KEYS[1], lease_until, lease_id)
            redis.call('EXPIRE', KEYS[1], lease_seconds)
            redis.call('EXPIRE', KEYS[2], lease_seconds)
            redis.call('EXPIRE', KEYS[3], lease_seconds)
            return 1
            """, Long.class);

    private final AiProperties props;
    private final ChatAuditSink sink;
    private final QuotaPrincipalClient principalClient;
    private final ReactiveStringRedisTemplate redis;
    private final Clock clock;
    private DependencyTelemetry telemetry = DependencyTelemetry.NOOP;

    @Autowired
    public void setTelemetry(DependencyTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    public ChatQuotaAuditService(AiProperties props,
                                 ChatAuditSink sink,
                                 QuotaPrincipalClient principalClient,
                                 ReactiveStringRedisTemplate redis) {
        this(props, sink, principalClient, redis, Clock.systemUTC());
    }

    ChatQuotaAuditService(AiProperties props,
                          ChatAuditSink sink,
                          QuotaPrincipalClient principalClient,
                          ReactiveStringRedisTemplate redis,
                          Clock clock) {
        this.props = props;
        this.sink = sink;
        this.principalClient = principalClient;
        this.redis = redis;
        this.clock = clock;
    }

    /**
     * 解析部门并原子领取一个持久化配额租约。
     */
    public Mono<Lease> acquire(String tokenValue, int questionChars) {
        return principalClient.resolve(tokenValue)
                .flatMap(principal -> acquire(principal, tokenValue, questionChars))
                .onErrorMap(error -> {
                    if (error instanceof QuotaExceededException
                            || error instanceof QuotaUnavailableException) {
                        return error;
                    }
                    return new QuotaUnavailableException("配额服务不可用，请稍后重试", error);
                });
    }

    /**
     * 在实际领取前复核当前主体，并保持与独立登记时的绑定一致。
     */
    public Mono<Lease> acquireConfirmed(QuotaPrincipal principal, String tokenValue, int questionChars) {
        if (principal == null || principal.userId() <= 0 || principal.departmentId() <= 0)
            return Mono.error(new QuotaUnavailableException("配额主体无效"));
        return Mono.defer(() -> principalClient.resolve(tokenValue)
                .switchIfEmpty(Mono.error(new QuotaPrincipalRejectedException("当前配额主体不可用")))
                .flatMap(current -> {
                    if (!principal.equals(current))
                        return Mono.error(new QuotaPrincipalRejectedException("当前配额主体已变化，请重新发起请求"));
                    return acquire(principal, tokenValue, questionChars);
                }));
    }

    private Mono<Lease> acquire(QuotaPrincipal principal, String tokenValue, int questionChars) {
        Instant now = clock.instant();
        String leaseId = UUID.randomUUID().toString();
        // 保留现有请求/并发 key 的命名，避免升级时把窗口计数静默重置；新增 token
        // 账本使用同一部门前缀，Lua 脚本在 Redis 单实例中原子执行。
        String departmentKey = Long.toString(principal.departmentId());
        String requestKey = props.quotaKeyPrefix() + ":dept:" + departmentKey + ":requests";
        String activeKey = props.quotaKeyPrefix() + ":dept:" + departmentKey + ":active";
        String tokenUsedKey = props.quotaKeyPrefix() + ":dept:" + departmentKey + ":tokens:used";
        String tokenReservedKey = props.quotaKeyPrefix() + ":dept:" + departmentKey + ":tokens:reserved";
        String tokenReservationsKey = props.quotaKeyPrefix() + ":dept:" + departmentKey + ":tokens:reservations";
        long nowMillis = now.toEpochMilli();
        long leaseUntil = nowMillis + props.quotaLeaseSeconds() * 1000L;

        return execute(ACQUIRE_SCRIPT,
                List.of(requestKey, activeKey, tokenUsedKey, tokenReservedKey,
                        tokenReservationsKey), List.of(
                        Long.toString(nowMillis),
                        Integer.toString(props.requestsPerMinute()),
                        Long.toString(leaseUntil),
                        "60",
                        Integer.toString(props.maxConcurrent()),
                        leaseId,
                        Integer.toString(props.quotaLeaseSeconds()),
                        Integer.toString(props.tokenBudgetPerMinute()),
                        Integer.toString(props.maxTokensPerRequest())))
                .next()
                .switchIfEmpty(Mono.error(new QuotaUnavailableException("配额服务未返回结果")))
                .flatMap(allowed -> {
                    if (allowed != 1L) {
                        boolean tokenRejected = allowed == -2L;
                        telemetry.quotaRejected(tokenRejected ? DependencyTelemetry.QuotaReason.TOKEN_BUDGET
                                : DependencyTelemetry.QuotaReason.REQUEST_OR_CONCURRENCY);
                        ChatAuditEvent event = auditEvent(principal, fingerprint(tokenValue), questionChars,
                                tokenRejected ? "token_quota_rejected" : "quota_rejected",
                                0, 0, now, UUID.randomUUID().toString());
                        Mono<Void> audit = persistAudit(event)
                                .doOnError(error -> SafeChatLog.error(log, "quota_rejection_audit", error))
                                .onErrorResume(error -> Mono.empty());
                        return audit.then(Mono.error(tokenRejected
                                ? new TokenQuotaExceededException("所在部门的 token 配额已用尽，请稍后重试。")
                                : new QuotaExceededException("所在部门的 AI 配额已用尽，请稍后重试。")));
                    }
                    return Mono.just(new Lease(principal, fingerprint(tokenValue),
                            questionChars, leaseId, now, activeKey, tokenReservedKey,
                            tokenReservationsKey, tokenUsedKey, UUID.randomUUID().toString()));
                })
                .onErrorMap(error -> error instanceof QuotaExceededException
                        || error instanceof QuotaUnavailableException
                        ? error : new QuotaUnavailableException("配额存储不可用，请稍后重试", error));
    }

    private Mono<Void> finish(Lease lease, String status, int promptTokens, int completionTokens) {
        int chargedPromptTokens = Math.max(0, promptTokens);
        int chargedCompletionTokens = Math.max(0, completionTokens);
        long chargedTokens = (long) chargedPromptTokens + chargedCompletionTokens;
        ChatAuditEvent event = auditEvent(lease.principal, lease.fingerprint, lease.questionChars,
                status, chargedPromptTokens, chargedCompletionTokens, lease.started,
                lease.auditEventId);

        Mono<Void> release = withRedisRetry(execute(RELEASE_SCRIPT,
                List.of(lease.activeKey, lease.tokenReservedKey,
                        lease.tokenReservationsKey, lease.tokenUsedKey), List.of(
                        lease.leaseId, Long.toString(chargedTokens), "60")))
                .next()
                .switchIfEmpty(Mono.error(new QuotaUnavailableException("配额释放未返回结果")))
                .then();
        AtomicReference<Throwable> releaseFailure = new AtomicReference<>();
        Mono<Void> releaseSafe = release
                .doOnError(error -> SafeChatLog.error(log, "quota_release", error))
                .onErrorResume(error -> {
                    releaseFailure.set(error);
                    return Mono.empty();
                });
        Mono<Void> audit = persistAudit(event)
                .doOnError(error -> SafeChatLog.error(log, "quota_audit", error))
                // 审计失败不能让已经生成的答案变成模型错误；重试耗尽后保留错误日志，
                // 租约释放结果仍由 releaseFailure 单独决定。
                .onErrorResume(error -> Mono.empty());
        return Mono.when(releaseSafe, audit)
                .then(Mono.defer(() -> {
                    Throwable error = releaseFailure.get();
                    return error == null ? Mono.empty() : Mono.error(error);
                }));
    }

    private ChatAuditEvent auditEvent(QuotaPrincipal principal, String principalFingerprint,
                                      int questionChars, String status, int promptTokens,
                                      int completionTokens, Instant started, String eventId) {
        return new ChatAuditEvent(clock.instant(), principal.userId(), principal.departmentId(),
                principalFingerprint, questionChars, status, promptTokens, completionTokens,
                Math.max(0, clock.millis() - started.toEpochMilli()), eventId);
    }

    private Mono<Void> persistAudit(ChatAuditEvent event) {
        Mono<Void> write;
        try {
            write = sink.recordAsync(event);
        } catch (RuntimeException error) {
            return Mono.error(error);
        }
        if (write == null) {
            // 兼容只提供同步 record 的旧实现，以及 Mockito 等不执行 default
            // interface method 的测试替身。
            return Mono.fromRunnable(() -> sink.record(event));
        }
        return write.retryWhen(redisRetry());
    }

    private Mono<Void> renew(Lease lease) {
        synchronized (lease) {
            if (lease.finishStarted) return Mono.empty();
        }
        long leaseUntil = clock.millis() + props.quotaLeaseSeconds() * 1_000L;
        return withRedisRetry(execute(RENEW_SCRIPT,
                List.of(lease.activeKey, lease.tokenReservedKey,
                        lease.tokenReservationsKey), List.of(
                        lease.leaseId, Long.toString(leaseUntil),
                        Integer.toString(props.quotaLeaseSeconds()),
                        Long.toString(clock.millis()))))
                .next()
                .switchIfEmpty(Mono.error(new QuotaUnavailableException("配额续期未返回结果")))
                .flatMap(result -> {
                    synchronized (lease) {
                        if (lease.finishStarted) return Mono.empty();
                    }
                    return result == 1L
                            ? Mono.empty()
                            : Mono.error(new QuotaUnavailableException("配额租约已失效"));
                });
    }

    private Flux<Long> execute(RedisScript<Long> script, List<String> keys, List<String> args) {
        return Flux.defer(() -> {
            Flux<Long> command = redis.execute(script, keys, args);
            return command == null
                    ? Flux.error(new QuotaUnavailableException("配额 Redis 命令未创建"))
                    : command;
        });
    }

    private Flux<Long> withRedisRetry(Flux<Long> command) {
        return command.retryWhen(redisRetry());
    }

    private Retry redisRetry() {
        if (props.redisRetryAttempts() <= 1) return Retry.max(0);
        return Retry.backoff(props.redisRetryAttempts() - 1,
                        Duration.ofMillis(props.redisRetryBackoffMs()))
                .jitter(0.0)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }

    private static String fingerprint(String tokenValue) {
        String value = tokenValue == null ? "anonymous" : tokenValue;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(16);
            for (int i = 0; i < 8; i++) result.append(String.format("%02x", digest[i]));
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }

    public final class Lease {
        private final QuotaPrincipal principal;
        private final String fingerprint;
        private final int questionChars;
        private final String leaseId;
        private final Instant started;
        private final String activeKey;
        private final String tokenReservedKey;
        private final String tokenReservationsKey;
        private final String tokenUsedKey;
        private final String auditEventId;
        private boolean finishStarted;
        private Mono<Void> finishOperation;

        private Lease(QuotaPrincipal principal, String fingerprint, int questionChars,
                      String leaseId, Instant started, String activeKey,
                      String tokenReservedKey, String tokenReservationsKey, String tokenUsedKey,
                      String auditEventId) {
            this.principal = principal;
            this.fingerprint = fingerprint;
            this.questionChars = questionChars;
            this.leaseId = leaseId;
            this.started = started;
            this.activeKey = activeKey;
            this.tokenReservedKey = tokenReservedKey;
            this.tokenReservationsKey = tokenReservationsKey;
            this.tokenUsedKey = tokenUsedKey;
            this.auditEventId = auditEventId;
        }

        public void complete(int promptTokens, int completionTokens) {
            completeAsync(promptTokens, completionTokens).subscribe(null,
                    error -> SafeChatLog.error(log, "quota_complete", error));
        }

        public void fail() {
            failAsync().subscribe(null, error -> SafeChatLog.error(log, "quota_fail", error));
        }

        public void cancel() {
            cancelAsync().subscribe(null, error -> SafeChatLog.error(log, "quota_cancel", error));
        }

        public Mono<Void> completeAsync(int promptTokens, int completionTokens) {
            return startFinish("completed", promptTokens, completionTokens);
        }

        public Mono<Void> failAsync() {
            return startFinish("failed", 0, 0);
        }

        public Mono<Void> cancelAsync() {
            return startFinish("cancelled", 0, 0);
        }

        public Mono<Void> renewAsync() {
            return renew(this);
        }

        /**
         * 多轮只读取稳定关联信息，关闭改由固定账本结算接口处理。
         */
        public long userId() {
            return principal.userId();
        }

        public long departmentId() {
            return principal.departmentId();
        }

        public String leaseId() {
            return leaseId;
        }

        public String eventId() {
            return auditEventId;
        }

        public long reservedTokens() {
            return props.maxTokensPerRequest();
        }

        private Mono<Void> startFinish(String status, int promptTokens, int completionTokens) {
            synchronized (this) {
                if (finishOperation == null) {
                    finishStarted = true;
                    finishOperation = finish(this, status, promptTokens, completionTokens).cache();
                }
                return finishOperation;
            }
        }
    }

    public static class QuotaExceededException extends RuntimeException {
        public QuotaExceededException(String message) {
            super(message);
        }
    }

    /**
     * token 预算拒绝，供日志和上层错误指标区分请求数/并发拒绝。
     */
    public static final class TokenQuotaExceededException extends QuotaExceededException {
        public TokenQuotaExceededException(String message) {
            super(message);
        }
    }
}
