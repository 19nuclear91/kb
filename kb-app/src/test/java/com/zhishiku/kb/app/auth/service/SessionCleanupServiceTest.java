package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.lifecycle.SessionCleanupJob;
import com.zhishiku.kb.app.auth.lifecycle.SessionCleanupWorker;
import com.zhishiku.kb.app.auth.mapper.SessionCleanupMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionCleanupServiceTest {
    @Test
    void retryNeverRevokesRestoredNewSessionEvenIfIndexContainsAnOldAssociation() {
        var index = mock(SessionTokenIndex.class);
        var sessions = mock(EpochSessionService.class);
        var service = new SessionCleanupService(index, sessions);
        var old = new SessionTokenIndex.Token(1, "old");
        var wrong = new SessionTokenIndex.Token(1, "new");
        when(index.pending(7, 1, 100)).thenReturn(List.of(old, wrong));
        when(sessions.recorded("old")).thenReturn(new EpochSessionService.Session(7, 1, EpochSessionService.Method.LOCAL, false));
        when(sessions.recorded("new")).thenReturn(new EpochSessionService.Session(7, 3, EpochSessionService.Method.LOCAL, false));
        doThrow(new IllegalStateException("redis unavailable")).doNothing().when(sessions).revoke("old");
        var job = new SessionCleanupJob(7, 1, 0);
        assertThatThrownBy(() -> service.cleanPage(job)).isInstanceOf(IllegalStateException.class);
        verify(index, never()).remove(7, old);
        assertThat(service.cleanPage(job)).isTrue();
        verify(index).remove(7, old);
        verify(sessions, never()).revoke("new");
    }

    @Test
    void indexFailureDoesNotReportCleanupCompleteOrReadUserWideSessionLists() {
        var index = mock(SessionTokenIndex.class);
        var sessions = mock(EpochSessionService.class);
        when(index.pending(7, 9, 100)).thenThrow(new IllegalStateException("unknown"));
        assertThatThrownBy(() -> new SessionCleanupService(index, sessions).cleanPage(new SessionCleanupJob(7, 9, 0)))
                .isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verifyNoInteractions(sessions);
    }

    @Test
    @SuppressWarnings("unchecked")
    void persistedJobReleasesLeaseForRetryAndLaterCompletesUnderSameRevocationBoundary() {
        var jobs = mock(SessionCleanupMapper.class);
        var cleanup = mock(SessionCleanupService.class);
        var tx = mock(TransactionTemplate.class);
        var status = mock(TransactionStatus.class);
        when(tx.execute(any())).thenAnswer(call -> call.getArgument(0, TransactionCallback.class).doInTransaction(status));
        doAnswer(call -> {
            call.getArgument(0, Consumer.class).accept(status);
            return null;
        }).when(tx).executeWithoutResult(any());
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var clock = Clock.fixed(now, ZoneOffset.UTC);
        var job = new SessionCleanupJob(7, 1, 0);
        when(jobs.next(now)).thenReturn(job);
        when(jobs.claim(eq(7L), eq(1L), any(), any())).thenReturn(1);
        when(cleanup.cleanPage(job)).thenThrow(new IllegalStateException("redis unavailable")).thenReturn(true);
        var worker = new SessionCleanupWorker(jobs, cleanup, tx, clock,
                mock(com.zhishiku.kb.app.auth.lifecycle.LifecycleObservations.class));
        assertThat(worker.runOne()).isTrue();
        verify(jobs).release(eq(7L), eq(1L), any(), eq(false), eq(true), eq(now), eq(now.plusSeconds(5)));
        assertThat(worker.runOne()).isTrue();
        verify(jobs).release(eq(7L), eq(1L), any(), eq(true), eq(false), eq(now), eq(now));
    }
}
