package com.zhishiku.kb.gateway.config;

import com.zhishiku.kb.common.security.AuthenticationEndpoints;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 实际 YAML 绑定与内存谓词，不启动网关、客户端或 Redis。 */
class KnowledgeGapRouteTest {
    @Test
    void rootsSubpathsAndUnsupportedMethodsReachOnlyAppInBothProfiles() throws Exception {
        for (boolean dev : new boolean[]{false, true}) {
            var routes = routes(dev);
            for (String path : List.of("/api/knowledge-gaps", "/api/knowledge-gaps/123", "/api/knowledge-gaps/123/history",
                    "/api/knowledge-gaps/123/solutions", "/api/knowledge-gaps/invalid/unknown",
                    "/api/admin/knowledge-gaps", "/api/admin/knowledge-gaps/123/assignees/search")) {
                for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH,
                        HttpMethod.DELETE, HttpMethod.OPTIONS)) {
                    var exchange = MockServerWebExchange.from(MockServerHttpRequest.method(method, URI.create(path)));
                    var matches = new ArrayList<String>();
                    for (var route : routes) {
                        var definition = route.getPredicates().stream().filter(value -> value.getName().equals("Path")).findFirst().orElseThrow();
                        var predicate = new PathRoutePredicateFactory().apply(new PathRoutePredicateFactory.Config()
                                .setPatterns(new ArrayList<>(definition.getArgs().values())));
                        if (predicate.test(exchange)) {
                            matches.add(route.getId());
                            assertThat(route.getUri().toString()).isEqualTo("http://app.invalid");
                            assertThat(route.getMetadata().get("response-timeout").toString()).isEqualTo("10000");
                            assertThat(route.getFilters()).noneMatch(filter -> filter.getName().equals("ModifyResponseBody"));
                        }
                        if (route.getId().equals("kb-ai-stream")) {
                            assertThat(route.getMetadata().get("response-timeout").toString()).isEqualTo("-1");
                        }
                    }
                    assertThat(matches).containsExactly("kb-app");
                    assertThat(AuthenticationEndpoints.isPublic(method.name(), path)).isFalse();
                }
            }
            for (String excluded : List.of("/api/knowledge-gapsx", "/knowledge-gaps", "/internal/knowledge-gaps", "/actuator/health")) {
                for (var route : routes) {
                    var definition = route.getPredicates().stream().filter(value -> value.getName().equals("Path")).findFirst().orElseThrow();
                    var predicate = new PathRoutePredicateFactory().apply(new PathRoutePredicateFactory.Config()
                            .setPatterns(new ArrayList<>(definition.getArgs().values())));
                    assertThat(predicate.test(MockServerWebExchange.from(MockServerHttpRequest.get(excluded)))).isFalse();
                }
            }
        }
    }

    private static List<RouteDefinition> routes(boolean dev) throws Exception {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("stub", Map.of(
                "KB_APP_BASE_URL", "http://app.invalid", "KB_AI_BASE_URL", "http://ai.invalid")));
        var loader = new YamlPropertySourceLoader();
        for (var source : loader.load("base", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        if (dev) {
            for (var source : loader.load("dev", new ClassPathResource("application-dev.yml"))) {
                environment.getPropertySources().addAfter("stub", source);
            }
        }
        return Binder.get(environment).bind("spring.cloud.gateway", GatewayProperties.class).get().getRoutes();
    }
}
