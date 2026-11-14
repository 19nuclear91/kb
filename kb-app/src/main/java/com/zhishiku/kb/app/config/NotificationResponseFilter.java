package com.zhishiku.kb.app.config;

import com.zhishiku.kb.common.security.NotificationEndpoints;
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

/** 先于身份拦截器设置通知响应策略，成功、拒绝与故障均不缓存。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class NotificationResponseFilter extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !NotificationEndpoints.matches(PrivateApiResponse.path(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var protectedResponse = PrivateApiResponse.wrap(response);
        HttpServletRequest input = request;
        if ("PUT".equals(request.getMethod())) {
            // 在表单过滤器消费正文前记录至多一个字节，供控制器判定原正文是否非空。
            input = new ContentCachingRequestWrapper(request, 1);
        }
        try {
            chain.doFilter(input, protectedResponse);
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
