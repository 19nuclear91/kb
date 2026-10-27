package com.zhishiku.kb.app.config;

import com.zhishiku.kb.common.security.DocumentReviewEndpoints;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 在认证拦截器之前覆盖审核成功、拒绝和故障响应的缓存策略。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DocumentReviewResponseFilter extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(DocumentReviewEndpoints.matches(path) || path.equals("/api/documents")
                || path.startsWith("/api/documents/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }
}
