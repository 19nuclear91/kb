package com.zhishiku.kb.app.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.OncePerRequestFilter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Servlet 内存请求/响应及异常投影，不启动 app 或外部身份组件。 */
class KnowledgeGapResponsePolicyTest {
    @Test
    void everySuccessEarlyDenialAndDownstreamOverrideKeepsNoStore() throws Exception {
        for (String path : List.of("/api/knowledge-gaps", "/api/knowledge-gaps/1/unknown", "/api/admin/knowledge-gaps/1/assign",
                "/kb/api/knowledge-gaps/1", "/api/knowledge%2Dgaps/1", "/api/notifications", "/api/notifications/unread-count")) {
            for (int status : List.of(200, 201, 400, 401, 403, 404, 409, 413, 503)) {
                var request = new MockHttpServletRequest("GET", path);
                if (path.startsWith("/kb/")) {
                    request.setContextPath("/kb");
                }
                var response = new MockHttpServletResponse();
                OncePerRequestFilter filter = path.contains("notifications") ? new NotificationResponseFilter() : new KnowledgeGapResponseFilter();
                filter.doFilter(request, response, (input, output) -> {
                    var target = (HttpServletResponse) output;
                    assertThat(target.getHeader("Cache-Control")).isEqualTo("no-store");
                    target.reset();
                    target.setStatus(status);
                    target.setHeader("cache-control", "public");
                    target.addHeader("Cache-Control", "max-age=600");
                    target.setContentType("application/json");
                    target.getWriter().write("{\"code\":500,\"data\":null}");
                    target.flushBuffer();
                });
                assertThat(response.getStatus()).isEqualTo(status);
                assertThat(response.getHeaders("Cache-Control")).containsExactly("no-store");
                assertThat(response.getContentType()).startsWith("application/json");
                assertThat(response.getContentAsString()).isEqualTo("{\"code\":500,\"data\":null}");
            }
        }
    }

    @Test
    void errorDispatchAndEncodedExceptionUseFixedProjectionInsteadOfRawErrorOrHtml() throws Exception {
        var request = new MockHttpServletRequest("POST", "/error");
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/kb/api/knowledge%2Dgaps/1/notes");
        request.setContextPath("/kb");
        var response = new MockHttpServletResponse();
        new KnowledgeGapResponseFilter().doFilter(request, response, (input, output) -> {
            ((HttpServletResponse) output).setHeader("Cache-Control", "public");
        });
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            var error = new GlobalExceptionHandler().handleOther(new IllegalStateException("secret description sql satoken"));
            assertThat(error.getStatusCode().value()).isEqualTo(503);
            assertThat(error.getHeaders().getCacheControl()).isEqualTo("no-store");
            assertThat(error.getBody().data()).isNull();
            assertThat(error.getBody().message()).doesNotContain("secret", "description", "sql", "satoken", "html");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void adjacentAiAndSimilarNamespacesAreUntouched() throws Exception {
        for (String path : List.of("/api/ai/chat", "/api/knowledge-gapsx", "/api/admin/knowledge-gapsx", "/knowledge-gaps")) {
            var response = new MockHttpServletResponse();
            new KnowledgeGapResponseFilter().doFilter(new MockHttpServletRequest("GET", path), response, (input, output) -> {
                ((HttpServletResponse) output).setHeader("Cache-Control", "public");
            });
            assertThat(response.getHeader("Cache-Control")).isEqualTo("public");
        }
    }
}
