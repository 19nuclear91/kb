package com.zhishiku.kb.worker.embedding;

import com.zhishiku.kb.worker.embedding.model.RebuildBatch;
import com.zhishiku.kb.worker.embedding.service.EmbeddingBatchProcessor;
import com.zhishiku.kb.worker.embedding.service.EmbeddingLeaseService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Component
public class EmbeddingRebuildRunner {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingRebuildRunner.class);
    private final UUID owner = UUID.randomUUID();
    private final EmbeddingLeaseService leases;
    private final EmbeddingBatchProcessor processor;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "embedding-rebuild");
        thread.setDaemon(true);
        return thread;
    });
    private RebuildBatch current;
    private Future<?> future;
    private boolean lost;

    public EmbeddingRebuildRunner(EmbeddingLeaseService leases, EmbeddingBatchProcessor processor) {
        this.leases = leases;
        this.processor = processor;
    }

    @Scheduled(fixedDelay = 2000)
    public synchronized void tick() {
        try {
            if (future != null) {
                if (!future.isDone()) {
                    if (!lost && !leases.renew(current)) lost = true;
                    return;
                }
                try {
                    future.get();
                } finally {
                    future = null;
                    current = null;
                    lost = false;
                }
            }
            var batch = leases.claim(owner);
            if (batch == null) return;
            if (batch.items().isEmpty()) {
                leases.release(batch);
                return;
            }
            current = batch;
            future = executor.submit(() -> processor.process(batch));
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            // 不记录异常对象，避免 JDBC 参数或远端响应体中的正文进入日志。
            log.warn("embedding rebuild executor unavailable");
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
