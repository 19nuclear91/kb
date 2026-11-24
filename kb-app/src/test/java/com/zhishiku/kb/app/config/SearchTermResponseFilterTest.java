package com.zhishiku.kb.app.config;

import com.zhishiku.kb.common.security.SearchTermEndpoints;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 仅调用过滤器与内存 Servlet 请求／响应，不组装路由或控制器。 */
class SearchTermResponseFilterTest {
    @Test
    void everyStatusAndOverrideIsProtectedBeforeDownstreamExecutionAndCommit() throws Exception {
        for (String path : List.of(SearchTermEndpoints.PREFIX, SearchTermEndpoints.PREFIX + "/terms/query",
                SearchTermEndpoints.PREFIX + "/unknown", "/kb/api/admin/search-terms/terms/9",
                "/api/admin/search%2Dterms/corrections/9")) {
            for (int status : List.of(200, 400, 401, 403, 404, 405, 409, 413, 415, 500, 503)) {
                var request = new MockHttpServletRequest("POST", path);
                if (path.startsWith("/kb/")) {
                    request.setContextPath("/kb");
                }
                var response = new MockHttpServletResponse();
                new SearchTermResponseFilter().doFilter(request, response, (input, output) -> {
                    var target = (HttpServletResponse) output;
                    assertThat(target.getHeader("Cache-Control")).isEqualTo("no-store");
                    target.reset();
                    target.setStatus(status);
                    target.setHeader("cache-control", "public");
                    target.addHeader("Cache-Control", "max-age=600");
                    target.setContentType("application/json");
                    target.getWriter().write("{}");
                    target.flushBuffer();
                });
                assertThat(response.getStatus()).isEqualTo(status);
                assertThat(response.getHeaders("Cache-Control")).containsExactly("no-store");
                assertThat(response.isCommitted()).isTrue();
            }
        }
    }

    @Test
    void asyncErrorAndNestedErrorDispatchRetainOriginalManagementBoundary() throws Exception {
        var filter = new SearchTermResponseFilter();
        for (DispatcherType type : List.of(DispatcherType.ERROR, DispatcherType.ASYNC)) {
            var request = new MockHttpServletRequest("POST", type == DispatcherType.ERROR ? "/error" : SearchTermEndpoints.PREFIX);
            request.setDispatcherType(type);
            if (type == DispatcherType.ERROR) {
                request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, SearchTermEndpoints.PREFIX + "/terms/query");
            }
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (input, output) -> ((HttpServletResponse) output).setHeader("Cache-Control", "public"));
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        }
        var request = new MockHttpServletRequest("POST", SearchTermEndpoints.PREFIX + "/terms");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (input, output) -> {
            request.setDispatcherType(DispatcherType.ERROR);
            request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, request.getRequestURI());
            request.setRequestURI("/error");
            filter.doFilter(request, response, (nestedInput, nestedOutput) -> {
                var nested = (HttpServletResponse) nestedOutput;
                nested.reset();
                nested.addHeader("Cache-Control", "public");
            });
        });
        assertThat(response.getHeaders("Cache-Control")).containsExactly("no-store");
    }

    @Test
    void failedWriteWaitingDoesNotEraseProtectionOrGenerateSuccess() {
        var response = new MockHttpServletResponse();
        var failure = new ServletException("private-term-unknown-result");
        assertThatThrownBy(() -> new SearchTermResponseFilter().doFilter(
                new MockHttpServletRequest("PUT", SearchTermEndpoints.PREFIX + "/terms/9"), response, (input, output) -> {
                    ((HttpServletResponse) output).reset();
                    throw failure;
                })).isSameAs(failure);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void similarPrefixesAndNonManagementPathsAreNotClaimed() throws Exception {
        assertThat(SearchTermEndpoints.matches(null)).isFalse();
        for (String path : List.of("/api/admin/search-terms-other", "/api/admin/search-termsx/terms", "/admin/search-terms",
                "/api/search", "/api/search/assisted", "/api/admin/document-tags/query")) {
            var response = new MockHttpServletResponse();
            new SearchTermResponseFilter().doFilter(new MockHttpServletRequest("POST", path), response,
                    (input, output) -> ((HttpServletResponse) output).setHeader("Cache-Control", "public"));
            assertThat(response.getHeader("Cache-Control")).isEqualTo("public");
        }
    }
}
