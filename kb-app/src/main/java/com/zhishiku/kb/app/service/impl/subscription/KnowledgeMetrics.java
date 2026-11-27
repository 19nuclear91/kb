package com.zhishiku.kb.app.service.impl.subscription;

import com.zhishiku.kb.app.domain.dto.subscription.KnowledgeWork;
import com.zhishiku.kb.app.mapper.subscription.KnowledgeDerivationMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Set;

/** 只读完整积压快照，抓取指标不查库，失败不伪造零积压。 */
@Service
public class KnowledgeMetrics {
    private final KnowledgeDerivationMapper mapper;
    private final MeterRegistry registry;
    private volatile KnowledgeWork.Backlog snapshot;
    private volatile boolean available;

    public KnowledgeMetrics(KnowledgeDerivationMapper mapper, MeterRegistry registry) {
        this.mapper = mapper;
        this.registry = registry;
        for (String stage : Set.of("match", "weekly", "delivery")) {
            Gauge.builder("kb.knowledge.subscriptions.backlog", this, value -> value.pending(stage)).tag("stage", stage).register(registry);
        }
        Gauge.builder("kb.knowledge.subscriptions.oldest.age.seconds", this,
                value -> value.snapshot == null ? Double.NaN : value.snapshot.oldestSeconds()).register(registry);
        Gauge.builder("kb.knowledge.subscriptions.snapshot.available", this, value -> value.available ? 1 : 0).register(registry);
        Gauge.builder("kb.knowledge.subscriptions.snapshot.timestamp", this,
                value -> value.snapshot == null ? Double.NaN : value.snapshot.sampledAt().getEpochSecond()).register(registry);
    }

    public void result(String stage, String outcome) {
        if (!Set.of("match", "weekly", "delivery", "discover", "close", "snapshot").contains(stage)
                || !Set.of("success", "failure", "takeover", "lease_lost").contains(outcome)) {
            throw new IllegalArgumentException("Invalid knowledge metric label");
        }
        registry.counter("kb.knowledge.subscriptions.work", "stage", stage, "outcome", outcome).increment();
    }

    @Scheduled(fixedDelayString = "${kb.knowledge-subscriptions.metrics-delay-ms:30000}")
    public void sample() {
        try {
            KnowledgeWork.Backlog current = mapper.backlog();
            if (current == null || current.sampledAt().isAfter(Instant.now().plusSeconds(60))) {
                throw new IllegalStateException("Knowledge snapshot is unavailable");
            }
            snapshot = current;
            available = true;
        } catch (RuntimeException ignored) {
            available = false;
            result("snapshot", "failure");
        }
    }

    private double pending(String stage) {
        KnowledgeWork.Backlog current = snapshot;
        if (current == null) {
            return Double.NaN;
        }
        return switch (stage) {
            case "match" -> current.matching();
            case "weekly" -> current.weekly();
            default -> current.delivery();
        };
    }
}
