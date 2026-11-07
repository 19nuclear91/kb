package com.zhishiku.kb.worker.embedding.execution;

import com.zhishiku.kb.common.embedding.CallBudget;

import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** 一次生成的逻辑状态；实际退出与容量由执行协调器管理。 */
public final class WorkerExecution implements CallBudget {
    private final Purpose purpose;
    private final int maxResponseBytes;
    private final LongSupplier clock;
    private final Scheduler scheduler;
    private final Consumer<Runnable> cancellations;
    private final Consumer<WorkerEmbeddingFailure.Reason> stopObserver;
    private final Map<CancelAction, Boolean> actions = new IdentityHashMap<>();
    private Phase phase = Phase.NEW;
    private WorkerEmbeddingFailure.Reason reason;
    private long started;
    private long duration;
    private Registration parentTimer;
    private Child child;
    private int retainedResources;

    public WorkerExecution(Purpose purpose, int maxResponseBytes, LongSupplier clock,
                           Scheduler scheduler, Consumer<Runnable> cancellations) {
        this(purpose, maxResponseBytes, clock, scheduler, cancellations, reason -> { });
    }

    WorkerExecution(Purpose purpose, int maxResponseBytes, LongSupplier clock, Scheduler scheduler,
                    Consumer<Runnable> cancellations, Consumer<WorkerEmbeddingFailure.Reason> stopObserver) {
        this.purpose = Objects.requireNonNull(purpose, "purpose");
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("invalid response limit");
        }
        this.maxResponseBytes = maxResponseBytes;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.cancellations = Objects.requireNonNull(cancellations, "cancellations");
        this.stopObserver = Objects.requireNonNull(stopObserver, "stopObserver");
    }

    public synchronized void begin(Duration budget) {
        if (phase == Phase.STOPPED) {
            throw new WorkerEmbeddingFailure(reason);
        }
        if (phase != Phase.NEW || budget.isNegative() || budget.isZero()) {
            throw new IllegalStateException("invalid execution budget start");
        }
        duration = budget.toNanos();
        started = clock.getAsLong();
        phase = Phase.RUNNING;
        parentTimer = scheduler.schedule(() -> expire(null), duration);
    }

    public Purpose purpose() {
        return purpose;
    }

    @Override
    public synchronized long remainingNanos() {
        if (phase != Phase.RUNNING) {
            return 0;
        }
        long now = clock.getAsLong();
        long remaining = duration - (now - started);
        if (child != null) {
            remaining = Math.min(remaining, child.duration - (now - child.started));
        }
        return Math.max(0, remaining);
    }

    @Override
    public int maxResponseBytes() {
        return maxResponseBytes;
    }

    @Override
    public void check() {
        boolean expired;
        synchronized (this) {
            requireRunning();
            expired = remainingNanos() == 0;
        }
        if (expired) {
            stop(WorkerEmbeddingFailure.Reason.TIMEOUT);
        }
        synchronized (this) {
            requireRunning();
        }
    }

    /** 在同一状态锁内核对截止并选定成功，随后旧计时器失效。 */
    public void succeed() {
        synchronized (this) {
            requireRunning();
            if (remainingNanos() > 0) {
                phase = Phase.SUCCEEDED;
                cancelTimers();
                return;
            }
        }
        stop(WorkerEmbeddingFailure.Reason.TIMEOUT);
        throw new WorkerEmbeddingFailure(reason());
    }

    public boolean stop(WorkerEmbeddingFailure.Reason failure) {
        ArrayList<CancelAction> pending;
        synchronized (this) {
            if (phase == Phase.STOPPED || phase == Phase.SUCCEEDED) {
                return false;
            }
            pending = selectStop(failure);
        }
        dispatchStop(pending);
        return true;
    }

    public synchronized boolean stopped() {
        return phase == Phase.STOPPED;
    }

    public synchronized boolean succeeded() {
        return phase == Phase.SUCCEEDED;
    }

    public synchronized WorkerEmbeddingFailure.Reason reason() {
        return reason;
    }

    synchronized boolean resourcesExited() {
        return actions.isEmpty() && retainedResources == 0;
    }

    /** 只有资源所有者确认实际退出后才能归还；关闭失败不能伪造退出。 */
    public synchronized Registration retainResource() {
        retainedResources++;
        var released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                synchronized (WorkerExecution.this) {
                    retainedResources--;
                }
            }
        };
    }

    /** 只允许取消当前生成阶段；迟到回调不能中断后续数据库收尾。 */
    public Registration interruptCurrentThread() {
        Thread thread = Thread.currentThread();
        Object gate = new Object();
        boolean[] active = {true};
        Registration registration = onCancel(() -> {
            synchronized (gate) {
                if (active[0]) {
                    thread.interrupt();
                }
            }
        });
        return () -> {
            synchronized (gate) {
                active[0] = false;
            }
            registration.close();
            Thread.interrupted();
        };
    }

    @Override
    public Registration onCancel(Runnable action) {
        var registered = new CancelAction(Objects.requireNonNull(action, "action"));
        boolean stopped;
        synchronized (this) {
            if (phase == Phase.SUCCEEDED) {
                throw new IllegalStateException("embedding generation already finished");
            }
            if (actions.size() >= 16) {
                throw new IllegalStateException("too many embedding cancellation resources");
            }
            actions.put(registered, Boolean.TRUE);
            stopped = phase == Phase.STOPPED;
        }
        if (stopped) {
            registered.dispatch();
        }
        return registered::close;
    }

    @Override
    public Registration limitTo(long nanos) {
        check();
        final Child limit;
        synchronized (this) {
            requireRunning();
            if (nanos <= 0 || child != null) {
                throw new IllegalArgumentException("invalid nested embedding deadline");
            }
            limit = new Child(clock.getAsLong(), Math.min(nanos, remainingNanos()));
            child = limit;
            limit.timer = scheduler.schedule(() -> expire(limit), limit.duration);
        }
        return () -> {
            synchronized (WorkerExecution.this) {
                if (child == limit) {
                    child = null;
                    limit.timer.close();
                }
            }
        };
    }

    private void expire(Child expected) {
        ArrayList<CancelAction> pending;
        synchronized (this) {
            if (phase != Phase.RUNNING || (expected != null && child != expected)) {
                return;
            }
            if (remainingNanos() > 0) {
                return;
            }
            // 锁内选定结果，锁外派发，避免与通道占用锁形成反向锁序。
            pending = selectStop(WorkerEmbeddingFailure.Reason.TIMEOUT);
        }
        dispatchStop(pending);
    }

    private void dispatchStop(ArrayList<CancelAction> pending) {
        try {
            if (duration > 0) {
                // 短小的内存观测独立于可能阻塞的取消队列；空领取不计为生成故障。
                stopObserver.accept(reason);
            }
        } finally {
            pending.forEach(CancelAction::dispatch);
        }
    }

    private ArrayList<CancelAction> selectStop(WorkerEmbeddingFailure.Reason failure) {
        reason = Objects.requireNonNull(failure, "failure");
        phase = Phase.STOPPED;
        cancelTimers();
        return new ArrayList<>(actions.keySet());
    }

    private void requireRunning() {
        if (phase == Phase.STOPPED) {
            throw new WorkerEmbeddingFailure(reason);
        }
        if (phase != Phase.RUNNING) {
            throw new IllegalStateException("embedding budget is not running");
        }
    }

    private void cancelTimers() {
        if (parentTimer != null) {
            parentTimer.close();
        }
        if (child != null) {
            if (child.timer != null) {
                child.timer.close();
            }
            child = null;
        }
    }

    private enum Phase { NEW, RUNNING, SUCCEEDED, STOPPED }

    public enum Purpose { PARSE, REBUILD, PROBE }

    @FunctionalInterface
    public interface Scheduler {
        Registration schedule(Runnable action, long nanos);
    }

    private static final class Child {
        private final long started;
        private final long duration;
        private Registration timer;

        private Child(long started, long duration) {
            this.started = started;
            this.duration = duration;
        }
    }

    private final class CancelAction {
        private final Runnable action;
        private final AtomicInteger state = new AtomicInteger();

        private CancelAction(Runnable action) {
            this.action = action;
        }

        private void dispatch() {
            if (state.compareAndSet(0, 1)) {
                cancellations.accept(() -> {
                    try {
                        action.run();
                    } finally {
                        state.set(2);
                        synchronized (WorkerExecution.this) {
                            actions.remove(this);
                        }
                    }
                });
            }
        }

        private void close() {
            if (state.compareAndSet(0, 2)) {
                synchronized (WorkerExecution.this) {
                    actions.remove(this);
                }
            }
        }
    }
}
