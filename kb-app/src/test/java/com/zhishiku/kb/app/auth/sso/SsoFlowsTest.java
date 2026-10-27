package com.zhishiku.kb.app.auth.sso;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 受控存储替身验证编排，不执行 Lua，不宣称 Redis 原子性。
 */
class SsoFlowsTest {
    private final SsoSettings settings = new SsoSettings(SsoSettingsTest.configured("feishu"), new MockEnvironment());
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-17T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void bindsIntentBrowserAndConnectionAndCannotReviveConsumedFlow() {
        var store = new ControlledStore();
        var flows = new SsoFlows(store, settings, clock);
        var a = flows.start("feishu", "LOGIN", null, null, null, null);
        assertThat(a.state()).hasSize(43).isNotEqualTo(a.browser());
        assertThat(a.flow().getStateDigest()).doesNotContain(a.state());
        assertThatThrownBy(() -> flows.start("feishu", "LOGIN", a.browser(), 1L, "token", "cred")).isInstanceOf(SsoException.class);
        assertThatThrownBy(() -> flows.start("feishu", "BIND", a.browser(), null, null, null)).isInstanceOf(SsoException.class);
        assertThatThrownBy(() -> flows.claim("feishu", SsoCrypto.random(), a.state())).isInstanceOf(SsoException.class);
        var claimed = flows.claim("feishu", a.browser(), a.state());
        assertThatThrownBy(() -> flows.claim("feishu", a.browser(), a.state())).isInstanceOf(SsoException.class);
        var secret = flows.ready(claimed, identity(), null);
        assertThat(secret.ttlSeconds()).isEqualTo(60);
        var result = flows.current(a.browser());
        assertThatThrownBy(() -> flows.consume(result, SsoCrypto.random())).isInstanceOf(SsoException.class);
        assertThat(flows.consume(result, secret.value()).getIntent()).isEqualTo("LOGIN");
        assertThatThrownBy(() -> flows.consume(result, secret.value())).isInstanceOf(SsoException.class);
        assertThatThrownBy(() -> flows.current(a.browser())).isInstanceOf(SsoException.class);
    }

    @Test
    void newFlowSupersedesOldCallbackAndOldReadyCannotOverwriteNewResult() {
        var store = new ControlledStore();
        var flows = new SsoFlows(store, settings, clock);
        var a = flows.start("feishu", "LOGIN", null, null, null, null);
        var claimedA = flows.claim("feishu", a.browser(), a.state());
        var b = flows.start("feishu", "BIND", a.browser(), 7L, "session", "credential");
        assertThatThrownBy(() -> flows.ready(claimedA, identity(), null)).isInstanceOf(SsoException.class);
        assertThatThrownBy(() -> store.claim("dingtalk", b.flow().getBrowserDigest(), b.flow().getStateDigest(),
                clock.millis()))
                .isInstanceOf(SsoException.class);
        var claimedB = flows.claim("feishu", b.browser(), b.state());
        flows.ready(claimedB, identity(), null);
        assertThat(flows.current(b.browser()).getFlowId()).isEqualTo(b.flow().getFlowId());
        var cookie = new SsoCookies(settings);
        assertThat(cookie.resultName(a.flow().getFlowId())).isNotEqualTo(cookie.resultName(b.flow().getFlowId()));
    }

    @Test
    void expiryBudgetConfigurationAndStorageFailuresReject() {
        var store = mock(SsoFlowStore.class);
        var flows = new SsoFlows(store, settings, clock);
        var started = flows.start("feishu", "LOGIN", null, null, null, null);
        started.flow().setExpiresAt(clock.millis() + 1234);
        assertThat(flows.ready(started.flow(), identity(), null).ttlSeconds()).isEqualTo(2);
        started.flow().setExpiresAt(clock.millis());
        assertThatThrownBy(() -> flows.ready(started.flow(), identity(), null)).isInstanceOf(SsoException.class);
        settings.connection("feishu").setClientSecret("rotated");
        assertThatThrownBy(() -> flows.consume(started.flow(), SsoCrypto.random()))
                .isInstanceOfSatisfying(SsoException.class, e -> assertThat(e.code()).isEqualTo("CONFIG_CHANGED"));
        doThrow(new SsoException(429, "RATE_LIMITED")).when(store).create(any(), anyInt(), anyInt());
        assertThatThrownBy(() -> flows.start("feishu", "LOGIN", null, null, null, null))
                .isInstanceOfSatisfying(SsoException.class, e -> assertThat(e.status()).isEqualTo(429));
        when(store.current(anyString(), anyLong())).thenThrow(SsoException.storage());
        assertThatThrownBy(() -> flows.current(started.browser()))
                .isInstanceOfSatisfying(SsoException.class, e -> assertThat(e.code()).isEqualTo("STORAGE_UNAVAILABLE"));
    }

    @Test
    void redisBoundaryRejectsMissingAndBrokenStorageAndSerializesLongOwnerAsString() throws Exception {
        var redis = mock(StringRedisTemplate.class);
        var adapter = new RedisSsoFlowStore(redis, new ObjectMapper());
        assertThatThrownBy(() -> adapter.current("browser", clock.millis())).isInstanceOfSatisfying(SsoException.class,
                e -> assertThat(e.code()).isEqualTo("STORAGE_UNAVAILABLE"));
        var f = new SsoFlow();
        f.setOwnerUserId(Long.MAX_VALUE);
        String encoded = new ObjectMapper().writeValueAsString(f);
        assertThat(new ObjectMapper().readTree(encoded).get("ownerUserId").isTextual()).isTrue();
        assertThat(new ObjectMapper().readValue(encoded, SsoFlow.class).getOwnerUserId()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void expiredResultAndAtomicLimitOutcomeAreRejectedWithoutFallback() {
        var store = new ControlledStore();
        var flows = new SsoFlows(store, settings, clock);
        var a = flows.start("feishu", "LOGIN", null, null, null, null);
        flows.ready(flows.claim("feishu", a.browser(), a.state()), identity(), null);
        var later = new SsoFlows(store, settings, Clock.offset(clock, Duration.ofSeconds(61)));
        assertThatThrownBy(() -> later.current(a.browser())).isInstanceOf(SsoException.class);
        var redis = mock(StringRedisTemplate.class);
        doReturn("LIMIT").when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        var adapter = new RedisSsoFlowStore(redis, new ObjectMapper());
        assertThatThrownBy(() -> adapter.create(a.flow(), 10, 1000)).isInstanceOfSatisfying(SsoException.class,
                e -> assertThat(e.status()).isEqualTo(429));
        doThrow(new IllegalStateException("redis failed")).when(redis).execute(any(RedisScript.class), anyList(),
                any(Object[].class));
        assertThatThrownBy(() -> adapter.create(a.flow(), 10, 1000)).isInstanceOfSatisfying(SsoException.class,
                e -> assertThat(e.code()).isEqualTo("STORAGE_UNAVAILABLE"));
    }

    static VerifiedExternalIdentity identity() {
        return new VerifiedExternalIdentity("feishu", "corp", "app", "app-open-id", "OpenA");
    }

    private static final class ControlledStore implements SsoFlowStore {
        final Map<String, SsoFlow> rows = new HashMap<>();
        String current;

        @Override
        public void create(SsoFlow f, int rate, int max) {
            rows.put(f.getFlowId(), copy(f));
            current = f.getFlowId();
        }

        @Override
        public SsoFlow claim(String id, String browser, String state, long now) {
            SsoFlow f = rows.get(current);
            if (f == null || !f.getConnectionId().equals(id) || !f.getBrowserDigest().equals(browser)
                    || !f.getStateDigest().equals(state) || !f.getStage().equals("CREATED") || f.getExpiresAt() <= now)
                throw SsoException.invalid();
            f.setStage("PROCESSING");
            return copy(f);
        }

        @Override
        public void ready(SsoFlow f, long now) {
            if (!f.getFlowId().equals(current) || !rows.get(current).getStage().equals("PROCESSING"))
                throw SsoException.invalid();
            rows.put(f.getFlowId(), copy(f));
        }

        @Override
        public SsoFlow current(String browser, long now) {
            SsoFlow f = rows.get(current);
            if (f == null || !f.getBrowserDigest().equals(browser) || !f.getStage().equals("RESULT_READY") || f.getExpiresAt() <= now)
                throw SsoException.invalid();
            return copy(f);
        }

        @Override
        public SsoFlow consume(SsoFlow f, String secret, long now) {
            SsoFlow currentFlow = current(f.getBrowserDigest(), now);
            if (!f.getFlowId().equals(current) || !currentFlow.getResultDigest().equals(secret))
                throw SsoException.invalid();
            rows.remove(current);
            current = null;
            return currentFlow;
        }

        private static SsoFlow copy(SsoFlow f) {
            try {
                return new ObjectMapper().readValue(new ObjectMapper().writeValueAsBytes(f), SsoFlow.class);
            } catch (Exception impossible) {
                throw new AssertionError(impossible);
            }
        }
    }
}
