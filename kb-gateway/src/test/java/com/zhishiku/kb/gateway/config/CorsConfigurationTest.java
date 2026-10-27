package com.zhishiku.kb.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.reactive.DefaultCorsProcessor;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigurationTest {
    @Test
    void patchPreflightUsesConfiguredOriginsMethodsAndHeaders() {
        new ApplicationContextRunner().withPropertyValues("spring.profiles.active=prod",
                        "KB_CORS_ALLOWED_ORIGINS=https://kb.example.com",
                        "KB_APP_BASE_URL=http://app:8081", "KB_AI_BASE_URL=http://ai:8082",
                        "KB_REDIS_HOST=redis")
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> {
                    new ProductionEnvironmentCheck().postProcessEnvironment(context.getEnvironment(),
                            new SpringApplication());
                    var properties = Binder.get(context.getEnvironment())
                            .bind("spring.cloud.gateway.globalcors", GlobalCorsProperties.class).get();
                    var config = properties.getCorsConfigurations().get("/**");
                    var valid = exchange("https://kb.example.com");
                    assertThat(new DefaultCorsProcessor().process(config, valid)).isTrue();
                    assertThat(valid.getResponse().getHeaders().getAccessControlAllowMethods())
                            .contains(HttpMethod.PATCH);
                    assertThat(valid.getResponse().getHeaders().getAccessControlAllowHeaders().stream()
                            .map(value -> value.toLowerCase(Locale.ROOT)).toList())
                            .contains("satoken", "x-request-id", "content-type");
                    var invalid = exchange("https://untrusted.example.com");
                    assertThat(new DefaultCorsProcessor().process(config, invalid)).isFalse();
                });
    }

    private MockServerWebExchange exchange(String origin) {
        return MockServerWebExchange.from(MockServerHttpRequest.options("http://gateway/api/admin/spaces/1")
                .header("Origin", origin).header("Access-Control-Request-Method", "PATCH")
                .header("Access-Control-Request-Headers", "satoken,x-request-id,content-type").build());
    }
}
