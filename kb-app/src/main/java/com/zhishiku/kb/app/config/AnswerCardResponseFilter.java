package com.zhishiku.kb.app.config;

import com.zhishiku.kb.common.security.AnswerCardEndpoints;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** 答案卡在认证、路由和解析之前禁止缓存，未知路径同样保护。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AnswerCardResponseFilter extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !AnswerCardEndpoints.matches(PrivateApiResponse.path(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var protectedResponse = PrivateApiResponse.wrap(response);
        if (request.getContentLengthLong() > AnswerCardEndpoints.bodyLimit(PrivateApiResponse.path(request))) {
            protectedResponse.setStatus(413);
            protectedResponse.setContentType("application/json;charset=UTF-8");
            protectedResponse.getWriter().write("{\"code\":500,\"message\":\"标准答案请求过大\",\"data\":null}");
            return;
        }
        try {
            chain.doFilter(request, protectedResponse);
        } finally {
            if (!response.isCommitted()) {
                response.setHeader("Cache-Control", "no-store");
                if (response.getStatus() >= 400) {
                    response.setHeader("X-Answer-Card-Version", null);
                }
            }
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
