package com.zhishiku.kb.gateway.filter;

import com.zhishiku.kb.common.security.AnswerCardEndpoints;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.nio.charset.StandardCharsets;

/** 在路由与认证前有界读取卡片请求；所有结果no-store，不补成功协议声明。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AnswerCardResponsePolicy implements WebFilter {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        try {
            path = UriUtils.decode(path, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // 保留原路径交给路由拒绝，不记录输入。
        }
        if (!AnswerCardEndpoints.matches(path)) {
            return chain.filter(exchange);
        }
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().setCacheControl("no-store");
            if (exchange.getResponse().getStatusCode() != null && exchange.getResponse().getStatusCode().isError()) {
                exchange.getResponse().getHeaders().remove("X-Answer-Card-Version");
            }
            return Mono.empty();
        });
        int limit = AnswerCardEndpoints.bodyLimit(path);
        if (exchange.getRequest().getHeaders().getContentLength() > limit) {
            return tooLarge(exchange);
        }
        return DataBufferUtils.join(exchange.getRequest().getBody(), limit).map(buffer -> {
            try {
                byte[] bytes = new byte[buffer.readableByteCount()];
                buffer.read(bytes);
                return bytes;
            } finally {
                DataBufferUtils.release(buffer);
            }
        }).defaultIfEmpty(new byte[0]).flatMap(bytes -> {
            var request = new ServerHttpRequestDecorator(exchange.getRequest()) {
                @Override
                public Flux<DataBuffer> getBody() {
                    return Flux.defer(() -> Flux.just(exchange.getResponse().bufferFactory().wrap(bytes)));
                }
            };
            return chain.filter(exchange.mutate().request(request).build());
        }).onErrorResume(DataBufferLimitException.class, ignored -> tooLarge(exchange));
    }

    private Mono<Void> tooLarge(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes = "{\"code\":500,\"message\":\"标准答案请求过大\",\"data\":null}".getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }
}
