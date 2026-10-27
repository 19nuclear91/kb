package com.zhishiku.kb.app.conversation.service;

import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.conversation.RequestSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;

/**
 * 服务身份先于用户资格；去重存储故障失败关闭。
 */
@Service
@ConditionalOnProperty(prefix = "kb.conversation", name = "enabled", havingValue = "true")
public class InternalRequestAuthenticator {
    private final ConversationProperties properties;
    private final RequestSignature.NonceStore nonces;
    private final Clock clock;

    @Autowired
    public InternalRequestAuthenticator(ConversationProperties properties, StringRedisTemplate redis) {
        this(properties, (caller, nonce, ttl) -> Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(
                "kb:conversation:nonce:" + caller + ":" + nonce, "1", Duration.ofSeconds(ttl))), Clock.systemUTC());
    }

    public InternalRequestAuthenticator(ConversationProperties properties, RequestSignature.NonceStore nonces,
                                        Clock clock) {
        this.properties = properties;
        this.nonces = nonces;
        this.clock = clock;
    }

    public void verify(String method, String path, byte[] body, String token, RequestSignature.Headers headers) {
        try {
            RequestSignature.verify(properties.getSecret(), "kb-ai", method, path, body, token, headers, clock,
                    properties.getSignatureWindowSeconds(), properties.getNonceTtlSeconds(), nonces);
        } catch (SecurityException error) {
            throw ApiException.forbidden("内部请求身份无效");
        }
    }
}

