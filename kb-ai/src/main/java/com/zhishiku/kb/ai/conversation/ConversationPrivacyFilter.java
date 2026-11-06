package com.zhishiku.kb.ai.conversation;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 新接口的成功与错误响应都禁止缓存；未知大请求直接拒绝。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ConversationPrivacyFilter implements WebFilter {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (!path.equals("/api/ai/chat") && !path.startsWith("/api/ai/conversations") && !path.startsWith("/internal/conversations")) {
            return chain.filter(exchange);
        }
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        if (exchange.getRequest().getHeaders().getContentLength() > 262144) {
            exchange.getResponse().setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }
}

