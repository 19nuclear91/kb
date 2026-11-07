package com.zhishiku.kb.worker.embedding.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/** 领取前准入；工作与取消资源均退出后才释放通道。 */
public final class WorkerExecutions implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(WorkerExecutions.class);
    private final WorkerEmbeddingSettings settings;
    private final ScheduledThreadPoolExecutor deadlines;
    private final LongSupplier clock;
    private final WorkerExecution.Scheduler scheduler;
    private final Map<Channel, Lane> lanes = new EnumMap<>(Channel.class);
    private volatile boolean closed;
    private volatile BiConsumer<WorkerExecution.Purpose, WorkerEmbeddingFailure.Reason> stopObserver = (purpose, reason) -> { };

    public WorkerExecutions(WorkerEmbeddingSettings settings) {
        this(settings, System::nanoTime, null);
    }

    WorkerExecutions(WorkerEmbeddingSettings settings, LongSupplier clock, WorkerExecution.Scheduler scheduler) {
        this.settings = settings;
        this.clock = Objects.requireNonNull(clock, "clock");
        deadlines = new ScheduledThreadPoolExecutor(1, threads("embedding-deadline"));
        deadlines.setRemoveOnCancelPolicy(true);
        deadlines.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.scheduler = scheduler == null ? (action, nanos) -> {
            var timer = deadlines.schedule(action, nanos, TimeUnit.NANOSECONDS);
            return () -> timer.cancel(false);
        } : scheduler;
        for (Channel channel : Channel.values()) {
            lanes.put(channel, new Lane(channel));
        }
    }

    /** 返回 null 表示未准入；callback 内才可以开始数据库领取。 */
    public Ticket submit(WorkerExecution.Purpose purpose, Consumer<WorkerExecution> callback) {
        Lane lane = lanes.get(channel(purpose));
        synchronized (lane) {
            if (closed || lane.current != null) {
                return null;
            }
            Run run = new Run(lane, purpose);
            lane.current = run;
            try {
                lane.work.execute(() -> execute(run, callback));
            } catch (RejectedExecutionException unavailable) {
                lane.current = null;
                run.exited.complete(null);
                return null;
            }
            return new Ticket(run.execution, run.exited);
        }
    }

    public int occupied(Channel channel) {
        Lane lane = lanes.get(channel);
        synchronized (lane) {
            return lane.current == null ? 0 : 1;
        }
    }

    public int lingering(Channel channel) {
        Lane lane = lanes.get(channel);
        synchronized (lane) {
            return lane.current != null && (lane.current.execution.stopped()
                    || lane.current.execution.succeeded()) ? 1 : 0;
        }
    }

    /** 装配时绑定，只允许常数时间的内存观测；不得执行 I/O 或取消资源。 */
    void observeStops(BiConsumer<WorkerExecution.Purpose, WorkerEmbeddingFailure.Reason> observer) {
        stopObserver = Objects.requireNonNull(observer, "observer");
    }

    private void recordStop(WorkerExecution.Purpose purpose, WorkerEmbeddingFailure.Reason reason) {
        try {
            stopObserver.accept(purpose, reason);
        } catch (RuntimeException failure) {
            log.warn("Worker embedding stop observation unavailable purpose={}", purpose);
        }
    }

    private void execute(Run run, Consumer<WorkerExecution> callback) {
        try {
            if (!run.execution.stopped()) {
                callback.accept(run.execution);
            }
        } catch (WorkerEmbeddingFailure failure) {
            run.execution.stop(failure.reason());
            log.warn("Worker embedding execution stopped purpose={} reason={}",
                    run.execution.purpose(), failure.reason());
        } catch (RuntimeException failure) {
            log.warn("Worker embedding execution unavailable purpose={}", run.execution.purpose());
        } finally {
            run.execution.stop(WorkerEmbeddingFailure.Reason.SHUTDOWN);
            synchronized (run.lane) {
                run.workExited = true;
                releaseIfExited(run);
            }
            // 线程复用之前清除本次中断，不影响下一执行。
            Thread.interrupted();
        }
    }

    private void releaseIfExited(Run run) {
        if (run.lane.current == run && run.workExited && run.pendingCancellations == 0 && !run.draining
                && run.execution.resourcesExited()) {
            run.lane.current = null;
            if (closed) {
                run.lane.cancel.shutdown();
            }
            run.exited.complete(null);
        }
    }

    @Override
    public void close() {
        closed = true;
        for (Lane lane : lanes.values()) {
            Run run;
            synchronized (lane) {
                run = lane.current;
                lane.work.shutdown();
                if (run == null) {
                    lane.cancel.shutdown();
                }
            }
            if (run != null) {
                run.execution.stop(WorkerEmbeddingFailure.Reason.SHUTDOWN);
            }
        }
        deadlines.shutdown();
    }

    public static Channel channel(WorkerExecution.Purpose purpose) {
        return purpose == WorkerExecution.Purpose.PROBE ? Channel.PROBE : Channel.BUSINESS;
    }

    private static ThreadFactory threads(String name) {
        return action -> {
            var thread = new Thread(action, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    public enum Channel { BUSINESS, PROBE }

    public record Ticket(WorkerExecution execution, CompletableFuture<Void> exited) { }

    private static final class Lane {
        private final ThreadPoolExecutor work;
        private final ThreadPoolExecutor cancel;
        private Run current;

        private Lane(Channel channel) {
            String prefix = "worker-embedding-" + channel.name().toLowerCase(java.util.Locale.ROOT);
            work = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new SynchronousQueue<>(),
                    threads(prefix), new ThreadPoolExecutor.AbortPolicy());
            // 资源合并到一个排空任务；一个交接位置只供前任务退出时使用。
            cancel = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
                    threads(prefix + "-cancel"), new ThreadPoolExecutor.AbortPolicy());
        }
    }

    private final class Run {
        private final Lane lane;
        private final WorkerExecution execution;
        private final CompletableFuture<Void> exited = new CompletableFuture<>();
        private boolean workExited;
        private int pendingCancellations;
        private boolean draining;
        private final ArrayDeque<Runnable> cancelActions = new ArrayDeque<>();

        private Run(Lane lane, WorkerExecution.Purpose purpose) {
            this.lane = lane;
            execution = new WorkerExecution(purpose, settings.maxResponseBytes(), clock,
                    scheduler, this::cancel, reason -> recordStop(purpose, reason));
        }

        private void cancel(Runnable action) {
            synchronized (lane) {
                pendingCancellations++;
                cancelActions.addLast(action);
                if (!draining) {
                    draining = true;
                    lane.cancel.execute(this::drain);
                }
            }
        }

        private void drain() {
            while (true) {
                Runnable action;
                synchronized (lane) {
                    action = cancelActions.pollFirst();
                    if (action == null) {
                        draining = false;
                        releaseIfExited(this);
                        return;
                    }
                }
                try {
                    action.run();
                } catch (RuntimeException error) {
                    log.warn("Worker embedding cancellation unavailable purpose={}", execution.purpose());
                } finally {
                    synchronized (lane) {
                        pendingCancellations--;
                    }
                }
            }
        }
    }
}
