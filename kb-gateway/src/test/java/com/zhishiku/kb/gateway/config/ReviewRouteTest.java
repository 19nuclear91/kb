package com.zhishiku.kb.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
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

/** 绑定实际路由并执行谓词，不创建网关或任何网络客户端。 */
class ReviewRouteTest {
    @Test
    void bothProfilesRouteReviewRequestsToAppAndRetainAuthentication() throws Exception {
        for (String profile : List.of("prod", "dev")) {
            var environment = new StandardEnvironment();
            var loader = new YamlPropertySourceLoader();
            for (var source : loader.load("base", new ClassPathResource("application.yml"))) {
                environment.getPropertySources().addLast(source);
            }
            if (profile.equals("dev")) {
                for (var source : loader.load("dev", new ClassPathResource("application-dev.yml"))) {
                    environment.getPropertySources().addFirst(source);
                }
            }
            environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of("KB_APP_BASE_URL", "http://app", "KB_AI_BASE_URL", "http://ai")));
            var routes = Binder.get(environment).bind("spring.cloud.gateway", GatewayProperties.class).get().getRoutes();
            var authentication = new SaTokenGatewayConfig().saReactorFilter();
            for (var route : routes) {
                var definition = route.getPredicates().stream().filter(item -> item.getName().equals("Path")).findFirst().orElseThrow();
                var predicate = new PathRoutePredicateFactory().apply(new PathRoutePredicateFactory.Config().setPatterns(new ArrayList<>(definition.getArgs().values())));
                for (String path : List.of("/api/reviews", "/api/reviews/invalid", "/api/documents/7/versions/8/comments",
                        "/api/documents/7/versions/8/comments/00000000-0000-0000-0000-000000000001/replies")) {
                    assertThat(predicate.test(MockServerWebExchange.from(MockServerHttpRequest.get(path)))).isEqualTo(route.getId().equals("kb-app"));
                    assertThat(authentication.includeList).anyMatch(pattern -> new AntPathMatcher().match(pattern, path));
                    assertThat(authentication.excludeList).noneMatch(pattern -> new AntPathMatcher().match(pattern, path));
                }
                assertThat(predicate.test(MockServerWebExchange.from(MockServerHttpRequest.post("/internal/reviews")))).isFalse();
                if (route.getId().equals("kb-ai-stream")) {
                    assertThat(route.getMetadata().get("response-timeout").toString()).isEqualTo("-1");
                }
            }
        }
    }
}
