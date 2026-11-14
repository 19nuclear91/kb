package com.zhishiku.kb.gateway.filter;

import com.zhishiku.kb.common.security.NotificationEndpoints;
import com.zhishiku.kb.common.security.NotificationContract;
import com.zhishiku.kb.common.api.ApiException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/** 本人通知在提前拒绝与下游提交前均禁止缓存，不读取或改写响应体。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class NotificationResponsePolicy implements WebFilter {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        try {
            path = UriUtils.decode(path, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalid) {
            // 编码拒绝不回显地址。
        }
        if (NotificationEndpoints.matches(path)) {
            exchange.getResponse().getHeaders().setCacheControl("no-store");
            exchange.getResponse().beforeCommit(() -> {
                exchange.getResponse().getHeaders().setCacheControl("no-store");
                return Mono.empty();
            });
            if (exchange.getRequest().getMethod() == HttpMethod.GET
                    && (path.equals("/api/notifications") || path.equals("/api/notifications/unread-count"))) {
                try {
                    NotificationContract.parse(exchange.getRequest().getHeaders().get(NotificationContract.HEADER));
                } catch (ApiException invalid) {
                    exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
                    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    byte[] body = "{\"code\":500,\"message\":\"通知能力声明无效\",\"data\":null}".getBytes(StandardCharsets.UTF_8);
                    return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
                }
            }
        }
        return chain.filter(exchange);
    }
}
