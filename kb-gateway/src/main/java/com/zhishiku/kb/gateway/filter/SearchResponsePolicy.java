package com.zhishiku.kb.gateway.filter;

import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** 搜索成功和提前拒绝均不可缓存；原状态、正文和 Retry-After 原样保留。 */
@Component
public class SearchResponsePolicy implements WebFilter, Ordered {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.equals("/api/search") || path.startsWith("/api/search/")) {
            exchange.getResponse().getHeaders().setCacheControl("no-store");
            exchange.getResponse().beforeCommit(() -> {
                exchange.getResponse().getHeaders().setCacheControl("no-store");
                return Mono.empty();
            });
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
