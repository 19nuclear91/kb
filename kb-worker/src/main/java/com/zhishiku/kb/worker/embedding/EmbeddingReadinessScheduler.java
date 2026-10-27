package com.zhishiku.kb.worker.embedding;

import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingProbe;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingReadinessMapper;
import com.zhishiku.kb.worker.embedding.service.EmbeddingProfileRegistration;
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

    public EmbeddingReadinessScheduler(EmbeddingClientRegistry clients, EmbeddingReadinessMapper mapper,
                                       EmbeddingProfileRegistration registration,
                                       @Value("${kb.embedding.probe.interval-ms:30000}") long intervalMs,
                                       @Value("${kb.embedding.probe.lifetime-ms:90000}") long lifetimeMs) {
        this.mapper = mapper;
        this.probe = new EmbeddingProbe(clients, Clock.systemUTC(), Duration.ofMillis(intervalMs),
                Duration.ofMillis(lifetimeMs));
    }

    @Scheduled(fixedDelayString = "${kb.embedding.probe.interval-ms:30000}")
    public void poll() {
        try {
            probe.poll(row -> {
                if (mapper.save(row) != 1) throw new IllegalStateException("embedding readiness was not stored");
            });
            mapper.deleteExpired();
        } catch (RuntimeException e) {
            log.warn("embedding readiness update failed");
        }
    }
}
