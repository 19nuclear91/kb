package com.zhishiku.kb.worker.embedding;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.embedding.EmbeddingRun;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingStateMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddingMetricsTest {
    @Test
    void fixedLabelsPersistedEventCountsAndSanitizedLogsDoNotGrowWithRuns() {
        var registry = new SimpleMeterRegistry();
        var state = mock(EmbeddingStateMapper.class);
        var jdbc = mock(JdbcTemplate.class);
        var id = UUID.randomUUID();
        when(state.control()).thenReturn(new EmbeddingControl("BLOCKED", null, null, "private-profile", "fp", 3, id));
        when(state.run(id)).thenReturn(new EmbeddingRun(id, "private-profile", 1, "BLOCKED", 2, "REBUILDING", 5,
                null, null, 3, 3, null, 10, 4, 1, "private body api-key"));
        when(jdbc.queryForObject(anyString(), eq(Long.class), anyString())).thenReturn(2L);
        var logger = (Logger) LoggerFactory.getLogger(EmbeddingMetrics.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var metrics = new EmbeddingMetrics(registry, state, jdbc);
            int size = registry.getMeters().size();
            metrics.refresh();
            metrics.refresh();
            assertThat(registry.getMeters()).hasSize(size);
            assertThat(registry.get("kb.embedding.mode").tag("state", "BLOCKED").gauge().value()).isEqualTo(1);
            assertThat(registry.get("kb.embedding.progress").tag("result", "remaining").gauge().value()).isEqualTo(5);
            assertThat(registry.get("kb.embedding.events.persisted").tag("operation", "START").gauge().value()).isEqualTo(2);
            assertThat(registry.get("kb.embedding.error").tag("category", "INTERNAL_ERROR").gauge().value()).isEqualTo(1);
            when(state.control()).thenThrow(new IllegalStateException("private body api-key"));
            metrics.refresh();
            assertThat(registry.get("kb.embedding.metrics.available").gauge().value()).isZero();
            assertThat(appender.list).hasSize(2);
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("private", "api-key", id.toString());
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags().toString())
                    .doesNotContain("private", id.toString(), "profileId", "chunkId"));
        } finally {
            logger.detachAppender(appender);
            registry.close();
        }
    }
}
