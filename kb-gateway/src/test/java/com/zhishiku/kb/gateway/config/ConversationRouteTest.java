package com.zhishiku.kb.gateway.config;

import com.zhishiku.kb.gateway.filter.ConversationResponsePolicy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.reactive.DefaultCorsProcessor;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 加载实际 profile 并执行路径谓词，不启动网关或下游监听服务。 */
class ConversationRouteTest {
    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    void runtimePredicatesKeepInternalPrivateAndCorsExposesBothIdentifiers(String profile) {
        new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=" + profile,
                        "KB_APP_BASE_URL=http://app", "KB_AI_BASE_URL=http://ai",
                        "KB_CORS_ALLOWED_ORIGINS=https://kb.example.com", "KB_REDIS_HOST=redis")
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> {
                    var binder = Binder.get(context.getEnvironment());
                    var routes = binder.bind("spring.cloud.gateway", GatewayProperties.class).get().getRoutes();
                    assertThat(routes).extracting(RouteDefinition::getId)
                            .contains("kb-app", "kb-ai-stream");
                    for (var request : List.of(
                            MockServerHttpRequest.get("/api/conversations"),
                            MockServerHttpRequest.post("/api/conversations"),
                            MockServerHttpRequest.get("/api/conversations/9"),
                            MockServerHttpRequest.get("/api/conversations/9/turns"),
                            MockServerHttpRequest.delete("/api/conversations/9"))) {
                        assertThat(matchingRoutes(routes, request.build())).containsExactly("kb-app");
                    }
                    for (String path : List.of("/api/ai/conversations/9/turns", "/api/ai/chat")) {
                        assertThat(matchingRoutes(routes, MockServerHttpRequest.post(path).build()))
                                .containsExactly("kb-ai-stream");
                    }
                    for (String path : List.of("/internal/conversations/finish", "/internal/conversations/9",
                            "/api/conversationsx")) {
                        assertThat(matchingRoutes(routes, MockServerHttpRequest.post(path).build())).isEmpty();
                    }
                    var app = routes.stream().filter(route -> route.getId().equals("kb-app")).findFirst().orElseThrow();
                    var ai = routes.stream().filter(route -> route.getId().equals("kb-ai-stream")).findFirst().orElseThrow();
                    assertThat(app.getUri().toString()).isEqualTo("http://app");
                    assertThat(ai.getUri().toString()).isEqualTo("http://ai");
                    assertThat(app.getMetadata()).containsEntry("response-timeout", 10000);
                    assertThat(ai.getMetadata()).containsEntry("response-timeout", -1)
                            .containsEntry("connect-timeout", 2000);
                    assertThat(ai.getFilters()).anySatisfy(filter -> {
                        assertThat(filter.getName()).isEqualTo("AddResponseHeader");
                        assertThat(filter.getArgs().values()).containsExactly("X-Accel-Buffering", "no");
                    });
                    var cors = binder.bind("spring.cloud.gateway.globalcors", GlobalCorsProperties.class)
                            .get().getCorsConfigurations().get("/**");
                    String origin = profile.equals("dev") ? "http://localhost:5173" : "https://kb.example.com";
                    var exchange = MockServerWebExchange.from(MockServerHttpRequest
                            .get("http://gateway/api/conversations/9").header("Origin", origin));
                    assertThat(new DefaultCorsProcessor().process(cors, exchange)).isTrue();
                    assertThat(exchange.getResponse().getHeaders().getAccessControlExposeHeaders())
                            .contains("X-Conversation-Id", "X-Turn-Id");
                    new ConversationResponsePolicy().filter(exchange, ignored -> Mono.empty()).block();
                    assertThat(exchange.getResponse().getHeaders().getCacheControl()).isEqualTo("no-store");
                });
    }

    private static List<String> matchingRoutes(List<RouteDefinition> routes, MockServerHttpRequest request) {
        var factory = new PathRoutePredicateFactory();
        var exchange = MockServerWebExchange.from(request);
        List<String> matching = new ArrayList<>();
        for (var route : routes) {
            var predicate = route.getPredicates().stream().filter(value -> value.getName().equals("Path"))
                    .findFirst().orElseThrow();
            var config = new PathRoutePredicateFactory.Config()
                    .setPatterns(new ArrayList<>(predicate.getArgs().values()));
            if (factory.apply(config).test(exchange)) {
                matching.add(route.getId());
            }
        }
        return matching;
    }
}
