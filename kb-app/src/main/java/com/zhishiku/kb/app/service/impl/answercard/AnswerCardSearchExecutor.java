package com.zhishiku.kb.app.service.impl.answercard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zhishiku.kb.app.config.AnswerCardSearchProperties;
import com.zhishiku.kb.app.service.answercard.AnswerCardFailure;
import com.zhishiku.kb.common.api.R;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** 无排队的独立搜索执行器；响应取消后仍等待真实工作退出才释放容量。 */
@Component
public class AnswerCardSearchExecutor {
    private final AnswerCardSearchProperties limits;
    private final ObjectMapper json;
    private final ThreadPoolExecutor workers;
    private final Map<Long, Integer> users = new HashMap<>();
    private int active;

    public AnswerCardSearchExecutor(AnswerCardSearchProperties limits, ObjectMapper json) {
        this.limits = limits;
        this.json = json.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        workers = new ThreadPoolExecutor(limits.maxConcurrent(), limits.maxConcurrent(), 0, TimeUnit.SECONDS,
                new SynchronousQueue<>(), task -> {
                    Thread thread = new Thread(task, "answer-card-search");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public byte[] execute(long actor, Supplier<?> action) {
        var budget = new AnswerCardSearchBudget(limits.budgetMillis(), System::nanoTime);
        acquire(actor);
        var worker = new AtomicReference<Thread>();
        var cancelled = new AtomicBoolean();
        var result = new CompletableFuture<byte[]>();
        try {
            workers.execute(() -> {
                synchronized (worker) {
                    worker.set(Thread.currentThread());
                }
                try (budget) {
                    budget.install();
                    if (cancelled.get()) {
                        throw AnswerCardFailure.unavailable();
                    }
                    budget.remainingNanos();
                    Object value = action.get();
                    budget.remainingNanos();
                    byte[] bytes = json.writeValueAsBytes(R.ok(value));
                    if (bytes.length > limits.responseBytes()) {
                        throw AnswerCardFailure.unavailable();
                    }
                    budget.remainingNanos();
                    result.complete(bytes);
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                } finally {
                    synchronized (worker) {
                        worker.set(null);
                    }
                    release(actor);
                }
            });
        } catch (RejectedExecutionException rejected) {
            release(actor);
            throw new AnswerCardFailure(429, "CAPACITY");
        }
        try {
            byte[] bytes = result.get(budget.remainingNanos(), TimeUnit.NANOSECONDS);
            budget.remainingNanos();
            return bytes;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw AnswerCardFailure.unavailable();
        } catch (TimeoutException timeout) {
            throw AnswerCardFailure.unavailable();
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw AnswerCardFailure.unavailable();
        } finally {
            cancelled.set(true);
            synchronized (worker) {
                Thread running = worker.get();
                if (running != null) {
                    running.interrupt();
                }
            }
        }
    }

    private synchronized void acquire(long actor) {
        if (workers.isShutdown() || active >= limits.maxConcurrent() || users.getOrDefault(actor, 0) >= limits.perUser()) {
            throw new AnswerCardFailure(429, "CAPACITY");
        }
        active++;
        users.merge(actor, 1, Integer::sum);
    }

    private synchronized void release(long actor) {
        active--;
        users.computeIfPresent(actor, (id, count) -> count == 1 ? null : count - 1);
    }

    @PreDestroy
    public void close() {
        workers.shutdownNow();
    }
}
