package com.zhishiku.kb.ai.observation;

import com.zhishiku.kb.ai.chat.AiProperties;
import com.zhishiku.kb.ai.chat.ChatEvent;
import com.zhishiku.kb.ai.chat.ChatModelClient;
import com.zhishiku.kb.ai.chat.ChatObservation;
import com.zhishiku.kb.ai.chat.ChatQuotaAuditService;
import com.zhishiku.kb.ai.chat.ChatService;
import com.zhishiku.kb.ai.chat.QuotaPrincipalRejectedException;
import com.zhishiku.kb.ai.telemetry.StreamTelemetry;
import com.zhishiku.kb.ai.auth.OutputPermitService;
import com.zhishiku.kb.ai.auth.OutputRevokedException;
import com.zhishiku.kb.common.aiusage.AiObservation;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单轮仅提交观测事实；元数据重试不会重新执行模型或账本。
 */
@Service
public class AiObservedChat {
    private final AiObservationClient client;
    private final ChatQuotaAuditService quota;
    private final ChatService chat;
    private final AiProperties ai;
    private final AiObservationProperties properties;
    private OutputPermitService permits;

    @org.springframework.beans.factory.annotation.Autowired
    public void setOutputPermits(OutputPermitService permits) {
        this.permits = permits;
    }

    public AiObservedChat(AiObservationClient client, ChatQuotaAuditService quota, ChatService chat,
                          AiProperties ai, AiObservationProperties properties) {
        this.client = client; this.quota = quota; this.chat = chat; this.ai = ai; this.properties = properties;
    }

    public boolean enabled() { return client.enabled(); }

    public Flux<ServerSentEvent<Object>> stream(String question, String token) {
        return OutputPermitService.protect(permits, token, null, () -> streamWithinPermit(question, token))
                .onErrorResume(OutputRevokedException.class, error -> Flux.just(sse(new ChatEvent.Error(error.getMessage(), error.code()))));
    }

    private Flux<ServerSentEvent<Object>> streamWithinPermit(String question, String token) {
        if (question == null || question.isBlank()) return Flux.just(sse(new ChatEvent.Error("问题不能为空。")));
        if (question.codePointCount(0, question.length()) > ai.maxQuestionChars()) return Flux.just(sse(new ChatEvent.Error("问题过长，请缩短后重试。")));
        return Flux.usingWhen(client.open(token).map(Run::new), run -> run.body(question, token),
                run -> run.close("CANCELLED", 0, "CANCELLED").onErrorResume(error -> Mono.empty()),
                (run, error) -> run.close("FAILED", 0, "FAILED").onErrorResume(ignored -> Mono.empty()),
                run -> run.close("CANCELLED", 0, "CANCELLED").onErrorResume(error -> Mono.empty()))
                .transform(StreamTelemetry::captureFailures)
                .onErrorResume(error -> Flux.just(sse(new ChatEvent.Error("聊天采集或依赖暂不可用，请稍后重试。"))));
    }

    private final class Run implements ChatObservation {
        private final AiObservationClient.Session session;
        private volatile ChatQuotaAuditService.Lease lease;
        private volatile boolean acquisitionIntended;
        private volatile boolean dispatched;
        private volatile String result = "FAILED";
        private volatile Long prompt;
        private volatile Long completion;
        private volatile boolean usageCandidate;
        private volatile boolean confirmedUsage;
        private int chargedPrompt;
        private int chargedCompletion;
        private Mono<Void> terminal;
        private volatile boolean finalizing;
        private volatile boolean finalized;
        private final Sinks.One<Boolean> finalizationOutcome = Sinks.one();

        private Run(AiObservationClient.Session session) { this.session = session; }

        private Flux<ServerSentEvent<Object>> body(String question, String token) {
            Mono<Void> acquire = Mono.defer(() -> {
                acquisitionIntended = true;
                return session.simple("ACQUIRE_INTENDED").then(quota.acquireConfirmed(session.principal(), token, question.length()))
                        .doOnNext(value -> lease = value).flatMap(value -> session.reserved(value.leaseId(), value.reservedTokens()));
            });
            Flux<ChatEvent> source = acquire.thenMany(Flux.defer(() -> chat.chat(question, token, this)))
                    .concatMap(event -> {
                        if (event instanceof ChatEvent.Done done) {
                            chargedPrompt = Math.max(0, done.promptTokens());
                            chargedCompletion = Math.max(0, done.completionTokens());
                            long amount = (long) chargedPrompt + chargedCompletion;
                            return close("COMPLETED", amount, result).thenReturn(event);
                        }
                        if (event instanceof ChatEvent.Error) {
                            return close("FAILED", 0, "FAILED").onErrorResume(error -> Mono.empty()).thenReturn(event);
                        }
                        return Mono.just(event);
                    }).transform(StreamTelemetry::captureFailures).onErrorResume(error -> {
                        if (lease == null && error instanceof QuotaPrincipalRejectedException) acquisitionIntended = false;
                        boolean rejected = error instanceof ChatQuotaAuditService.QuotaExceededException;
                        return close(rejected ? "REJECTED" : "FAILED", 0, rejected ? "QUOTA_REJECTED" : "DEPENDENCY_UNAVAILABLE")
                                .onErrorResume(ignored -> Mono.empty()).thenMany(Flux.just(new ChatEvent.Error(rejected ? "所在部门的 AI 配额已用尽，请稍后重试。" : "聊天处理暂未完成，请稍后重试。")));
                    });
            Flux<ServerSentEvent<Object>> heartbeat = Flux.interval(Duration.ofMillis(ai.keepAliveMs()))
                    .onBackpressureDrop().map(t -> ServerSentEvent.<Object>builder().comment("keep-alive").build());
            Duration renewalInterval = Duration.ofMillis(Math.min(properties.getRenewalMs(), ai.quotaRenewalIntervalMs()));
            Flux<ServerSentEvent<Object>> renew = Flux.interval(renewalInterval).onBackpressureDrop()
                    .concatMap(t -> renewObservation().thenMany(Flux.<ServerSentEvent<Object>>empty()))
                    .takeUntilOther(finalizationOutcome.asMono());
            Flux<ServerSentEvent<Object>> quotaRenew = Flux.interval(renewalInterval).onBackpressureDrop()
                    .concatMap(t -> Mono.defer(() -> lease == null ? Mono.empty() : lease.renewAsync())
                            .thenMany(Flux.<ServerSentEvent<Object>>empty()));
            return Flux.merge(source.map(AiObservedChat::sse), heartbeat, renew, quotaRenew)
                    .takeUntil(event -> "done".equals(event.event()) || "error".equals(event.event()));
        }

        private Mono<Void> renewObservation() {
            return Mono.defer(() -> finalized ? Mono.empty() : session.renew())
                    .onErrorResume(error -> {
                        if (finalized) return Mono.empty();
                        // 终态已写入但确认尚在途中时，续期拒绝只有在终态确认后才可忽略。
                        if (finalizing) return finalizationOutcome.asMono()
                                .flatMap(confirmed -> confirmed ? Mono.empty() : Mono.error(error));
                        return Mono.error(error);
                    });
        }

        @Override
        public Mono<Void> dispatch() {
            dispatched = true;
            result = "MODEL";
            return session.simple("DISPATCHED");
        }
        @Override
        public void result(String code) { result = code; }
        @Override
        public void usage(ChatModelClient.StreamEvent event) {
            if (event.text() != null && !event.text().isEmpty()) usageCandidate = false;
            if (event.promptTokens() != null && event.completionTokens() != null && event.text() == null
                    && event.promptTokens() >= 0 && event.completionTokens() >= 0) {
                prompt = event.promptTokens().longValue(); completion = event.completionTokens().longValue(); usageCandidate = true;
            }
        }
        @Override
        public void completedUsage() { confirmedUsage = usageCandidate; }

        private synchronized Mono<Void> close(String generation, long amount, String code) {
            if (terminal != null) return terminal;
            finalizing = true;
            boolean rejected = "REJECTED".equals(generation);
            String settlement = lease != null ? "PENDING" : rejected || !acquisitionIntended ? "NOT_REQUIRED" : "REVIEW";
            Long planned = "REVIEW".equals(settlement) ? null : amount;
            var usage = AiObservation.usage(dispatched, confirmedUsage, prompt, completion);
            var failure = new AtomicReference<Throwable>();
            terminal = session.finalized(generation, code, usage, planned, settlement)
                    .doOnSuccess(ignored -> { finalized = true; finalizationOutcome.tryEmitValue(true); })
                    .doOnError(error -> { failure.set(error); finalizationOutcome.tryEmitValue(false); })
                    .onErrorResume(error -> Mono.empty())
                    .then(Mono.defer(() -> {
                        if (lease == null) return Mono.empty();
                        Mono<Void> release = "COMPLETED".equals(generation)
                                ? lease.completeAsync(chargedPrompt, chargedCompletion)
                                : "CANCELLED".equals(generation) ? lease.cancelAsync() : lease.failAsync();
                        return release.doOnError(failure::set).onErrorResume(error -> Mono.empty())
                                .then(Mono.defer(() -> failure.get() == null ? session.settled(lease.leaseId(), amount)
                                        .onErrorResume(error -> session.simple("REVIEW").onErrorResume(ignored -> Mono.empty()).then(Mono.error(AiObservationClient.unavailable())))
                                        : session.simple("REVIEW").onErrorResume(error -> Mono.empty())));
                    })).then(Mono.defer(() -> failure.get() == null ? Mono.<Void>empty() : Mono.<Void>error(AiObservationClient.unavailable())))
                    .cache();
            return terminal;
        }
    }

    private static ServerSentEvent<Object> sse(ChatEvent event) {
        return ServerSentEvent.builder().event(event.type()).data((Object) event).build();
    }
}
