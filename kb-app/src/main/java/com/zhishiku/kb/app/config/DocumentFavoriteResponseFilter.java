package com.zhishiku.kb.app.config;

import com.zhishiku.kb.common.security.DocumentFavoriteEndpoints;
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

/** 先于身份拦截器设置收藏响应策略，拒绝响应也不缓存。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DocumentFavoriteResponseFilter extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !DocumentFavoriteEndpoints.matches(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        HttpServletRequest input = request;
        if (request.getRequestURI().endsWith("/favorite")
                && ("PUT".equals(request.getMethod()) || "DELETE".equals(request.getMethod()))) {
            // 在表单过滤器消费正文前记录至多一个字节，供控制器判定原正文是否非空。
            input = new ContentCachingRequestWrapper(request, 1);
        }
        chain.doFilter(input, response);
    }
}
