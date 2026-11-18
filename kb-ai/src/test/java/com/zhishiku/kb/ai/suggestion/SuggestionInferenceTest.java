package com.zhishiku.kb.ai.suggestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.ai.chat.SuggestionQuota;
import com.zhishiku.kb.ai.model.ChatModelFailure;
import com.zhishiku.kb.ai.model.MetadataSuggestionModel;
import com.zhishiku.kb.ai.model.ModelEvent;
import com.zhishiku.kb.common.conversation.UsageSettlement;
import com.zhishiku.kb.common.suggestion.SuggestionData;
import com.zhishiku.kb.common.suggestion.SuggestionHttp;
import com.zhishiku.kb.common.suggestion.SuggestionProtocol;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SuggestionInferenceTest {
    private final SuggestionHttp app = mock(SuggestionHttp.class);
    private final SuggestionQuota quota = mock(SuggestionQuota.class);
    private final MetadataSuggestionModel model = mock(MetadataSuggestionModel.class);
    private final SuggestionProtocol.Key key = new SuggestionProtocol.Key(UUID.randomUUID(), UUID.randomUUID(), 1, System.currentTimeMillis() + 60000);
    private final SuggestionData.Material material = new SuggestionData.Material(List.of(), List.of());
    private final SuggestionProtocol.Run run = new SuggestionProtocol.Run(key, material);
    private final MetadataSuggestionModel.Plan plan = new MetadataSuggestionModel.Plan(null, 10);
    private final SuggestionInference inference = newInstance();

    private SuggestionInference newInstance() {
        var beans = new StaticListableBeanFactory();
        beans.addBean("model", model);
        return new SuggestionInference(beans.getBeanProvider(MetadataSuggestionModel.class), quota, new ObjectMapper(), app, 10);
    }

    SuggestionInferenceTest() {
        when(model.prepare(material)).thenReturn(plan);
        when(quota.reserve(key, 10)).thenReturn(true);
        when(quota.renew(key)).thenReturn(true);
        when(app.post(any(), any(), eq("session"), any(), anyInt())).thenReturn("true".getBytes());
    }

    @Test
    void inputLimitPreventsDispatchAndResponseLimitReportsModelOutput() {
        var limit = new ChatModelFailure(ChatModelFailure.Kind.PAYLOAD_LIMIT);
        when(model.prepare(material)).thenThrow(limit).thenReturn(plan);
        assertThat(inference.run(run, "session").reason()).isEqualTo("INPUT_TOO_LARGE");
        verify(model, never()).send(any());
        when(model.send(plan)).thenReturn(Flux.error(limit));
        assertThat(inference.run(run, "session").reason()).isEqualTo("OUTPUT_TOO_LARGE");
        verify(model).send(plan);
    }

    @Test
    void truncatedSuggestionChargesConfirmedUsageWithoutReturningText() {
        when(model.send(plan)).thenReturn(Flux.concat(Flux.just(new ModelEvent.FinalUsage(20, 5)),
                Flux.error(new ChatModelFailure(ChatModelFailure.Kind.OUTPUT_TRUNCATED))));
        var outcome = inference.run(run, "session");
        assertThat(outcome.reason()).isEqualTo("OUTPUT_TOO_LARGE");
        assertThat(outcome.result()).isNull();
        assertThat(UsageSettlement.calculate(true, outcome.prompt() != null && outcome.completion() != null,
                outcome.prompt(), outcome.completion(), 100)).isEqualTo(new UsageSettlement.Value("KNOWN", 20L, 5L, 25));
    }

    @Test
    void interruptedReservationsAndMissingConfirmationsNeverReachTransport() {
        when(quota.reserve(key, 10)).thenReturn(false).thenThrow(new IllegalStateException("配额服务未响应")).thenReturn(true);
        assertThat(inference.run(run, "session").reason()).isEqualTo("QUOTA_REJECTED");
        assertThat(inference.run(run, "session").reason()).isEqualTo("UNAVAILABLE");
        when(app.post(eq("reserved"), any(), eq("session"), any(), anyInt())).thenThrow(new IllegalStateException("丢失确认"));
        assertThat(inference.run(run, "session")).isEqualTo(SuggestionProtocol.Outcome.failure("UNAVAILABLE"));
        verify(model, never()).send(any());
    }

    @Test
    void duplicatedWorkersAndLostPermitAcknowledgmentCannotResend() throws Exception {
        var permit = new AtomicBoolean();
        when(app.post(eq("permit"), any(), eq("session"), any(), anyInt())).thenAnswer(call ->
                Boolean.toString(permit.compareAndSet(false, true)).getBytes());
        var calls = new AtomicInteger();
        when(model.send(plan)).thenReturn(Flux.defer(() -> {
            calls.incrementAndGet();
            return Flux.just(new ModelEvent.Delta("候选"), new ModelEvent.FinalUsage(8, 2));
        }));
        when(model.decode("候选", material)).thenReturn(new SuggestionData.Result(null, null, null));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> inference.run(run, "session"));
            var second = pool.submit(() -> inference.run(run, "session"));
            assertThat(List.of(first.get(), second.get()).stream().filter(item -> item.result() != null).count()).isOne();
        }
        assertThat(calls.get()).isOne();
        when(app.post(eq("permit"), any(), eq("session"), any(), anyInt())).thenThrow(new IllegalStateException("许可确认丢失"));
        assertThat(inference.run(run, "session")).isEqualTo(SuggestionProtocol.Outcome.failure("UNAVAILABLE"));
        assertThat(calls.get()).isOne();
    }

    @Test
    void renewalFailureDuringInferencePreservesUsageAndReportsDependencyFailure() {
        when(quota.renew(key)).thenReturn(true).thenThrow(new IllegalStateException("续租未确认"));
        when(model.send(plan)).thenReturn(Flux.concat(Flux.just(new ModelEvent.FinalUsage(8, 2)), Flux.never()));
        assertThat(inference.run(run, "session")).isEqualTo(new SuggestionProtocol.Outcome("UNAVAILABLE", 8L, 2L, null));
        verify(model).send(plan);
    }

    @Test
    void pollingStopsTheOwnerWhenNotificationHitsAnotherInstance() throws Exception {
        var otherInstance = newInstance();
        var streaming = new CountDownLatch(1);
        var running = new AtomicBoolean(true);
        var upstreamCancelled = new AtomicBoolean();
        when(model.send(plan)).thenReturn(Flux.concat(Flux.just(new ModelEvent.FinalUsage(8, 2)),
                Flux.<ModelEvent>never().doOnSubscribe(subscription -> streaming.countDown()))
                .doOnCancel(() -> upstreamCancelled.set(true)));
        when(app.post(eq("active"), any(), eq("session"), any(), anyInt())).thenAnswer(call ->
                Boolean.toString(running.get()).getBytes(StandardCharsets.UTF_8));
        try (var pool = Executors.newSingleThreadExecutor()) {
            var result = pool.submit(() -> inference.run(run, "session"));
            assertThat(streaming.await(3, TimeUnit.SECONDS)).isTrue();
            running.set(false);
            assertThat(otherInstance.stop(key)).isFalse();

            var outcome = result.get(3, TimeUnit.SECONDS);
            assertThat(upstreamCancelled.get()).isTrue();
            assertThat(outcome.result()).isNull();
            assertThat(outcome.prompt()).isEqualTo(8);
            assertThat(outcome.completion()).isEqualTo(2);
            verify(model).send(plan);
        }
    }

    @Test
    void notificationStopsOnlyTheBoundExecution() throws Exception {
        var subscribed = new CountDownLatch(1);
        var cancelled = new AtomicBoolean();
        when(model.send(plan)).thenReturn(Flux.<ModelEvent>never().doOnSubscribe(sub -> subscribed.countDown())
                .doOnCancel(() -> cancelled.set(true)));
        try (var pool = Executors.newSingleThreadExecutor()) {
            var result = pool.submit(() -> inference.run(run, "session"));
            assertThat(subscribed.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(inference.stop(new SuggestionProtocol.Key(key.requestId(), UUID.randomUUID(), 1, key.deadlineMillis()))).isFalse();
            assertThat(inference.stop(key)).isTrue();
            assertThat(result.get(3, TimeUnit.SECONDS).result()).isNull();
            assertThat(cancelled.get()).isTrue();
            assertThat(inference.stop(key)).isFalse();
        }
    }

    @Test
    void protocolFailureDoesNotExposePrivateTextOrLoseKnownUsage() {
        when(model.send(plan)).thenReturn(Flux.concat(Flux.just(new ModelEvent.FinalUsage(0, 0)),
                Flux.error(new IllegalStateException("正文凭据及模型名称"))));
        var outcome = inference.run(run, "session");
        assertThat(outcome.reason()).isEqualTo("MODEL_UNAVAILABLE");
        assertThat(outcome.result()).isNull();
        assertThat(outcome.prompt()).isZero();
        assertThat(outcome.completion()).isZero();
        when(model.send(plan)).thenReturn(Flux.just(new ModelEvent.Delta("无效"), new ModelEvent.FinalUsage(9, 8)));
        when(model.decode("无效", material)).thenThrow(new IllegalArgumentException("INVALID_MODEL_OUTPUT"));
        var invalid = inference.run(run, "session");
        assertThat(invalid.reason()).isEqualTo("INVALID_MODEL_OUTPUT");
        assertThat(invalid.prompt()).isEqualTo(9);
        assertThat(invalid.completion()).isEqualTo(8);
    }
}
