package com.zhishiku.kb.app.config;

import com.zhishiku.kb.common.security.DocumentCommentEndpoints;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;

/** 先于身份拦截器设置评论响应策略，拒绝与故障同样不缓存；删除请求预先记录首字节以拒绝任何请求体。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DocumentCommentResponseFilter extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !DocumentCommentEndpoints.matches(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        HttpServletRequest input = "DELETE".equals(request.getMethod()) ? new ContentCachingRequestWrapper(request, 1) : request;
        chain.doFilter(input, response);
    }
}
