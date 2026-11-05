package com.zhishiku.kb.gateway.office;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.common.office.OfficeProtocol;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Component
public class OfficeViewerFilter implements WebFilter, Ordered {
    private final OfficeGatewaySettings settings;
    private final OfficeAuthorizationClient authorization;
    private final ObjectMapper json;
    private final byte[] shell;

    public OfficeViewerFilter(OfficeGatewaySettings settings, OfficeAuthorizationClient authorization, ObjectMapper json) {
        this.settings = settings;
        this.authorization = authorization;
        this.json = json;
        try (var input = new ClassPathResource("office-preview/shell.js").getInputStream()) {
            shell = input.readAllBytes();
        } catch (Exception missing) {
            throw new IllegalStateException("Office 查看器资源缺失");
        }
    }

    @Override
    public int getOrder() {
        return -80;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getRawPath();
        if (!path.startsWith("/s/")) {
            if (path.startsWith("/api/office-preview/") || path.endsWith("/preview-info") || path.endsWith("/office-preview/sessions")) {
                exchange.getResponse().getHeaders().setCacheControl("no-store");
                exchange.getResponse().getHeaders().set("Referrer-Policy", "no-referrer");
            }
            if (previewHost(exchange)) {
                return OfficePreviewExchange.error(exchange, new OfficeProxyFailure(404));
            }
            return chain.filter(exchange);
        }
        OfficePreviewExchange.headers(exchange, settings);
        return Mono.defer(() -> {
            String id = OfficePreviewExchange.sessionId(exchange, settings);
            String operation = path.substring(("/s/" + id + "/").length());
            if (!Set.of("bootstrap", "view", "shell.js", "configuration").contains(operation)) {
                return chain.filter(exchange);
            }
            if (exchange.getRequest().getURI().getRawQuery() != null) {
                return Mono.error(new OfficeProxyFailure(400));
            }
            if (operation.equals("bootstrap")) {
                return bootstrap(exchange, id);
            }
            if (exchange.getRequest().getMethod() != HttpMethod.GET) {
                return Mono.error(new OfficeProxyFailure(404));
            }
            String cookie = OfficePreviewExchange.cookie(exchange);
            if (operation.equals("configuration")) {
                return authorization.call("configuration", Map.of("sessionId", id, "cookie", cookie)).flatMap(config -> {
                    try {
                        return write(exchange, MediaType.APPLICATION_JSON, json.writeValueAsBytes(config));
                    } catch (Exception invalid) {
                        return Mono.error(new OfficeProxyFailure(503));
                    }
                });
            }
            return authorization.authorize(id, cookie).flatMap(grant -> operation.equals("shell.js")
                    ? write(exchange, MediaType.parseMediaType("application/javascript;charset=UTF-8"), shell)
                    : write(exchange, MediaType.TEXT_HTML, html(id).getBytes(StandardCharsets.UTF_8)));
        }).onErrorResume(error -> OfficePreviewExchange.error(exchange, error));
    }

    private boolean previewHost(ServerWebExchange exchange) {
        try {
            String authority = URI.create(settings.getPreviewOrigin()).getRawAuthority();
            return authority != null && authority.equalsIgnoreCase(exchange.getRequest().getHeaders().getFirst("Host"));
        } catch (RuntimeException invalidDisabledSetting) {
            return false;
        }
    }

    private Mono<Void> bootstrap(ServerWebExchange exchange, String id) {
        String origin = exchange.getRequest().getHeaders().getOrigin();
        MediaType contentType = exchange.getRequest().getHeaders().getContentType();
        if (exchange.getRequest().getMethod() != HttpMethod.POST || !settings.getApplicationOrigin().equals(origin)
                || contentType == null || !MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(contentType)) {
            return Mono.error(new OfficeProxyFailure(404));
        }
        return OfficePreviewExchange.readBody(exchange, 2048).flatMap(bytes -> {
            Map<String, String> form = new HashMap<>();
            for (String part : new String(bytes, StandardCharsets.UTF_8).split("&")) {
                String[] pair = part.split("=", 2);
                if (pair.length != 2 || form.putIfAbsent(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(pair[1], StandardCharsets.UTF_8)) != null) {
                    return Mono.error(new OfficeProxyFailure(400));
                }
            }
            String token = form.get("launchToken");
            if (form.size() != 1 || token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
                return Mono.error(new OfficeProxyFailure(400));
            }
            return authorization.call("bootstrap", Map.of("sessionId", id, "launchToken", token, "origin", origin));
        }).flatMap(result -> {
            String cookie = result.path("cookie").asText();
            long seconds = (result.path("expiresAt").asLong() - System.currentTimeMillis()) / 1000;
            if (!cookie.matches("[A-Za-z0-9_-]{43}") || seconds <= 0 || seconds > 3600) {
                return Mono.error(new OfficeProxyFailure(503));
            }
            exchange.getResponse().addCookie(ResponseCookie.from(OfficeProtocol.COOKIE_NAME, cookie).httpOnly(true)
                    .secure(true).sameSite("Strict").path("/s/" + id + "/").maxAge(Duration.ofSeconds(seconds)).build());
            exchange.getResponse().setStatusCode(HttpStatus.SEE_OTHER);
            exchange.getResponse().getHeaders().setLocation(URI.create("/s/" + id + "/view"));
            return exchange.getResponse().setComplete();
        });
    }

    private String html(String id) {
        // Origin 已由配置校验限制为无路径、查询及用户信息的 HTTPS authority。
        return "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"referrer\" content=\"no-referrer\">"
                + "<title>Office 只读预览</title><style>html,body,#viewer{margin:0;width:100%;height:100%;overflow:hidden}"
                + "#status{font:16px sans-serif;padding:24px}</style><body data-session=\"" + id
                + "\" data-parent=\"" + settings.getApplicationOrigin() + "\"><div id=\"status\">正在打开文档…</div>"
                + "<div id=\"viewer\"></div><script src=\"./shell.js\" defer></script></body></html>";
    }

    private static Mono<Void> write(ServerWebExchange exchange, MediaType type, byte[] bytes) {
        exchange.getResponse().getHeaders().setContentType(type);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }
}
