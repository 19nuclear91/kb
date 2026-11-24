package com.zhishiku.kb.app.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.dev33.satoken.exception.NotLoginException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.search.model.SearchFailure;
import com.zhishiku.kb.app.search.term.TermException;
import com.zhishiku.kb.app.search.term.web.TermAdminAdvice;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.api.R;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.SearchTermEndpoints;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/** 固定错误分派与序列化单测；不运行异常解析器或 MVC 请求链。 */
class SearchTermGlobalFailureTest {
    private static final String PROBE = "PRIVATE_TERM_QUERY_CONTEXT_TOKEN";

    @Test
    void preControllerFailuresUseFixedJsonIncludingUnknownRoutesAndErrorDispatchWithoutLogLeaks() throws Exception {
        var handler = new GlobalExceptionHandler();
        var logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        Level previous = logger.getLevel();
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(captured);
        try {
            for (String path : List.of(SearchTermEndpoints.PREFIX + "/terms/query", SearchTermEndpoints.PREFIX + "/unknown",
                    "/kb/api/admin/search%2Dterms/terms", "/error")) {
                var request = new MockHttpServletRequest("POST", path);
                request.setQueryString("nameQuery=" + PROBE);
                if (path.startsWith("/kb/")) {
                    request.setContextPath("/kb");
                }
                if (path.equals("/error")) {
                    request.setDispatcherType(DispatcherType.ERROR);
                    request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, SearchTermEndpoints.PREFIX + "/terms");
                }
                RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
                List<Exception> failures = List.of(new TermException(TermException.Reason.INVALID_INPUT),
                        new NotLoginException(PROBE, NotLoginException.NOT_TOKEN, null), IdentityException.disabled(PROBE),
                        ApiException.notFound(PROBE), new TermException(TermException.Reason.REQUEST_MISMATCH),
                        new TermException(TermException.Reason.PAYLOAD_TOO_LARGE), new DataAccessResourceFailureException(PROBE),
                        new TransactionSystemException(PROBE), new IllegalStateException(PROBE, new IllegalArgumentException(PROBE)),
                        new MissingRequestHeaderException(PROBE, null), new HttpRequestMethodNotSupportedException(PROBE),
                        new HttpMediaTypeNotSupportedException(PROBE), new ResponseStatusException(HttpStatus.NOT_FOUND, PROBE));
                var responses = failures.stream().map(handler::handle).toList();
                assertThat(responses).extracting(response -> response.getStatusCode().value())
                        .containsExactly(400, 401, 403, 404, 409, 413, 503, 503, 500, 400, 405, 415, 404);
                var json = new ObjectMapper();
                for (var response : responses) {
                    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
                    assertThat(response.getBody()).isInstanceOf(TermAdminAdvice.Failure.class);
                    String encoded = json.writeValueAsString(response.getBody());
                    assertThat(encoded).doesNotContain(PROBE);
                    JsonNode body = json.readTree(encoded);
                    assertThat(body.get("code").intValue()).isEqualTo(500);
                    assertThat(body.get("data").isNull()).isTrue();
                    assertThat(body.get("reasonCode").isTextual()).isTrue();
                    assertThat(Set.copyOf(json.convertValue(body, java.util.Map.class).keySet()))
                            .containsExactlyInAnyOrder("code", "message", "data", "reasonCode");
                }
            }
            assertThat(captured.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(PROBE);
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            RequestContextHolder.resetRequestAttributes();
            logger.detachAppender(captured);
            logger.setLevel(previous);
            captured.stop();
        }
    }

    @Test
    void frameworkAndUnrecognizedStatusesNeverBecomeSuccessOrEchoExceptionText() {
        for (int status : List.of(200, 201, 302, 422, 429, 504)) {
            var response = TermAdminAdvice.project(new ResponseStatusException(HttpStatus.valueOf(status), PROBE));
            assertThat(response.getStatusCode().value()).isEqualTo(500);
            assertThat(response.getBody().reasonCode()).isEqualTo("INTERNAL_ERROR");
            assertThat(response.getBody().message()).doesNotContain(PROBE);
        }
    }

    @Test
    void nonManagementDispatchRetainsExistingTypedHandlersAndRuntimeResolution() throws Exception {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest("POST", "/api/admin/search-terms-other")));
        try {
            var handler = spy(new GlobalExceptionHandler());
            var sentinel = org.springframework.http.ResponseEntity.status(409).body(R.<Void>fail("safe"));
            var identity = IdentityException.forbidden(PROBE);
            doReturn(sentinel).when(handler).handleIdentity(identity);
            assertThat(handler.handle(identity)).isSameAs(sentinel);
            verify(handler).handleIdentity(identity);
            var search = new SearchFailure(SearchFailure.Kind.DEPENDENCY);
            doReturn(sentinel).when(handler).handleSearch(search);
            assertThat(handler.handle(search)).isSameAs(sentinel);
            verify(handler).handleSearch(search);
            var api = ApiException.conflict(PROBE);
            doReturn(sentinel).when(handler).handleApi(api);
            assertThat(handler.handle(api)).isSameAs(sentinel);
            verify(handler).handleApi(api);
            var invalid = new MissingRequestHeaderException(PROBE, null);
            doReturn(sentinel).when(handler).handleInvalidRequest(invalid);
            assertThat(handler.handle(invalid)).isSameAs(sentinel);
            verify(handler).handleInvalidRequest(invalid);
            var upload = new MaxUploadSizeExceededException(1);
            doReturn(sentinel).when(handler).handleUploadTooLarge(upload);
            assertThat(handler.handle(upload)).isSameAs(sentinel);
            verify(handler).handleUploadTooLarge(upload);
            var method = new HttpRequestMethodNotSupportedException(PROBE);
            doReturn(sentinel).when(handler).handleMethodNotAllowed(method);
            assertThat(handler.handle(method)).isSameAs(sentinel);
            verify(handler).handleMethodNotAllowed(method);
            var media = new HttpMediaTypeNotSupportedException(PROBE);
            doReturn(sentinel).when(handler).handleUnsupportedMediaType(media);
            assertThat(handler.handle(media)).isSameAs(sentinel);
            verify(handler).handleUnsupportedMediaType(media);
            var missing = new NoHandlerFoundException("GET", "/private", new HttpHeaders());
            doReturn(sentinel).when(handler).handleNotFound(missing);
            assertThat(handler.handle(missing)).isSameAs(sentinel);
            verify(handler).handleNotFound(missing);
            var token = new NotLoginException(PROBE, NotLoginException.NOT_TOKEN, null);
            doReturn(sentinel).when(handler).handleAuth(token);
            assertThat(handler.handle(token)).isSameAs(sentinel);
            verify(handler).handleAuth(token);
            var status = new ResponseStatusException(HttpStatus.CONFLICT, PROBE);
            doReturn(sentinel).when(handler).handleStatus(status);
            assertThat(handler.handle(status)).isSameAs(sentinel);
            verify(handler).handleStatus(status);
            var unknown = new IllegalStateException(PROBE);
            doReturn(sentinel).when(handler).handleOther(unknown);
            assertThat(handler.handle(unknown)).isSameAs(sentinel);
            verify(handler).handleOther(unknown);
            var resolver = new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);
            assertThat(resolver.resolveMethod(identity)).isEqualTo(GlobalExceptionHandler.class.getMethod("handle", Exception.class));
            assertThat(resolver.resolveMethod(unknown)).isEqualTo(GlobalExceptionHandler.class.getMethod("handle", Exception.class));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
