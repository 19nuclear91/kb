package com.zhishiku.kb.gateway.config;

import com.zhishiku.kb.gateway.filter.ConversationResponsePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.reactive.DefaultCorsProcessor;
import reactor.core.publisher.Mono;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationRouteTest {
    @Test
    void runtimePredicatesKeepInternalPrivateAndCorsExposesBothIdentifiers() {
        new ApplicationContextRunner().withPropertyValues("spring.profiles.active=prod", "KB_APP_BASE_URL=http://app"
                        , "KB_AI_BASE_URL=http://ai",
                        "KB_CORS_ALLOWED_ORIGINS=https://kb.example.com", "KB_REDIS_HOST=redis")
                .withInitializer(new ConfigDataApplicationContextInitializer()).run(context -> {
                    var binder = Binder.get(context.getEnvironment());
                    var routes = binder.bind("spring.cloud.gateway", GatewayProperties.class).get().getRoutes();
                    var factory = new PathRoutePredicateFactory();
                    for (var route : routes) {
                        var predicate =
                                route.getPredicates().stream().filter(value -> value.getName().equals("Path")).findFirst().orElseThrow();
                        var config =
                                new PathRoutePredicateFactory.Config().setPatterns(new ArrayList<>(predicate.getArgs().values()));
                        var applied = factory.apply(config);
                        assertThat(applied.test(MockServerWebExchange.from(MockServerHttpRequest.post("/internal" +
                                "/conversations/finish")))).isFalse();
                        if (route.getId().equals("kb-app")) {
                            assertThat(applied.test(MockServerWebExchange.from(MockServerHttpRequest.get("/api" +
                                    "/conversations/9")))).isTrue();
                        } else if (route.getId().equals("kb-ai-stream")) {
                            assertThat(applied.test(MockServerWebExchange.from(MockServerHttpRequest.post("/api/ai" +
                                    "/conversations/9/turns")))).isTrue();
                            assertThat(applied.test(MockServerWebExchange.from(MockServerHttpRequest.post("/api/ai" +
                                    "/chat")))).isTrue();
                        }
                    }
                    var cors =
                            binder.bind("spring.cloud.gateway.globalcors", GlobalCorsProperties.class).get().getCorsConfigurations().get("/**");
                    var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("http://gateway/api" +
                            "/conversations/9").header("Origin", "https://kb.example.com"));
                    assertThat(new DefaultCorsProcessor().process(cors, exchange)).isTrue();
                    assertThat(exchange.getResponse().getHeaders().getAccessControlExposeHeaders()).contains("X" +
                            "-Conversation-Id", "X-Turn-Id");
                    new ConversationResponsePolicy().filter(exchange, ignored -> Mono.empty()).block();
                    assertThat(exchange.getResponse().getHeaders().getCacheControl()).isEqualTo("no-store");
                });
    }
}
