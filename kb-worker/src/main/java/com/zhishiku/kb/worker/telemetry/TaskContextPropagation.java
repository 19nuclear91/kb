package com.zhishiku.kb.worker.telemetry;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.contextpropagation.ObservationAwareSpanThreadLocalAccessor;
import org.springframework.stereotype.Component;

@Component
public final class TaskContextPropagation {
    private final ContextSnapshotFactory snapshots;

    public TaskContextPropagation(ObservationRegistry registry, Tracer tracer) {
        snapshots = ContextSnapshotFactory.builder().clearMissing(true).contextRegistry(new ContextRegistry()
                .registerThreadLocalAccessor(new ObservationAwareSpanThreadLocalAccessor(registry, tracer))).build();
    }

    public ContextSnapshot capture() {
        return snapshots.captureAll();
    }
}
