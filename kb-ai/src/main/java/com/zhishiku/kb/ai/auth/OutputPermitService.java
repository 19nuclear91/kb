package com.zhishiku.kb.ai.auth;

import com.zhishiku.kb.common.security.AccountRevokedException;
import com.zhishiku.kb.common.security.AuthenticationCheck;
import com.zhishiku.kb.common.security.OutputStopReport;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.ContextView;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** 每次执行独立的短许可；定时器不经过正文、持久化或执行续期队列。 */
@Component
public class OutputPermitService {
    private static final Object CONTEXT = new Object();
    private static final long MAX_AGE = Duration.ofSeconds(10).toNanos();
    private static final long REFRESH = Duration.ofSeconds(3).toNanos();
    private static final long TIMEOUT = Duration.ofSeconds(2).toNanos();
    private final AuthenticationClient authentication;
    private final Scheduler timer;
    private final LongSupplier nanos;
    private final boolean ownsTimer;
    private OutputPermitMetrics metrics;
    private OutputStopReporter stopReporter;

    @Autowired
    public void setStopReporter(OutputStopReporter stopReporter) {
        this.stopReporter = stopReporter;
    }

    @Autowired
    public void setMetrics(OutputPermitMetrics metrics) {
        this.metrics = metrics;
    }

    public record Binding(long userId, long authEpoch, String executionId) {
        public Binding {
            if (userId < 0 || authEpoch < 0 || executionId == null || executionId.isBlank()) {
                throw new IllegalArgumentException("执行绑定无效");
            }
        }

        @Override
        public String toString() {
            return "OutputBinding[redacted]";
        }
    }

    @Autowired
    public OutputPermitService(AuthenticationClient authentication) {
        this(authentication, Schedulers.newSingle("kb-output-permit", true), System::nanoTime, true);
    }

    public OutputPermitService(AuthenticationClient authentication, Scheduler timer, LongSupplier nanos) {
        this(authentication, timer, nanos, false);
    }

    private OutputPermitService(AuthenticationClient authentication, Scheduler timer, LongSupplier nanos, boolean ownsTimer) {
        this.authentication = authentication;
        this.timer = timer;
        this.nanos = nanos;
        this.ownsTimer = ownsTimer;
    }

    /** 多层流水线复用同一请求许可，最终写出层再次检查，不能借慢结算延长输出。 */
    public static <T> Flux<T> protect(OutputPermitService service, String token, Binding expected, Supplier<Flux<T>> source) {
        return Flux.deferContextual(context -> {
            Permit inherited = context.getOrDefault(CONTEXT, null);
            if (inherited != null) {
                inherited.requireBinding(token, expected);
                return inherited.guard(Flux.defer(source));
            }
            if (service == null) {
                return Flux.error(new OutputRevokedException("AUTHENTICATION_UNAVAILABLE"));
            }
            return service.acquire(token, expected).flatMapMany(permit -> permit.guard(Flux.defer(source))
                    .contextWrite(values -> values.put(CONTEXT, permit)).doFinally(signal -> permit.dispose()));
        });
    }

    public Mono<Permit> acquire(String token, Binding expected) {
        return Mono.<Permit>defer(() -> {
            if (token == null || token.isBlank()) {
                return Mono.error(new OutputRevokedException("ACCOUNT_REVOKED"));
            }
            long started = nanos.getAsLong();
            Binding requested = expected == null ? new Binding(0, 0, UUID.randomUUID().toString()) : expected;
            return authentication.check(token).timeout(Duration.ofNanos(TIMEOUT), timer)
                    .switchIfEmpty(Mono.error(new OutputRevokedException("AUTHENTICATION_UNAVAILABLE")))
                    .map(current -> {
                        if (nanos.getAsLong() - started >= TIMEOUT) {
                            throw new OutputRevokedException("AUTHENTICATION_UNAVAILABLE");
                        }
                        validate(current, requested);
                        var permit = new Permit(token, new Binding(current.userId(), current.authEpoch(), requested.executionId()), started,
                                new Anchor(current.checkedAt(), started, nanos.getAsLong()));
                        try {
                            permit.start();
                        } catch (RuntimeException unavailable) {
                            permit.dispose();
                            throw unavailable;
                        }
                        return permit;
                    });
        }).onErrorMap(OutputPermitService::failure).doOnError(error -> {
            if (metrics != null) {
                metrics.acquisitionFailed(((OutputRevokedException) error).code());
            }
        }).doOnDiscard(Permit.class, Permit::dispose);
    }

    public static void requireCurrent(ContextView context) {
        Permit permit = context.getOrDefault(CONTEXT, null);
        if (permit == null) {
            throw new OutputRevokedException("AUTHENTICATION_UNAVAILABLE");
        }
        permit.require();
    }

    /** 清理阶段同样复用原许可确认撤销；输出尚未建立时不虚构停止记录。 */
    public static void revokeCurrent(ContextView context, AccountRevokedException denied) {
        Permit permit = context.getOrDefault(CONTEXT, null);
        if (permit != null) {
            permit.revoke(failure(denied));
        }
    }

    private static void validate(AuthenticationCheck current, Binding expected) {
        if (current.mustChangePassword() || (expected.userId() > 0 && current.userId() != expected.userId())
                || (expected.authEpoch() > 0 && current.authEpoch() != expected.authEpoch())) {
            throw new OutputRevokedException("ACCOUNT_REVOKED");
        }
    }

    private static OutputRevokedException failure(Throwable error) {
        if (error instanceof OutputRevokedException revoked) {
            return revoked;
        }
        return new OutputRevokedException(error instanceof AccountRevokedException
                ? "ACCOUNT_REVOKED" : "AUTHENTICATION_UNAVAILABLE");
    }

    @PreDestroy
    public void close() {
        if (ownsTimer) {
            timer.dispose();
        }
    }

    public final class Permit implements Disposable {
        private final String token;
        private final Binding binding;
        private final Sinks.One<OutputRevokedException> stopped = Sinks.one();
        private final Disposable.Swap request = Disposables.swap();
        private final Disposable.Swap ticks = Disposables.swap();
        private long deadline;
        private long lastRequest;
        private boolean refreshing;
        private boolean disposed;
        private OutputRevokedException rejection;
        private final OutputPermitMetrics.Handle observation;
        private final UUID evidenceId = UUID.randomUUID();
        private Anchor anchor;

        private Permit(String token, Binding binding, long started, Anchor anchor) {
            this.token = token;
            this.binding = binding;
            deadline = started + MAX_AGE;
            lastRequest = started;
            this.anchor = anchor;
            observation = metrics == null ? null : metrics.begin(started, deadline);
        }

        private void start() {
            ticks.update(timer.schedulePeriodically(this::tick, 100, 100, TimeUnit.MILLISECONDS));
        }

        public Binding binding() {
            return binding;
        }

        private void requireBinding(String value, Binding expected) {
            if (!Objects.equals(token, value) || (expected != null && !binding.equals(expected))) {
                throw new OutputRevokedException("ACCOUNT_REVOKED");
            }
            require();
        }

        public void require() {
            OutputRevokedException error;
            synchronized (this) {
                if (!disposed && nanos.getAsLong() - deadline < 0) {
                    return;
                }
                error = rejection == null ? new OutputRevokedException("OUTPUT_PERMIT_EXPIRED") : rejection;
            }
            revoke(error);
            throw error;
        }

        public <T> Flux<T> guard(Flux<T> source) {
            return Flux.defer(() -> {
                require();
                return source.<T>handle((value, sink) -> {
                    require();
                    sink.next(value);
                }).onErrorMap(AccountRevokedException.class, denied -> {
                    // 执行入口可能先于许可刷新发现撤销；同一许可负责取消及一次停止观测。
                    var revoked = failure(denied);
                    revoke(revoked);
                    return revoked;
                }).takeUntilOther(stopped.asMono()).concatWith(Flux.defer(() -> {
                    synchronized (this) {
                        return rejection == null ? Flux.empty() : Flux.error(rejection);
                    }
                }));
            });
        }

        private void tick() {
            long started;
            synchronized (this) {
                if (disposed) {
                    return;
                }
                started = nanos.getAsLong();
                if (started - deadline >= 0) {
                    started = Long.MIN_VALUE;
                } else if (refreshing || started - lastRequest < REFRESH) {
                    return;
                } else {
                    refreshing = true;
                    lastRequest = started;
                }
            }
            if (started == Long.MIN_VALUE) {
                revoke(new OutputRevokedException("OUTPUT_PERMIT_EXPIRED"));
                return;
            }
            long requestedAt = started;
            try {
                request.update(authentication.check(token).timeout(Duration.ofNanos(TIMEOUT), timer)
                        .switchIfEmpty(Mono.error(new OutputRevokedException("AUTHENTICATION_UNAVAILABLE")))
                        .subscribe(current -> refreshed(current, requestedAt), error -> revoke(failure(error))));
            } catch (RuntimeException error) {
                revoke(failure(error));
            }
        }

        private void refreshed(AuthenticationCheck current, long started) {
            OutputRevokedException error = null;
            synchronized (this) {
                if (disposed) {
                    return;
                }
                long now = nanos.getAsLong();
                if (!refreshing || started != lastRequest || now - deadline >= 0) {
                    error = new OutputRevokedException("OUTPUT_PERMIT_EXPIRED");
                } else if (now - started >= TIMEOUT) {
                    error = new OutputRevokedException("AUTHENTICATION_UNAVAILABLE");
                } else {
                    try {
                        validate(current, binding);
                        deadline = started + MAX_AGE;
                        anchor = new Anchor(current.checkedAt(), started, now);
                        if (observation != null) {
                            observation.refreshed(deadline);
                        }
                        refreshing = false;
                    } catch (OutputRevokedException denied) {
                        error = denied;
                    }
                }
            }
            if (error != null) {
                revoke(error);
            }
        }

        private void revoke(OutputRevokedException error) {
            synchronized (this) {
                if (disposed) {
                    return;
                }
                rejection = error;
                disposed = true;
            }
            ticks.dispose();
            request.dispose();
            stopped.tryEmitValue(error);
            // 只有取消信号已经发出后才取停止时刻并提交观测，不等待网络或持久化。
            if (stopReporter != null) {
                try {
                    long stoppedAt = nanos.getAsLong();
                    stopReporter.submit(new OutputStopReport(evidenceId, binding.userId(), binding.authEpoch(), anchor.checkedAt(),
                            stoppedAt - anchor.responded(), stoppedAt - anchor.requested(), error.code()));
                } catch (RuntimeException missing) {
                    stopReporter.missingAnchor();
                }
            }
            if (observation != null) {
                observation.stopped(error.code());
            }
        }

        @Override
        public synchronized boolean isDisposed() {
            return disposed;
        }

        @Override
        public void dispose() {
            synchronized (this) {
                disposed = true;
            }
            ticks.dispose();
            request.dispose();
            if (observation != null) {
                observation.closed();
            }
        }

        @Override
        public String toString() {
            return "OutputPermit[redacted]";
        }
    }

    private record Anchor(Instant checkedAt, long requested, long responded) {
    }
}
