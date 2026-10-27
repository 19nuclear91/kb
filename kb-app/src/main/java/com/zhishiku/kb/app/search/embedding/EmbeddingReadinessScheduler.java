package com.zhishiku.kb.app.search.embedding;

import com.zhishiku.kb.app.embedding.mapper.EmbeddingReadinessMapper;
import com.zhishiku.kb.app.embedding.service.EmbeddingControlService;
import com.zhishiku.kb.app.embedding.service.EmbeddingProfileRegistration;
import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

@Component
@EnableScheduling
public class EmbeddingReadinessScheduler {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingReadinessScheduler.class);
    private final EmbeddingProbe probe;
    private final EmbeddingReadinessMapper mapper;
    private final EmbeddingControlService control;
    private final EmbeddingInstanceReadiness instance;

    public EmbeddingReadinessScheduler(EmbeddingClientRegistry clients, EmbeddingReadinessMapper mapper,
                                       EmbeddingProfileRegistration registration, EmbeddingControlService control,
                                       EmbeddingInstanceReadiness instance,
                                       @Value("${kb.embedding.probe.interval-ms:30000}") long intervalMs,
                                       @Value("${kb.embedding.probe.lifetime-ms:90000}") long lifetimeMs) {
        this.mapper = mapper;
        this.control = control;
        this.instance = instance;
        this.probe = new EmbeddingProbe(clients, Clock.systemUTC(), Duration.ofMillis(intervalMs),
                Duration.ofMillis(lifetimeMs), instance.instanceId());
    }

    @Scheduled(fixedDelayString = "${kb.embedding.probe.interval-ms:30000}")
    public void poll() {
        observeControl();
        try {
            probe.poll(row -> {
                if (mapper.save(row) != 1) throw new IllegalStateException("embedding readiness was not stored");
                instance.record(row);
            });
            mapper.deleteExpired();
        } catch (RuntimeException e) {
            log.warn("embedding readiness update failed");
        } finally {
            observeControl();
        }
    }

    private void observeControl() {
        try {
            instance.observe(control.current());
        } catch (RuntimeException unavailable) {
            instance.controlUnavailable();
        }
    }
}
