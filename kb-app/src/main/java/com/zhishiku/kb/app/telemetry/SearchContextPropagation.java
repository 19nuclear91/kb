package com.zhishiku.kb.app.telemetry;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.context.ThreadLocalAccessor;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.contextpropagation.ObservationAwareSpanThreadLocalAccessor;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/** 搜索工作只桥接遥测与本地 requestId，不复制请求、认证或事务上下文。 */
@Component
public final class SearchContextPropagation {
    public static final SearchContextPropagation REQUEST_ID_ONLY = new SearchContextPropagation(ObservationRegistry.NOOP, Tracer.NOOP);
    private final ContextSnapshotFactory snapshots;

    public SearchContextPropagation(ObservationRegistry registry, Tracer tracer) {
        snapshots = ContextSnapshotFactory.builder().clearMissing(true).contextRegistry(new ContextRegistry()
                .registerThreadLocalAccessor(new ObservationThreadLocalAccessor(registry))
                .registerThreadLocalAccessor(new ObservationAwareSpanThreadLocalAccessor(registry, tracer))
                .registerThreadLocalAccessor(new RequestIdAccessor())).build();
    }

    public ContextSnapshot capture() {
        return snapshots.captureAll();
    }

    private static final class RequestIdAccessor implements ThreadLocalAccessor<String> {
        @Override
        public Object key() {
            return "kb.search.requestId";
        }

        @Override
        public String getValue() {
            return MDC.get("requestId");
        }

        @Override
        public void setValue(String value) {
            MDC.put("requestId", value);
        }

        @Override
        public void setValue() {
            MDC.remove("requestId");
        }
    }
}
