package com.zhishiku.kb.app.search.execution;

import com.zhishiku.kb.app.search.config.SearchExecutionProperties;
import com.zhishiku.kb.app.search.model.SearchFailure;
import com.zhishiku.kb.app.telemetry.SearchContextPropagation;
import io.micrometer.context.ContextSnapshot;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** 响应截止与真实工作退出分别记账的有界搜索执行器。 */
@Component
public class SearchExecutor implements AutoCloseable {
    private final SearchExecutionProperties properties;
    private final SearchAdmission admission;
    private final LongSupplier clock;
    private final ExecutorService workers;
    private final ExecutorService external;
    private final ExecutorService cancellations;
    private final ScheduledExecutorService timer;
    private final Set<Execution> active = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private SearchMetrics metrics = new SearchMetrics(null);
    private SearchContextPropagation contexts = SearchContextPropagation.REQUEST_ID_ONLY;

    @Autowired
    public void setContextPropagation(SearchContextPropagation contexts) {
        this.contexts = contexts;
    }

    @Autowired
    public SearchExecutor(SearchExecutionProperties properties, SearchAdmission admission, SearchMetrics metrics) {
        this(properties, admission);
        this.metrics = metrics;
        metrics.bind(admission, this);
    }

    public SearchExecutor(SearchExecutionProperties properties, SearchAdmission admission) {
        this(properties, admission, System::nanoTime, pool(properties.maxConcurrent(), "search"),
                pool(properties.maxConcurrent(), "search-external"), timer(),
                new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(64), threads("search-cancel"), new ThreadPoolExecutor.AbortPolicy()));
    }

    public SearchExecutor(SearchExecutionProperties properties, SearchAdmission admission, LongSupplier clock,
                          ExecutorService workers, ExecutorService external, ScheduledExecutorService timer,
                          ExecutorService cancellations) {
        this.properties = properties;
        this.admission = admission;
        this.clock = clock;
        this.workers = workers;
        this.external = external;
        this.timer = timer;
        this.cancellations = cancellations;
    }

    private static ExecutorService pool(int size, String name) {
        return new ThreadPoolExecutor(size, size, 0, TimeUnit.SECONDS, new SynchronousQueue<>(),
                threads(name), new ThreadPoolExecutor.AbortPolicy());
    }

    private static ScheduledExecutorService timer() {
        var timer = new ScheduledThreadPoolExecutor(1, threads("search-deadline"));
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }

    private static ThreadFactory threads(String prefix) {
        var sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public <T> T execute(long userId, Function<Execution, T> action) {
        return execute(userId, SearchMetrics.Operation.SEARCH, action);
    }

    public <T> T execute(long userId, SearchMetrics.Operation operation, Function<Execution, T> action) {
        Objects.requireNonNull(operation);
        SearchMetrics.Outcome outcome = SearchMetrics.Outcome.FAILED;
        var current = new AtomicReference<Execution>();
        try {
            T result = executeWork(userId, operation, execution -> {
                current.set(execution);
                return action.apply(execution);
            });
            outcome = current.get().empty ? SearchMetrics.Outcome.EMPTY : SearchMetrics.Outcome.SUCCESS;
            return result;
        } catch (SearchFailure failure) {
            metrics.rejected(operation, failure.kind());
            outcome = switch (failure.kind()) {
                case USER_LIMIT, CAPACITY -> SearchMetrics.Outcome.REJECTED;
                case CANCELLED -> SearchMetrics.Outcome.CANCELLED;
                default -> SearchMetrics.Outcome.FAILED;
            };
            throw failure;
        } finally {
            metrics.request(operation, outcome);
        }
    }

    private <T> T executeWork(long userId, SearchMetrics.Operation operation, Function<Execution, T> action) {
        var budget = new SearchBudget(properties, clock);
        if (closed.get()) {
            throw capacity();
        }
        var execution = new Execution(budget, admission.acquire(userId), operation);
        active.add(execution);
        Work<T> work = new Work<>(execution, budget.work(), () -> action.apply(execution), contexts.capture());
        try {
            if (closed.get()) {
                throw new RejectedExecutionException();
            }
            workers.execute(work);
            T result = await(work.result, budget.total());
            budget.total().check();
            return result;
        } catch (RejectedExecutionException rejected) {
            execution.cancel(capacity());
            throw capacity();
        } catch (SearchFailure failure) {
            execution.cancel(failure);
            throw failure;
        } finally {
            execution.responded.set(true);
        }
    }

    /** 在当前主线程执行必要阶段，独立阶段超时会结束整个搜索。 */
    public <T> T required(Execution execution, SearchBudget.Stage stage, Supplier<T> action) {
        return measured(execution.operation, stage, () -> requiredWork(execution, stage, action));
    }

    private <T> T requiredWork(Execution execution, SearchBudget.Stage stage, Supplier<T> action) {
        var context = SearchContext.current();
        if (context == null || context.work.execution != execution) {
            throw new IllegalStateException("搜索阶段缺少执行上下文");
        }
        var window = execution.budget.begin(stage);
        window.check();
        try (var deadline = scheduleDeadline(() -> execution.cancel(timeout()), window.remainingNanos());
             var scope = SearchContext.install(context.work, window)) {
            context.work.check();
            T result = action.get();
            context.work.check();
            window.check();
            return result;
        }
    }

    private SearchContext.Scope scheduleDeadline(Runnable action, long nanos) {
        var open = new AtomicBoolean(true);
        var deadline = timer.schedule(() -> {
            if (open.compareAndSet(true, false)) {
                action.run();
            }
        }, Math.max(0, nanos), TimeUnit.NANOSECONDS);
        return () -> {
            // cancel(false) 无法撤回已取出的回调，先关闭阶段以屏蔽迟到信号。
            open.set(false);
            deadline.cancel(false);
        };
    }

    /** 可选阶段在独立有界线程运行；超时后其真实工作仍登记在原请求。 */
    public <T> T optional(Execution execution, SearchBudget.Stage stage, Supplier<T> action) {
        return measured(execution.operation, stage, () -> optionalWork(execution, stage, action));
    }

    private <T> T optionalWork(Execution execution, SearchBudget.Stage stage, Supplier<T> action) {
        execution.check();
        var window = execution.budget.begin(stage);
        if (window.remainingNanos() <= 0) {
            throw new SearchStageFailure(SearchStageFailure.Reason.TIME_BUDGET);
        }
        var work = new Work<>(execution, window, action, contexts.capture());
        try {
            external.execute(work);
            T value = await(work.result, window);
            execution.check();
            window.check();
            return value;
        } catch (RejectedExecutionException rejected) {
            work.cancel(capacity());
            execution.check();
            throw new SearchStageFailure(SearchStageFailure.Reason.CAPACITY);
        } catch (SearchFailure failure) {
            work.cancel(failure);
            execution.check();
            if (failure.kind() == SearchFailure.Kind.TIMEOUT) {
                throw new SearchStageFailure(SearchStageFailure.Reason.TIMEOUT);
            }
            throw failure;
        }
    }

    private static <T> T await(CompletableFuture<T> value, SearchBudget.Window window) {
        try {
            window.check();
            return value.get(window.remainingNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException expired) {
            throw timeout();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SearchFailure(SearchFailure.Kind.CANCELLED);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failed.getCause() instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("搜索工作失败");
        }
    }

    private <T> T measured(SearchMetrics.Operation operation, SearchBudget.Stage stage, Supplier<T> action) {
        long started = clock.getAsLong();
        Throwable failure = null;
        try {
            return action.get();
        } catch (RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            metrics.stage(operation, stage, failure, clock.getAsLong() - started);
        }
    }

    public void degraded(SearchBudget.Stage stage, SearchStageFailure.Reason reason) {
        metrics.degradation(stage, reason);
    }

    public int lingering() {
        return (int) active.stream().filter(item -> item.responded.get()).count();
    }

    private SearchFailure capacity() {
        return new SearchFailure(SearchFailure.Kind.CAPACITY, properties.retryAfterSeconds());
    }

    private static SearchFailure timeout() {
        return new SearchFailure(SearchFailure.Kind.TIMEOUT);
    }

    @Override
    @PreDestroy
    public void close() {
        if (closed.compareAndSet(false, true)) {
            active.forEach(item -> item.cancel(new SearchFailure(SearchFailure.Kind.CANCELLED)));
            workers.shutdownNow();
            external.shutdownNow();
            timer.shutdownNow();
            cancellations.shutdown();
        }
    }

    /** 一次准入的真实工作集合；不持有权限快照。 */
    public final class Execution {
        private final SearchBudget budget;
        private final SearchAdmission.Ticket ticket;
        private final SearchMetrics.Operation operation;
        private final Set<Work<?>> jobs = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean responded = new AtomicBoolean();
        private final CompletableFuture<Void> exited = new CompletableFuture<>();
        private volatile SearchFailure failure;
        private boolean empty;
        private int references;
        private boolean released;

        private Execution(SearchBudget budget, SearchAdmission.Ticket ticket, SearchMetrics.Operation operation) {
            this.budget = budget;
            this.ticket = ticket;
            this.operation = operation;
        }

        public SearchBudget budget() {
            return budget;
        }

        public CompletableFuture<Void> exited() {
            return exited;
        }

        public void resultCount(int count) {
            empty = count == 0;
        }

        public void check() {
            if (failure != null) {
                throw failure;
            }
            budget.total().check();
        }

        private synchronized boolean retain() {
            if (released) {
                return false;
            }
            references++;
            return true;
        }

        private synchronized void exit() {
            if (--references == 0) {
                released = true;
                ticket.close();
                active.remove(this);
                exited.complete(null);
            }
        }

        public void cancel(SearchFailure cause) {
            synchronized (this) {
                if (failure == null) {
                    failure = cause;
                }
            }
            jobs.forEach(job -> job.cancel(failure));
        }

        private void dispatchCancellation(Runnable action) {
            if (!retain()) {
                return;
            }
            try {
                cancellations.execute(() -> {
                    try {
                        action.run();
                    } finally {
                        exit();
                    }
                });
            } catch (RejectedExecutionException unavailable) {
                exit();
            }
        }

        private SearchContext.Scope limit(Work<?> work, long nanos) {
            return scheduleDeadline(() -> work.cancel(timeout()), nanos);
        }
    }

    /** 自定义运行状态，取消 Future 不等于 finally 已执行。 */
    static final class Work<T> implements Runnable {
        private final Execution execution;
        private final SearchBudget.Window window;
        private final Supplier<T> action;
        private final ContextSnapshot snapshot;
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private final Set<Registration> registrations = ConcurrentHashMap.newKeySet();
        private int state;
        private Thread thread;
        private volatile SearchFailure failure;

        private Work(Execution execution, SearchBudget.Window window, Supplier<T> action, ContextSnapshot snapshot) {
            this.execution = execution;
            this.window = window;
            this.action = action;
            this.snapshot = snapshot;
            if (!execution.retain()) {
                throw new IllegalStateException("搜索已退出");
            }
            execution.jobs.add(this);
            if (execution.failure != null) {
                cancel(execution.failure);
            }
        }

        @Override
        public void run() {
            synchronized (this) {
                if (state != 0) {
                    return;
                }
                state = 1;
                thread = Thread.currentThread();
            }
            try (var telemetry = snapshot.setThreadLocals(); var scope = SearchContext.install(this, window)) {
                check();
                window.check();
                T value = action.get();
                check();
                result.complete(value);
            } catch (Throwable failed) {
                result.completeExceptionally(failed);
            } finally {
                registrations.forEach(Registration::close);
                synchronized (this) {
                    state = 2;
                    thread = null;
                }
                execution.jobs.remove(this);
                execution.exit();
            }
        }

        void check() {
            if (failure != null) {
                throw failure;
            }
            execution.check();
        }

        void cancel(SearchFailure cause) {
            boolean unstarted;
            synchronized (this) {
                if (state == 2) {
                    return;
                }
                failure = cause;
                unstarted = state == 0;
                if (unstarted) {
                    state = 2;
                } else {
                    thread.interrupt();
                }
                result.completeExceptionally(cause);
            }
            registrations.forEach(Registration::cancel);
            if (unstarted) {
                execution.jobs.remove(this);
                execution.exit();
            }
        }

        SearchContext.Scope onCancel(Runnable action) {
            var registration = new Registration(action);
            registrations.add(registration);
            if (failure != null || execution.failure != null) {
                registration.cancel();
            }
            return registration;
        }

        SearchContext.Scope limitTo(long nanos) {
            return execution.limit(this, nanos);
        }

        private final class Registration implements SearchContext.Scope {
            private final Runnable action;
            private final AtomicBoolean open = new AtomicBoolean(true);
            private final AtomicBoolean dispatched = new AtomicBoolean();

            private Registration(Runnable action) {
                this.action = action;
            }

            private void cancel() {
                if (open.get() && dispatched.compareAndSet(false, true)) {
                    execution.dispatchCancellation(() -> {
                        if (open.compareAndSet(true, false)) {
                            action.run();
                        }
                    });
                }
            }

            @Override
            public void close() {
                open.set(false);
                registrations.remove(this);
            }
        }
    }
}
