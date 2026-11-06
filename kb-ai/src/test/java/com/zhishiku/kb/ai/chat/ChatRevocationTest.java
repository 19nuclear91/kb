package com.zhishiku.kb.ai.chat;

import com.zhishiku.kb.ai.auth.AuthenticationClient;
import com.zhishiku.kb.ai.auth.OutputPermitService;
import com.zhishiku.kb.ai.observation.AiObservationClient;
import com.zhishiku.kb.ai.observation.AiObservationProperties;
import com.zhishiku.kb.ai.observation.AiObservedChat;
import com.zhishiku.kb.common.security.AuthenticationCheck;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.QuotaPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatRevocationTest {
    private final VirtualTimeScheduler timer = VirtualTimeScheduler.getOrSet();
    private final AuthenticationClient authentication = mock(AuthenticationClient.class);
    private final AtomicBoolean denied = new AtomicBoolean();
    private final AtomicBoolean upstreamCancelled = new AtomicBoolean();
    private final ChatQuotaAuditService quota = mock(ChatQuotaAuditService.class);
    private final ChatQuotaAuditService.Lease lease = mock(ChatQuotaAuditService.Lease.class);
    private final AiObservationClient client = mock(AiObservationClient.class);
    private final AiObservationClient.Session session = mock(AiObservationClient.Session.class);
    private final ArrayList<ServerSentEvent<Object>> output = new ArrayList<>();
    private final AiProperties properties = new AiProperties("http://app.invalid", 5, 60000, 60);

    ChatRevocationTest() {
        when(authentication.check(anyString())).thenAnswer(call -> denied.get()
                ? Mono.error(IdentityException.forbidden("身份已撤销")) : Mono.just(new AuthenticationCheck(1, 1, false)));
        when(quota.acquire(any(), anyInt())).thenReturn(Mono.just(lease));
        when(quota.acquireConfirmed(any(), any(), anyInt())).thenReturn(Mono.just(lease));
        when(lease.renewAsync()).thenReturn(Mono.empty());
        when(lease.cancelAsync()).thenReturn(Mono.empty());
        when(lease.failAsync()).thenReturn(Mono.empty());
        when(lease.completeAsync(anyInt(), anyInt())).thenReturn(Mono.empty());
        when(lease.leaseId()).thenReturn("lease");
        when(lease.reservedTokens()).thenReturn(12000L);
        when(client.enabled()).thenReturn(true);
        when(client.open(any())).thenReturn(Mono.just(session));
        when(session.principal()).thenReturn(new QuotaPrincipal(1, 2));
        when(session.simple(any())).thenReturn(Mono.empty());
        when(session.reserved(any(), anyLong())).thenReturn(Mono.empty());
        when(session.renew()).thenReturn(Mono.empty());
        when(session.finalized(any(), any(), any(), nullable(Long.class), any())).thenReturn(Mono.empty());
        when(session.settled(any(), anyLong())).thenReturn(Mono.empty());
    }

    @AfterEach
    void resetClock() {
        VirtualTimeScheduler.reset();
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void allSingleTurnModesStopSilentModelOrPlaceholder(boolean observed, boolean placeholder) {
        var controller = controller(observed, placeholder ? null : Flux.<String>never()
                .doOnCancel(() -> upstreamCancelled.set(true)));
        controller.chat("问题", "token").subscribe(output::add);
        denied.set(true);
        timer.advanceTimeBy(Duration.ofSeconds(3));
        assertStopped();
        if (!placeholder) {
            assertThat(upstreamCancelled).isTrue();
        }
        int count = output.size();
        timer.advanceTimeBy(Duration.ofSeconds(30));
        assertThat(output).hasSize(count);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void slowFinalizationCannotHoldOutputOpenOrReleaseQueuedDone(boolean observed) {
        when(lease.completeAsync(anyInt(), anyInt())).thenReturn(Mono.never());
        when(session.finalized(any(), any(), any(), nullable(Long.class), any())).thenReturn(Mono.never());
        var controller = controller(observed, Flux.just("已经输出的前缀"));
        controller.chat("问题", "token").subscribe(output::add);
        assertThat(output).anyMatch(event -> "delta".equals(event.event()));
        denied.set(true);
        timer.advanceTimeBy(Duration.ofSeconds(3));
        assertStopped();
    }

    @Test
    void resumingWithoutTimerTickRejectsBufferedContentBeforeWriting() {
        var monotonic = new AtomicLong();
        var source = Sinks.many().unicast().<String>onBackpressureBuffer();
        var controller = controller(false, source.asFlux().doOnCancel(() -> upstreamCancelled.set(true)));
        controller.setOutputPermits(new OutputPermitService(authentication, timer, monotonic::get));
        controller.chat("问题", "token").subscribe(output::add);
        monotonic.set(Duration.ofSeconds(11).toNanos());
        source.tryEmitNext("不应输出");
        assertThat(output).noneMatch(event -> "delta".equals(event.event()) || "done".equals(event.event()));
        assertThat(output.getLast().data()).isInstanceOf(ChatEvent.Error.class);
        assertThat(upstreamCancelled).isTrue();
    }

    private ChatController controller(boolean observed, Flux<String> model) {
        var retrieval = mock(RetrievalClient.class);
        var citation = new RetrievalClient.Citation(1, "说明".repeat(100), null, 2, "正文");
        when(retrieval.retrieve(anyString(), anyString())).thenReturn(Mono.just(List.of(citation)));
        var beans = new StaticListableBeanFactory();
        if (model != null) {
            beans.addBean("model", (ChatModelClient) (question, sources) -> model);
        }
        var chat = new ChatService(retrieval, beans.getBeanProvider(ChatModelClient.class), properties);
        var controller = new ChatController(chat, properties, quota);
        controller.setOutputPermits(new OutputPermitService(authentication, timer, () -> timer.now(TimeUnit.NANOSECONDS)));
        if (observed) {
            controller.setObservedChat(new AiObservedChat(client, quota, chat, properties,
                    new AiObservationProperties(new MockEnvironment())));
        }
        return controller;
    }

    private void assertStopped() {
        assertThat(output).noneMatch(event -> "done".equals(event.event()));
        assertThat(output.getLast().event()).isEqualTo("error");
        assertThat(((ChatEvent.Error) output.getLast().data()).code()).isEqualTo("ACCOUNT_REVOKED");
    }
}
