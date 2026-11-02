package com.zhishiku.kb.gateway.telemetry;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.HttpHandlerDecoratorFactory;
import reactor.core.publisher.Mono;

import java.util.List;

@Configuration(proxyBeanMethods = false)
public class TraceBoundaryConfiguration {
    private static final List<String> HEADERS = List.of("traceparent", "tracestate", "baggage");

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public HttpHandlerDecoratorFactory traceBoundary() {
        return delegate -> (request, response) -> {
            var sanitized = request.mutate().headers(headers -> HEADERS.forEach(headers::remove)).build();
            response.beforeCommit(() -> {
                HEADERS.forEach(response.getHeaders()::remove);
                return Mono.empty();
            });
            return delegate.handle(sanitized, response);
        };
    }
}
