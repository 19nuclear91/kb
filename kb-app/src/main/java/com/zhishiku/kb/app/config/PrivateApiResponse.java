package com.zhishiku.kb.app.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

/** 仅用于问题/通知私有响应；不读取正文，不提供身份或动作资格。 */
final class PrivateApiResponse {
    private PrivateApiResponse() {
    }

    static String path(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (request.getDispatcherType() == DispatcherType.ERROR
                && request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String original) {
            path = original;
        }
        String context = request.getContextPath();
        if (!context.isEmpty() && path.startsWith(context + "/")) {
            path = path.substring(context.length());
        }
        try {
            return UriUtils.decode(path, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalid) {
            return path;
        }
    }

    static HttpServletResponse wrap(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return new HttpServletResponseWrapper(response) {
            @Override
            public void setHeader(String name, String value) {
                super.setHeader(name, "Cache-Control".equalsIgnoreCase(name) ? "no-store" : value);
            }

            @Override
            public void addHeader(String name, String value) {
                if ("Cache-Control".equalsIgnoreCase(name)) {
                    super.setHeader(name, "no-store");
                } else {
                    super.addHeader(name, value);
                }
            }

            @Override
            public void reset() {
                super.reset();
                super.setHeader("Cache-Control", "no-store");
            }
        };
    }
}
