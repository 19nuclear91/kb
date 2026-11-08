package com.zhishiku.kb.gateway.filter;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** 导入历史、回执与提前拒绝统一禁止缓存，不改写状态或响应体。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ImportResponsePolicy implements WebFilter {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.equals("/api/document-imports") || path.startsWith("/api/document-imports/")) {
            exchange.getResponse().getHeaders().setCacheControl("no-store");
            exchange.getResponse().beforeCommit(() -> {
                exchange.getResponse().getHeaders().setCacheControl("no-store");
                return Mono.empty();
            });
        }
        return chain.filter(exchange);
    }
}
