package com.zhishiku.kb.app.aiusage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.aiusage.mapper.AiUsageMapper;
import com.zhishiku.kb.app.aiusage.model.AiEventRow;
import com.zhishiku.kb.app.aiusage.service.AiUsageReadService;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.query.QueryException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiUsageReadTest {
    private final AiUsageMapper mapper = mock(AiUsageMapper.class);
    private final AdminQueryAccess access = mock(AdminQueryAccess.class);
    private final AiObservationProperties properties = new AiObservationProperties(new MockEnvironment());
    private final Instant now = Instant.parse("2026-10-24T00:00:00Z");
    private final AiUsageReadService service = new AiUsageReadService(mapper, access, properties,
            new ObjectMapper(), Clock.fixed(now, ZoneOffset.UTC));

    AiUsageReadTest() { when(mapper.periods(any(), any(), anyInt())).thenReturn(List.of()); }

    @Test
    void exactAggregationAndUnknownAreNotReplacedByZero() {
        var metrics = new HashMap<String, Object>();
        metrics.put("callCount", 2L); metrics.put("knownCount", 0L); metrics.put("promptTokens", null);
        metrics.put("plannedChargedTokens", new BigDecimal("18446744073709551617"));
        when(mapper.aggregate(any())).thenReturn(metrics);
        var response = service.usage(Map.of(), false);
        assertThat(((Map<?, ?>) response.get("metrics")).get("plannedChargedTokens")).isEqualTo("18446744073709551617");
        assertThat(((Map<?, ?>) response.get("metrics")).get("promptTokens")).isNull();
        assertThat(response).containsEntry("collectionEnabled", false).containsEntry("historyMayBeIncomplete", true);
        verify(access).require(Permission.AI_USAGE_READ);
    }

    @Test
    void noPermissionIsCheckedBeforeTargetFormatOrExistence() {
        when(access.require(Permission.AI_AUDIT_READ)).thenThrow(QueryException.forbidden());
        assertThatThrownBy(() -> service.detail("not-an-id")).isInstanceOf(QueryException.class)
                .extracting(error -> ((QueryException) error).status()).isEqualTo(403);
        verify(mapper, never()).event(anyString());
    }

    @Test
    void auditProjectionNeverReturnsRawExtraPayloadOrCredentials() {
        var row = new AiEventRow(); String id = UUID.randomUUID().toString();
        row.setId(7); row.setEventId(id); row.setCallId(id); row.setKind("REGISTERED");
        row.setOccurredAt(now); row.setRecordedAt(now);
        row.setPayload("{\"callId\":\"" + id + "\",\"mode\":\"SINGLE\",\"userId\":\"1\",\"departmentId\":\"2\",\"content\":\"private\",\"leaseId\":\"secret\"}");
        when(mapper.event(id)).thenReturn(row);
        var response = service.detail(id);
        assertThat(response.toString()).doesNotContain("private", "leaseId", "secret", "payloadDigest");
        assertThat(response).containsEntry("snapshotAvailable", true);
        row.setPayload("{\"callId\":\"" + id + "\",\"resultCode\":\"private-secret\"}");
        assertThat(service.detail(id)).containsEntry("snapshotAvailable", false);
    }

    @Test
    void coverageGapsAndOldReviewOnlyWindowsNeverClaimCompleteHistory() {
        when(mapper.aggregate(any())).thenReturn(Map.of("callCount", 1, "reviewCount", 1));
        when(mapper.firstCoverage()).thenReturn(now.minusSeconds(86400));
        when(mapper.periods(any(), any(), anyInt())).thenReturn(List.of(
                Map.of("openedAt", now.minusSeconds(86400), "closedAt", now.minusSeconds(7200)),
                Map.of("openedAt", now.minusSeconds(3600))));
        assertThat(service.usage(Map.of(), false)).containsEntry("historyMayBeIncomplete", true);
        when(mapper.periods(any(), any(), anyInt())).thenReturn(List.of(Map.of("openedAt", now.minusSeconds(7 * 86400L))));
        assertThat(service.usage(Map.of(), false)).containsEntry("historyMayBeIncomplete", false);
        var old = Map.of("from", List.of(now.minusSeconds(200 * 86400L).toString()), "to", List.of(now.minusSeconds(199 * 86400L).toString()));
        assertThat(service.usage(old, false)).containsEntry("historyMayBeIncomplete", true);
    }

    @Test
    void groupingUsesStableIdsAndEachPagePublishesItsOwnAsOf() {
        when(mapper.groups(any())).thenReturn(List.of(
                Map.of("key", "0009007199254740993", "callCount", 2, "knownCount", 1, "unknownCount", 1, "promptTokens", 10,
                        "completionTokens", 5, "plannedChargedTokens", 12015, "settledChargedTokens", 15),
                Map.of("key", "0009007199254740994", "callCount", 1)));
        var first = service.usage(Map.of("groupBy", List.of("department"), "limit", List.of("1")), true);
        assertThat(first).containsEntry("hasMore", true).containsEntry("asOf", now.toString());
        var item = (Map<?, ?>) ((List<?>) first.get("items")).get(0);
        assertThat(item.get("key")).isEqualTo("9007199254740993");
        assertThat(item.get("promptTokens")).isEqualTo("10");
        assertThat(item.get("plannedChargedTokens")).isEqualTo("12015");
        assertThat(item.get("settledChargedTokens")).isEqualTo("15");
        var capture = org.mockito.ArgumentCaptor.forClass(Map.class);
        service.usage(Map.of("groupBy", List.of("department"), "limit", List.of("1"), "cursor", List.of(first.get("nextCursor").toString())), true);
        verify(mapper, org.mockito.Mockito.times(2)).groups(capture.capture());
        assertThat(capture.getAllValues().get(1)).containsEntry("afterGroup", "0009007199254740993");
    }

    @Test
    void extendingRetentionCannotRestoreAlreadyDeletedUsageOrAuditHistory() {
        when(mapper.aggregate(any())).thenReturn(Map.of("callCount", 0));
        when(mapper.events(any())).thenReturn(List.of());
        when(mapper.periods(any(), any(), anyInt())).thenReturn(List.of(Map.of("openedAt", now.minusSeconds(180L * 86400))));
        properties.setRetentionDays(1);
        assertThat(service.usage(Map.of(), false)).containsEntry("historyMayBeIncomplete", true);
        Instant cleared = now.minusSeconds(2L * 86400);
        when(mapper.clearedThrough()).thenReturn(cleared);
        properties.setRetentionDays(180);
        assertThat(service.usage(Map.of(), false)).containsEntry("historyMayBeIncomplete", true);
        assertThat(service.audits(Map.of())).containsEntry("historyMayBeIncomplete", true);
        var boundaryWindow = Map.of("from", List.of(cleared.toString()), "to", List.of(now.toString()));
        assertThat(service.usage(boundaryWindow, false)).containsEntry("historyMayBeIncomplete", true);
        var laterWindow = Map.of("from", List.of(cleared.plusSeconds(1).toString()), "to", List.of(now.toString()));
        assertThat(service.usage(laterWindow, false)).containsEntry("historyMayBeIncomplete", false);
        assertThat(service.audits(laterWindow)).containsEntry("historyMayBeIncomplete", false);
    }

    @Test
    void firstAuditWatermarkIsCarriedAcrossLateInsertionsAndSameTimestampBoundary() {
        var rows = new java.util.ArrayList<AiEventRow>();
        for (int number : new int[]{10, 9}) {
            var row = new AiEventRow(); row.setId(number); row.setEventId(UUID.randomUUID().toString()); row.setCallId(UUID.randomUUID().toString());
            row.setKind("FINALIZED"); row.setOccurredAt(now.minusSeconds(1)); row.setRecordedAt(now); row.setPayload("{}"); rows.add(row);
        }
        when(mapper.upperEventId()).thenReturn(10L, 11L);
        when(mapper.events(any())).thenReturn(rows);
        var first = service.audits(Map.of("limit", List.of("1")));
        service.audits(Map.of("limit", List.of("1"), "cursor", List.of(first.get("nextCursor").toString())));
        var capture = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(mapper, org.mockito.Mockito.times(2)).events(capture.capture());
        assertThat(capture.getAllValues().get(1)).containsEntry("upper", 10L).containsEntry("lastId", 10L)
                .containsEntry("lastTime", now.minusSeconds(1));
        verify(mapper, org.mockito.Mockito.times(1)).upperEventId();
    }
}
