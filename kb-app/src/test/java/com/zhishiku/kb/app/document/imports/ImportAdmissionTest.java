package com.zhishiku.kb.app.document.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.service.EpochSessionService;
import com.zhishiku.kb.app.document.imports.service.ImportAccess;
import com.zhishiku.kb.app.document.imports.service.ImportCapacity;
import com.zhishiku.kb.app.document.imports.web.ImportAdmissionFilter;
import com.zhishiku.kb.app.document.imports.web.ImportHeadersFilter;
import com.zhishiku.kb.common.security.IdentityException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.Part;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.FormContentFilter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ImportAdmissionTest {
    private final ImportProperties properties = new ImportProperties("100", "1024", "2", "1", "120", "60", "30000", "100");
    private final ImportCapacity capacity = new ImportCapacity(properties);
    private final ImportAccess access = mock(ImportAccess.class);
    private final ImportAdmissionFilter filter = new ImportAdmissionFilter(access, capacity, new ObjectMapper());

    @Test
    void refusalHappensBeforeAnyMultipartAccessOrDownstreamWork() throws Exception {
        var chain = mock(FilterChain.class);
        when(access.current("token")).thenReturn(new EpochSessionService.Session(1, 2, EpochSessionService.Method.LOCAL, false));
        try (var occupied = capacity.acquire(1)) {
            var response = new MockHttpServletResponse();
            filter.doFilter(request(), response, chain);
            assertThat(response.getStatus()).isEqualTo(429);
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(response.getContentAsString()).contains("导入上传繁忙").doesNotContain("AI");
        }
        when(access.current("token")).thenThrow(IdentityException.unauthorized("revoked"));
        var response = new MockHttpServletResponse();
        filter.doFilter(request(), response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    @Test
    void globalAndUserLimitsAreIndependentAndClosingIsIdempotent() {
        var first = capacity.acquire(1);
        var second = capacity.acquire(2);
        assertThatThrownBy(() -> capacity.acquire(3)).hasMessage("IMPORT_CAPACITY");
        first.close();
        first.close();
        try (var third = capacity.acquire(3)) {
            assertThatThrownBy(() -> capacity.acquire(2)).hasMessage("IMPORT_CAPACITY");
            assertThatThrownBy(() -> capacity.acquire(4)).hasMessage("IMPORT_CAPACITY");
        }
        second.close();
    }

    @Test
    void responseTimeoutDoesNotReleaseWorkStillExiting() throws Exception {
        when(access.current("token")).thenReturn(new EpochSessionService.Session(1, 2, EpochSessionService.Method.LOCAL, false));
        CountDownLatch responded = new CountDownLatch(1);
        CountDownLatch exit = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var work = pool.submit(() -> {
                filter.doFilter(request(), new MockHttpServletResponse(), (request, response) -> {
                    ((MockHttpServletResponse) response).setStatus(503);
                    response.flushBuffer();
                    responded.countDown();
                    try {
                        if (!exit.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("work exit timeout");
                        }
                    } catch (InterruptedException failed) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failed);
                    }
                });
                return null;
            });
            try {
                assertThat(responded.await(5, TimeUnit.SECONDS)).isTrue();
                var response = new MockHttpServletResponse();
                filter.doFilter(request(), response, mock(FilterChain.class));
                assertThat(response.getStatus()).isEqualTo(429);
            } finally {
                exit.countDown();
            }
            work.get(5, TimeUnit.SECONDS);
        }
        try (var available = capacity.acquire(1)) {
            assertThat(available).isNotNull();
        }
    }

    @Test
    void orderedAdmissionPrecedesFormParsingAndHeaderErrorsRemainUncached() {
        var headers = new ImportHeadersFilter();
        var form = new FormContentFilter();
        var filters = new ArrayList<>(List.of(form, filter, headers));
        AnnotationAwareOrderComparator.sort(filters);
        assertThat(filters).containsExactly(headers, filter, form);
    }

    @Test
    void encodedOrMatrixPathsCannotBypassCapacityAdmission() throws Exception {
        when(access.current("token")).thenReturn(new EpochSessionService.Session(1, 2, EpochSessionService.Method.LOCAL, false));
        var chain = mock(FilterChain.class);
        try (var occupied = capacity.acquire(1)) {
            for (String path : List.of("/api/document-imports/9/items/2/fi%6ce", "/api/document-imports/9/items/2/file;ignored=1")) {
                var request = request();
                request.setRequestURI(path);
                var response = new MockHttpServletResponse();
                filter.doFilter(request, response, chain);
                assertThat(response.getStatus()).isEqualTo(429);
            }
        }
        verifyNoInteractions(chain);
    }

    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("POST", "/api/document-imports/9/items/2/file") {
            @Override
            public String getParameter(String name) {
                throw new AssertionError("multipart parsed before admission");
            }

            @Override
            public Map<String, String[]> getParameterMap() {
                throw new AssertionError("multipart parsed before admission");
            }

            @Override
            public Collection<Part> getParts() {
                throw new AssertionError("multipart parsed before admission");
            }

            @Override
            public ServletInputStream getInputStream() {
                throw new AssertionError("multipart parsed before admission");
            }
        };
        request.addHeader("satoken", "token");
        return request;
    }
}
