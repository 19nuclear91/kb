package com.zhishiku.kb.app.telemetry;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.ServerHttpObservationFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RequestIdFilterTest {
    @Test
    void correlatesLogsCleansThreadAndKeepsReplayTraceIndependent() throws Exception {
        var controller = new Endpoint();
        var logger = (Logger) LoggerFactory.getLogger(Endpoint.class);
        var events = new ListAppender<ILoggingEvent>() {
            @Override
            protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        events.start();
        logger.addAppender(events);
        try (var tracing = new InMemoryTracing()) {
            var mvc = MockMvcBuilders.standaloneSetup(controller)
                    .addFilters(new ServerHttpObservationFilter(tracing.registry), new RequestIdFilter()).build();
            for (int i = 0; i < 2; i++) {
                mvc.perform(get("/probe").header("X-Request-Id", "same-request")).andExpect(status().isOk());
                assertThat(MDC.get("requestId")).isNull();
            }
            mvc.perform(get("/probe")).andExpect(status().isOk());
            assertThat(controller.requestIds).containsExactly("same-request", "same-request", null);
            assertThat(events.list).hasSize(3);
            assertThat(events.list.get(0).getMDCPropertyMap()).containsEntry("requestId", "same-request");
            assertThat(events.list.get(1).getMDCPropertyMap()).containsEntry("requestId", "same-request");
            assertThat(events.list.get(2).getMDCPropertyMap()).doesNotContainKey("requestId");
            assertThat(tracing.exporter.getFinishedSpanItems()).hasSize(3);
            assertThat(tracing.exporter.getFinishedSpanItems().get(0).getTraceId())
                    .isNotEqualTo(tracing.exporter.getFinishedSpanItems().get(1).getTraceId());
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void alsoCleansOnExceptionAndRemovesStaleValuesOnEntry() {
        MDC.put("requestId", "stale");
        assertThatThrownBy(() -> new RequestIdFilter().doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (request, response) -> {
                    assertThat(MDC.get("requestId")).isNull();
                    throw new IOException("failure");
                })).isInstanceOf(IOException.class);
        assertThat(MDC.get("requestId")).isNull();
    }

    @RestController
    static class Endpoint {
        private final List<String> requestIds = new ArrayList<>();

        @GetMapping("/probe")
        String probe() {
            requestIds.add(MDC.get("requestId"));
            LoggerFactory.getLogger(Endpoint.class).info("请求处理中");
            return "ok";
        }
    }
}
