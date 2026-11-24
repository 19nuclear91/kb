package com.zhishiku.kb.app.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.search.model.SearchFailure;
import com.zhishiku.kb.app.telemetry.HttpObservationConfiguration;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.SearchEndpoints;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 分别调用过滤器、异常投影和观测组件，不组装 MVC。 */
class AssistedSearchProtectionTest {
    private static final String PROBE = "PRIVATE_QUERY_TERM_SELECTION_TOKEN";

    @Test
    void exactSearchScopeAndAllDispatchesRemainUncacheable() throws Exception {
        assertThat(SearchEndpoints.matches("/api/search/assisted")).isTrue();
        for (String path : List.of("/api/search/assisted-other", "/api/search/assisted/unknown", "/api/searching")) {
            assertThat(SearchEndpoints.matches(path)).isFalse();
        }
        for (String path : List.of("/api/search/assisted", "/kb/api/search/assisted", "/api/search/%61ssisted", "/error")) {
            var request = request(path);
            for (int status : List.of(200, 400, 401, 403, 404, 405, 409, 413, 429, 500, 503)) {
                var response = new MockHttpServletResponse();
                new SearchResponseFilter().doFilter(request, response, (input, output) -> {
                    var target = (HttpServletResponse) output;
                    target.reset();
                    target.setStatus(status);
                    target.setHeader("Cache-Control", "public");
                    target.addHeader("Cache-Control", "max-age=600");
                });
                assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
                assertThat(response.getStatus()).isEqualTo(status);
            }
        }
    }

    @Test
    void preControllerAndUnknownFailuresHaveFixedProjectionWithoutLogContent() throws Exception {
        var log = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        log.addAppender(captured);
        try {
            for (String path : List.of("/api/search/assisted", "/error")) {
                RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request(path)));
                for (Exception failure : List.of(IdentityException.unauthorized(PROBE), IdentityException.passwordChangeRequired(PROBE),
                        new SearchFailure(SearchFailure.Kind.ASSISTANCE_STALE), new SearchFailure(SearchFailure.Kind.ASSISTANCE_UNAVAILABLE),
                        new IllegalArgumentException(PROBE), new IllegalStateException(PROBE, new RuntimeException(PROBE)))) {
                    var response = new GlobalExceptionHandler().handle(failure);
                    assertThat(response.getStatusCode().value()).isNotEqualTo(200);
                    assertThat(new ObjectMapper().writeValueAsString(response.getBody())).doesNotContain(PROBE);
                    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
                }
            }
            assertThat(captured.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(PROBE);
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            RequestContextHolder.resetRequestAttributes();
            log.detachAppender(captured);
            captured.stop();
        }
    }

    @Test
    void observationsUseOnlyTemplateOrUnknownNotQueryBodyOrHeaders() {
        for (String pattern : List.of("/api/search/assisted", "/api/admin/search-terms/terms/{id}", "UNKNOWN")) {
            var request = request("/api/search/assisted");
            request.setQueryString("query=" + PROBE);
            request.addHeader("satoken", PROBE);
            var context = new ServerRequestObservationContext(request, new MockHttpServletResponse());
            context.setPathPattern(pattern.equals("UNKNOWN") ? null : pattern);
            var convention = new HttpObservationConfiguration.ServerConvention();
            assertThat(convention.getHighCardinalityKeyValues(context).toString()).doesNotContain(PROBE, "query=");
            assertThat(convention.getLowCardinalityKeyValues(context).toString()).doesNotContain(PROBE, "query=");
        }
    }

    private static MockHttpServletRequest request(String path) {
        var request = new MockHttpServletRequest("POST", path);
        if (path.startsWith("/kb/")) {
            request.setContextPath("/kb");
        }
        if (path.equals("/error")) {
            request.setDispatcherType(DispatcherType.ERROR);
            request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/search/assisted");
        }
        return request;
    }
}
