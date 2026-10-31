package com.zhishiku.kb.gateway.config;

import com.zhishiku.kb.gateway.filter.SearchResponsePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.reactive.DefaultCorsProcessor;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SearchRouteTest {
    @Test
    void actualRouteObjectsMatchSearchAndKeepAiTimeoutIndependent() throws Exception {
        for (boolean dev : new boolean[]{false, true}) {
            var env = new StandardEnvironment();
            env.getPropertySources().addFirst(new MapPropertySource("stub", Map.of(
                    "KB_APP_BASE_URL", "http://app.invalid", "KB_AI_BASE_URL", "http://ai.invalid",
                    "KB_CORS_ALLOWED_ORIGINS", "https://kb.example.com")));
            var loader = new YamlPropertySourceLoader();
            for (var source : loader.load("base", new ClassPathResource("application.yml"))) {
                env.getPropertySources().addLast(source);
            }
            if (dev) {
                for (var source : loader.load("dev", new ClassPathResource("application-dev.yml"))) {
                    env.getPropertySources().addAfter("stub", source);
                }
            }
            var binder = Binder.get(env);
            var definitions = binder.bind("spring.cloud.gateway", GatewayProperties.class).get().getRoutes();
            var search = MockServerWebExchange.from(MockServerHttpRequest.post("/api/search"));
            int matches = 0;
            for (var definition : definitions) {
                var path = definition.getPredicates().stream().filter(p -> p.getName().equals("Path")).findFirst().orElseThrow();
                var predicate = new PathRoutePredicateFactory().apply(new PathRoutePredicateFactory.Config()
                        .setPatterns(new ArrayList<>(path.getArgs().values())));
                var route = Route.async().id(definition.getId()).uri(definition.getUri())
                        .metadata(definition.getMetadata()).predicate(predicate).build();
                if (Boolean.TRUE.equals(Mono.from(route.getPredicate().apply(search)).block())) {
                    matches++;
                    assertThat(route.getId()).isEqualTo("kb-search");
                    assertThat(route.getMetadata().get("response-timeout").toString()).isEqualTo("10000");
                }
                if (route.getId().equals("kb-ai-stream")) {
                    assertThat(Mono.from(route.getPredicate().apply(MockServerWebExchange.from(MockServerHttpRequest.post("/api/ai/chat")))).block()).isTrue();
                    assertThat(route.getMetadata().get("response-timeout").toString()).isEqualTo("-1");
                }
            }
            assertThat(matches).isOne();
            var cors = binder.bind("spring.cloud.gateway.globalcors", GlobalCorsProperties.class).get().getCorsConfigurations().get("/**");
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("http://gateway/api/search").header("Origin", "https://kb.example.com"));
            assertThat(new DefaultCorsProcessor().process(cors, exchange)).isTrue();
            assertThat(exchange.getResponse().getHeaders().getAccessControlExposeHeaders()).contains("Retry-After", "X-Turn-Id");
        }
    }

    @Test
    void downstreamCapacityStatusBodyAndRetryAfterPassThroughUnchanged() {
        for (var status : new HttpStatus[]{HttpStatus.TOO_MANY_REQUESTS, HttpStatus.SERVICE_UNAVAILABLE}) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/search"));
            new SearchResponsePolicy().filter(exchange, current -> {
                current.getResponse().setStatusCode(status);
                current.getResponse().getHeaders().set("Retry-After", "2");
                current.getResponse().getHeaders().setCacheControl("public");
                current.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                return current.getResponse().writeWith(Mono.just(current.getResponse().bufferFactory()
                        .wrap("{\"code\":500}".getBytes(StandardCharsets.UTF_8))));
            }).block();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(status);
            assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("2");
            assertThat(exchange.getResponse().getHeaders().getCacheControl()).isEqualTo("no-store");
            assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo("{\"code\":500}");
        }
    }
}
