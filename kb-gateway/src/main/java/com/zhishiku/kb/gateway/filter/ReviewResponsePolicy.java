package com.zhishiku.kb.gateway.filter;

import com.zhishiku.kb.common.security.DocumentCommentEndpoints;
import com.zhishiku.kb.common.security.DocumentReviewEndpoints;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** 候选资料、审核与版本评论响应在提前拒绝与下游提交前均禁止缓存，不处理响应体。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ReviewResponsePolicy implements WebFilter {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (DocumentReviewEndpoints.matches(path) || DocumentCommentEndpoints.matches(path)
                || path.equals("/api/documents") || path.startsWith("/api/documents/")
                || path.startsWith("/api/admin/document-tags")
                || path.matches("/api/admin/spaces/[^/]+/categories(?:/[^/]+)?")
                || path.matches("/api/admin/documents/[^/]+/(?:relocation-context|space)")) {
            exchange.getResponse().getHeaders().setCacheControl("no-store");
            exchange.getResponse().beforeCommit(() -> {
                exchange.getResponse().getHeaders().setCacheControl("no-store");
                return Mono.empty();
            });
        }
        return chain.filter(exchange);
    }
}
