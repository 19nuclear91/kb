package com.zhishiku.kb.gateway.office;

import com.zhishiku.kb.common.office.OfficeProtocol;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerResponse;

import java.net.URI;
import java.nio.charset.StandardCharsets;

final class OfficePreviewExchange {
    private OfficePreviewExchange() {
    }

    static String sessionId(ServerWebExchange exchange, OfficeGatewaySettings settings) {
        if (!settings.isEnabled()) {
            throw new OfficeProxyFailure(503);
        }
        String host = exchange.getRequest().getHeaders().getFirst("Host");
        if (!URI.create(settings.getPreviewOrigin()).getRawAuthority().equalsIgnoreCase(host)) {
            throw new OfficeProxyFailure(404);
        }
        String[] path = exchange.getRequest().getURI().getRawPath().split("/", 4);
        if (path.length != 4 || !"s".equals(path[1]) || !OfficeProtocol.validSessionId(path[2])) {
            throw new OfficeProxyFailure(404);
        }
        return path[2];
    }

    static String cookie(ServerWebExchange exchange) {
        var cookies = exchange.getRequest().getCookies().get(OfficeProtocol.COOKIE_NAME);
        if (cookies == null || cookies.size() != 1 || !cookies.getFirst().getValue().matches("[A-Za-z0-9_-]{43}")) {
            throw new OfficeProxyFailure(404);
        }
        return cookies.getFirst().getValue();
    }

    static void headers(ServerWebExchange exchange, OfficeGatewaySettings settings) {
        var headers = exchange.getResponse().getHeaders();
        headers.setCacheControl("no-store");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Content-Type-Options", "nosniff");
        if (settings.isEnabled() && !settings.getApplicationOrigin().isEmpty() && !settings.getPreviewOrigin().isEmpty()) {
            headers.set("Content-Security-Policy", "default-src 'none'; script-src 'self' 'unsafe-inline' 'unsafe-eval' 'wasm-unsafe-eval'; "
                    + "style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; "
                    + "connect-src 'self' " + settings.getPreviewOrigin().replaceFirst("^https:", "wss:") + "; "
                    + "frame-src 'self' blob:; worker-src 'self' blob:; media-src 'self' blob:; "
                    + "object-src 'none'; base-uri 'self'; form-action 'none'; frame-ancestors "
                    + settings.getApplicationOrigin() + " " + settings.getPreviewOrigin());
        }
    }

    static Mono<byte[]> readBody(ServerWebExchange exchange, int maxBytes) {
        return DataBufferUtils.join(exchange.getRequest().getBody(), maxBytes).map(buffer -> {
            try {
                byte[] body = new byte[buffer.readableByteCount()];
                buffer.read(body);
                return body;
            } finally {
                DataBufferUtils.release(buffer);
            }
        }).defaultIfEmpty(new byte[0]);
    }

    static Mono<Void> error(ServerWebExchange exchange, Throwable error) {
        if (exchange.getResponse().isCommitted()) {
            // 已开始的资源流只能断开，避免框架错误日志携带供应商签名查询串。
            try {
                Object nativeResponse = ServerHttpResponseDecorator.getNativeResponse(exchange.getResponse());
                if (nativeResponse instanceof HttpServerResponse response) {
                    response.withConnection(connection -> connection.dispose());
                    return Mono.empty();
                }
            } catch (RuntimeException noNativeTransport) {
                // 内存响应替身没有底层连接，仅结束已提交的响应。
            }
            return exchange.getResponse().setComplete();
        }
        int status = error instanceof OfficeProxyFailure failure ? failure.status() : 503;
        exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(status));
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        exchange.getResponse().getHeaders().remove("Set-Cookie");
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes = "{\"message\":\"Office 预览请求未完成\"}".getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }
}
