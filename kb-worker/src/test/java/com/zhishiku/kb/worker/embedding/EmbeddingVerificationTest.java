package com.zhishiku.kb.worker.embedding;

import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.embedding.EmbeddingRun;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingStateMapper;
import com.zhishiku.kb.worker.embedding.service.EmbeddingCoverageScanner;
import com.zhishiku.kb.worker.embedding.service.EmbeddingVerificationService;
import com.zhishiku.kb.worker.embedding.service.EmbeddingVerificationStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingVerificationTest {
    private final EmbeddingControl control = new EmbeddingControl("VERIFYING", null, null, "p", "fp", 4,
            UUID.randomUUID());

    @Test
    void scansActualPagesAndRejectsInvalidOrMissingIdentityEvenWhenCountsClaimComplete() {
        var verification = mock(EmbeddingVerificationService.class);
        when(verification.page(control, 0)).thenReturn(List.of(new EmbeddingVerificationStore.Chunk(1, true)));
        when(verification.page(control, 1)).thenReturn(List.of(new EmbeddingVerificationStore.Chunk(2, false)));
        assertThat(new EmbeddingCoverageScanner(verification).scan(control)).isEqualTo("COVERAGE_INVALID");
        verify(verification, never()).check(any());
    }

    @Test
    void emptyTargetAndDeletionShrunkRangeStillRequireFinalChecks() {
        var verification = mock(EmbeddingVerificationService.class);
        when(verification.page(control, 0)).thenReturn(List.of());
        assertThat(new EmbeddingCoverageScanner(verification).scan(control)).isNull();
        verify(verification).check(control);
        when(verification.check(control)).thenReturn("MISSING_CHUNKS");
        assertThat(new EmbeddingCoverageScanner(verification).scan(control)).isEqualTo("MISSING_CHUNKS");
    }

    @Test
    void actualCoverageOutstandingClaimsAndMissingChunksEachPreventCompletion() {
        var state = mock(EmbeddingStateMapper.class);
        var store = mock(EmbeddingVerificationStore.class);
        when(state.lockShared()).thenReturn(control);
        when(store.targetsReady(control.runId())).thenReturn(true);
        var verification = new EmbeddingVerificationService(state, store);
        assertThat(verification.check(control)).isNull();
        when(store.invalidCoverage(control.runId(), "p")).thenReturn(true);
        assertThat(verification.check(control)).isEqualTo("COVERAGE_INVALID");
        when(store.invalidCoverage(control.runId(), "p")).thenReturn(false);
        when(store.unfinished(control.runId())).thenReturn(true);
        assertThat(verification.check(control)).isEqualTo("COVERAGE_INVALID");
        when(store.unfinished(control.runId())).thenReturn(false);
        when(store.executionOutstanding(control.runId())).thenReturn(true);
        assertThat(verification.check(control)).isEqualTo("COVERAGE_INVALID");
        when(store.missingChunks()).thenReturn(true);
        assertThat(verification.check(control)).isEqualTo("MISSING_CHUNKS");
    }

    @Test
    void beginWaitsForUnfinishedWorkAndFencesTokensBeforeVerifying() {
        var state = mock(EmbeddingStateMapper.class);
        var store = mock(EmbeddingVerificationStore.class);
        var previous = new EmbeddingControl("REBUILDING", null, null, "p", "fp", 3, control.runId());
        var run = new EmbeddingRun(control.runId(), "p", 1, "REBUILDING", 2, null, 4, null, null, 0, 3, null, 9, 9, 0
                , null);
        when(state.lockExclusive()).thenReturn(previous);
        when(state.run(control.runId())).thenReturn(run);
        when(store.targetsReady(control.runId())).thenReturn(true);
        when(store.unfinished(control.runId())).thenReturn(true, false);
        when(store.beginRun(run)).thenReturn(1);
        when(store.beginControl(previous)).thenReturn(1);
        var service = new EmbeddingVerificationService(state, store);
        assertThat(service.begin()).isNull();
        assertThat(service.begin()).isEqualTo(control);
        verify(store).beginRun(run);
        verify(store).beginControl(previous);
    }
}
