package com.zhishiku.kb.app.document.preview;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 在登录与参数校验之前设置响应头，失败响应同样不能缓存。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class OfficeResponseHeaders extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.startsWith("/api/office-preview/") || path.startsWith("/internal/office-preview/")
                || path.startsWith("/api/documents/") && (path.endsWith("/preview-info") || path.endsWith("/office-preview/sessions"))) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Referrer-Policy", "no-referrer");
            response.setHeader("X-Content-Type-Options", "nosniff");
        }
        chain.doFilter(request, response);
    }
}
