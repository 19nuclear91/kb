package com.zhishiku.kb.app.telemetry;

import io.opentelemetry.api.common.AttributeKey;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.filter.ServerHttpObservationFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class HttpObservationPrivacyTest {
    @Test
    void exportedServerAndClientSpansContainOnlySafeRoutingAttributes() throws Exception {
        try (var tracing = new InMemoryTracing()) {
            var configuration = new HttpObservationConfiguration();
            var mvc = MockMvcBuilders.standaloneSetup(new Endpoints()).addFilters(new ServerHttpObservationFilter(
                    tracing.registry, configuration.serverRequestObservationConvention("/actuator"))).build();
            mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"secret-query\"}")
                    .header("satoken", "secret-token"));
            mvc.perform(get("/api/auth/sso/wecom/callback?code=secret-code&state=secret-state"));
            assertThat(tracing.exporter.getFinishedSpanItems()).hasSize(2).allSatisfy(span -> {
                assertThat(span.getAttributes().get(AttributeKey.stringKey("http.route"))).isNotBlank();
                assertThat(span.getAttributes().asMap().keySet()).allMatch(key -> java.util.Set.of("http.route", "uri", "method", "status", "outcome", "exception").contains(key.getKey()));
                assertThat(span.getAttributes().toString()).doesNotContain("secret-", "http.url", "http.request.header", "?");
            });
            RestClient.builder().observationRegistry(tracing.registry).observationConvention(configuration.clientRequestObservationConvention())
                    .requestInterceptor((request, body, execution) -> new MockClientHttpResponse(new byte[0], HttpStatus.OK))
                    .build().get().uri("http://internal.invalid/internal/conversations/42?token=secret-token").retrieve().toBodilessEntity();
            var client = tracing.exporter.getFinishedSpanItems().getLast();
            assertThat(client.getAttributes().get(AttributeKey.stringKey("client.name"))).isEqualTo("internal-ai");
            assertThat(client.getAttributes().toString()).doesNotContain("internal.invalid", "secret-", "42", "http.url", "uri=");
        }
    }

    @RestController
    static class Endpoints {
        @PostMapping("/api/search")
        String search() {
            return "ok";
        }

        @GetMapping("/api/auth/sso/wecom/callback")
        String callback() {
            return "ok";
        }
    }
}
