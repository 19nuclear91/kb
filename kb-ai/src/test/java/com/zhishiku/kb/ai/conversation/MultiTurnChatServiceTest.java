package com.zhishiku.kb.ai.conversation;

import com.zhishiku.kb.ai.auth.PermitFixtures;

import com.zhishiku.kb.ai.chat.AiProperties;
import com.zhishiku.kb.ai.chat.ChatQuotaAuditService;
import com.zhishiku.kb.ai.chat.RetrievalClient;
import com.zhishiku.kb.ai.chat.RetrievalFailure;
import com.zhishiku.kb.ai.budget.BudgetCapability;
import com.zhishiku.kb.ai.budget.BudgetKind;
import com.zhishiku.kb.ai.budget.InputTokenCount;
import com.zhishiku.kb.ai.model.ChatModelFailure;
import com.zhishiku.kb.ai.conversation.config.ConversationProperties;
import com.zhishiku.kb.common.conversation.UsageSettlement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
    final ExecutionKey key = new ExecutionKey(9, UUID.randomUUID(), 1, 0, 1, "ticket");
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
    MultiTurnModel activeModel = model;

    MultiTurnModel estimated(long count) {
        var estimated = mock(MultiTurnModel.class);
        when(estimated.budgetCapability()).thenReturn(new BudgetCapability(BudgetKind.ESTIMATED, 16000, 512));
        when(estimated.countInputTokens(any())).thenReturn(new InputTokenCount(BudgetKind.ESTIMATED, count));
        when(estimated.generate(any())).thenAnswer(call -> model.generate(call.getArgument(0)));
        activeModel = estimated;
        return estimated;
    }

    private static List<RetrievalClient.Citation> materials() {
        return List.of(new RetrievalClient.Citation(1, "title", null, 3, "材料", 2L, 1, 0,
                null, null, null, null, null, null, null, false));
    }

    MultiTurnChatService service(boolean provider) {
        var beans = new StaticListableBeanFactory();
        if (provider) {
            beans.addBean("model", activeModel);
        }
        return PermitFixtures.multi(app, retrieval, quota, settlement, properties, new AiProperties("http://app",
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
    void retrievalFailureAndUnversionedMaterialNeverSealOrAppendAndSettleZero() {
        for (boolean unversioned : new boolean[]{false, true}) {
            var fixture = new MultiTurnChatServiceTest();
            when(fixture.retrieval.retrieve(anyString(), anyString())).thenReturn(unversioned
                    ? Mono.just(List.of(new RetrievalClient.Citation(1, "old", null, 2, "text")))
                    : Mono.error(new RetrievalFailure(RetrievalFailure.Kind.HTTP)));
            var service = fixture.service(true);
            var state = service.prepare(9, "q", "r", 0, "token").block();
            StepVerifier.create(service.stream(state)).expectNextMatches(event -> "error".equals(event.event())
                    && event.data().toString().contains("SEARCH_UNAVAILABLE")).verifyComplete();
            assertThat(fixture.order).containsExactly("finish:FAILED", "settle");
            assertThat(fixture.subscriptions).hasValue(0);
            verify(fixture.app, never()).seal(any(), anyString(), any(), any(), anyString());
            verify(fixture.app, never()).append(any(), anyLong(), anyString());
            verify(fixture.app, never()).dispatch(any());
            verify(fixture.settlement).settle(org.mockito.ArgumentMatchers.argThat(intent ->
                    intent.generationStatus().equals("FAILED") && intent.usageStatus().equals("NOT_INVOKED")
                            && intent.chargedTokens() == 0 && intent.promptTokens() == 0 && intent.completionTokens() == 0));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationWinsWhileSelectionErrorDeliveryIsInFlight(boolean budgetFailure) throws Exception {
        var source = TestPublisher.<List<RetrievalClient.Citation>>create();
        var finishing = TestPublisher.<SettlementIntent>create();
        var errorEntered = new CountDownLatch(1);
        var releaseError = new CountDownLatch(1);
        when(retrieval.retrieve(anyString(), anyString())).thenReturn(source.mono().doOnError(error -> {
            errorEntered.countDown();
            awaitErrorRelease(releaseError);
        }));
        if (budgetFailure) {
            when(estimated(9441).countInputTokens(any())).thenAnswer(call -> {
                errorEntered.countDown();
                awaitErrorRelease(releaseError);
                return new InputTokenCount(BudgetKind.ESTIMATED, 9441);
            });
        }
        when(app.finish(any(), anyString(), anyLong(), anyBoolean(), any(), any())).thenReturn(finishing.mono());
        var service = service(true);
        var state = service.prepare(9, "q", "r", 0, "token").block();
        var output = new CopyOnWriteArrayList<ServerSentEvent<Object>>();
        var errors = new CopyOnWriteArrayList<Throwable>();
        var completed = new AtomicInteger();
        var subscription = service.stream(state).subscribe(output::add, errors::add, completed::incrementAndGet);
        var sender = Executors.newSingleThreadExecutor();
        try {
            var delivered = sender.submit(() -> {
                if (budgetFailure) {
                    source.emit(materials());
                } else {
                    source.error(new RetrievalFailure(RetrievalFailure.Kind.HTTP));
                }
            });
            assertThat(errorEntered.await(3, TimeUnit.SECONDS)).isTrue();
            subscription.dispose();
            finishing.assertSubscribers(1);
            verify(app).finish(key, "CANCELLED", 0, false, null, null);
            releaseError.countDown();
            delivered.get(3, TimeUnit.SECONDS);
            finishing.assertSubscribers(1).assertWasNotCancelled();
            var intent = unusedIntent("CANCELLED");
            finishing.emit(intent);
            subscription.dispose();
            assertSingleRetrievalCleanup(intent);
            assertThat(output).isEmpty();
            assertThat(errors).isEmpty();
            assertThat(completed).hasValue(0);
        } finally {
            releaseError.countDown();
            subscription.dispose();
            sender.shutdownNow();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void selectionFailureCleanupSurvivesCancellationDuringFinishOrSettlement(boolean finishConfirmed, boolean budgetFailure) {
        var source = TestPublisher.<List<RetrievalClient.Citation>>create();
        var finishing = TestPublisher.<SettlementIntent>create();
        var settling = TestPublisher.<Void>create();
        var finishSubscriptions = new AtomicInteger();
        var settleSubscriptions = new AtomicInteger();
        when(retrieval.retrieve(anyString(), anyString())).thenReturn(source.mono());
        if (budgetFailure) {
            estimated(9441);
        }
        when(app.finish(any(), anyString(), anyLong(), anyBoolean(), any(), any()))
                .thenReturn(finishing.mono().doOnSubscribe(ignored -> finishSubscriptions.incrementAndGet()));
        when(settlement.settle(any()))
                .thenReturn(settling.mono().doOnSubscribe(ignored -> settleSubscriptions.incrementAndGet()));
        var service = service(true);
        var state = service.prepare(9, "q", "r", 0, "token").block();
        var output = new ArrayList<ServerSentEvent<Object>>();
        var errors = new ArrayList<Throwable>();
        var completed = new AtomicInteger();
        var subscription = service.stream(state).subscribe(output::add, errors::add, completed::incrementAndGet);
        try {
            if (budgetFailure) {
                source.emit(materials());
            } else {
                source.error(new RetrievalFailure(RetrievalFailure.Kind.TIMEOUT));
            }
            finishing.assertSubscribers(1);
            verify(app).finish(key, "FAILED", 0, false, null, null);
            var intent = unusedIntent("FAILED");
            if (finishConfirmed) {
                finishing.emit(intent);
                settling.assertSubscribers(1);
            }
            subscription.dispose();
            if (!finishConfirmed) {
                finishing.assertSubscribers(1).assertWasNotCancelled();
                finishing.emit(intent);
            }
            settling.assertSubscribers(1).assertWasNotCancelled();
            verify(app, never()).acknowledge(any());
            settling.complete();
            subscription.dispose();
            assertSingleRetrievalCleanup(intent);
            assertThat(finishSubscriptions).hasValue(1);
            assertThat(settleSubscriptions).hasValue(1);
            assertThat(output).isEmpty();
            assertThat(errors).isEmpty();
            assertThat(completed).hasValue(0);
        } finally {
            subscription.dispose();
        }
    }

    private SettlementIntent unusedIntent(String status) {
        return new SettlementIntent(key.turnId(), 1, 10, UUID.fromString(lease.leaseId()),
                UUID.fromString(lease.eventId()), 12000, "NOT_INVOKED", 0L, 0L, 0L, status, "digest", Instant.now());
    }

    private void assertSingleRetrievalCleanup(SettlementIntent intent) {
        verify(retrieval).retrieve("q", "token");
        verify(app).finish(any(), anyString(), anyLong(), anyBoolean(), any(), any());
        verify(settlement).settle(intent);
        verify(app).acknowledge(intent);
        verify(app, never()).seal(any(), anyString(), any(), any(), anyString());
        verify(app, never()).append(any(), anyLong(), anyString());
        verify(app, never()).dispatch(any());
        assertThat(subscriptions).hasValue(0);
        assertThat(dispatched).isFalse();
        assertThat(input).isNull();
    }

    private static void awaitErrorRelease(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("检索错误交付未被测试释放");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void selectionFailureCleanupTimesOutAndConfirmedFailedIsNotRewrittenBySettlementFailure(boolean budgetFailure) {
        properties.setCleanupTimeoutMs(100);
        if (budgetFailure) {
            estimated(9441);
        } else {
            when(retrieval.retrieve(anyString(), anyString())).thenReturn(Mono.error(new RetrievalFailure(RetrievalFailure.Kind.TIMEOUT)));
        }
        when(app.finish(any(), anyString(), anyLong(), anyBoolean(), any(), any())).thenReturn(Mono.never());
        var service = service(true);
        var state = service.prepare(9, "q", "r", 0, "token").block();
        StepVerifier.withVirtualTime(() -> service.stream(state)).thenAwait(Duration.ofMillis(101))
                .expectNextMatches(event -> "error".equals(event.event())).verifyComplete();
        verify(settlement, never()).settle(any());
        var other = new MultiTurnChatServiceTest();
        if (budgetFailure) {
            other.estimated(9441);
        } else {
            when(other.retrieval.retrieve(anyString(), anyString())).thenReturn(Mono.error(new RetrievalFailure(RetrievalFailure.Kind.HTTP)));
        }
        when(other.settlement.settle(any())).thenReturn(Mono.error(new IllegalStateException("private")));
        var next = other.service(true);
        next.stream(next.prepare(9, "q", "r", 0, "token").block()).blockLast(Duration.ofSeconds(2));
        assertThat(other.order).containsExactly("finish:FAILED");
        verify(other.app, never()).acknowledge(any());
    }

    @Test
    void failedReplayIsReadOnlyButNewRequestChecksCurrentSequenceAndRetrievesOnce() {
        when(app.check(9, "q", "old", 0, "token"))
                .thenReturn(Mono.just(new BeginResult(9, key.turnId(), 1, "FAILED", true, null)));
        var service = service(true);
        var replay = service.prepare(9, "q", "old", 0, "token").block();
        assertThat(replay.result().status()).isEqualTo("FAILED");
        verify(quota, never()).acquire(anyString(), anyInt());
        verify(retrieval, never()).retrieve(anyString(), anyString());
        when(retrieval.retrieve(anyString(), anyString())).thenReturn(Mono.error(new RetrievalFailure(RetrievalFailure.Kind.HTTP)));
        var fresh = service.prepare(9, "q", "new", 1, "token").block();
        service.stream(fresh).blockLast(Duration.ofSeconds(2));
        verify(app).check(9, "q", "new", 1, "token");
        verify(app).begin(9, "q", "new", 1, "token");
        verify(quota).acquire("token", 1);
        verify(retrieval).retrieve("q", "token");
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

    @Test
    void budgetExhaustionIsSafeFailedNotInvokedWithoutCitationsOrAnswer() {
        var estimated = estimated(9441);
        var service = service(true);
        var state = service.prepare(9, "private-question", "r", 0, "token").block();
        var output = service.stream(state).collectList().block(Duration.ofSeconds(2));
        assertThat(output).extracting(ServerSentEvent::event).containsExactly("error");
        assertThat(output.getFirst().data().toString()).contains("INPUT_BUDGET_EXCEEDED", "输入超出模型预算")
                .doesNotContain("private-question", "token", "材料", "未找到");
        verify(estimated, never()).generate(any());
        verify(app, never()).seal(any(), anyString(), any(), any(), anyString());
        verify(app, never()).append(any(), anyLong(), anyString());
        verify(app, never()).dispatch(any());
        assertThat(order).containsExactly("finish:FAILED", "settle");
        verify(settlement).settle(org.mockito.ArgumentMatchers.argThat(intent ->
                intent.generationStatus().equals("FAILED") && intent.usageStatus().equals("NOT_INVOKED")
                        && intent.promptTokens() == 0 && intent.completionTokens() == 0 && intent.chargedTokens() == 0));
        verify(app).acknowledge(any());
    }

    @Test
    void whitespaceOnlyMaterialAfterClippingFailsWithoutSealingOrDispatchAndSettlesZero() {
        var estimated = estimated(100);
        properties.setMaxContextCodepoints(ConversationBudget.INSTRUCTION.codePointCount(0,
                ConversationBudget.INSTRUCTION.length()) + 3);
        when(retrieval.retrieve(anyString(), anyString())).thenReturn(Mono.just(List.of(
                new RetrievalClient.Citation(1, "t", 2, 3, " \u3000😀文", 4L,
                        null, null, null, null, null, null, null, null, null, false))));
        var service = service(true);
        var output = service.stream(service.prepare(9, "Q", "r", 0, "token").block())
                .collectList().block(Duration.ofSeconds(2));
        assertThat(output).extracting(ServerSentEvent::event).containsExactly("error");
        assertThat(output.getFirst().data().toString()).contains("INPUT_BUDGET_EXCEEDED")
                .doesNotContain("未找到", "😀文", "token");
        verify(estimated, never()).generate(any());
        verify(app, never()).seal(any(), anyString(), any(), any(), anyString());
        verify(app, never()).append(any(), anyLong(), anyString());
        verify(app, never()).dispatch(any());
        verify(app).finish(key, "FAILED", 0, false, null, null);
        verify(settlement).settle(org.mockito.ArgumentMatchers.argThat(intent ->
                intent.generationStatus().equals("FAILED") && intent.usageStatus().equals("NOT_INVOKED")
                        && intent.promptTokens() == 0 && intent.completionTokens() == 0 && intent.chargedTokens() == 0));
        verify(app).acknowledge(any());
    }

    @Test
    void estimatedUsageAboveReservationIsPreservedWithoutClampingOrRetry() {
        var estimated = estimated(100);
        events = Flux.just(new MultiTurnModel.Event("answer", false, null, null),
                new MultiTurnModel.Event(null, true, 15000L, 3000L));
        var service = service(true);
        var output = service.stream(service.prepare(9, "q", "r", 0, "token").block())
                .collectList().block(Duration.ofSeconds(2));
        assertThat(output.getLast().event()).isEqualTo("done");
        assertThat(output.getLast().data().toString()).contains("KNOWN", "18000", "15000", "3000");
        verify(app).finish(key, "COMPLETED", 1, true, 15000L, 3000L);
        verify(settlement).settle(org.mockito.ArgumentMatchers.argThat(intent ->
                intent.usageStatus().equals("KNOWN") && intent.chargedTokens() == 18000));
        verify(estimated).generate(any());
        assertThat(subscriptions).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void estimatedMissingUsageOrUpstreamRejectionStaysUnknownAndNeverRetries(boolean rejection) {
        var estimated = estimated(100);
        events = rejection ? Flux.error(new ChatModelFailure(ChatModelFailure.Kind.HTTP_REJECTED))
                : Flux.just(new MultiTurnModel.Event("answer", false, null, null));
        var service = service(true);
        var output = service.stream(service.prepare(9, "q", "r", 0, "token").block())
                .collectList().block(Duration.ofSeconds(2));
        assertThat(output.getLast().event()).isEqualTo(rejection ? "error" : "done");
        if (!rejection) {
            assertThat(output.getLast().data().toString())
                    .contains("UNKNOWN", "promptTokens=null", "completionTokens=null", "chargedTokens=12000", "lastBatchSeq=1");
        }
        verify(settlement).settle(org.mockito.ArgumentMatchers.argThat(intent ->
                intent.usageStatus().equals("UNKNOWN") && intent.chargedTokens() == 12000
                        && intent.promptTokens() == null && intent.completionTokens() == null
                        && intent.generationStatus().equals(rejection ? "FAILED" : "COMPLETED")));
        verify(estimated).generate(any());
        assertThat(subscriptions).hasValue(1);
    }

    @Test
    void finalBudgetCheckFailureAfterDispatchStillSettlesUnknownEvenWithoutGenerationSubscription() {
        var estimated = estimated(100);
        when(estimated.generate(any())).thenThrow(new ChatModelFailure(ChatModelFailure.Kind.BUDGET_UNAVAILABLE));
        var service = service(true);
        var output = service.stream(service.prepare(9, "q", "r", 0, "token").block())
                .collectList().block(Duration.ofSeconds(2));
        assertThat(output).extracting(ServerSentEvent::event).containsExactly("citation", "error");
        assertThat(order).containsExactly("seal", "dispatch", "finish:FAILED", "settle");
        assertThat(subscriptions).hasValue(0);
        verify(estimated).generate(any());
        verify(settlement).settle(org.mockito.ArgumentMatchers.argThat(intent ->
                intent.usageStatus().equals("UNKNOWN") && intent.chargedTokens() == 12000
                        && intent.promptTokens() == null && intent.completionTokens() == null));
    }

    @Test
    void capabilityDriftAfterSealingCannotSwitchBudgetForGeneration() {
        var estimated = estimated(100);
        when(app.dispatch(any())).thenAnswer(call -> Mono.fromRunnable(() -> {
            dispatched.set(true);
            when(estimated.budgetCapability()).thenReturn(new BudgetCapability(BudgetKind.ESTIMATED, 32000, 0));
        }));
        var service = service(true);
        var output = service.stream(service.prepare(9, "q", "r", 0, "token").block())
                .collectList().block(Duration.ofSeconds(2));
        assertThat(output.getLast().event()).isEqualTo("error");
        verify(estimated, never()).generate(any());
        verify(settlement).settle(org.mockito.ArgumentMatchers.argThat(intent ->
                intent.usageStatus().equals("UNKNOWN") && intent.chargedTokens() == 12000));
    }
}
