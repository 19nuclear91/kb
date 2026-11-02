package com.zhishiku.kb.gateway.telemetry;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.contextpropagation.BaggageToPropagate;
import io.micrometer.tracing.contextpropagation.ObservationAwareBaggageThreadLocalAccessor;
import io.micrometer.tracing.contextpropagation.ObservationAwareSpanThreadLocalAccessor;
import io.micrometer.tracing.otel.bridge.Slf4JBaggageEventListener;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
public class ReactiveRequestContext {
    @Bean
    public Slf4JBaggageEventListener localRequestIdMdcListener() {
        return new Slf4JBaggageEventListener(List.of("requestId"));
    }

    @Bean
    public RequestIdWebFilter requestIdWebFilter(ObservationRegistry registry, Tracer tracer,
                                               @Value("${kb.telemetry.tracing.enabled:false}") boolean tracing) {
        return new RequestIdWebFilter(registry, tracer, tracing);
    }

    public static final class RequestIdWebFilter implements WebFilter, Ordered, DisposableBean {
        private static final String LOCAL_KEY = "kb.local.requestId";
        private final boolean tracing;
        private final List<ThreadLocalAccessor<?>> accessors;

        public RequestIdWebFilter(ObservationRegistry registry, Tracer tracer, boolean tracing) {
            this.tracing = tracing;
            if (tracing) {
                accessors = List.of(new ObservationAwareSpanThreadLocalAccessor(registry, tracer),
                        new ObservationAwareBaggageThreadLocalAccessor(registry, tracer));
            } else {
                accessors = List.of(new ThreadLocalAccessor<String>() {
                    @Override
                    public Object key() {
                        return LOCAL_KEY;
                    }

                    @Override
                    public String getValue() {
                        return MDC.get("requestId");
                    }

                    @Override
                    public void setValue(String value) {
                        MDC.put("requestId", value);
                    }

                    @Override
                    public void setValue() {
                        MDC.remove("requestId");
                    }
                });
            }
            accessors.forEach(ContextRegistry.getInstance()::registerThreadLocalAccessor);
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
            String requestId = exchange.getRequest().getHeaders().getFirst("X-Request-Id");
            if (requestId == null || requestId.isBlank()) {
                return chain.filter(exchange);
            }
            if (tracing) {
                // 由 accessor 在每次信号的作用域内通过 Tracer API 恢复本地 baggage，并在离开线程时清理。
                return chain.filter(exchange).contextWrite(context -> context.put(ObservationAwareBaggageThreadLocalAccessor.KEY,
                        new BaggageToPropagate(Map.of("requestId", requestId))));
            }
            return chain.filter(exchange).contextWrite(context -> context.put(LOCAL_KEY, requestId));
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE + 2;
        }

        @Override
        public void destroy() {
            accessors.forEach(accessor -> ContextRegistry.getInstance().removeThreadLocalAccessor(accessor.key().toString()));
        }
    }
}
