package com.zhishiku.kb.worker.telemetry;

import io.opentelemetry.api.common.AttributeKey;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.ServerHttpObservationFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class HttpObservationPrivacyTest {
    @Test
    void serverSpansDoNotRecordQueryOrHeaders() throws Exception {
        try (var tracing = new InMemoryTracing()) {
            var mvc = MockMvcBuilders.standaloneSetup(new Endpoint()).addFilters(new ServerHttpObservationFilter(
                    tracing.registry, new HttpObservationConfiguration.ServerConvention())).build();
            mvc.perform(get("/probe?code=secret-code&state=secret-state").header("satoken", "secret-token"));
            assertThat(tracing.exporter.getFinishedSpanItems()).singleElement().satisfies(span -> {
                assertThat(span.getAttributes().get(AttributeKey.stringKey("http.route"))).isEqualTo("/probe");
                assertThat(span.getAttributes().toString()).doesNotContain("secret-", "http.url", "http.request.header", "?");
            });
        }
    }

    @RestController
    static class Endpoint {
        @GetMapping("/probe")
        String probe() {
            return "ok";
        }
    }
}
