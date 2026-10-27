package com.zhishiku.kb.app.auth.sso;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SsoHttpTest {
    @Test
    void enforcesHostRedirectSizeAndRoundBudget() {
        AtomicLong time = new AtomicLong();
        var http = new SsoHttp((method, uri, headers, body, timeout) -> {
            assertThat(timeout).isLessThanOrEqualTo(Duration.ofSeconds(10));
            time.addAndGet(Duration.ofSeconds(10).toNanos());
            return new SsoHttp.Response(200, "{}".getBytes(StandardCharsets.UTF_8));
        }, new ObjectMapper(), time::get);
        var budget = http.budget();
        http.call(budget, "GET", "https://open.feishu.cn/a", Map.of(), null);
        http.call(budget, "GET", "https://open.feishu.cn/a", Map.of(), null);
        assertThatThrownBy(() -> http.call(budget, "GET", "https://open.feishu.cn/a", Map.of(), null))
                .isInstanceOf(SsoException.class);
        for (String url : new String[]{"http://open.feishu.cn/a", "https://attacker.example/a", "https://open.feishu" +
                ".cn:444/a"})
            assertThatThrownBy(() -> http.call(http.budget(), "GET", url, Map.of(), null)).isInstanceOf(SsoException.class);
        for (var response : new SsoHttp.Response[]{new SsoHttp.Response(302, new byte[0]),
                new SsoHttp.Response(200, new byte[SsoHttp.MAX_BYTES + 1]), new SsoHttp.Response(200, new byte[0])}) {
            var invalid = new SsoHttp((a, b, c, d, e) -> response, new ObjectMapper(), time::get);
            assertThatThrownBy(() -> invalid.call(invalid.budget(), "GET", "https://open.feishu.cn/a", Map.of(), null))
                    .isInstanceOf(SsoException.class);
        }
        var timeout = new SsoHttp((a, b, c, d, e) -> {
            throw new SocketTimeoutException();
        }, new ObjectMapper(), time::get);
        assertThatThrownBy(() -> timeout.call(timeout.budget(), "GET", "https://open.feishu.cn/a", Map.of(), null))
                .isInstanceOf(SsoException.class).hasMessage("企业认证请求未完成");
    }

    @Test
    void cacheSeparatesScopeAndCredentialGenerationAndRejectsRedisFailures() {
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var cache = new SsoAppTokens(redis);
        var c = SsoSettingsTest.configured("feishu").getConnections().get("feishu");
        cache.get("feishu", c, () -> new SsoAppTokens.Token("app-token", 7200));
        String key = "kb:sso:app:" + SsoScope.provider("feishu", c) + ":" + SsoScope.generation(c);
        verify(values).set(eq(key), eq("app-token"), eq(Duration.ofSeconds(7140)));
        c.setClientSecret("rotated");
        assertThat("kb:sso:app:" + SsoScope.provider("feishu", c) + ":" + SsoScope.generation(c)).isNotEqualTo(key);
        when(values.get(anyString())).thenThrow(new IllegalStateException("redis failed"));
        assertThatThrownBy(() -> cache.get("feishu", c, () -> {
            throw new AssertionError();
        }))
                .isInstanceOfSatisfying(SsoException.class, error -> assertThat(error.code()).isEqualTo(
                        "STORAGE_UNAVAILABLE"));
    }
}
