package com.zhishiku.kb.ai.conversation;

import com.zhishiku.kb.ai.chat.AiProperties;
import com.zhishiku.kb.ai.chat.ChatQuotaAuditService;
import com.zhishiku.kb.ai.chat.RetrievalClient;
import com.zhishiku.kb.ai.conversation.config.ConversationProperties;
import com.zhishiku.kb.common.conversation.UsageSettlement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultiTurnChatServiceTest {
    final ConversationAppClient app = mock(ConversationAppClient.class);
    final RetrievalClient retrieval = mock(RetrievalClient.class);
    final ChatQuotaAuditService quota = mock(ChatQuotaAuditService.class);
    final ChatQuotaAuditService.Lease lease = mock(ChatQuotaAuditService.Lease.class);
    final ConversationSettlementService settlement = mock(ConversationSettlementService.class);
    final ConversationProperties properties = new ConversationProperties();
    final AtomicInteger subscriptions = new AtomicInteger();
    final AtomicBoolean cancelled = new AtomicBoolean();
    final AtomicBoolean dispatched = new AtomicBoolean();
    final List<String> order = new ArrayList<>();
    Flux<MultiTurnModel.Event> events = Flux.just(new MultiTurnModel.Event("答案😀", false, null, null),
            new MultiTurnModel.Event(null, true, 10L, 1L), new MultiTurnModel.Event(null, true, 12L, 2L));
    MultiTurnModel.Input input;
    final ExecutionKey key = new ExecutionKey(9, UUID.randomUUID(), 1, 0, "ticket");
    final BeginResult begin = new BeginResult(9, key.turnId(), 1, "RUNNING", false, key);
    final MultiTurnModel model = new MultiTurnModel() {
        public boolean supportsTokenUpperBound() {
            return true;
        }

        public long contextWindowTokens() {
            return 12000;
        }

        public long inputTokenUpperBound(Input value) {
            return 100;
        }

        public Flux<Event> generate(Input value) {
            input = value;
            return Flux.defer(() -> {
                subscriptions.incrementAndGet();
                return events.doOnCancel(() -> cancelled.set(true));
            });
        }
    };

    MultiTurnChatService service(boolean provider) {
        var beans = new StaticListableBeanFactory();
        if (provider) beans.addBean("model", model);
        return new MultiTurnChatService(app, retrieval, quota, settlement, properties, new AiProperties("http://app",
                5, 10000, 60), beans.getBeanProvider(MultiTurnModel.class));
    }

    MultiTurnChatServiceTest() {
        when(app.check(anyLong(), anyString(), anyString(), anyLong(), anyString())).thenReturn(Mono.just(new BeginResult(0, null, 0, null, false, null)));
        when(app.begin(anyLong(), anyString(), anyString(), anyLong(), anyString())).thenReturn(Mono.just(begin));
        when(quota.acquire(anyString(), anyInt())).thenReturn(Mono.just(lease));
        when(lease.userId()).thenReturn(1L);
        when(lease.departmentId()).thenReturn(10L);
        when(lease.leaseId()).thenReturn(UUID.randomUUID().toString());
        when(lease.eventId()).thenReturn(UUID.randomUUID().toString());
        when(lease.reservedTokens()).thenReturn(12000L);
        when(lease.renewAsync()).thenReturn(Mono.empty());
        when(app.reserve(any(), anyLong(), anyString(), anyString(), anyLong())).thenReturn(Mono.empty());
        when(app.prepare(9, "token")).thenReturn(Mono.just(List.of()));
        when(retrieval.retrieve(anyString(), anyString())).thenReturn(Mono.just(List.of(new RetrievalClient.Citation(1, "title", null, 3, "材料", 2L, 1, 0, null, null, null, null, null, null, null, false))));
        when(app.seal(any(), anyString(), any(), any(), anyString())).thenAnswer(call -> Mono.fromRunnable(() -> order.add("seal")));
        when(app.dispatch(any())).thenAnswer(call -> Mono.fromRunnable(() -> {
            dispatched.set(true);
            order.add("dispatch");
        }));
        when(app.renew(any())).thenReturn(Mono.empty());
        when(app.append(any(), anyLong(), anyString())).thenAnswer(call -> Mono.fromSupplier(() -> {
            order.add("append:" + call.getArgument(2));
            return (Long) call.getArgument(1);
        }));
        when(app.finish(any(), anyString(), anyLong(), anyBoolean(), any(), any())).thenAnswer(call -> Mono.fromSupplier(() -> {
            String status = call.getArgument(1);
            order.add("finish:" + status);
            var usage = UsageSettlement.calculate(dispatched.get(), call.getArgument(3), call.getArgument(4),
                    call.getArgument(5), 12000);
            return new SettlementIntent(key.turnId(), 1, 10, UUID.fromString(lease.leaseId()),
                    UUID.fromString(lease.eventId()), 12000, usage.status(),
                    usage.promptTokens(), usage.completionTokens(), usage.chargedTokens(), status, "digest",
                    Instant.now());
        }));
        when(settlement.settle(any())).thenAnswer(call -> Mono.fromRunnable(() -> order.add("settle")));
        when(app.acknowledge(any())).thenReturn(Mono.empty());
    }

    @Test
    void subscribesOnceUsesLastCumulativeUsageAndSavesBeforeSettlementDone() {
        var service = service(true);
        var prepared = service.prepare(9, "问题", "r", 0, "token").block();
        var output = service.stream(prepared).collectList().block(Duration.ofSeconds(5));
        assertThat(subscriptions).hasValue(1);
        assertThat(output.stream().map(ServerSentEvent::event).toList()).containsExactly("citation", "delta", "done");
        assertThat(order).containsExactly("seal", "dispatch", "append:答案😀", "finish:COMPLETED", "settle");
        verify(app).finish(key, "COMPLETED", 1, true, 12L, 2L);
        assertThat(input.messages()).containsExactly(new MultiTurnModel.Message("user", "问题"));
        StepVerifier.create(service.stream(prepared)).expectError(ResponseStatusException.class).verify();
        assertThat(subscriptions).hasValue(1);
    }

    @Test
    void noResultsNeverInvokesModelOrImportsHistoryAndReportsNotInvoked() {
        when(retrieval.retrieve(anyString(), anyString())).thenReturn(Mono.just(List.of()));
        var service = service(true);
        var prepared = service.prepare(9, "问题", "r", 0, "token").block();
        var output = service.stream(prepared).collectList().block(Duration.ofSeconds(5));
        assertThat(subscriptions).hasValue(0);
        verify(app, never()).dispatch(any());
        verify(app).seal(key, "NO_RESULTS", List.of(), List.of(), "token");
        assertThat(output.getLast().data().toString()).contains("NOT_INVOKED");
        assertThat(order).contains("append:" + MultiTurnChatService.NO_RESULTS, "finish:COMPLETED", "settle");
    }

    @Test
    void replayNeverAcquiresQuotaOrStartsModelAndMissingAdapterFailsBeforeBegin() {
        when(app.check(anyLong(), anyString(), anyString(), anyLong(), anyString())).thenReturn(Mono.just(new BeginResult(9, key.turnId(), 1, "COMPLETED", true, null)));
        var service = service(false);
        assertThat(service.prepare(9, "问题", "r", 0, "token").block().result().replayed()).isTrue();
        verify(quota, never()).acquire(anyString(), anyInt());
        verify(app, never()).begin(anyLong(), anyString(), anyString(), anyLong(), anyString());
        when(app.check(anyLong(), anyString(), anyString(), anyLong(), anyString())).thenReturn(Mono.just(new BeginResult(0, null, 0, null, false, null)));
        StepVerifier.create(service.prepare(9, "问题", "new", 0, "token")).expectErrorMatches(e -> e instanceof ResponseStatusException s && s.getStatusCode().value() == 503).verify();
    }

    @Test
    void cancellationStopsModelFlushesSavedPrefixAndClosesIncomplete() {
        events = Flux.just(new MultiTurnModel.Event("部分😀", false, null, null)).concatWith(Flux.never());
        var service = service(true);
        var prepared = service.prepare(9, "问题", "r", 0, "token").block();
        StepVerifier.withVirtualTime(() -> service.stream(prepared)).expectNextMatches(e -> "citation".equals(e.event()))
                .expectNextMatches(e -> "delta".equals(e.event())).thenCancel().verify();
        assertThat(cancelled).isTrue();
        assertThat(order).contains("append:部分😀", "finish:CANCELLED", "settle");
        assertThat(order).doesNotContain("finish:COMPLETED");
    }

    @Test
    void timersFlushSmallBatchAndRenewFailureCancelsBeforeError() {
        events = Flux.just(new MultiTurnModel.Event("部分", false, null, null)).concatWith(Flux.never());
        properties.setRenewalMs(2000);
        when(app.renew(key)).thenReturn(Mono.error(new IllegalStateException("private")));
        var service = service(true);
        var prepared = service.prepare(9, "问题", "r", 0, "token").block();
        StepVerifier.withVirtualTime(() -> service.stream(prepared)).expectNextCount(2).thenAwait(Duration.ofSeconds(2))
                .expectNextMatches(e -> "error".equals(e.event())).verifyComplete();
        assertThat(cancelled).isTrue();
        assertThat(order).contains("append:部分", "finish:FAILED");
    }

    @Test
    void settlementFailureKeepsCompletedStateAndNeverSendsDone() {
        when(settlement.settle(any())).thenReturn(Mono.error(new IllegalStateException("private")));
        var service = service(true);
        var prepared = service.prepare(9, "问题", "r", 0, "token").block();
        var output = service.stream(prepared).collectList().block(Duration.ofSeconds(5));
        assertThat(output.getLast().event()).isEqualTo("error");
        assertThat(output.getLast().data().toString()).doesNotContain("private");
        assertThat(order).contains("finish:COMPLETED").doesNotContain("finish:FAILED", "finish:CANCELLED");
    }

    @Test
    void outputLimitAndSlowPersistenceKeepModelDemandBounded() {
        properties.setBatchCodepoints(1);
        properties.setMaxAnswerCodepoints(100);
        AtomicInteger produced = new AtomicInteger();
        events = Flux.range(0, 100).map(i -> {
            produced.incrementAndGet();
            return new MultiTurnModel.Event("x", false, null, null);
        });
        when(app.append(any(), anyLong(), anyString())).thenAnswer(call -> Mono.delay(Duration.ofSeconds(1)).map(t -> (Long) call.getArgument(1)));
        var service = service(true);
        var prepared = service.prepare(9, "问题", "r", 0, "token").block();
        StepVerifier.withVirtualTime(() -> service.stream(prepared)).expectNextMatches(e -> "citation".equals(e.event()))
                .thenAwait(Duration.ofSeconds(2)).expectNextCount(2).thenCancel().verify();
        assertThat(produced.get()).isLessThan(10);
        assertThat(cancelled).isTrue();
    }

    @Test
    void oversizedAnswerNeverSendsDoneOrStoresOverflow() {
        properties.setMaxAnswerCodepoints(2);
        events = Flux.just(new MultiTurnModel.Event("😀😀😀", false, null, null));
        var service = service(true);
        var prepared = service.prepare(9, "问题", "r", 0, "token").block();
        var output = service.stream(prepared).collectList().block();
        assertThat(output.getLast().event()).isEqualTo("error");
        assertThat(order).contains("finish:FAILED").noneMatch(value -> value.startsWith("append:"));
    }
}

