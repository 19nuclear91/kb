package com.zhishiku.kb.gateway.filter;

import com.zhishiku.kb.common.security.NotificationEndpoints;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** 本人通知在提前拒绝与下游提交前均禁止缓存，不读取或改写响应体。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class NotificationResponsePolicy implements WebFilter {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (NotificationEndpoints.matches(exchange.getRequest().getPath().value())) {
            exchange.getResponse().getHeaders().setCacheControl("no-store");
            exchange.getResponse().beforeCommit(() -> {
                exchange.getResponse().getHeaders().setCacheControl("no-store");
                return Mono.empty();
            });
        }
        return chain.filter(exchange);
    }
}
