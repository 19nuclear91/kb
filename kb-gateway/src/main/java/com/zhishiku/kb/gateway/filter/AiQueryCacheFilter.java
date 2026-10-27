package com.zhishiku.kb.gateway.filter;

import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 提前覆盖网关认证拒绝和下游响应的缓存策略。
 */
@Component
public class AiQueryCacheFilter implements WebFilter, Ordered {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (exchange.getRequest().getPath().value().startsWith("/api/admin/ai/")) {
            exchange.getResponse().beforeCommit(() -> {
                exchange.getResponse().getHeaders().setCacheControl("no-store");
                return Mono.empty();
            });
        }
        return chain.filter(exchange);
    }
    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
}
