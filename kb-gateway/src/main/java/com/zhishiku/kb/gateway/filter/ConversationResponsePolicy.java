package com.zhishiku.kb.gateway.filter;

import com.zhishiku.kb.common.security.DocumentFavoriteEndpoints;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 网关提前拒绝的会话和个人收藏请求同样禁止缓存。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ConversationResponsePolicy implements WebFilter {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (path.startsWith("/api/conversations") || path.startsWith("/api/ai/conversations")
                || DocumentFavoriteEndpoints.matches(path))
            exchange.getResponse().getHeaders().setCacheControl("no-store");
        return chain.filter(exchange);
    }
}
