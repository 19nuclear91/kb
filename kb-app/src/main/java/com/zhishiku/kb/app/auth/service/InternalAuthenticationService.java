package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.AuthenticationCheck;
import com.zhishiku.kb.common.security.AuthenticationSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;

@Service
public class InternalAuthenticationService {
    private final String secret;
    private final AuthenticationSignature.NonceStore nonces;
    private final EpochSessionService sessions;
    private final Clock clock;

    @Autowired
    public InternalAuthenticationService(@Value("${kb.auth.internal.secret:}") String secret,
                                         StringRedisTemplate redis, EpochSessionService sessions) {
        this(secret, (caller, nonce, ttl) -> Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(
                "kb:auth:nonce:" + caller + ":" + nonce, "1", Duration.ofSeconds(ttl))), sessions, Clock.systemUTC());
    }

    public InternalAuthenticationService(String secret, AuthenticationSignature.NonceStore nonces,
                                         EpochSessionService sessions, Clock clock) {
        this.secret = secret;
        this.nonces = nonces;
        this.sessions = sessions;
        this.clock = clock;
    }

    public AuthenticationCheck check(String method, String path, byte[] body, String token,
                                      AuthenticationSignature.Headers headers) {
        if (!AuthenticationSignature.configured(secret)) {
            throw ApiException.unavailable("内部认证暂不可用");
        }
        try {
            AuthenticationSignature.verify(secret, method, path, body, token, headers, clock, nonces);
        } catch (SecurityException invalid) {
            throw ApiException.forbidden("内部认证请求无效");
        } catch (RuntimeException failed) {
            throw ApiException.unavailable("内部认证暂不可用");
        }
        var result = sessions.check(token);
        return new AuthenticationCheck(result.userId(), result.authEpoch(), result.mustChangePassword(), result.checkedAt());
    }
}
