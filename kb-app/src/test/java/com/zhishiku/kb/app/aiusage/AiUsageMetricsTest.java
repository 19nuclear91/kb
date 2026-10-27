package com.zhishiku.kb.app.aiusage;

import com.zhishiku.kb.app.aiusage.mapper.AiUsageMapper;
import com.zhishiku.kb.app.aiusage.service.*;
import com.zhishiku.kb.app.aiusage.web.AiObservationResponseFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiUsageMetricsTest {
    @Test
    void gaugesCountersAndEarlyFailuresUseNoIdentityTags() throws Exception {
        var registry = new SimpleMeterRegistry();
        var collection = mock(AiCollectionService.class); var mapper = mock(AiUsageMapper.class);
        when(mapper.expired(any(), anyInt())).thenReturn(List.of());
        when(collection.cleanup()).thenReturn(3);
        when(mapper.operationalMetrics()).thenReturn(Map.of("unresolved", 4, "oldestAge", 180));
        var maintenance = new AiUsageMaintenance(collection, mock(AiUsageWriter.class), mapper,
                new AiObservationProperties(new MockEnvironment()), registry);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AiUsageMaintenance.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start(); logger.addAppender(logs);
        try {
        maintenance.tick();
        assertThat(registry.get("kb.ai.observation.cleaned").counter().count()).isEqualTo(3);
        assertThat(registry.get("kb.ai.observation.unresolved").gauge().value()).isEqualTo(4);
        assertThat(registry.get("kb.ai.observation.oldest_age_seconds").gauge().value()).isEqualTo(180);
        doThrow(new IllegalStateException("private-token-payload")).when(collection).synchronize(); maintenance.tick();
        assertThat(registry.get("kb.ai.observation.failures").counter().count()).isEqualTo(1);
        var response = new MockHttpServletResponse();
        new AiObservationResponseFilter(registry).doFilter(new MockHttpServletRequest("POST", "/internal/ai-observability/events"), response,
                (request, result) -> ((jakarta.servlet.http.HttpServletResponse) result).setStatus(403));
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(registry.get("kb.ai.observation.ingest_failures").counter().count()).isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).isEmpty());
        assertThat(logs.list).hasSize(2).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain("private", "token", "payload");
            assertThat(event.getThrowableProxy()).isNull();
        });
        } finally { logger.detachAppender(logs); logs.stop(); }
    }
}
