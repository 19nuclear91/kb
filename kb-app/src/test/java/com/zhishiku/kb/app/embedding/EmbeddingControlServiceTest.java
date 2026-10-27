package com.zhishiku.kb.app.embedding;

import com.zhishiku.kb.app.embedding.mapper.EmbeddingControlMapper;
import com.zhishiku.kb.app.embedding.mapper.EmbeddingStateMapper;
import com.zhishiku.kb.app.embedding.service.EmbeddingControlService;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.embedding.EmbeddingRun;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.ConcurrentModificationException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmbeddingControlServiceTest {
    private final EmbeddingStateMapper state = mock(EmbeddingStateMapper.class);
    private final EmbeddingControlMapper commands = mock(EmbeddingControlMapper.class);
    private final UUID runId = UUID.randomUUID();

    private EmbeddingControl control(String phase, long epoch) {
        return new EmbeddingControl(phase, null, null, "target", "fingerprint", epoch, runId);
    }

    private EmbeddingRun run(String phase, long version) {
        return new EmbeddingRun(runId, "target", 1, phase, version, null, 0, null, null,
                0, 3, null, 0, 0, 0, null);
    }

    @Test
    void reconstructedServiceReadsStoredStateWithoutActivatingOrResettingEpoch() {
        AtomicReference<EmbeddingControl> stored = new AtomicReference<>(
                new EmbeddingControl("UNINITIALIZED", null, null, null, null, 0, null));
        when(state.control()).thenAnswer(ignored -> stored.get());
        assertThat(new EmbeddingControlService(state, commands).current().state()).isEqualTo("UNINITIALIZED");
        stored.set(control("REBUILDING", 17));
        var reconstructed = new EmbeddingControlService(state, commands);
        assertThat(reconstructed.current()).isEqualTo(stored.get());
        verifyNoInteractions(commands);
    }

    @Test
    void phaseAdvanceUsesControlLockThenCasForBothVersions() throws Exception {
        when(state.lockExclusive()).thenReturn(control("QUIESCING", 2));
        when(state.run(runId)).thenReturn(run("QUIESCING", 3));
        when(commands.advance(runId, "QUIESCING", 2, "REBUILDING")).thenReturn(1);
        when(commands.advanceRun(runId, "QUIESCING", 3, "REBUILDING")).thenReturn(1);
        new EmbeddingControlService(state, commands).advancePhase(runId, 2, 3, "REBUILDING");
        var order = inOrder(state, commands);
        order.verify(state).lockExclusive();
        order.verify(state).run(runId);
        order.verify(commands).advance(runId, "QUIESCING", 2, "REBUILDING");
        order.verify(commands).advanceRun(runId, "QUIESCING", 3, "REBUILDING");
        var boundary = EmbeddingControlService.class.getMethod("advancePhase", UUID.class, long.class, long.class,
                        String.class)
                .getAnnotation(Transactional.class);
        assertThat(boundary.timeout()).isEqualTo(10);
    }

    @Test
    void casMissPropagatesAndGenericPhaseAdvanceCannotActivate() {
        when(state.lockExclusive()).thenReturn(control("QUIESCING", 2));
        when(state.run(runId)).thenReturn(run("QUIESCING", 3));
        var service = new EmbeddingControlService(state, commands);
        assertThatThrownBy(() -> service.advancePhase(runId, 2, 3, "REBUILDING"))
                .isInstanceOf(ConcurrentModificationException.class);
        verify(commands, never()).advanceRun(any(), any(), anyLong(), any());
        assertThatThrownBy(() -> service.advancePhase(runId, 2, 3, "ACTIVE"))
                .isInstanceOf(IllegalArgumentException.class);
        when(commands.advance(runId, "QUIESCING", 2, "REBUILDING")).thenReturn(1);
        assertThatThrownBy(() -> service.advancePhase(runId, 2, 3, "REBUILDING"))
                .isInstanceOf(ConcurrentModificationException.class);
        assertThatThrownBy(() -> service.advancePhase(runId, 1, 3, "REBUILDING"))
                .isInstanceOf(ConcurrentModificationException.class);
    }

    @Test
    void missingStateAndStoreErrorsAreNotTreatedAsActive() {
        var service = new EmbeddingControlService(state, commands);
        assertThatThrownBy(service::current).isInstanceOf(IllegalStateException.class);
        when(state.control()).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(service::current).hasMessage("database unavailable");
        when(state.run(runId)).thenReturn(run("REBUILDING", 3));
        assertThat(service.run(runId).version()).isEqualTo(3);
        verifyNoInteractions(commands);
    }
}
