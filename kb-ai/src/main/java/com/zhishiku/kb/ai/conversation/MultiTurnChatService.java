package com.zhishiku.kb.ai.conversation;

import com.zhishiku.kb.ai.chat.AiProperties;
import com.zhishiku.kb.ai.chat.ChatQuotaAuditService;
import com.zhishiku.kb.ai.chat.QuotaPrincipalRejectedException;
import com.zhishiku.kb.ai.chat.RetrievalClient;
import com.zhishiku.kb.ai.chat.RetrievalFailure;
import com.zhishiku.kb.ai.budget.BudgetCapability;
import com.zhishiku.kb.ai.conversation.config.ConversationProperties;
import com.zhishiku.kb.ai.telemetry.StreamTelemetry;
import com.zhishiku.kb.ai.auth.OutputPermitService;
import com.zhishiku.kb.ai.auth.OutputRevokedException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次订阅串行保存，断连取消模型；持久过期恢复只负责关闭与结算。
 */
@Service
@ConditionalOnProperty(prefix = "kb.conversation", name = "enabled", havingValue = "true")
public class MultiTurnChatService {
    public static final String NO_RESULTS = "未找到与问题相关的内容。";
    private final ConversationAppClient app;
    private final RetrievalClient retrieval;
    private final ChatQuotaAuditService quota;
    private final ConversationSettlementService settlement;
    private final ConversationProperties properties;
    private final AiProperties ai;
    private final ObjectProvider<MultiTurnModel> models;
    private com.zhishiku.kb.ai.observation.AiObservationClient observation;
    private StreamTelemetry telemetry = StreamTelemetry.NOOP;
    private OutputPermitService permits;

    @org.springframework.beans.factory.annotation.Autowired
    public void setOutputPermits(OutputPermitService permits) {
        this.permits = permits;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setTelemetry(StreamTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setObservation(com.zhishiku.kb.ai.observation.AiObservationClient observation) {
        this.observation = observation;
    }

    public MultiTurnChatService(ConversationAppClient app, RetrievalClient retrieval, ChatQuotaAuditService quota,
                                ConversationSettlementService settlement, ConversationProperties properties,
                                AiProperties ai, ObjectProvider<MultiTurnModel> models) {
        this.app = app;
        this.retrieval = retrieval;
        this.quota = quota;
        this.settlement = settlement;
        this.properties = properties;
        this.ai = ai;
        this.models = models;
    }

    /**
     * 请求范围内状态，无全局会话缓存。
     */
    public static final class Prepared {
        private BeginResult result;
        private MultiTurnModel model;
        private ChatQuotaAuditService.Lease lease;
        private final String question;
        private final String token;
        private final StringBuilder buffer = new StringBuilder();
        private final AtomicBoolean subscribed = new AtomicBoolean();
        private long position;
        private int total;
        private Pending pending;
        private boolean finalUsage;
        private Long prompt;
        private Long completion;
        private volatile boolean terminal;
        private volatile boolean outputStopped;
        private volatile boolean accountRevoked;
        private SettlementIntent intent;
        private Mono<Void> closing;

        private Prepared(String question, String token) {
            this.question = question;
            this.token = token;
        }

        public BeginResult result() {
            return result;
        }
    }

    private static final class Pending {
        private final long sequence;
        private final String text;

        private Pending(long sequence, String text) {
            this.sequence = sequence;
            this.text = text;
        }
    }

    public Mono<Prepared> prepare(long id, String question, String request, long expected, String token) {
        var state = new Prepared(question, token);
        return app.check(id, question, request, expected, token).flatMap(check -> {
                    if (check.replayed()) {
                        state.result = check;
                        return Mono.just(state);
                    }
                    state.model = models.getIfAvailable();
                    new ConversationBudget(properties).ready(state.model);
                    return app.begin(id, question, request, expected, token).flatMap(begin -> {
                        state.result = begin;
                        if (begin.replayed()) return Mono.just(state);
                        return quota.acquire(token, question.codePointCount(0, question.length())).flatMap(lease -> {
                            state.lease = lease;
                            if (lease.userId() != begin.execution().ownerUserId())
                                return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "当前主体已变化"));
                            return app.reserve(begin.execution(), lease.departmentId(), lease.leaseId(),
                                    lease.eventId(), lease.reservedTokens()).thenReturn(state);
                        });
                    });
                }).onErrorResume(error -> {
                    Mono<Void> rejected = error instanceof ChatQuotaAuditService.QuotaExceededException
                            && state.result != null && !state.result.replayed() && observation != null
                            ? observation.rejectedMulti(state.result.turnId())
                                    .onErrorResume(ignored -> Mono.empty()) : Mono.empty();
                    return rejected.then(cleanup(state, "FAILED")).then(Mono.error(preflightError(error)));
                })
                .doOnCancel(() -> cleanup(state, "CANCELLED").subscribe(ignored -> {
                }, ignored -> {
                }));
    }

    public Flux<ServerSentEvent<Object>> stream(Prepared state) {
        return Flux.defer(() -> {
            if (state.result.replayed() || !state.subscribed.compareAndSet(false, true)) {
                return Flux.error(new ResponseStatusException(HttpStatus.CONFLICT, "轮次不能重复执行"));
            }
            return telemetry.observe(() -> streamOnce(state));
        });
    }

    private Flux<ServerSentEvent<Object>> streamOnce(Prepared state) {
            var key = state.result.execution();
            var budget = new ConversationBudget(properties);
            Flux<ServerSentEvent<Object>> body = app.prepare(key.conversationId(), state.token)
                    .flatMap(history -> retrieval.retrieve(budget.query(history, state.question), state.token)
                            .switchIfEmpty(Mono.error(new RetrievalFailure(RetrievalFailure.Kind.PROTOCOL))).map(materials -> {
                        var versioned =
                                materials.stream().filter(c -> c.versionId() != null && c.versionId() > 0).toList();
                        if (!materials.isEmpty() && versioned.isEmpty()) {
                            throw new RetrievalFailure(RetrievalFailure.Kind.PROTOCOL);
                        }
                        return versioned.isEmpty() ? new ConversationBudget.Plan(null, List.of(), BudgetCapability.unavailable()) :
                                budget.select(state.model, state.question, history, versioned);
                    })).flatMapMany(plan -> {
                        boolean noResults = plan.input() == null || plan.input().materials().isEmpty();
                        List<RetrievalClient.Citation> materials = noResults ? List.of() : plan.input().materials();
                        return app.seal(key, noResults ? "NO_RESULTS" : "MODEL", noResults ? List.of() :
                                        plan.history(), materials, state.token)
                                .thenMany(Flux.fromIterable(materials).map(c -> event("citation",
                                        ConversationAppClient.citation(c))))
                                .concatWith(Flux.defer(() -> {
                                    Flux<MultiTurnModel.Event> source = noResults
                                            ? Flux.just(new MultiTurnModel.Event(NO_RESULTS, false, null, null))
                                            :
                                            app.dispatch(key).thenMany(Flux.deferContextual(context -> {
                                                OutputPermitService.requireCurrent(context);
                                                if (!plan.capability().equals(state.model.budgetCapability())) {
                                                    return Flux.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                                                            "多轮模型能力暂不可用"));
                                                }
                                                return state.model.generate(plan.input());
                                            }));
                                    return commands(state, source.timeout(Duration.ofSeconds(ai.upstreamTimeout())));
                                }));
                    });
            Flux<ServerSentEvent<Object>> keepAlive = Flux.interval(Duration.ofMillis(ai.keepAliveMs()))
                    .onBackpressureDrop().map(t -> ServerSentEvent.builder().comment("keep-alive").<Object>build());
            var binding = new OutputPermitService.Binding(key.ownerUserId(), key.authEpoch(), key.turnId().toString());
            return OutputPermitService.protect(permits, state.token, binding,
                            () -> Flux.merge(1, body, keepAlive).takeUntil(e -> "done".equals(e.event()) || "error".equals(e.event())))
                    .transform(StreamTelemetry::captureFailures)
                    .onErrorResume(error -> {
                        if (error instanceof OutputRevokedException stopped) {
                            state.outputStopped = true;
                            state.accountRevoked = "ACCOUNT_REVOKED".equals(stopped.code());
                        }
                        return cleanup(state, state.accountRevoked ? "CANCELLED" : "FAILED")
                                .thenMany(Flux.just(event("error", failure(state, error))));
                    })
                    .doFinally(signal -> {
                        if (signal == SignalType.CANCEL && !state.terminal)
                            cleanup(state, "CANCELLED").subscribe(ignored -> {
                            }, ignored -> {
                            });
                    });
    }

    private Flux<ServerSentEvent<Object>> commands(Prepared state, Flux<MultiTurnModel.Event> source) {
        Flux<Command> data =
                source.concatMapIterable(this::split, 1).map(Command::data).concatWithValues(Command.end());
        Flux<Command> batch =
                Flux.interval(Duration.ofMillis(properties.getBatchMs())).onBackpressureDrop().map(t -> Command.flush());
        Flux<Command> renew = Flux.interval(Duration.ofMillis(Math.min(properties.getRenewalMs(),
                ai.quotaRenewalIntervalMs()))).onBackpressureDrop().map(t -> Command.renew());
        return Flux.merge(1, data, batch, renew).takeUntil(command -> command.kind == 3).concatMap(command -> Flux.deferContextual(context -> {
            OutputPermitService.requireCurrent(context);
            if (command.kind == 1) return flush(state).thenMany(Flux.empty());
            if (command.kind == 2)
                return app.renew(state.result.execution()).then(state.lease.renewAsync()).thenMany(Flux.empty());
            if (command.kind == 3) {
                return flush(state).then(finish(state, "COMPLETED"))
                        .thenMany(Flux.defer(() -> Flux.just(event("done", done(state)))));
            }
            var incoming = command.data;
            String text = incoming.text() == null ? "" : incoming.text();
            synchronized (state) {
                if (!text.isEmpty()) {
                    state.finalUsage = false;
                    if (!unicode(text) || (long) state.total + text.codePointCount(0, text.length()) > properties.getMaxAnswerCodepoints())
                        return Flux.error(new IllegalStateException("回答超过保存上限"));
                    state.total += text.codePointCount(0, text.length());
                    state.buffer.append(text);
                }
                if (incoming.finalUsage()) {
                    if (incoming.promptTokens() == null || incoming.completionTokens() == null || incoming.promptTokens() < 0 || incoming.completionTokens() < 0)
                        return Flux.error(new IllegalStateException("累计用量无效"));
                    state.finalUsage = true;
                    state.prompt = incoming.promptTokens();
                    state.completion = incoming.completionTokens();
                }
            }
            Mono<Void> save =
                    state.buffer.codePointCount(0, state.buffer.length()) >= properties.getBatchCodepoints() ?
                            flush(state, false) : Mono.empty();
            return save.thenMany(text.isEmpty() ? Flux.empty() : Flux.just(event("delta", Map.of("text", text))));
        }), 1);
    }

    private Mono<Void> flush(Prepared state) {
        return flush(state, true);
    }

    private Mono<Void> flush(Prepared state, boolean all) {
        return Mono.defer(() -> {
            Pending pending;
            synchronized (state) {
                if (state.buffer.isEmpty()) return Mono.empty();
                if (!all && state.pending == null && state.buffer.codePointCount(0, state.buffer.length()) < properties.getBatchCodepoints())
                    return Mono.empty();
                if (state.pending == null) {
                    int count = Math.min(properties.getBatchCodepoints(), state.buffer.codePointCount(0,
                            state.buffer.length()));
                    state.pending = new Pending(state.position + 1, state.buffer.substring(0,
                            state.buffer.offsetByCodePoints(0, count)));
                }
                pending = state.pending;
            }
            return app.append(state.result.execution(), pending.sequence, pending.text).doOnNext(saved -> {
                if (saved < pending.sequence) throw new IllegalStateException("保存位置无效");
                synchronized (state) {
                    if (state.pending == pending) {
                        state.buffer.delete(0, pending.text.length());
                        state.position = pending.sequence;
                        state.pending = null;
                    }
                }
            }).then(Mono.defer(() -> flush(state, all)));
        });
    }

    private List<MultiTurnModel.Event> split(MultiTurnModel.Event event) {
        String text = event.text();
        if (text == null || text.isEmpty()) return List.of(event);
        if (!unicode(text) || text.codePointCount(0, text.length()) > properties.getMaxAnswerCodepoints())
            throw new IllegalStateException("回答超过保存上限");
        var chunks = new ArrayList<MultiTurnModel.Event>();
        int offset = 0;
        while (offset < text.length()) {
            int end = text.offsetByCodePoints(offset, Math.min(properties.getBatchCodepoints(),
                    text.codePointCount(offset, text.length())));
            boolean last = end == text.length();
            chunks.add(new MultiTurnModel.Event(text.substring(offset, end), last && event.finalUsage(), last ?
                    event.promptTokens() : null, last ? event.completionTokens() : null));
            offset = end;
        }
        return chunks;
    }

    private Mono<Void> finish(Prepared state, String status) {
        return Mono.defer(() -> state.accountRevoked
                        ? app.finishRevoked(state.result.execution(), state.position, state.finalUsage, state.prompt, state.completion)
                        : app.finish(state.result.execution(), status, state.position, state.finalUsage, state.prompt, state.completion))
                .doOnNext(intent -> {
                    state.intent = intent;
                    state.terminal = true;
                })
                .flatMap(intent -> settlement.settle(intent).then(Mono.defer(() -> app.acknowledge(intent))))
                .doOnSuccess(ignored -> state.terminal = true);
    }

    private Mono<Void> cleanup(Prepared state, String status) {
        synchronized (state) {
            if (state.result == null || state.result.replayed() || state.terminal) return Mono.empty();
            if (state.closing == null) {
                state.closing = (state.outputStopped ? Mono.<Void>empty() : flush(state).onErrorResume(error -> Mono.empty()))
                        .then(finish(state, status))
                        .timeout(Duration.ofMillis(properties.getCleanupTimeoutMs())).onErrorResume(error -> Mono.empty()).cache();
            }
            return state.closing;
        }
    }

    private Map<String, Object> done(Prepared state) {
        var result = new LinkedHashMap<String, Object>();
        result.put("conversationId", state.result.conversationId());
        result.put("turnId", state.result.turnId());
        result.put("status", "COMPLETED");
        result.put("lastBatchSeq", state.position);
        result.put("usageStatus", state.intent == null ? "NOT_INVOKED" : state.intent.usageStatus());
        result.put("promptTokens", state.intent == null ? Long.valueOf(0) : state.intent.promptTokens());
        result.put("completionTokens", state.intent == null ? Long.valueOf(0) : state.intent.completionTokens());
        result.put("chargedTokens", state.intent == null ? 0L : state.intent.chargedTokens());
        return result;
    }

    private static ServerSentEvent<Object> event(String name, Object value) {
        return ServerSentEvent.builder(value).event(name).build();
    }

    private static Map<String, Object> failure(Prepared state, Throwable error) {
        var data = new LinkedHashMap<String, Object>();
        data.put("conversationId", state.result.conversationId());
        data.put("turnId", state.result.turnId());
        data.put("message", error instanceof RetrievalFailure ? RetrievalFailure.MESSAGE : "生成或保存暂未完成，请查看历史状态。");
        if (error instanceof OutputRevokedException stopped) {
            data.put("code", stopped.code());
            data.put("message", stopped.getMessage());
        } else if (error instanceof RetrievalFailure) {
            data.put("code", RetrievalFailure.CODE);
        } else if (error instanceof InputBudgetExceededException) {
            data.put("code", InputBudgetExceededException.CODE);
            data.put("message", InputBudgetExceededException.MESSAGE);
        }
        return data;
    }

    private static ResponseStatusException preflightError(Throwable error) {
        if (error instanceof ResponseStatusException status) return status;
        if (error instanceof ChatQuotaAuditService.QuotaExceededException)
            return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "部门配额不足");
        if (error instanceof QuotaPrincipalRejectedException principal)
            return new ResponseStatusException(HttpStatus.FORBIDDEN, "当前身份不可使用");
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "多轮服务暂不可用");
    }

    public static boolean unicode(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 0) return false;
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }

    private static final class Command {
        private final int kind;
        private final MultiTurnModel.Event data;

        private Command(int kind, MultiTurnModel.Event data) {
            this.kind = kind;
            this.data = data;
        }

        private static Command data(MultiTurnModel.Event data) {
            return new Command(0, data);
        }

        private static Command flush() {
            return new Command(1, null);
        }

        private static Command renew() {
            return new Command(2, null);
        }

        private static Command end() {
            return new Command(3, null);
        }
    }
}
