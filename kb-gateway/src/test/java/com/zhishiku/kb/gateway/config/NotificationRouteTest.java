package com.zhishiku.kb.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.util.AntPathMatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 绑定实际 prod/dev 路由并执行谓词；不创建网关、下游或网络客户端。 */
class NotificationRouteTest {
    private static final List<String> PATHS = List.of("/api/notifications", "/api/notifications/unread-count",
            "/api/notifications/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/read", "/api/notifications/invalid/read");

    @Test
    void bothProfilesRouteNotificationsToAppAndKeepAuthenticationAndAiStreamUnchanged() throws Exception {
        for (String profile : List.of("prod", "dev")) {
            var routes = routes(profile);
            var authentication = new SaTokenGatewayConfig().saReactorFilter();
            assertThat(routes).extracting(route -> route.getId()).contains("kb-app", "kb-ai-stream");
            for (var route : routes) {
                var definition = route.getPredicates().stream().filter(item -> item.getName().equals("Path")).findFirst().orElseThrow();
                var predicate = new PathRoutePredicateFactory().apply(
                        new PathRoutePredicateFactory.Config().setPatterns(new ArrayList<>(definition.getArgs().values())));
                for (String path : PATHS) {
                    for (var request : List.of(MockServerHttpRequest.get(path), MockServerHttpRequest.put(path))) {
                        assertThat(predicate.test(MockServerWebExchange.from(request))).as("%s %s %s", profile, route.getId(), path)
                                .isEqualTo(route.getId().equals("kb-app"));
                    }
                    assertThat(authentication.includeList).anyMatch(pattern -> new AntPathMatcher().match(pattern, path));
                    assertThat(authentication.excludeList).noneMatch(pattern -> new AntPathMatcher().match(pattern, path));
                }
                assertThat(predicate.test(MockServerWebExchange.from(MockServerHttpRequest.get("/internal/notifications")))).isFalse();
                assertThat(predicate.test(MockServerWebExchange.from(MockServerHttpRequest.get("/api/notificationsx")))).isFalse();
                if (route.getId().equals("kb-ai-stream")) assertThat(route.getMetadata().get("response-timeout").toString()).isEqualTo("-1");
            }
        }
    }

    private static List<RouteDefinition> routes(String profile) throws Exception {
        var environment = new StandardEnvironment();
        var loader = new YamlPropertySourceLoader();
        for (var source : loader.load("base", new ClassPathResource("application.yml"))) environment.getPropertySources().addLast(source);
        if (profile.equals("dev")) for (var source : loader.load("dev", new ClassPathResource("application-dev.yml")))
            environment.getPropertySources().addFirst(source);
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of("KB_APP_BASE_URL", "http://app",
                "KB_AI_BASE_URL", "http://ai")));
        return Binder.get(environment).bind("spring.cloud.gateway", GatewayProperties.class).get().getRoutes();
    }
}
