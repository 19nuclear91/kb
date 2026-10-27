package com.zhishiku.kb.worker.embedding;

import com.zhishiku.kb.worker.embedding.mapper.EmbeddingStateMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class EmbeddingMetrics {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingMetrics.class);
    private static final List<String> STATES = List.of("UNINITIALIZED", "ACTIVE", "QUIESCING", "REBUILDING",
            "VERIFYING", "BLOCKED", "UNKNOWN");
    private static final List<String> ERRORS = List.of("NONE", "MODEL_UNAVAILABLE", "INVALID_RESPONSE", "LEASE_LOST",
            "CONTENT_CHANGED",
            "MISSING_CHUNKS", "COVERAGE_INVALID", "READINESS_EXPIRED", "RETRY_EXHAUSTED", "DATABASE_ERROR",
            "INTERNAL_ERROR");
    private final EmbeddingStateMapper state;
    private final JdbcTemplate jdbc;
    private final Map<String, AtomicLong> modes = new LinkedHashMap<>();
    private final Map<String, AtomicLong> errors = new LinkedHashMap<>();
    private final Map<String, AtomicLong> events = new LinkedHashMap<>();
    private final Map<String, AtomicLong> progress = new LinkedHashMap<>();
    private final AtomicLong available = new AtomicLong();
    private String previous;

    public EmbeddingMetrics(MeterRegistry registry, EmbeddingStateMapper state, JdbcTemplate jdbc) {
        this.state = state;
        this.jdbc = jdbc;
        register(registry, "kb.embedding.mode", "state", STATES, modes);
        register(registry, "kb.embedding.error", "category", ERRORS, errors);
        register(registry, "kb.embedding.events.persisted", "operation", List.of("START", "RESUME", "BLOCKED",
                "SUCCEEDED"), events);
        register(registry, "kb.embedding.progress", "result", List.of("total", "succeeded", "skipped", "remaining"),
                progress);
        Gauge.builder("kb.embedding.metrics.available", available, AtomicLong::get).register(registry);
    }

    private static void register(MeterRegistry registry, String name, String label, List<String> values, Map<String,
            AtomicLong> gauges) {
        for (String value : values) {
            var gauge = new AtomicLong();
            gauges.put(value, gauge);
            Gauge.builder(name, gauge, AtomicLong::get).tag(label, value).register(registry);
        }
    }

    @Scheduled(fixedDelay = 30000)
    public void refresh() {
        try {
            var control = state.control();
            if (control == null) throw new IllegalStateException("embedding control unavailable");
            var run = control.runId() == null ? null : state.run(control.runId());
            String mode = STATES.contains(control.state()) ? control.state() : "UNKNOWN";
            String error = run == null || run.errorCode() == null ? "NONE"
                    : ERRORS.contains(run.errorCode()) ? run.errorCode() : "INTERNAL_ERROR";
            modes.forEach((key, value) -> value.set(key.equals(mode) ? 1 : 0));
            errors.forEach((key, value) -> value.set(key.equals(error) ? 1 : 0));
            long total = run == null ? 0 : run.totalCount();
            long succeeded = run == null ? 0 : run.succeededCount();
            long skipped = run == null ? 0 : run.skippedCount();
            progress.get("total").set(total);
            progress.get("succeeded").set(succeeded);
            progress.get("skipped").set(skipped);
            progress.get("remaining").set(Math.max(0, total - succeeded - skipped));
            // 从持久去重事件采样，不把多实例重复观察变成新的成功计数。
            for (var entry : events.entrySet()) {
                Long count = jdbc.queryForObject("SELECT count(*) FROM kb_embedding_operation WHERE operation_domain " +
                                "= ?",
                        Long.class, entry.getKey());
                entry.getValue().set(count == null ? 0 : count);
            }
            available.set(1);
            String signature = mode + ":" + error;
            if (!signature.equals(previous)) {
                log.info("embedding state={} error={}", mode, error);
                previous = signature;
            }
        } catch (RuntimeException unavailable) {
            available.set(0);
            log.warn("embedding metrics unavailable");
        }
    }
}
