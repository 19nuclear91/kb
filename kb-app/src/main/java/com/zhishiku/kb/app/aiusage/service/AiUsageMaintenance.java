package com.zhishiku.kb.app.aiusage.service;

import com.zhishiku.kb.app.aiusage.AiObservationProperties;
import com.zhishiku.kb.app.aiusage.mapper.AiUsageMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 有界恢复与清理，低基数指标不带主体或调用标识。
 */
@Component
@EnableScheduling
@Order(3)
public class AiUsageMaintenance implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AiUsageMaintenance.class);
    private final AiCollectionService collection;
    private final AiUsageWriter writer;
    private final AiUsageMapper mapper;
    private final AiObservationProperties properties;
    private final Counter failures;
    private final Counter deleted;
    private final AtomicLong unresolved = new AtomicLong();
    private final AtomicLong oldest = new AtomicLong();

    public AiUsageMaintenance(AiCollectionService collection, AiUsageWriter writer, AiUsageMapper mapper,
                              AiObservationProperties properties, MeterRegistry registry) {
        this.collection = collection;
        this.writer = writer;
        this.mapper = mapper;
        this.properties = properties;
        failures = registry.counter("kb.ai.observation.failures");
        deleted = registry.counter("kb.ai.observation.cleaned");
        Gauge.builder("kb.ai.observation.unresolved", unresolved, AtomicLong::get).register(registry);
        Gauge.builder("kb.ai.observation.oldest_age_seconds", oldest, AtomicLong::get).register(registry);
    }

    @Override
    public void run(ApplicationArguments args) { collection.synchronize(); }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void tick() {
        try {
            collection.synchronize();
            for (var call : mapper.expired(Instant.now(), Math.min(100, properties.getCleanupBatchSize()))) writer.recover(call.getCallId());
            for (var call : mapper.unconfirmed(Instant.now().minusSeconds(properties.getLeaseSeconds()), Math.min(100, properties.getCleanupBatchSize()))) writer.recover(call.getCallId());
            int count = collection.cleanup();
            deleted.increment(count);
            var metrics = mapper.operationalMetrics();
            unresolved.set(new BigDecimal(metrics.get("unresolved").toString()).longValueExact());
            oldest.set(new BigDecimal(metrics.get("oldestAge").toString()).max(BigDecimal.ZERO).longValue());
            if (count > 0) log.info("AI 观测清理完成 count={}", count);
        } catch (RuntimeException ignored) {
            failures.increment();
            log.warn("AI 观测维护暂不可用");
        }
    }
}
