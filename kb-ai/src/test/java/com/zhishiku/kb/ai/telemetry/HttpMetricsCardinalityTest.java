package com.zhishiku.kb.ai.telemetry;

import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HttpMetricsCardinalityTest {
    @ParameterizedTest
    @ValueSource(strings = {"/actuator", "/monitoring"})
    void realReactiveLifecycleExcludesManagementBeforeRoutingIncludingActiveMeters(String basePath) {
        var meters = new SimpleMeterRegistry();
        try {
            meters.config().meterFilter(new HttpMetricsConfiguration().managementEndpointMetricsFilter(basePath));
            var observations = ObservationRegistry.create();
            observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
            var handler = WebHttpHandlerBuilder.webHandler(exchange -> {
                String path = exchange.getRequest().getPath().pathWithinApplication().value();
                if (!path.endsWith("-other")) {
                    assertThat(meters.getMeters()).isEmpty();
                }
                ServerRequestObservationContext.findCurrent(exchange.getAttributes()).orElseThrow().setPathPattern(path);
                exchange.getResponse().setStatusCode(HttpStatus.OK);
                return Mono.empty();
            }).observationRegistry(observations).observationConvention(new HttpObservationConfiguration.ServerConvention(basePath)).build();
            for (String path : new String[]{basePath, basePath + "/health", basePath + "/prometheus"}) {
                StepVerifier.create(handler.handle(MockServerHttpRequest.get(path).build(), new MockServerHttpResponse())).verifyComplete();
                assertThat(meters.getMeters()).isEmpty();
            }
            StepVerifier.create(handler.handle(MockServerHttpRequest.get("/kb" + basePath + "/health").contextPath("/kb").build(),
                    new MockServerHttpResponse())).verifyComplete();
            assertThat(meters.getMeters()).isEmpty();
            StepVerifier.create(handler.handle(MockServerHttpRequest.get(basePath + "-other").build(), new MockServerHttpResponse())).verifyComplete();
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
        var response = new MockServerHttpResponse();
        response.setStatusCode(HttpStatusCode.valueOf(status));
        var context = new ServerRequestObservationContext(MockServerHttpRequest.get(path).build(), response, new HashMap<>());
        context.setPathPattern(pattern);
        Observation.createNotStarted("http.server.requests", () -> context, registry)
                .observationConvention(new HttpObservationConfiguration.ServerConvention()).start().stop();
    }
}
