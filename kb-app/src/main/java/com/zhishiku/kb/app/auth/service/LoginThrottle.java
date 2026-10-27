package com.zhishiku.kb.app.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 登录失败节流（设计 D7）。
 *
 * <p>只按账户维度计数：键 {@code kb:auth:login:fail:<username>}，每次凭据失败
 * INCR 并刷新 TTL，成功登录删除计数；达到阈值后拒绝后续尝试。计数必然携带
 * TTL，到期自动解锁，不存在需要人工处理的持久锁定。
 *
 * <p>Redis 异常一律向上抛出（fail-closed）：会话本就存于 Redis，Redis 故障时
 * 登录不可能成功，节流层不把故障伪装成放行。
 */
@Component
public class LoginThrottle {

    static final String KEY_PREFIX = "kb:auth:login:fail:";

    private final StringRedisTemplate redis;
    /**
     * 窗口内允许的连续失败次数，达到后开始拒绝。
     */
    private final int maxFailures;
    /**
     * 计数键的存活时长，也是自动解锁的等待时间。
     */
    private final Duration lockDuration;

    public LoginThrottle(StringRedisTemplate redis,
                         @Value("${kb.auth.login.max-failures:5}") int maxFailures,
                         @Value("${kb.auth.login.lock-duration:15m}") Duration lockDuration) {
        this.redis = redis;
        this.maxFailures = maxFailures;
        this.lockDuration = lockDuration;
    }

    /**
     * 是否处于节流状态。调用方负责把 true 转成与凭据失败完全一致的 401，
     * 不向客户端暴露节流状态本身。
     */
    public boolean isThrottled(String username) {
        String key = key(username);
        String value = redis.opsForValue().get(key);
        if (value == null || Long.parseLong(value) < maxFailures) {
            return false;
        }
        // 自愈：INCR 与 EXPIRE 是两条命令，进程在两者之间退出会留下无 TTL 的计数。
        // 那正是规格禁止的“需要人工解锁的持久锁定”，补一次 TTL 让它必然到期。
        if (redis.getExpire(key) < 0) {
            redis.expire(key, lockDuration);
        }
        return true;
    }

    /**
     * 记录一次凭据失败：INCR 并刷新 TTL（设计 D7 的 INCR 加 EXPIRE）。
     */
    public void recordFailure(String username) {
        String key = key(username);
        redis.opsForValue().increment(key);
        redis.expire(key, lockDuration);
    }

    /**
     * 登录成功后清零，失败计数不跨成功登录延续。
     */
    public void clear(String username) {
        redis.delete(key(username));
    }

    private static String key(String username) {
        return KEY_PREFIX + username;
    }
}
