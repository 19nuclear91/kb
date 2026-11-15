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

/** 绑定实际配置并执行路由谓词，不启动网关或发出网络请求。 */
class ValidityRouteTest {
    @Test
    void validityAndStewardshipUseExistingAuthenticatedAppRouteInBothProfiles() throws Exception {
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
                var predicate = new PathRoutePredicateFactory().apply(new PathRoutePredicateFactory.Config()
                        .setPatterns(new ArrayList<>(definition.getArgs().values())));
                for (String path : List.of("/api/documents/7/versions/8/applicability", "/api/documents/7/versions/8/applicability/history",
                        "/api/documents/applicability/query", "/api/documents/stewardship", "/api/documents/stewardship/count",
                        "/api/documents/7/stewardship/confirm", "/api/admin/documents/7/stewardship",
                        "/api/admin/documents/7/stewardship/candidates", "/api/admin/spaces/2/stewardship")) {
                    assertThat(predicate.test(MockServerWebExchange.from(MockServerHttpRequest.get(path)))).isEqualTo(route.getId().equals("kb-app"));
                    assertThat(authentication.includeList).anyMatch(pattern -> new AntPathMatcher().match(pattern, path));
                    assertThat(authentication.excludeList).noneMatch(pattern -> new AntPathMatcher().match(pattern, path));
                }
            }
        }
    }
}
