package com.zhishiku.kb.app.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.reporting.mapper.OperationsReportMapper;
import com.zhishiku.kb.app.reporting.service.OperationsReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationsReportTest {
    private final OperationsReportMapper mapper = mock(OperationsReportMapper.class);
    private final AdminQueryAccess access = mock(AdminQueryAccess.class);
    private final OperationsReportService service = new OperationsReportService(mapper, access);
    private final Instant now = Instant.parse("2026-10-27T12:00:00.123456Z");
    private final Instant from = Instant.parse("2026-10-25T18:00:00Z");

    @BeforeEach
    void base() {
        when(mapper.asOf()).thenReturn(now);
        when(mapper.documents()).thenReturn(Map.of("documentCount", "2", "archivedDocumentCount", "1", "docId", "private-id"));
        when(mapper.versions()).thenReturn(List.of(Map.of("format", "pdf", "parsingState", "indexed", "versionCount", "2"),
                Map.of("format", "UNKNOWN", "parsingState", "failed", "versionCount", "1")));
        when(mapper.pendingReviews()).thenReturn(Map.of("pendingReviewCount", "1"));
        when(mapper.coverage()).thenReturn(List.of(start("REVIEW", from.minusSeconds(86400)), start("COMMENT", from.plusSeconds(36000))));
        when(mapper.events(any(), any(), any(), any())).thenReturn(List.of());
    }

    @Test
    void independentInventoryFixedBucketsAndSafeProjectionSurviveChangingWindows() throws Exception {
        var result = service.read(window());
        assertThat(result.inventory().documentCount()).isEqualTo("2");
        assertThat(result.inventory().archivedDocumentCount()).isEqualTo("1");
        assertThat(result.inventory().versionCount()).isEqualTo("3");
        assertThat(result.inventory().formats()).containsEntry("UNKNOWN", "1").containsEntry("docx", "0").hasSize(10);
        assertThat(result.inventory().parsingStates()).containsEntry("failed", "1").containsEntry("UNKNOWN", "0").hasSize(5);
        assertThat(result.inventory().formats().values().stream().map(BigInteger::new).reduce(BigInteger.ZERO, BigInteger::add)).isEqualTo(3);
        assertThat(result.inventory().parsingStates().values().stream().map(BigInteger::new).reduce(BigInteger.ZERO, BigInteger::add)).isEqualTo(3);
        assertThat(service.read(Map.of()).inventory()).isEqualTo(result.inventory());
        assertThat(new ObjectMapper().writeValueAsString(result)).doesNotContain("private-id", "docId", "body", "payload");
        verify(access, org.mockito.Mockito.times(2)).require(Permission.OPERATIONS_REPORTS_READ);
    }

    @Test
    void independentCoverageKeepsTrueZeroUnknownAndPartialDays() {
        var result = service.read(window());
        assertThat(result.review().coverage().status()).isEqualTo("COMPLETE");
        assertThat(result.review().approvedCount()).isEqualTo("0");
        assertThat(result.review().meanDecisionMs()).isNull();
        assertThat(result.comments().coverage().status()).isEqualTo("PARTIAL");
        assertThat(result.comments().coverage().effectiveFrom()).isEqualTo("2026-10-26T04:00:00Z");
        assertThat(result.days()).hasSize(3);
        assertThat(result.days().getFirst().comments().rootCommentCount()).isNull();
        assertThat(result.days().getFirst().comments().coverage().status()).isEqualTo("NOT_STARTED");
        assertThat(result.days().getFirst().partialDay()).isTrue();
        assertThat(result.days().get(1).partialDay()).isFalse();
        assertThat(result.days().get(1).comments().coverage().status()).isEqualTo("PARTIAL");
        assertThat(result.days().getLast().partialDay()).isTrue();
        verify(mapper).events(from, now, from, Instant.parse("2026-10-26T04:00:00Z"));
        when(mapper.coverage()).thenReturn(List.of());
        result = service.read(window());
        assertThat(result.review().approvedCount()).isNull();
        assertThat(result.review().durationSampleCount()).isNull();
        assertThat(result.review().pendingReviewCount()).isEqualTo("1");
        assertThat(result.inventory().documentCount()).isEqualTo("2");
    }

    @Test
    void sameDailyFactsDriveTotalsAndPreciseDurationMeans() {
        when(mapper.events(any(), any(), any(), any())).thenReturn(List.of(
                event("2026-10-25", "1", "0", "1", "0", "1", "0", "0"),
                event("2026-10-26", "1", "1", "1", "1", "2", "1", "1"),
                event("2026-10-27", "0", "1", "1", "0", "2", "0", "0")));
        var result = service.read(window());
        assertThat(result.review().approvedCount()).isEqualTo("2");
        assertThat(result.review().rejectedCount()).isEqualTo("2");
        assertThat(result.review().durationSampleCount()).isEqualTo("3");
        assertThat(result.review().durationUnknownCount()).isEqualTo("1");
        assertThat(result.review().decisionElapsedMsTotal()).isEqualTo("5");
        assertThat(result.review().meanDecisionMs()).isEqualTo("1.67");
        assertThat(result.comments().rootCommentCount()).isEqualTo("1");
        assertThat(result.comments().replyCount()).isEqualTo("1");
        assertThat(result.days().stream().map(day -> new BigInteger(day.review().approvedCount()))
                .reduce(BigInteger.ZERO, BigInteger::add).toString()).isEqualTo(result.review().approvedCount());
    }

    @Test
    void hugeCountsAndDurationTotalsNeverOverflowOrRoundThroughDouble() {
        String huge = "999999999999999999999999999999999999999";
        when(mapper.events(any(), any(), any(), any())).thenReturn(List.of(event("2026-10-26", huge, "0", huge, "0", huge, huge, huge)));
        var result = service.read(window());
        assertThat(result.review().approvedCount()).isEqualTo(huge);
        assertThat(result.review().decisionElapsedMsTotal()).isEqualTo(huge);
        assertThat(result.review().meanDecisionMs()).isEqualTo("1.00");
    }

    @Test
    void longestWindowCanCross32UtcDatesAndOffsetDoesNotMoveEvents() {
        var result = service.read(Map.of("from", List.of("2026-09-26T20:00:00.123456+08:00"), "to", List.of(now.toString())));
        assertThat(result.days()).hasSize(32);
        assertThat(result.days().getFirst().day()).isEqualTo("2026-09-26");
        assertThat(result.days().getFirst().from()).isEqualTo("2026-09-26T12:00:00.123456Z");
    }

    @Test
    void inconsistentCoverageOrInvalidCountsFailClosed() {
        for (var rows : List.of(List.of(start("REVIEW", now.plusSeconds(1))),
                List.of(start("REVIEW", from), start("REVIEW", from)),
                List.of(Map.<String, Object>of("domain", "REVIEW", "protocolVersion", 1, "startedAt", from,
                        "firstOccurredAt", from.minusSeconds(1))))) {
            when(mapper.coverage()).thenReturn(rows);
            unavailable();
        }
        when(mapper.coverage()).thenReturn(List.of());
        when(mapper.documents()).thenReturn(Map.of("documentCount", "1.5", "archivedDocumentCount", "0"));
        unavailable();
    }

    @Test
    void everyRequiredQueryFailureIsWhole503AndNoOldResponseIsReused() {
        service.read(window());
        for (int stage = 0; stage < 6; stage++) {
            base();
            var failure = new DataAccessResourceFailureException("private-db");
            switch (stage) {
                case 0 -> when(mapper.asOf()).thenThrow(failure);
                case 1 -> when(mapper.coverage()).thenThrow(failure);
                case 2 -> when(mapper.documents()).thenThrow(failure);
                case 3 -> when(mapper.versions()).thenThrow(failure);
                case 4 -> when(mapper.pendingReviews()).thenThrow(failure);
                case 5 -> when(mapper.events(any(), any(), any(), any())).thenThrow(failure);
                default -> throw new AssertionError();
            }
            unavailable();
            org.mockito.Mockito.reset(mapper);
        }
        base();
        doThrow(new DataAccessResourceFailureException("private-auth")).when(access).require(any());
        unavailable();
    }

    @Test
    void synchronousReadTransactionHasShortTimeoutAndOneAnchor() throws Exception {
        Transactional tx = OperationsReportService.class.getMethod("read", Map.class).getAnnotation(Transactional.class);
        assertThat(tx.readOnly()).isTrue();
        assertThat(tx.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
        assertThat(tx.timeout()).isEqualTo(6);
        service.read(window());
        var order = inOrder(access, mapper);
        order.verify(access).require(Permission.OPERATIONS_REPORTS_READ);
        order.verify(mapper).asOf();
        order.verify(mapper).coverage();
        order.verify(mapper).documents();
        order.verify(mapper).versions();
        order.verify(mapper).pendingReviews();
        order.verify(mapper).events(any(), any(), any(), any());
        order.verifyNoMoreInteractions();
    }

    private void unavailable() {
        assertThatThrownBy(() -> service.read(window())).isInstanceOfSatisfying(QueryException.class,
                error -> assertThat(error.status()).isEqualTo(503)).hasMessage("运营报表暂不可用");
    }

    private Map<String, List<String>> window() {
        return Map.of("from", List.of(from.toString()), "to", List.of(now.toString()));
    }

    private static Map<String, Object> start(String domain, Instant start) {
        return Map.of("domain", domain, "protocolVersion", 1, "startedAt", start);
    }

    private static Map<String, Object> event(String day, String approved, String rejected, String samples,
                                            String unknown, String elapsed, String roots, String replies) {
        var row = new HashMap<String, Object>();
        row.put("day", day);
        row.put("approvedCount", approved);
        row.put("rejectedCount", rejected);
        row.put("durationSampleCount", samples);
        row.put("durationUnknownCount", unknown);
        row.put("decisionElapsedMsTotal", elapsed);
        row.put("rootCommentCount", roots);
        row.put("replyCount", replies);
        row.put("body", "private-body");
        return row;
    }
}
