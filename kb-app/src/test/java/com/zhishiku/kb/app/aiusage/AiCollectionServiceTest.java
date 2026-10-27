package com.zhishiku.kb.app.aiusage;

import com.zhishiku.kb.app.aiusage.mapper.AiUsageMapper;
import com.zhishiku.kb.app.aiusage.model.AiCallRow;
import com.zhishiku.kb.app.aiusage.service.AiCollectionService;
import com.zhishiku.kb.app.aiusage.service.AiObservationHeartbeat;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiCollectionServiceTest {
    private final AiUsageMapper mapper = mock(AiUsageMapper.class);
    private final AiObservationProperties properties = new AiObservationProperties(new MockEnvironment());
    private final Instant now = Instant.parse("2026-10-24T00:00:00Z");
    private final AiObservationHeartbeat heartbeat = mock(AiObservationHeartbeat.class);
    private final AiCollectionService service = new AiCollectionService(mapper, properties, heartbeat, Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void defaultDisabledClosesPeriodAndConflictingInstanceBlocksDeletion() {
        service.synchronize();
        verify(mapper).closePeriod(now);
        verify(mapper, never()).openPeriod(any(), anyInt());
        when(mapper.inconsistentInstances(any(), anyBoolean(), anyInt())).thenReturn(1);
        assertThat(service.cleanup()).isZero();
        verify(mapper, never()).deleteCall(anyString());
    }

    @Test
    void retentionBoundaryAndProtectedStatusesAreExplicit() {
        var row = eligible();
        Instant cutoff = now.minusSeconds(180L * 86400);
        assertThat(AiCollectionService.eligible(row, cutoff)).isTrue();
        row.setRetentionAnchorAt(cutoff.plusNanos(1));
        assertThat(AiCollectionService.eligible(row, cutoff)).isFalse();
        row.setRetentionAnchorAt(cutoff.minusSeconds(1));
        for (String state : List.of("RESERVED", "PENDING", "REVIEW")) {
            row.setSettlementStatus(state);
            assertThat(AiCollectionService.eligible(row, cutoff)).isFalse();
        }
        row.setSettlementStatus("NOT_REQUIRED");
        row.setGenerationStatus("RUNNING");
        assertThat(AiCollectionService.eligible(row, cutoff)).isFalse();
    }

    @Test
    void latestStatePreventsDeletionAndFailurePropagatesToTransactionBoundary() {
        when(mapper.cleanupLock()).thenReturn(true);
        var row = eligible();
        when(mapper.cleanupCandidates(any(), anyInt())).thenReturn(List.of(row));
        var latest = eligible(); latest.setSettlementStatus("REVIEW");
        when(mapper.lockCall("call")).thenReturn(latest);
        assertThat(service.cleanup()).isZero();
        verify(mapper, never()).deleteEvents(anyString());
        when(mapper.lockCall("call")).thenReturn(row);
        when(mapper.deletionBoundary("call")).thenReturn(row.getFinalizedAt());
        when(mapper.deleteCall("call")).thenReturn(0);
        assertThatThrownBy(service::cleanup).hasMessage("AI 观测状态冲突");
        verify(mapper).deleteEvents("call");
        verify(mapper, never()).advanceCoverage(any());
    }

    @Test
    void cleanupPublishesLatestDeletedTimeOnlyAfterAllDeletes() {
        when(mapper.cleanupLock()).thenReturn(true);
        var first = eligible(); var second = eligible(); second.setCallId("second");
        when(mapper.cleanupCandidates(any(), anyInt())).thenReturn(List.of(first, second));
        when(mapper.lockCall("call")).thenReturn(first); when(mapper.lockCall("second")).thenReturn(second);
        Instant newerEvent = now.minusSeconds(179L * 86400);
        when(mapper.deletionBoundary("call")).thenReturn(newerEvent);
        when(mapper.deletionBoundary("second")).thenReturn(second.getFinalizedAt());
        when(mapper.deleteCall(anyString())).thenReturn(1); when(mapper.advanceCoverage(any())).thenReturn(1);
        assertThat(service.cleanup()).isEqualTo(2);
        var order = org.mockito.Mockito.inOrder(mapper);
        order.verify(mapper).deletionBoundary("call"); order.verify(mapper).deleteEvents("call"); order.verify(mapper).deleteCall("call");
        order.verify(mapper).deletionBoundary("second"); order.verify(mapper).deleteEvents("second"); order.verify(mapper).deleteCall("second");
        order.verify(mapper).advanceCoverage(newerEvent);
    }

    @Test
    void missingBoundaryOrFailedWatermarkRefusesToConfirmCleanup() {
        when(mapper.cleanupLock()).thenReturn(true);
        var row = eligible();
        when(mapper.cleanupCandidates(any(), anyInt())).thenReturn(List.of(row)); when(mapper.lockCall("call")).thenReturn(row);
        assertThatThrownBy(service::cleanup).hasMessage("AI 观测清理界限不可用");
        verify(mapper, never()).deleteEvents(anyString());
        when(mapper.deletionBoundary("call")).thenReturn(row.getFinalizedAt()); when(mapper.deleteCall("call")).thenReturn(1);
        assertThatThrownBy(service::cleanup).hasMessage("AI 观测清理界限未确认");
    }

    @Test
    void emptyCleanupCannotAdvanceHistoryBoundaryAndConflictIsCheckedUnderLock() {
        when(mapper.cleanupLock()).thenReturn(true);
        assertThat(service.cleanup()).isZero(); verify(mapper, never()).advanceCoverage(any());
        when(mapper.inconsistentInstances(any(), anyBoolean(), anyInt())).thenReturn(0, 1);
        assertThatThrownBy(service::cleanup).hasMessage("AI 观测清理策略不一致");
        verify(mapper, never()).deleteCall(anyString());
    }

    private AiCallRow eligible() {
        var row = new AiCallRow(); row.setCallId("call"); row.setGenerationStatus("COMPLETED");
        row.setSettlementStatus("SETTLED"); row.setFinalizedAt(now.minusSeconds(181L * 86400));
        row.setRetentionAnchorAt(now.minusSeconds(180L * 86400)); return row;
    }
}
