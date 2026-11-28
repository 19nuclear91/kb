package com.zhishiku.kb.app.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** 雷达路径的身份拒绝与异常响应同样禁止缓存。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HealthResponseFilter extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PrivateApiResponse.path(request).matches("/api/admin/spaces/[^/]+/health-radar/?");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, PrivateApiResponse.wrap(response));
        } finally {
            response.setHeader("Cache-Control", "no-store");
        }
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterNestedErrorDispatch(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        doFilterInternal(request, response, chain);
    }
}
