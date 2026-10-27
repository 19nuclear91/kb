package com.zhishiku.kb.ai.chat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.common.security.QuotaPrincipal;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SafeChatLoggingTest {
    private static final String CANARY = "QUERY_CANARY_938";
    private static final AiProperties PROPS =
            new AiProperties("http://app.invalid", 5, 60_000, 30);

    @Test
    void chatServiceFailureKeepsSafeErrorAndOmitsQuestionAndCauseFromLog() {
        RetrievalClient retrieval = mock(RetrievalClient.class);
        when(retrieval.retrieve(CANARY, CANARY)).thenReturn(Mono.error(
                new IllegalStateException("response=" + CANARY,
                        new RuntimeException("cause=" + CANARY))));
        try (CapturedLog log = new CapturedLog(ChatService.class)) {
            StepVerifier.create(new ChatService(retrieval).chat(CANARY, CANARY))
                    .expectNext(new ChatEvent.Error("处理请求时出错，请稍后重试。"))
                    .verifyComplete();
            log.assertSafe("stage=chat");
        }
    }

    @Test
    void controllerFailureClosesSseWithSafeErrorAndOmitsQuestionAndToken() {
        ChatService service = mock(ChatService.class);
        when(service.chat(CANARY, CANARY)).thenReturn(Flux.error(
                new IllegalStateException("token=" + CANARY,
                        new RuntimeException("body=" + CANARY))));
        try (CapturedLog log = new CapturedLog(ChatController.class)) {
            StepVerifier.create(new ChatController(service, PROPS).chat(CANARY, CANARY))
                    .expectNextMatches(sse -> "error".equals(sse.event())
                            && sse.data() instanceof ChatEvent.Error error
                            && !error.message().contains(CANARY))
                    .verifyComplete();
            log.assertSafe("stage=stream");
        }
    }

    @Test
    void quotaRejectionAuditFailureOmitsQuestionAndToken() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        doReturn(Flux.just(0L)).when(redis).execute(any(), anyList(), anyList());
        ChatAuditSink sink = mock(ChatAuditSink.class);
        when(sink.recordAsync(any())).thenReturn(Mono.error(new IllegalStateException(
                "audit=" + CANARY, new RuntimeException("token=" + CANARY))));
        QuotaPrincipalClient principals = mock(QuotaPrincipalClient.class);
        when(principals.resolve(CANARY)).thenReturn(Mono.just(new QuotaPrincipal(7L, 42L)));
        try (CapturedLog log = new CapturedLog(ChatQuotaAuditService.class)) {
            ChatQuotaAuditService quota = new ChatQuotaAuditService(PROPS, sink, principals, redis);
            StepVerifier.create(quota.acquire(CANARY, CANARY.length()))
                    .expectError(ChatQuotaAuditService.QuotaExceededException.class).verify();
            log.assertSafe("stage=quota_rejection_audit");
        }
    }

    @Test
    void auditSinkFailureOmitsUpstreamBodyAndToken() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        doReturn(Flux.error(new IllegalStateException("upstream=" + CANARY,
                new RuntimeException("cause=" + CANARY))))
                .when(redis).execute(any(), anyList(), anyList());
        AiProperties props = new AiProperties("http://app.invalid", 5, 60_000, 30,
                8_000, 24_000, 60, 8, 120_000, 12_000, "kb:ai:quota", "kb:ai:audit",
                100_000, 120, 30_000, 1, 0, 86_400);
        RedisChatAuditSink sink = new RedisChatAuditSink(redis,
                new ObjectMapper().findAndRegisterModules(), props);
        ChatAuditEvent event = new ChatAuditEvent(Instant.parse("2026-10-01T13:00:00Z"),
                7L, 42L, "fingerprint", 12, "completed", 10, 4, 20, "event-1");
        try (CapturedLog log = new CapturedLog(RedisChatAuditSink.class)) {
            sink.record(event);
            log.assertSafe("stage=audit_write");
        }
    }

    private static final class CapturedLog implements AutoCloseable {
        private final Logger logger;
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        CapturedLog(Class<?> type) {
            logger = (Logger) LoggerFactory.getLogger(type);
            appender.start();
            logger.addAppender(appender);
        }

        void assertSafe(String stage) {
            assertThat(appender.list).isNotEmpty();
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(CANARY);
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(appender.list.getFirst().getFormattedMessage()).contains(stage);
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
