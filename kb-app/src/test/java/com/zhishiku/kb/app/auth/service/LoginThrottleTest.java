package com.zhishiku.kb.app.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginThrottleTest {

    private static final String KEY = "kb:auth:login:fail:alice";

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private LoginThrottle throttle;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        throttle = new LoginThrottle(redis, 5, Duration.ofMinutes(15));
    }

    @Test
    void throttleKeyFormatAndThreshold() {
        when(values.get(KEY)).thenReturn("4");
        assertThat(throttle.isThrottled("alice")).isFalse();

        when(redis.getExpire(KEY)).thenReturn(900L);
        when(values.get(KEY)).thenReturn("5");
        assertThat(throttle.isThrottled("alice")).isTrue();

        when(values.get(KEY)).thenReturn("9");
        assertThat(throttle.isThrottled("alice")).isTrue();

        verify(values, times(3)).get(KEY);
    }

    @Test
    void expiredCounterUnlocks() {
        when(redis.getExpire(KEY)).thenReturn(900L);
        when(values.get(KEY)).thenReturn("5");
        assertThat(throttle.isThrottled("alice")).isTrue();

        // TTL 到期后键消失，GET 返回 null 即解锁；锁定期内不重复补 TTL
        when(values.get(KEY)).thenReturn(null);
        assertThat(throttle.isThrottled("alice")).isFalse();
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void recordFailureIncrementsAndRefreshesTtl() {
        when(values.increment(KEY)).thenReturn(1L);
        throttle.recordFailure("alice");
        verify(values).increment(KEY);
        verify(redis).expire(KEY, Duration.ofMinutes(15));

        when(values.increment(KEY)).thenReturn(2L);
        throttle.recordFailure("alice");
        verify(redis, times(2)).expire(KEY, Duration.ofMinutes(15));
    }

    @Test
    void successfulLoginClearsCounter() {
        throttle.clear("alice");
        verify(redis).delete(KEY);
    }

    @Test
    void blockedCounterWithoutTtlGetsHealedInsteadOfLockingForever() {
        when(values.get(KEY)).thenReturn("5");
        when(redis.getExpire(KEY)).thenReturn(-1L);

        assertThat(throttle.isThrottled("alice")).isTrue();

        verify(redis).expire(KEY, Duration.ofMinutes(15));
    }

    @Test
    void redisFailureRefusesLoginInsteadOfAllowing() {
        when(values.get(KEY)).thenThrow(new RedisConnectionFailureException("redis down"));
        assertThatThrownBy(() -> throttle.isThrottled("alice"))
                .isInstanceOf(RedisConnectionFailureException.class);

        when(values.increment(KEY)).thenThrow(new RedisConnectionFailureException("redis down"));
        assertThatThrownBy(() -> throttle.recordFailure("alice"))
                .isInstanceOf(RedisConnectionFailureException.class);
    }
}
