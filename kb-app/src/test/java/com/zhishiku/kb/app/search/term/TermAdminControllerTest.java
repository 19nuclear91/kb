package com.zhishiku.kb.app.search.term;

import com.zhishiku.kb.app.search.term.web.TermAdminAdvice;
import com.zhishiku.kb.app.search.term.web.TermAdminController;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.SearchTermEndpoints;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TermAdminControllerTest {
    private static final String CREATE = "{\"canonicalName\":\"办公自动化\",\"description\":\"释义\",\"aliases\":[{\"text\":\"OA\",\"kind\":\"ABBREVIATION\"}]}";
    private static final String UPDATE = CREATE.substring(0, CREATE.length() - 1) + ",\"expectedVersion\":9223372036854775807}";
    private final TermReadService reads = mock(TermReadService.class);
    private final TermWriteService terms = mock(TermWriteService.class);
    private final CorrectionWriteService corrections = mock(CorrectionWriteService.class);
    private final TermReceiptService receipts = mock(TermReceiptService.class);
    private final TermAdminController controller = new TermAdminController(reads, terms, corrections, receipts);

    @Test
    void allTwelveRuntimeMappingsUseOnlyTheirDeclaredMethodsAndFixedManagementPrefix() {
        var root = AnnotatedElementUtils.findMergedAnnotation(TermAdminController.class, RequestMapping.class);
        assertThat(root).isNotNull();
        assertThat(root.path()).containsExactly(SearchTermEndpoints.PREFIX);
        Set<String> mappings = new LinkedHashSet<>();
        for (Method method : TermAdminController.class.getDeclaredMethods()) {
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping != null) {
                for (var action : mapping.method()) {
                    for (String path : mapping.path()) {
                        mappings.add(action.name() + " " + path);
                    }
                }
            }
        }
        assertThat(mappings).containsExactlyInAnyOrder("POST /terms/query", "GET /terms/{id}", "POST /terms", "PUT /terms/{id}",
                "PATCH /terms/{id}/enabled", "POST /terms/{id}/meanings/query", "POST /corrections/query", "GET /corrections/{id}",
                "POST /corrections", "PUT /corrections/{id}", "PATCH /corrections/{id}/enabled", "POST /commands/query");
        for (Method method : TermAdminController.class.getDeclaredMethods()) {
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping != null && !mapping.method()[0].name().equals("GET")) {
                assertThat(mapping.consumes()).containsExactly("application/json");
            }
        }
    }

    @Test
    void termCreateEditAndEnabledRoutesDecodeExactIdentityVersionAndIndependentContent() {
        var first = new TermReceipt(TermRows.EntityKind.TERM, Long.MAX_VALUE, 0, 1, false);
        when(terms.write(any(), any(), anyString())).thenReturn(first);
        when(terms.enabled(anyLong(), any(), anyString())).thenReturn(first);
        var created = controller.createTerm(request(CREATE), " create ");
        assertThat(created.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(created.getBody().data()).isSameAs(first);
        var aliases = List.of(new TermInputs.Alias("OA", "oa", TermRows.AliasKind.ABBREVIATION));
        verify(terms).write(null, new TermInputs.TermWrite("办公自动化", "办公自动化", "释义", aliases, null), "create");
        controller.updateTerm(Long.toString(Long.MAX_VALUE), request(UPDATE), "edit");
        verify(terms).write(Long.MAX_VALUE, new TermInputs.TermWrite("办公自动化", "办公自动化", "释义", aliases, Long.MAX_VALUE), "edit");
        controller.enableTerm(Long.toString(Long.MAX_VALUE), request("{\"enabled\":false,\"expectedVersion\":9223372036854775807}"), "disable");
        verify(terms).enabled(Long.MAX_VALUE, new TermInputs.EnabledWrite(false, Long.MAX_VALUE), "disable");
    }

    @Test
    void correctionCreateEditAndEnabledRoutesKeepTargetExactWithoutImplicitlyEditingOwnState() {
        var first = new TermReceipt(TermRows.EntityKind.CORRECTION, Long.MAX_VALUE, 0, 1, false);
        when(corrections.write(any(), any(), anyString())).thenReturn(first);
        when(corrections.enabled(anyLong(), any(), anyString())).thenReturn(first);
        controller.createCorrection(request("{\"wrongExpression\":\"报消\",\"termId\":9223372036854775807}"), "create");
        verify(corrections).write(null, new TermInputs.CorrectionWrite("报消", "报消", Long.MAX_VALUE, null), "create");
        controller.updateCorrection("9007199254740993", request("{\"wrongExpression\":\"报消\",\"termId\":9223372036854775807,\"expectedVersion\":0}"), "edit");
        verify(corrections).write(9007199254740993L, new TermInputs.CorrectionWrite("报消", "报消", Long.MAX_VALUE, 0L), "edit");
        controller.enableCorrection("9007199254740993", request("{\"enabled\":false,\"expectedVersion\":0}"), "disable");
        verify(corrections).enabled(9007199254740993L, new TermInputs.EnabledWrite(false, 0), "disable");
    }

    @Test
    void queriesDetailsAndOwnReceiptUseOnlyTheirDesignatedServices() {
        when(reads.terms(any())).thenReturn(new TermViews.Page<>(List.of(), false, null, "0"));
        when(reads.corrections(any())).thenReturn(new TermViews.Page<>(List.of(), false, null, "0"));
        when(reads.meanings(anyLong(), any())).thenReturn(new TermViews.Page<>(List.of(), false, null, "0"));
        controller.terms(request("{}"));
        verify(reads).terms(new TermInputs.Query(null, null, 20, null));
        controller.corrections(request("{\"nameQuery\":\" %_ \",\"enabled\":false,\"limit\":100}"));
        verify(reads).corrections(new TermInputs.Query("%_", false, 100, null));
        controller.meanings("9007199254740993", request("{\"expression\":\"OA\"}"));
        verify(reads).meanings(9007199254740993L, new TermInputs.MeaningQuery("OA", 20, null));
        OffsetDateTime time = OffsetDateTime.parse("2026-11-24T00:00:00Z");
        var term = new TermViews.Term("9", "名称", "释义", false, List.of(), true, "0", time, time);
        when(reads.term(9)).thenReturn(new TermViews.TermDetail(term, "1"));
        controller.term("9", request(""));
        verify(reads).term(9);
        var target = new TermViews.Target("9", "名称", "释义", true, "0");
        var mapping = new TermViews.Correction("10", "错词", target, true, true, "0", time, time);
        when(reads.correction(10)).thenReturn(new TermViews.CorrectionDetail(mapping, "1"));
        controller.correction("10", request(""));
        verify(reads).correction(10);
        var receipt = new TermReceipt(TermRows.EntityKind.TERM, 9, 0, 1, false);
        when(receipts.query("request")).thenReturn(receipt);
        assertThat(controller.command(request("{\"requestId\":\"request\"}")).getBody().data()).isSameAs(receipt);
        verify(receipts).query("request");
        verifyNoInteractions(terms, corrections);
    }

    @Test
    void malformedUnknownDuplicateTrailingAndWrongNumericBodiesNeverReachServices() {
        for (String raw : List.of("{} {}", "{\"canonicalName\":1,\"canonicalName\":2}", CREATE.replace("\"aliases\":[", "\"unknown\":1,\"aliases\":["),
                CREATE.replace("ABBREVIATION", "TYPO"), "[]", "null")) {
            invalid(() -> controller.createTerm(request(raw), "request"));
        }
        for (String version : List.of("null", "-1", "1.0", "\"1\"", "9223372036854775808")) {
            invalid(() -> controller.enableTerm("9", request("{\"enabled\":true,\"expectedVersion\":" + version + "}"), "request"));
        }
        invalid(() -> controller.terms(request("{\"enabled\":null}")));
        invalid(() -> controller.terms(request("{\"limit\":101}")));
        invalid(() -> controller.command(request("{\"requestId\":\"request\",\"actorUserId\":7}")));
        invalid(() -> controller.createTerm(request(CREATE), null));
        verifyNoInteractions(reads, terms, corrections, receipts);
    }

    @Test
    void invalidPathsAndAnyUrlQueryAreRejectedBeforeLookup() {
        for (String id : List.of("0", "-1", "+1", "1.0", " 1", "01", "9223372036854775808", "abc")) {
            invalid(() -> controller.term(id, request("")));
            invalid(() -> controller.enableCorrection(id, request("{\"enabled\":false,\"expectedVersion\":0}"), "request"));
        }
        MockHttpServletRequest query = request("{}");
        query.setQueryString("nameQuery=private-name");
        invalid(() -> controller.terms(query));
        invalid(() -> controller.term("9", query));
        invalid(() -> controller.correction("9", query));
        verifyNoInteractions(reads, terms, corrections, receipts);
    }

    @Test
    void actualBodyLimitStopsUnlengthenedStreamAt32769Bytes() {
        byte[] bytes = ("{}" + " ".repeat(40000)).getBytes(StandardCharsets.UTF_8);
        var delivered = new AtomicInteger();
        var raw = new ByteArrayInputStream(bytes);
        MockHttpServletRequest request = new MockHttpServletRequest() {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public ServletInputStream getInputStream() {
                return new ServletInputStream() {
                    @Override
                    public int read() {
                        int value = raw.read();
                        if (value >= 0) {
                            delivered.incrementAndGet();
                        }
                        return value;
                    }

                    @Override
                    public int read(byte[] target, int offset, int length) {
                        int count = raw.read(target, offset, length);
                        if (count > 0) {
                            delivered.addAndGet(count);
                        }
                        return count;
                    }

                    @Override
                    public boolean isFinished() {
                        return raw.available() == 0;
                    }

                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setReadListener(ReadListener listener) {
                        throw new UnsupportedOperationException();
                    }
                };
            }
        };
        assertThatThrownBy(() -> controller.terms(request)).isInstanceOfSatisfying(TermException.class,
                error -> assertThat(error.reason()).isEqualTo(TermException.Reason.PAYLOAD_TOO_LARGE));
        assertThat(delivered.get()).isEqualTo(32769);
        verifyNoInteractions(reads, terms, corrections, receipts);
    }

    @Test
    void independentReadWriteAndReceiptFailuresKeepTheirStatusesAndNoStoreWithoutEcho() {
        when(terms.write(any(), any(), anyString())).thenThrow(new TermException(TermException.Reason.FORBIDDEN));
        assertThatThrownBy(() -> controller.createTerm(request(CREATE), "request")).isInstanceOfSatisfying(TermException.class,
                error -> assertThat(TermAdminAdvice.project(error).getStatusCode().value()).isEqualTo(403));
        when(receipts.query("request")).thenThrow(new TermException(TermException.Reason.NOT_FOUND));
        assertThatThrownBy(() -> controller.command(request("{\"requestId\":\"request\"}"))).isInstanceOfSatisfying(TermException.class,
                error -> assertThat(TermAdminAdvice.project(error).getStatusCode().value()).isEqualTo(404));
        for (Exception error : List.of(IdentityException.unauthorized("private-token"), new TermException(TermException.Reason.VERSION_CONFLICT),
                new TermException(TermException.Reason.UNAVAILABLE), new IllegalStateException("private-query"))) {
            var response = TermAdminAdvice.project(error);
            assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
            assertThat(response.getBody().code()).isEqualTo(500);
            assertThat(response.getBody().data()).isNull();
            assertThat(response.getBody().message()).doesNotContain("private-");
        }
        assertThat(TermAdminAdvice.project(new IllegalStateException("private-query")).getStatusCode().value()).isEqualTo(500);
    }

    @Test
    void fixedProjectionKeepsAllRequiredStatusesAndConflictReasons() {
        var reasons = List.of(TermException.Reason.INVALID_INPUT, TermException.Reason.FORBIDDEN, TermException.Reason.NOT_FOUND,
                TermException.Reason.VERSION_CONFLICT, TermException.Reason.PAYLOAD_TOO_LARGE, TermException.Reason.UNAVAILABLE);
        assertThat(reasons.stream().map(reason -> TermAdminAdvice.project(new TermException(reason)).getStatusCode().value()).toList())
                .containsExactly(400, 403, 404, 409, 413, 503);
        for (TermException.Reason reason : TermException.Reason.values()) {
            var response = TermAdminAdvice.project(new TermException(reason));
            assertThat(response.getBody().reasonCode()).isEqualTo(reason.name());
            assertThat(response.getBody().data()).isNull();
        }
        var unauthorized = TermAdminAdvice.project(IdentityException.unauthorized("private-token"));
        assertThat(unauthorized.getStatusCode().value()).isEqualTo(401);
        assertThat(unauthorized.getBody().reasonCode()).isEqualTo("UNAUTHORIZED");
    }

    private static MockHttpServletRequest request(String body) {
        var request = new MockHttpServletRequest();
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static void invalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(TermException.class,
                error -> assertThat(error.reason()).isEqualTo(TermException.Reason.INVALID_INPUT));
    }
}
