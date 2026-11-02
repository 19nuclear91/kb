package com.zhishiku.kb.worker.telemetry;

import io.micrometer.tracing.exporter.FinishedSpan;
import io.micrometer.tracing.exporter.SpanFilter;
import io.micrometer.tracing.otel.bridge.OtelFinishedSpan;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.StatusData;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;

@Component
public final class TelemetrySpanFilter implements SpanFilter {
    @Override
    public FinishedSpan map(FinishedSpan span) {
        var tags = new HashMap<>(span.getTypedTags());
        tags.keySet().removeIf(TelemetrySpanFilter::forbidden);
        if (span.getName().startsWith("kb.parse.")) {
            tags.keySet().retainAll(TaskTelemetry.ATTRIBUTES);
        }
        span.setTypedTags(tags);
        var source = OtelFinishedSpan.toOtel(span);
        List<EventData> events = source.getEvents().stream().map(TelemetrySpanFilter::safeEvent).toList();
        return OtelFinishedSpan.fromOtel(new DelegatingSpanData(source) {
            @Override
            public List<EventData> getEvents() {
                return events;
            }

            @Override
            public StatusData getStatus() {
                return StatusData.create(source.getStatus().getStatusCode(), "");
            }
        });
    }

    private static boolean forbidden(String key) {
        return key.equals("http.url") || key.equals("url.full") || key.startsWith("http.request.header.")
                || key.startsWith("net.") || key.equals("exception.message") || key.equals("exception.stacktrace");
    }

    private static EventData safeEvent(EventData event) {
        var attributes = Attributes.builder();
        if ("exception".equals(event.getName())) {
            String type = event.getAttributes().get(AttributeKey.stringKey("exception.type"));
            if (type != null && type.length() <= 512 && type.matches("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}.]*")) {
                attributes.put("exception.type", type);
            }
        } else {
            attributes.putAll(event.getAttributes()).removeIf(key -> forbidden(key.getKey()));
        }
        return EventData.create(event.getEpochNanos(), event.getName(), attributes.build());
    }
}
