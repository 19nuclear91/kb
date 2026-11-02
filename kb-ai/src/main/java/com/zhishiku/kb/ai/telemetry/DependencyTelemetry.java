package com.zhishiku.kb.ai.telemetry;

import com.zhishiku.kb.ai.chat.RetrievalFailure;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.springframework.stereotype.Component;

@Component
public final class DependencyTelemetry {
    public static final DependencyTelemetry NOOP = new DependencyTelemetry(new CompositeMeterRegistry());
    private final MeterRegistry registry;

    public DependencyTelemetry(MeterRegistry registry) {
        this.registry = registry;
    }

    public void retrievalFailed(RetrievalFailure error) {
        String reason = switch (error.kind()) {
            case TIMEOUT -> "TIMEOUT";
            case PROTOCOL, PAYLOAD_LIMIT -> "PROTOCOL";
            case DEPENDENCY -> "UNAVAILABLE";
            case HTTP -> error.httpStatus() >= 400 && error.httpStatus() < 500 ? "REJECTED" : "UNAVAILABLE";
        };
        registry.counter("kb.ai.retrieval.failures", "reason", reason).increment();
    }

    public void quotaRejected(QuotaReason reason) {
        registry.counter("kb.ai.quota.rejections", "reason", reason.name()).increment();
    }

    public enum QuotaReason {
        REQUEST_OR_CONCURRENCY, TOKEN_BUDGET
    }
}
