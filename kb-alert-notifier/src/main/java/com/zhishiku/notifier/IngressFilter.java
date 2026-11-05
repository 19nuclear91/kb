package com.zhishiku.notifier;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

final class IngressFilter extends OncePerRequestFilter {
    static final String PATH = "/internal/alerts/dingtalk";
    private final NotifierSettings settings;
    private final DeliveryService service;
    private final NotifierMetrics metrics;

    IngressFilter(NotifierSettings settings, DeliveryService service, NotifierMetrics metrics) {
        this.settings = settings;
        this.service = service;
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (request.getLocalPort() == settings.managementPort && request.getQueryString() == null
                && "GET".equals(request.getMethod()) && ("/actuator/health".equals(request.getRequestURI())
                || "/actuator/prometheus".equals(request.getRequestURI()))) {
            chain.doFilter(request, response);
            return;
        }
        int status = 503;
        try {
            if (request.getLocalPort() != settings.port || !PATH.equals(request.getRequestURI())
                    || request.getQueryString() != null) {
                throw new Rejection(404, Rejection.Reason.route);
            }
            // 认证先于媒体类型、正文长度判断以及任何正文读取。
            if (Collections.list(request.getHeaders("Authorization")).size() != 1
                    || !settings.authenticates(request.getHeader("Authorization"))) {
                throw new Rejection(401, Rejection.Reason.unauthorized);
            }
            if (!"POST".equals(request.getMethod())) {
                throw new Rejection(405, Rejection.Reason.method);
            }
            media(request);
            if (request.getContentLengthLong() > BoundedJson.REQUEST_BYTES) {
                throw new Rejection(413, Rejection.Reason.too_large);
            }
            service.deliver(BoundedJson.read(request.getInputStream(), BoundedJson.REQUEST_BYTES));
            status = 204;
        } catch (Rejection rejection) {
            status = rejection.status();
            metrics.reject(rejection.reason());
        } catch (IOException exception) {
            status = 400;
            metrics.reject(Rejection.Reason.invalid);
        } catch (Exception exception) {
            metrics.reject(Rejection.Reason.internal);
        } finally {
            metrics.request(status);
        }
        // 不使用 sendError，避免容器错误页回显路径、异常或报文。
        response.setStatus(status);
        response.setContentLength(0);
    }

    private static void media(HttpServletRequest request) {
        if (request.getHeader("Content-Encoding") != null) {
            throw new Rejection(415, Rejection.Reason.media_type);
        }
        try {
            MediaType type = MediaType.parseMediaType(request.getContentType());
            if (!type.getType().equals("application") || !type.getSubtype().equals("json")
                    || (type.getCharset() != null && !type.getCharset().equals(StandardCharsets.UTF_8))
                    || type.getParameters().keySet().stream().anyMatch(key -> !key.equals("charset"))) {
                throw new IllegalArgumentException();
            }
        } catch (Exception exception) {
            throw new Rejection(415, Rejection.Reason.media_type);
        }
    }
}
