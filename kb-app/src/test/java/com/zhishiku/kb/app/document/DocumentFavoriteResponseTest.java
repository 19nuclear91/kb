package com.zhishiku.kb.app.document;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zhishiku.kb.app.config.DocumentFavoriteResponseFilter;
import com.zhishiku.kb.app.document.web.DocumentFavoriteAdvice;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentFavoriteResponseTest {
    @Test
    void noStoreIsSetBeforeSuccessOrIdentityRejection() throws Exception {
        var filter = new DocumentFavoriteResponseFilter();
        for (String path : List.of("/api/documents/favorites", "/api/documents/favorites/status",
                "/api/documents/11/favorite", "/api/documents/not-a-number/favorite")) {
            var request = new MockHttpServletRequest("GET", path);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> {
                assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
                response.setStatus(401);
            });
            assertThat(response.getStatus()).isEqualTo(401);
        }
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/documents/11"), response, (req, res) -> { });
        assertThat(response.getHeader("Cache-Control")).isNull();
    }

    @Test
    void safeErrorProjectionDoesNotLogOrEchoSensitiveExceptions() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        root.addAppender(captured);
        try {
            var advice = new DocumentFavoriteAdvice();
            var errors = List.of(QueryException.invalid(), IdentityException.unauthorized("secret token"),
                    IdentityException.disabled("secret name"), ApiException.notFound("secret title"),
                    new IllegalStateException("secret sql", new RuntimeException("secret body")));
            for (int i = 0; i < errors.size(); i++) {
                var result = advice.failure(errors.get(i));
                assertThat(result.getStatusCode().value()).isEqualTo(List.of(400, 401, 403, 404, 503).get(i));
                assertThat(result.getHeaders().getCacheControl()).isEqualTo("no-store");
                assertThat(result.getBody().message()).doesNotContain("secret");
                assertThat(result.getBody().data()).isNull();
            }
            assertThat(captured.list).isEmpty();
        } finally {
            root.detachAppender(captured);
            captured.stop();
        }
    }
}
