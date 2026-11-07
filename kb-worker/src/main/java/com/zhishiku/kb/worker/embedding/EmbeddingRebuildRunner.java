package com.zhishiku.kb.worker.embedding;

import com.zhishiku.kb.worker.embedding.execution.WorkerEmbeddingFailure;
import com.zhishiku.kb.worker.embedding.execution.WorkerEmbeddingSettings;
import com.zhishiku.kb.worker.embedding.execution.WorkerExecution;
import com.zhishiku.kb.worker.embedding.execution.WorkerExecutions;
import com.zhishiku.kb.worker.embedding.model.RebuildBatch;
import com.zhishiku.kb.worker.embedding.service.EmbeddingBatchProcessor;
import com.zhishiku.kb.worker.embedding.service.EmbeddingLeaseService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class EmbeddingRebuildRunner {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingRebuildRunner.class);
    private final UUID owner = UUID.randomUUID();
    private final EmbeddingLeaseService leases;
    private final EmbeddingBatchProcessor processor;
    private final WorkerExecutions executions;
    private final AtomicReference<Active> current = new AtomicReference<>();
    private boolean ownsExecutions;
    private volatile boolean closed;

    private record Active(RebuildBatch batch, WorkerExecution execution) {
    }

    public EmbeddingRebuildRunner(EmbeddingLeaseService leases, EmbeddingBatchProcessor processor) {
        this(leases, processor, new WorkerExecutions(WorkerEmbeddingSettings.defaults()));
        ownsExecutions = true;
    }

    @Autowired
    public EmbeddingRebuildRunner(EmbeddingLeaseService leases, EmbeddingBatchProcessor processor,
                                   WorkerExecutions executions) {
        this.leases = leases;
        this.processor = processor;
        this.executions = executions;
    }

    @Scheduled(fixedDelay = 2000)
    public WorkerExecutions.Ticket tick() {
        if (closed) {
            return null;
        }
        return executions.submit(WorkerExecution.Purpose.REBUILD, execution -> {
            Active active = null;
            try {
                if (closed || execution.stopped()) {
                    return;
                }
                var batch = leases.claim(owner);
                if (batch == null) {
                    return;
                }
                if (batch.items().isEmpty()) {
                    leases.release(batch);
                    return;
                }
                active = new Active(batch, execution);
                current.set(active);
                if (closed) {
                    execution.stop(WorkerEmbeddingFailure.Reason.SHUTDOWN);
                }
                processor.process(batch, execution);
            } catch (RuntimeException error) {
                log.warn("embedding rebuild executor unavailable");
            } finally {
                if (active != null) {
                    current.compareAndSet(active, null);
                }
            }
        });
    }

    @Scheduled(fixedDelay = 2000)
    public void heartbeat() {
        Active active = current.get();
        if (active == null || active.execution().stopped()) {
            return;
        }
        try {
            if (!leases.renew(active.batch())) {
                active.execution().stop(WorkerEmbeddingFailure.Reason.LEASE_LOST);
            }
        } catch (RuntimeException error) {
            active.execution().stop(WorkerEmbeddingFailure.Reason.LEASE_UNKNOWN);
            log.warn("embedding rebuild lease status unavailable");
        }
    }

    @PreDestroy
    public void close() {
        closed = true;
        Active active = current.get();
        if (active != null) {
            active.execution().stop(WorkerEmbeddingFailure.Reason.SHUTDOWN);
        }
        if (ownsExecutions) {
            executions.close();
        }
    }
}
