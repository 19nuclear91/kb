package com.zhishiku.kb.gateway.office;

import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
public class OfficeResourceFilter implements WebFilter, Ordered {
    private final OfficeResourceProxy proxy;

    public OfficeResourceFilter(OfficeResourceProxy proxy) {
        this.proxy = proxy;
    }

    @Override
    public int getOrder() {
        return -79;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getURI().getRawPath().startsWith("/s/")) {
            return chain.filter(exchange);
        }
        return proxy.proxy(exchange).onErrorResume(error -> OfficePreviewExchange.error(exchange, error));
    }
}
