package com.zhishiku.kb.gateway.filter;

import com.zhishiku.kb.common.security.KnowledgeGapEndpoints;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/** 提前身份/正文拒绝与下游响应均 no-store；不聚合正文，不影响 AI/SSE。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class KnowledgeGapResponsePolicy implements WebFilter {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        try {
            path = UriUtils.decode(path, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalid) {
            // 非法编码仍由 HTTP/业务入口拒绝，不输出原地址。
        }
        if (KnowledgeGapEndpoints.matches(path)) {
            exchange.getResponse().getHeaders().setCacheControl("no-store");
            exchange.getResponse().beforeCommit(() -> {
                exchange.getResponse().getHeaders().setCacheControl("no-store");
                return Mono.empty();
            });
        }
        return chain.filter(exchange);
    }
}
