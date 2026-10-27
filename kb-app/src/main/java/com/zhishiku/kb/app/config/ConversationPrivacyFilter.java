package com.zhishiku.kb.app.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 含正文接口错误响应也禁止缓存，读取硬上限适用于分块传输。
 */
@Component
public class ConversationPrivacyFilter extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/conversations") && !path.startsWith("/internal/conversations");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        var wrapped = new HttpServletRequestWrapper(request) {
            @Override
            public ServletInputStream getInputStream() throws IOException {
                var input = request.getInputStream();
                return new ServletInputStream() {
                    private int bytes;

                    @Override
                    public int read() throws IOException {
                        int value = input.read();
                        if (value >= 0 && ++bytes > 2_000_000) throw new IOException("请求体超过上限");
                        return value;
                    }

                    @Override
                    public boolean isFinished() {
                        return input.isFinished();
                    }

                    @Override
                    public boolean isReady() {
                        return input.isReady();
                    }

                    @Override
                    public void setReadListener(ReadListener listener) {
                        input.setReadListener(listener);
                    }
                };
            }
        };
        if (request.getContentLengthLong() > 2_000_000) {
            response.setStatus(413);
            return;
        }
        chain.doFilter(wrapped, response);
    }
}

