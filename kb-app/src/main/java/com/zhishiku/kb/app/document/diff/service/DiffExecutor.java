package com.zhishiku.kb.app.document.diff.service;

import com.zhishiku.kb.app.document.diff.DocumentDiffProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.LongSupplier;

@Component
public class DiffExecutor implements AutoCloseable {
    private final DocumentDiffProperties properties;
    private final LongSupplier clock;
    private final ThreadPoolExecutor workers;

    @Autowired
    public DiffExecutor(DocumentDiffProperties properties) {
        this(properties, System::nanoTime);
    }

    public DiffExecutor(DocumentDiffProperties properties, LongSupplier clock) {
        this.properties = properties;
        this.clock = clock;
        this.workers = new ThreadPoolExecutor(properties.maxConcurrent(), properties.maxConcurrent(),
                0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(), runnable -> {
                    Thread thread = new Thread(runnable, "document-diff");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** 接管已取得的票据；请求超时不会释放尚在计算的票据。 */
    public <T> T execute(DiffAdmission.Ticket ticket, Function<DiffDeadline, T> computation) {
        var deadline = new DiffDeadline(properties.timeoutMs(), clock);
        var result = new CompletableFuture<T>();
        try {
            workers.execute(() -> {
                try {
                    deadline.check();
                    T computed = computation.apply(deadline);
                    deadline.check();
                    result.complete(computed);
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                } finally {
                    ticket.close();
                }
            });
        } catch (RejectedExecutionException rejected) {
            ticket.close();
            throw new DiffException(429, "DIFF_CAPACITY");
        }
        try {
            return result.get(properties.timeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            deadline.cancel();
            throw new DiffDeadline.Expired();
        } catch (InterruptedException interrupted) {
            deadline.cancel();
            Thread.currentThread().interrupt();
            throw new DiffException(503, "UNAVAILABLE");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof DiffDeadline.Expired) {
                throw new DiffDeadline.Expired();
            }
            throw new DiffException(503, "UNAVAILABLE");
        }
    }

    @PreDestroy
    @Override
    public void close() {
        workers.shutdownNow();
    }
}
