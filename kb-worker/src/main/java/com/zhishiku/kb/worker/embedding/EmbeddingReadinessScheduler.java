package com.zhishiku.kb.worker.embedding;

import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingProbe;
import com.zhishiku.kb.common.embedding.EmbeddingReadiness;
import com.zhishiku.kb.common.embedding.ProfileEmbeddingClient;
import com.zhishiku.kb.worker.embedding.execution.WorkerEmbeddingFailure;
import com.zhishiku.kb.worker.embedding.execution.WorkerEmbeddingSettings;
import com.zhishiku.kb.worker.embedding.execution.WorkerExecution;
import com.zhishiku.kb.worker.embedding.execution.WorkerExecutions;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingReadinessMapper;
import com.zhishiku.kb.worker.embedding.service.EmbeddingProfileRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Component
@EnableScheduling
public class EmbeddingReadinessScheduler {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingReadinessScheduler.class);
    private final List<ProfileEmbeddingClient> clients;
    private final EmbeddingReadinessMapper mapper;
    private final WorkerExecutions executions;
    private final WorkerEmbeddingSettings settings;
    private final Clock clock;
    private final Duration interval;
    private final Duration lifetime;
    private final UUID instanceId = UUID.randomUUID();
    private Instant nextProbe = Instant.MIN;
    private WorkerExecutions.Ticket current;
    private int index;
    private volatile boolean closed;

    @Autowired
    public EmbeddingReadinessScheduler(EmbeddingClientRegistry clients, EmbeddingReadinessMapper mapper,
                                        EmbeddingProfileRegistration registration,
                                        @Value("${kb.embedding.probe.interval-ms:30000}") long intervalMs,
                                        @Value("${kb.embedding.probe.lifetime-ms:90000}") long lifetimeMs,
                                        WorkerExecutions executions, WorkerEmbeddingSettings settings) {
        this(clients, mapper, intervalMs, lifetimeMs, executions, settings, Clock.systemUTC());
    }

    EmbeddingReadinessScheduler(EmbeddingClientRegistry clients, EmbeddingReadinessMapper mapper,
                                long intervalMs, long lifetimeMs, WorkerExecutions executions,
                                WorkerEmbeddingSettings settings, Clock clock) {
        if (intervalMs <= 0 || lifetimeMs <= intervalMs) {
            throw new IllegalArgumentException("embedding probe interval must be positive and shorter than lifetime");
        }
        this.clients = clients.clients().stream().sorted(Comparator.comparing(client -> client.profile().profileId())).toList();
        this.mapper = mapper;
        this.executions = executions;
        this.settings = settings;
        this.clock = clock;
        this.interval = Duration.ofMillis(intervalMs);
        this.lifetime = Duration.ofMillis(lifetimeMs);
    }

    /** 轻量推进游标；完整轮次间隔仍由 interval 控制，没有待执行任务队列。 */
    @Scheduled(fixedDelay = 100)
    public synchronized WorkerExecutions.Ticket poll() {
        if (closed) {
            return null;
        }
        if (current != null) {
            if (!current.exited().isDone()) {
                return null;
            }
            current = null;
            index++;
            if (index == clients.size()) {
                index = 0;
                nextProbe = clock.instant().plus(interval);
            }
        }
        if (clock.instant().isBefore(nextProbe)) {
            return null;
        }
        var client = clients.get(index);
        current = executions.submit(WorkerExecution.Purpose.PROBE, execution -> probe(client, execution));
        return current;
    }

    void probe(ProfileEmbeddingClient client, WorkerExecution execution) {
        boolean ready = false;
        try {
            if (closed) {
                execution.stop(WorkerEmbeddingFailure.Reason.SHUTDOWN);
            }
            execution.begin(Duration.ofSeconds(settings.probeTimeoutSeconds()));
            try (var interrupt = execution.interruptCurrentThread()) {
                execution.check();
                client.embed(List.of(EmbeddingProbe.SYNTHETIC_INPUT), execution).require(client.profile(), client.role(), 1);
            }
            execution.succeed();
            ready = true;
        } catch (RuntimeException failure) {
            execution.stop(failure instanceof WorkerEmbeddingFailure known
                    ? known.reason() : WorkerEmbeddingFailure.Reason.INVALID_RESPONSE);
        }
        if (closed || execution.reason() == WorkerEmbeddingFailure.Reason.SHUTDOWN) {
            return;
        }
        Instant checkedAt = clock.instant();
        var row = new EmbeddingReadiness(instanceId, client.role(), client.profile().profileId(),
                client.profile().fingerprint(), EmbeddingReadiness.PROTOCOL_VERSION, ready,
                checkedAt, checkedAt.plus(lifetime));
        try {
            if (mapper.save(row) != 1) {
                throw new IllegalStateException("embedding readiness was not stored");
            }
            mapper.deleteExpired();
        } catch (RuntimeException e) {
            log.warn("embedding readiness update failed");
        }
    }

    @PreDestroy
    public synchronized void close() {
        closed = true;
        if (current != null) {
            current.execution().stop(WorkerEmbeddingFailure.Reason.SHUTDOWN);
        }
    }
}
