package com.zhishiku.kb.app.auth.service;

import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class RedisSessionTokenIndex implements SessionTokenIndex {
    private static final DefaultRedisScript<Long> REGISTER = new DefaultRedisScript<>("""
            redis.call('ZADD', KEYS[1], 0, ARGV[1])
            local ttl = redis.call('TTL', KEYS[1])
            if ttl < tonumber(ARGV[2]) then redis.call('EXPIRE', KEYS[1], ARGV[2]) end
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;

    public RedisSessionTokenIndex(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void register(long userId, long epoch, String token, long ttlSeconds) {
        if (userId <= 0 || epoch <= 0 || ttlSeconds <= 0 || token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("令牌索引输入无效");
        }
        Long saved = redis.execute(REGISTER, List.of(key(userId)), member(new Token(epoch, token)), Long.toString(ttlSeconds));
        if (!Long.valueOf(1).equals(saved)) {
            throw new IllegalStateException("令牌索引写入失败");
        }
    }

    @Override
    public List<Token> pending(long userId, long revokedThroughEpoch, int limit) {
        if (limit < 1 || limit > 100 || revokedThroughEpoch < 1) {
            throw new IllegalArgumentException("令牌清理范围无效");
        }
        // 全部 score 为 0，按补齐的整数代次排序，避免 double 丢失 BIGINT 精度。
        Set<String> values = redis.opsForZSet().rangeByLex(key(userId),
                Range.closed("", String.format(Locale.ROOT, "%019d", revokedThroughEpoch) + ":~"),
                Limit.limit().count(limit));
        if (values == null) {
            throw new IllegalStateException("令牌索引读取失败");
        }
        return values.stream().map(RedisSessionTokenIndex::decode).toList();
    }

    @Override
    public void remove(long userId, Token token) {
        if (redis.opsForZSet().remove(key(userId), member(token)) == null) {
            throw new IllegalStateException("令牌索引清理失败");
        }
    }

    private static Token decode(String value) {
        if (value == null || !value.matches("[0-9]{19}:[A-Za-z0-9_-]{43}")) {
            throw new IllegalStateException("令牌索引内容无效");
        }
        return new Token(Long.parseLong(value.substring(0, 19)), value.substring(20));
    }

    private static String member(Token token) {
        return String.format(Locale.ROOT, "%019d", token.epoch()) + ":" + token.value();
    }

    private static String key(long userId) {
        return "kb:auth:tokens:" + userId;
    }
}
