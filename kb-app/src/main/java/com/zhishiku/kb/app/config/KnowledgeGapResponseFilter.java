package com.zhishiku.kb.app.config;

import com.zhishiku.kb.common.security.KnowledgeGapEndpoints;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 先于身份拦截器设置响应策略，故障和提前拒绝同样不可缓存。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class KnowledgeGapResponseFilter extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !KnowledgeGapEndpoints.matches(PrivateApiResponse.path(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var protectedResponse = PrivateApiResponse.wrap(response);
        try {
            chain.doFilter(request, protectedResponse);
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
