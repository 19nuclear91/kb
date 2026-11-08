package com.zhishiku.kb.gateway.config;

import com.zhishiku.kb.gateway.filter.ImportResponsePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 只绑定实际配置并执行内存路由谓词，不启动网关或连接下游。 */
class ImportRouteTest {
    private static final List<String> PATHS = List.of("/api/document-imports", "/api/document-imports/capabilities",
            "/api/document-imports/resolve", "/api/document-imports/9/items/2/file");

    @Test
    void devAndProductionRouteOnlyImportNamespaceWithIndependentBudget() throws Exception {
        for (boolean dev : new boolean[]{false, true}) {
            var env = new StandardEnvironment();
            env.getPropertySources().addFirst(new MapPropertySource("stub", Map.of("KB_APP_BASE_URL", "http://app.invalid",
                    "KB_AI_BASE_URL", "http://ai.invalid", "KB_CORS_ALLOWED_ORIGINS", "https://kb.example.com")));
            var loader = new YamlPropertySourceLoader();
            for (var source : loader.load("base", new ClassPathResource("application.yml"))) {
                env.getPropertySources().addLast(source);
            }
            if (dev) {
                for (var source : loader.load("dev", new ClassPathResource("application-dev.yml"))) {
                    env.getPropertySources().addAfter("stub", source);
                }
            }
            var routes = Binder.get(env).bind("spring.cloud.gateway", GatewayProperties.class).get().getRoutes();
            for (String path : PATHS) {
                List<String> matches = new ArrayList<>();
                for (var route : routes) {
                    var definition = route.getPredicates().stream().filter(p -> p.getName().equals("Path")).findFirst().orElseThrow();
                    var predicate = new PathRoutePredicateFactory().apply(new PathRoutePredicateFactory.Config()
                            .setPatterns(new ArrayList<>(definition.getArgs().values())));
                    if (predicate.test(MockServerWebExchange.from(MockServerHttpRequest.post(path)))) {
                        matches.add(route.getId());
                        assertThat(route.getUri().toString()).isEqualTo("http://app.invalid");
                        assertThat(route.getMetadata().get("response-timeout").toString()).isEqualTo("150000");
                    }
                    if (route.getId().equals("kb-document-imports")) {
                        for (String excluded : List.of("/api/document-importsx", "/api/documents/1", "/api/ai/chat", "/internal/auth", "/actuator/health")) {
                            assertThat(predicate.test(MockServerWebExchange.from(MockServerHttpRequest.post(excluded)))).isFalse();
                        }
                    }
                    if (route.getId().equals("kb-ai-stream")) {
                        assertThat(route.getMetadata().get("response-timeout").toString()).isEqualTo("-1");
                    }
                }
                assertThat(matches).containsExactly("kb-document-imports");
            }
        }
    }

    @Test
    void earlyAndDownstreamStatusesKeepNoStoreAndBody() {
        for (String path : PATHS) {
            for (HttpStatus status : List.of(HttpStatus.OK, HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN,
                    HttpStatus.CONFLICT, HttpStatus.PAYLOAD_TOO_LARGE, HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    HttpStatus.TOO_MANY_REQUESTS, HttpStatus.SERVICE_UNAVAILABLE)) {
                var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(path));
                new ImportResponsePolicy().filter(exchange, current -> {
                    current.getResponse().setStatusCode(status);
                    current.getResponse().getHeaders().setCacheControl("public");
                    return current.getResponse().writeWith(Mono.just(current.getResponse().bufferFactory()
                            .wrap("fixed-body".getBytes(StandardCharsets.UTF_8))));
                }).block();
                assertThat(exchange.getResponse().getStatusCode()).isEqualTo(status);
                assertThat(exchange.getResponse().getHeaders().getCacheControl()).isEqualTo("no-store");
                assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo("fixed-body");
            }
        }
    }
}
