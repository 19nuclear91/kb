package com.zhishiku.kb.app.telemetry;

import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.ServerHttpObservationFilter;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HttpMetricsCardinalityTest {
    @ParameterizedTest
    @ValueSource(strings = {"/actuator", "/monitoring"})
    void realServletLifecycleExcludesManagementBeforeRoutingIncludingActiveMeters(String basePath) throws Exception {
        var meters = new SimpleMeterRegistry();
        try {
            meters.config().meterFilter(new HttpMetricsConfiguration().managementEndpointMetricsFilter(basePath));
            var observations = ObservationRegistry.create();
            observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
            var mvc = MockMvcBuilders.standaloneSetup(new Endpoints(meters)).addFilters(new ServerHttpObservationFilter(
                    observations, new HttpObservationConfiguration.ServerConvention(basePath))).build();
            for (String path : new String[]{basePath, basePath + "/health", basePath + "/prometheus"}) {
                mvc.perform(get(path)).andExpect(status().isOk());
                assertThat(meters.getMeters()).isEmpty();
            }
            mvc.perform(get("/kb" + basePath + "/health").contextPath("/kb")).andExpect(status().isOk());
            assertThat(meters.getMeters()).isEmpty();
            mvc.perform(get(basePath + "-other")).andExpect(status().isOk());
            assertThat(meters.get("http.server.requests").timer().count()).isEqualTo(1);
            assertThat(meters.find("http.server.requests.active").longTaskTimers()).hasSize(1);
        } finally {
            meters.close();
        }
    }

    @Test
    void rawPathsDoNotGrowSeriesAndManagementEndpointsAreExcluded() {
        var meters = new SimpleMeterRegistry();
        var filters = new HttpMetricsConfiguration();
        meters.config().meterFilter(filters.managementEndpointMetricsFilter("/actuator")).meterFilter(filters.httpUriLimit(2));
        var observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        try {
            for (int i = 0; i < 100; i++) {
                record(observations, "/api/documents/" + i, "/api/documents/{id}", 200);
                record(observations, "/" + UUID.randomUUID(), null, 404);
                record(observations, "/actuator/health", "/actuator/health", 200);
            }
            record(observations, "/extra", "/extra", 200);
            assertThat(meters.getMeters()).hasSize(4);
            assertThat(meters.find("http.server.requests").timers()).hasSize(2);
            assertThat(meters.find("http.server.requests").tag("uri", "/api/documents/{id}").timer().count()).isEqualTo(100);
            assertThat(meters.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTag("uri"))
                    .isIn("/api/documents/{id}", "NOT_FOUND"));
        } finally {
            meters.close();
        }
    }

    private static void record(ObservationRegistry registry, String path, String pattern, int status) {
        var response = new MockHttpServletResponse();
        response.setStatus(status);
        var context = new ServerRequestObservationContext(new MockHttpServletRequest("GET", path), response);
        context.setPathPattern(pattern);
        Observation.createNotStarted("http.server.requests", () -> context, registry)
                .observationConvention(new HttpObservationConfiguration.ServerConvention()).start().stop();
    }

    @RestController
    static class Endpoints {
        private final SimpleMeterRegistry meters;

        Endpoints(SimpleMeterRegistry meters) {
            this.meters = meters;
        }

        @GetMapping({"/actuator", "/actuator/health", "/actuator/prometheus", "/monitoring", "/monitoring/health", "/monitoring/prometheus"})
        String management() {
            assertThat(meters.getMeters()).isEmpty();
            return "ok";
        }

        @GetMapping({"/actuator-other", "/monitoring-other"})
        String business() {
            return "ok";
        }
    }
}
