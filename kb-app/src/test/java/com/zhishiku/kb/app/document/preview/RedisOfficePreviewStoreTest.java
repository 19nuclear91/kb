package com.zhishiku.kb.app.document.preview;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisOfficePreviewStoreTest {
    @Test
    @SuppressWarnings("unchecked")
    void adapterMapsCapacityAndStorageFailureWithoutConnectingToRedis() {
        var redis = mock(StringRedisTemplate.class);
        var store = new RedisOfficePreviewStore(redis, new ObjectMapper());
        var session = session();
        doReturn("LIMIT").when(redis).execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any());
        OfficePreviewSessionsTest.fail(() -> store.create(session, 1000, 3, 100), 429);
        doThrow(new IllegalStateException("redis password")).when(redis).execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any());
        OfficePreviewSessionsTest.fail(() -> store.create(session, 1000, 3, 100), 503);
        OfficePreviewSessionsTest.fail(() -> store.find("../../another", 1000), 404);
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisPayloadPreservesIdsBeyondDoublePrecision() throws Exception {
        var json = new ObjectMapper();
        var redis = mock(StringRedisTemplate.class);
        var values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(), any(), any(), any())).thenAnswer(call -> {
            String raw = call.getArgument(2);
            var payload = json.readTree(raw);
            assertThat(payload.get("userId").isTextual()).isTrue();
            assertThat(payload.get("versionId").asText()).isEqualTo("9007199254740993");
            when(values.get("kb:{office-preview}:session:" + session().id())).thenReturn(raw);
            return "OK";
        });
        var store = new RedisOfficePreviewStore(redis, json);
        store.create(session(), 1000, 3, 100);
        assertThat(store.find(session().id(), 1001).versionId()).isEqualTo(9007199254740993L);
        OfficePreviewSessionsTest.fail(() -> store.find(session().id(), 61000), 404);
    }

    private static OfficePreviewSession session() {
        return new OfficePreviewSession("a".repeat(32), 9007199254740993L, "private-original-token", 8, 9007199254740993L,
                "kb-" + "b".repeat(64), "kb", "PENDING", "launch-digest", "", 1000, 1801000, 61000);
    }
}
