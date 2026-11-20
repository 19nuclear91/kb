package com.zhishiku.kb.ai.chat;

import com.zhishiku.kb.common.security.QuotaPrincipal;
import com.zhishiku.kb.common.suggestion.SuggestionProtocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SuggestionQuotaTest {
    private final AiProperties config = new AiProperties("http://app.invalid", 8, 60000, 60, 8000, 24000, 60, 1);
    private final ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    private final SuggestionQuota quota = new SuggestionQuota(redis, config);
    private final Globals lua = JsePlatform.standardGlobals();
    private final String department = config.quotaKeyPrefix() + ":dept:42";
    private boolean loseNextConfirmation;

    @BeforeEach
    void runProductionScriptsInMemory() throws IOException {
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/quota-redis.lua"))) {
            lua.load(input, "quota-redis", "t", lua).call();
        }
        doAnswer(call -> {
            RedisScript<?> script = call.getArgument(0);
            List<String> keys = call.getArgument(1);
            List<String> args = call.getArgument(2);
            lua.set("KEYS", luaList(keys));
            lua.set("ARGV", luaList(args));
            long result = lua.load(script.getScriptAsString()).call().checklong();
            if (loseNextConfirmation) {
                loseNextConfirmation = false;
                return Flux.error(new IllegalStateException("确认丢失"));
            }
            return Flux.just(result);
        }).when(redis).execute(any(), anyList(), anyList());
    }

    private LuaValue luaList(List<String> values) {
        return LuaValue.listOf(values.stream().map(LuaValue::valueOf).toArray(LuaValue[]::new));
    }

    private SuggestionProtocol.Key key() {
        return new SuggestionProtocol.Key(UUID.randomUUID(), UUID.randomUUID(), 42, System.currentTimeMillis() + 60000);
    }

    private long tokens(String counter) {
        LuaValue value = lua.get("redis").get("call").call(LuaValue.valueOf("GET"), LuaValue.valueOf(department + ":tokens:" + counter));
        return value.toboolean() ? value.checklong() : 0;
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 120})
    void sharedDepartmentLeaseAndLostSettlementConfirmationAreIdempotent(long charge) {
        var key = key();
        assertThat(quota.reserve(key, 100)).isTrue();
        assertThat(quota.reserve(key, 100)).isTrue();
        assertThat(tokens("reserved")).isEqualTo(100);
        var principals = mock(QuotaPrincipalClient.class);
        when(principals.resolve("token")).thenReturn(Mono.just(new QuotaPrincipal(9, 42)));
        var chat = new ChatQuotaAuditService(config, mock(ChatAuditSink.class), principals, redis);
        assertThatThrownBy(() -> chat.acquire("token", 1).block()).isInstanceOf(ChatQuotaAuditService.QuotaExceededException.class);
        var intent = new SuggestionProtocol.Charge(key, 100, charge);
        loseNextConfirmation = true;
        assertThatThrownBy(() -> quota.settle(intent)).hasMessageContaining("确认丢失");
        assertThat(quota.settle(intent)).isTrue();
        assertThat(quota.settle(intent)).isTrue();
        assertThat(tokens("reserved")).isZero();
        assertThat(tokens("used")).isEqualTo(charge);
        assertThat(quota.reserve(key(), 100)).isTrue();
        lua.set("clock", 86401);
        assertThat(quota.settle(intent)).isFalse();
        assertThat(tokens("used")).isZero();
    }

    @Test
    void onlyMatchingEvidenceCanConfirmZeroAfterQuotaRejection() {
        var owner = key();
        var rejected = key();
        var intent = new SuggestionProtocol.Charge(rejected, 100, 0);
        assertThat(quota.settle(intent)).isFalse();
        assertThat(quota.reserve(owner, 100)).isTrue();
        assertThat(quota.reserve(rejected, 100)).isFalse();
        assertThat(quota.reserve(rejected, 100)).isFalse();
        assertThatThrownBy(() -> quota.reserve(owner, 99)).isInstanceOf(IllegalStateException.class);
        assertThat(quota.settle(new SuggestionProtocol.Charge(rejected, 99, 0))).isFalse();
        assertThat(quota.settle(new SuggestionProtocol.Charge(rejected, 100, 1))).isFalse();
        assertThat(quota.settle(intent)).isTrue();
        assertThat(tokens("reserved")).isEqualTo(100);
        assertThat(tokens("used")).isZero();
        lua.set("clock", 86401);
        assertThat(quota.settle(intent)).isFalse();
        assertThat(quota.settle(new SuggestionProtocol.Charge(owner, 100, 0))).isFalse();
    }
}
