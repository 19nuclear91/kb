package com.zhishiku.kb.app.auth.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.IdentityException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class DingTalkMembership {
    private final DingTalkLifecycleSettings settings;
    private final LifecycleHttp http;
    private final Clock clock;
    private final ReentrantLock tokenLock = new ReentrantLock();
    private volatile CachedToken cached;
    private LifecycleObservations observations;

    @Autowired
    public void setObservations(LifecycleObservations observations) {
        this.observations = observations;
    }

    private record CachedToken(String value, long expiresAt) {
        @Override
        public String toString() {
            return "CachedToken[redacted]";
        }
    }

    @Autowired
    public DingTalkMembership(DingTalkLifecycleSettings settings, LifecycleHttp http) {
        this(settings, http, Clock.systemUTC());
    }

    DingTalkMembership(DingTalkLifecycleSettings settings, LifecycleHttp http, Clock clock) {
        this.settings = settings;
        this.http = http;
        this.clock = clock;
    }

    /** 不读取或写入 SSO 绑定；输入始终为管理员明确给出的精确员工标识。 */
    public CurrentEmployee current(String employeeId) {
        try {
            return currentMember(employeeId);
        } catch (RuntimeException failure) {
            if (observations != null && (!(failure instanceof ApiException api) || api.httpStatus() >= 500)) {
                observations.dependencyFailed(LifecycleObservations.Dependency.MEMBER);
            }
            throw failure;
        }
    }

    private CurrentEmployee currentMember(String employeeId) {
        settings.requireConfigured();
        String employee = LifecycleSupport.text(employeeId, "employeeId", 128);
        if (!employee.equals(employeeId) || employee.chars().anyMatch(Character::isISOControl)) {
            throw IdentityException.badRequest("员工标识格式无效");
        }
        var response = http.post("https://oapi.dingtalk.com/topapi/v2/user/get?access_token="
                + URLEncoder.encode(token(), StandardCharsets.UTF_8), Map.of("userid", employee));
        if (!response.path("errcode").isIntegralNumber()) {
            throw LifecycleFailures.unavailable();
        }
        if (response.path("errcode").longValue() != 0) {
            throw LifecycleFailures.unavailable();
        }
        JsonNode member = response.path("result");
        if (!member.isObject() || !member.path("userid").isTextual()
                || !employee.equals(member.path("userid").textValue()) || !member.path("active").isBoolean()) {
            throw LifecycleFailures.unavailable();
        }
        if (!member.path("active").booleanValue()) {
            throw IdentityException.conflict(com.zhishiku.kb.common.security.IdentityConflictReason.LIFECYCLE_CONFIRMATION_STALE,
                    "当前成员不可用于此操作，请重新核对");
        }
        String name = member.path("name").isTextual() ? member.path("name").textValue() : "";
        if (name.length() > 128 || name.chars().anyMatch(Character::isISOControl)) {
            throw LifecycleFailures.unavailable();
        }
        return new CurrentEmployee("dingtalk", settings.enterpriseId(), employee, settings.connectionId(), name, clock.instant());
    }

    private String token() {
        var value = cached;
        if (value != null && clock.millis() < value.expiresAt()) {
            return value.value();
        }
        // 不排队等待其他网络请求，忙时由管理员稍后重新核对。
        if (!tokenLock.tryLock()) {
            throw LifecycleFailures.unavailable();
        }
        try {
            value = cached;
            if (value != null && clock.millis() < value.expiresAt()) {
                return value.value();
            }
            long started = clock.millis();
            var response = http.post("https://api.dingtalk.com/v1.0/oauth2/accessToken",
                    Map.of("appKey", settings.clientId(), "appSecret", settings.clientSecret()));
            JsonNode token = response.path("accessToken");
            JsonNode expiry = response.path("expireIn");
            if (!token.isTextual() || token.textValue().isBlank() || token.textValue().length() > 4096
                    || !expiry.isIntegralNumber() || expiry.longValue() <= 60 || expiry.longValue() > 7200) {
                throw LifecycleFailures.unavailable();
            }
            cached = new CachedToken(token.textValue(), started + (expiry.longValue() - 60) * 1000);
            return cached.value();
        } finally {
            tokenLock.unlock();
        }
    }
}
