package com.zhishiku.kb.ai.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RedisChatAuditSinkTest {

    @Test
    void writesWithStableEventIdAndDeduplicationKey() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        doReturn(Flux.just(1L)).when(redis).execute(any(), anyList(), anyList());
        AiProperties props = new AiProperties("http://localhost:8081", 8, 60_000L, 60);
        RedisChatAuditSink sink = new RedisChatAuditSink(redis,
                new ObjectMapper().findAndRegisterModules(), props);
        ChatAuditEvent event = new ChatAuditEvent(Instant.parse("2026-10-01T13:00:00Z"),
                7L, 42L, "fingerprint", 12, "completed", 10, 4, 20, "event-1");

        sink.recordAsync(event).block();

        @SuppressWarnings("unchecked")
        var keys = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        var args = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(), keys.capture(), args.capture());
        assertThat(keys.getValue()).contains(props.auditKey(), props.auditKey() + ":dedupe:event-1");
        assertThat(args.getValue().get(1).toString()).contains("eventId");
    }
}
