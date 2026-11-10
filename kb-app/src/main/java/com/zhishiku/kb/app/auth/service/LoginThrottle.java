package com.zhishiku.kb.app.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 登录失败节流（设计 D7）。
 *
 * <p>账户维度：键 {@code kb:auth:login:fail:<username>}，每次凭据失败 INCR 并刷新 TTL，
 * 成功登录删除计数；达到阈值后拒绝后续尝试。来源维度：键
 * {@code kb:auth:login:source-fail:<规范化地址>}，阈值独立配置，与账户共用锁定时长，
 * 不随登录成功清除，避免攻击者用自己的账号登录来清零来源计数。两类前缀从第三段起即不同，
 * 用户名无法写入来源命名空间。
 *
 * <p>计数必然携带 TTL，到期自动解锁，不存在需要人工处理的持久锁定。
 *
 * <p>Redis 异常一律向上抛出（fail-closed）：会话本就存于 Redis，Redis 故障时
 * 登录不可能成功，节流层不把故障伪装成放行。
 */
@Component
public class LoginThrottle {

    static final String KEY_PREFIX = "kb:auth:login:fail:";
    static final String SOURCE_KEY_PREFIX = "kb:auth:login:source-fail:";

    private final StringRedisTemplate redis;
    /**
     * 窗口内允许的连续失败次数，达到后开始拒绝。
     */
    private final int maxFailures;
    /**
     * 同一来源在窗口内允许的凭据失败次数，达到后拒绝该来源的所有登录尝试。
     */
    private final int sourceMaxFailures;
    /**
     * 计数键的存活时长，也是自动解锁的等待时间。
     */
    private final Duration lockDuration;

    public LoginThrottle(StringRedisTemplate redis,
                         @Value("${kb.auth.login.max-failures:5}") int maxFailures,
                         @Value("${kb.auth.login.source-max-failures:20}") int sourceMaxFailures,
                         @Value("${kb.auth.login.lock-duration:15m}") Duration lockDuration) {
        if (sourceMaxFailures < 1) {
            throw new IllegalArgumentException("kb.auth.login.source-max-failures 必须至少为 1");
        }
        this.redis = redis;
        this.maxFailures = maxFailures;
        this.sourceMaxFailures = sourceMaxFailures;
        this.lockDuration = lockDuration;
    }

    /**
     * 账户是否处于节流状态。调用方负责把 true 转成与凭据失败完全一致的 401，
     * 不向客户端暴露节流状态本身。
     */
    public boolean isThrottled(String username) {
        return reached(KEY_PREFIX + username, maxFailures);
    }

    /**
     * 记录一次账户凭据失败：INCR 并刷新 TTL（设计 D7 的 INCR 加 EXPIRE）。
     */
    public void recordFailure(String username) {
        increment(KEY_PREFIX + username);
    }

    /**
     * 登录成功后清零账户计数，失败计数不跨成功登录延续；来源计数不受影响。
     */
    public void clear(String username) {
        redis.delete(KEY_PREFIX + username);
    }

    /**
     * 来源是否处于节流状态；调用方只对已知来源调用，拒绝响应与凭据失败一致。
     *
     * @param source 已规范化的来源地址
     */
    public boolean isSourceThrottled(String source) {
        return reached(SOURCE_KEY_PREFIX + source, sourceMaxFailures);
    }

    /**
     * 记录一次来源凭据失败。来源计数只随 TTL 过期，不提供清除入口。
     *
     * @param source 已规范化的来源地址
     */
    public void recordSourceFailure(String source) {
        increment(SOURCE_KEY_PREFIX + source);
    }

    private boolean reached(String key, int limit) {
        String value = redis.opsForValue().get(key);
        if (value == null || Long.parseLong(value) < limit) {
            return false;
        }
        // 自愈：INCR 与 EXPIRE 是两条命令，进程在两者之间退出会留下无 TTL 的计数。
        // 那正是规格禁止的“需要人工解锁的持久锁定”，补一次 TTL 让它必然到期。
        if (redis.getExpire(key) < 0) {
            redis.expire(key, lockDuration);
        }
        return true;
    }

    private void increment(String key) {
        redis.opsForValue().increment(key);
        redis.expire(key, lockDuration);
    }
}
