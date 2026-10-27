package com.zhishiku.kb.app.auth.sso;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 只接纳适配器应用令牌；用户 access/refresh token 不进入此缓存。
 */
@Component
public class SsoAppTokens {
    private final StringRedisTemplate redis;

    public SsoAppTokens(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public String get(String id, SsoProperties.Connection c, Supplier<Token> load) {
        String key = "kb:sso:app:" + SsoScope.provider(id, c) + ":" + SsoScope.generation(c);
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null && !cached.isBlank()) return cached;
            Token token = load.get();
            if (token.value() == null || token.value().isBlank() || token.value().length() > 8192 || token.expiresIn() <= 60)
                throw SsoException.unavailable();
            redis.opsForValue().set(key, token.value(), Duration.ofSeconds(Math.min(7200, token.expiresIn()) - 60));
            return token.value();
        } catch (SsoException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw SsoException.storage();
        }
    }

    /**
     * 应用身份访问令牌，禁止传入用户令牌。
     *
     * @param value     应用令牌
     * @param expiresIn 供应商有效秒数
     */
    public record Token(String value, long expiresIn) {
    }
}
