package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.embedding.EmbeddingRun;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingStateMapper;
import com.zhishiku.kb.worker.parse.mapper.ParseMaintenanceMapper;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ParseMaintenanceServiceTest {
    private final UUID runId = UUID.randomUUID();
    private final EmbeddingStateMapper state = mock(EmbeddingStateMapper.class);
    private final ParseMaintenanceMapper tasks = mock(ParseMaintenanceMapper.class);
    private final ParseMaintenanceService service = new ParseMaintenanceService(state, tasks);

    private void quiescing() {
        when(state.lockExclusive()).thenReturn(new EmbeddingControl("QUIESCING", null, null,
                "target", "fp", 5, runId));
        when(state.run(runId)).thenReturn(new EmbeddingRun(runId, "target", 1, "QUIESCING", 2,
                null, 0, null, null, 0, 3, null, 0, 0, 0, null));
        when(tasks.advanceRun(runId, 2)).thenReturn(1);
        when(tasks.advanceControl(runId, 5)).thenReturn(1);
    }

    @Test
    void storedClaimIsDeferredOnceAndPreviousFailuresRemain() {
        quiescing();
        var attempts = new AtomicInteger(3);
        var token = new AtomicLong(9);
        var running = new AtomicBoolean(true);
        when(tasks.lockRunning()).thenAnswer(call -> running.get()
                ? List.of(new ParseMaintenanceMapper.Claim(1, 2, token.get(), 4L, attempts.get(), 0)) : List.of());
        when(tasks.defer(1, 9, 5, 1)).thenAnswer(call -> {
            if (!running.compareAndSet(true, false)) return 0;
            attempts.decrementAndGet();
            token.incrementAndGet();
            return 1;
        });
        service.quiesce();
        new ParseMaintenanceService(state, tasks).quiesce();
        assertThat(attempts).hasValue(2);
        assertThat(token).hasValue(10);
        verify(tasks, times(1)).event(any(), eq("maintenance:1:9"));
        verify(tasks, times(1)).pendingVersion(2);
        var order = inOrder(state, tasks);
        order.verify(state).lockExclusive();
        order.verify(tasks).lockRunning();
        order.verify(tasks).defer(1, 9, 5, 1);
        order.verify(tasks).event(any(), eq("maintenance:1:9"));
        order.verify(tasks).pendingVersion(2);
        order.verify(tasks).runningCount();
        order.verify(tasks).advanceRun(runId, 2);
        order.verify(tasks).advanceControl(runId, 5);
    }

    @Test
    void refusesNewEpochClaimsAndWaitsForRemainingBatches() {
        quiescing();
        when(tasks.lockRunning()).thenReturn(List.of(new ParseMaintenanceMapper.Claim(1, 2, 9, 5L, 1, 0)));
        assertThatThrownBy(service::quiesce).isInstanceOf(IllegalStateException.class);
        verify(tasks, never()).defer(anyLong(), anyLong(), anyLong(), anyInt());
        when(tasks.lockRunning()).thenReturn(List.of());
        when(tasks.runningCount()).thenReturn(1L);
        service.quiesce();
        verify(tasks, never()).advanceRun(any(), anyLong());
    }

    @Test
    void preservesHistoricalFieldsAndNeverReusesAttemptCounterAsToken() throws Exception {
        var method = ParseMaintenanceMapper.class.getMethod("defer", long.class, long.class, long.class, int.class);
        String sql = String.join(" ", method.getAnnotation(Update.class).value());
        assertThat(sql).contains("attempts = attempts - #{refund}", "claim_token = claim_token + 1",
                        "maintenance_deferred_token = claim_token", "claim_token = #{token}")
                .doesNotContain("failure_class =", "failure_code =", "failure_summary =", "attempts = 0",
                        "next_attempt_at =", "last_failure_at =", "last_error =");
    }

    @Test
    void activeDoesNotTouchTasksAndFailedCasDoesNotAdvanceControl() {
        when(state.lockExclusive()).thenReturn(ParseEmbeddingFixture.active());
        service.quiesce();
        verifyNoInteractions(tasks);
        quiescing();
        when(tasks.advanceRun(runId, 2)).thenReturn(0);
        assertThatThrownBy(service::quiesce).isInstanceOf(IllegalStateException.class);
        verify(tasks, never()).advanceControl(any(), anyLong());
    }
}
