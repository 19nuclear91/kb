package com.zhishiku.kb.app.auth.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisSessionTokenIndexTest {
    @Test
    @SuppressWarnings("unchecked")
    void fullBigintEpochCanBeReadWithoutFloatingPointTruncationAndInputsRemainBounded() {
        var redis = mock(StringRedisTemplate.class);
        ZSetOperations<String, String> sorted = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(sorted);
        String token = "x".repeat(43);
        when(sorted.rangeByLex(eq("kb:auth:tokens:7"), any(Range.class), any(Limit.class)))
                .thenReturn(Set.of("9223372036854775806:" + token));
        var index = new RedisSessionTokenIndex(redis);
        var page = index.pending(7, Long.MAX_VALUE - 1, 100);
        assertThat(page).containsExactly(new SessionTokenIndex.Token(Long.MAX_VALUE - 1, token));
        assertThat(page.getFirst().toString()).doesNotContain(token);
        assertThatThrownBy(() -> index.pending(7, 1, 101)).isInstanceOf(IllegalArgumentException.class);
        when(sorted.rangeByLex(eq("kb:auth:tokens:7"), any(Range.class), any(Limit.class))).thenReturn(null);
        assertThatThrownBy(() -> index.pending(7, 1, 100)).isInstanceOf(IllegalStateException.class);
    }
}
