package com.zhishiku.kb.worker.embedding;

import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import com.zhishiku.kb.common.embedding.EmbeddingReadiness;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.common.embedding.EmbeddingRun;
import com.zhishiku.kb.common.embedding.ProfileEmbeddingClient;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingReadinessMapper;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingStateMapper;
import com.zhishiku.kb.worker.embedding.service.EmbeddingActivationService;
import com.zhishiku.kb.worker.embedding.service.EmbeddingCoverageScanner;
import com.zhishiku.kb.worker.embedding.service.EmbeddingVerificationService;
import com.zhishiku.kb.worker.embedding.service.EmbeddingVerificationStore;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.nullable;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingActivationTest {
    private final EmbeddingProfile p = new EmbeddingProfile("p", "local", "source", "local-hash-v1", "1", 1024,
            "cosine", "identity-v1", "identity-v1", "query", "document", "local-hash-v1");
    private final EmbeddingControl control = new EmbeddingControl("VERIFYING", null, null, "p", p.fingerprint(), 4,
            UUID.randomUUID());
    private final EmbeddingRun run = new EmbeddingRun(control.runId(), "p", 1, "VERIFYING", 3, null, 7, null, null,
            0, 3, null, 1, 1, 0, null);
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final EmbeddingStateMapper state = mock(EmbeddingStateMapper.class);
    private final EmbeddingVerificationStore store = mock(EmbeddingVerificationStore.class);
    private final EmbeddingVerificationService verification = mock(EmbeddingVerificationService.class);
    private final EmbeddingReadinessMapper readiness = mock(EmbeddingReadinessMapper.class);

    private EmbeddingActivationService service() {
        when(state.lockExclusive()).thenReturn(control);
        when(state.run(control.runId())).thenReturn(run);
        when(store.activateRun(run)).thenReturn(1);
        when(store.activateControl(control)).thenReturn(1);
        when(store.blockRun(eq(run), anyString())).thenReturn(1);
        when(store.blockControl(control)).thenReturn(1);
        when(store.event(eq(control), eq(run), anyString(), nullable(String.class))).thenReturn(1);
        return new EmbeddingActivationService(state, store, verification, readiness, Clock.fixed(now, ZoneOffset.UTC));
    }

    private EmbeddingReadiness ready(EmbeddingRole role, Instant expires) {
        return new EmbeddingReadiness(UUID.randomUUID(), role, "p", p.fingerprint(), 1, true, now.minusSeconds(10),
                expires);
    }

    @Test
    void exclusiveTransactionRechecksCoverageAndFreshReadinessBeforeStateAndAudit() throws Exception {
        var service = service();
        when(readiness.findFresh("p")).thenReturn(List.of(ready(EmbeddingRole.QUERY, now.plusSeconds(20)),
                ready(EmbeddingRole.DOCUMENT, now.plusSeconds(20))));
        assertThat(service.activate(control)).isTrue();
        var order = inOrder(state, store, verification, readiness);
        order.verify(store).timeouts();
        order.verify(state).lockExclusive();
        order.verify(verification).coverageError(control);
        order.verify(readiness).findFresh("p");
        order.verify(store).activateRun(run);
        order.verify(store).activateControl(control);
        order.verify(store).event(control, run, "SUCCEEDED", null);
        assertThat(Arrays.stream(EmbeddingActivationService.class.getDeclaredFields()).map(Field::getType))
                .doesNotContain(EmbeddingClientRegistry.class, ProfileEmbeddingClient.class);
        assertThat(EmbeddingActivationService.class.getMethod("activate", EmbeddingControl.class)
                .getAnnotation(Transactional.class).timeout()).isEqualTo(10);
    }

    @Test
    void expiredReadinessOrCoverageFailureBlocksInsteadOfActivating() {
        var service = service();
        when(readiness.findFresh("p")).thenReturn(List.of(ready(EmbeddingRole.QUERY, now),
                ready(EmbeddingRole.DOCUMENT, now.plusSeconds(20))));
        assertThat(service.activate(control)).isFalse();
        verify(store).blockRun(run, "READINESS_EXPIRED");
        verify(store, never()).activateRun(any());
        when(verification.coverageError(control)).thenReturn("MISSING_CHUNKS");
        assertThat(service.activate(control)).isFalse();
        verify(store).blockRun(run, "MISSING_CHUNKS");
    }

    @Test
    void auditFailurePropagatesAndAlreadyActivatedStateDoesNotWriteAgain() {
        var service = service();
        when(readiness.findFresh("p")).thenReturn(List.of(ready(EmbeddingRole.QUERY, now.plusSeconds(20)),
                ready(EmbeddingRole.DOCUMENT, now.plusSeconds(20))));
        when(store.event(control, run, "SUCCEEDED", null)).thenThrow(new IllegalStateException("audit unavailable"));
        assertThatThrownBy(() -> service.activate(control)).hasMessage("audit unavailable");
        clearInvocations(store);
        when(state.lockExclusive()).thenReturn(new EmbeddingControl("ACTIVE", "p", p.fingerprint(), null, null, 5,
                null));
        assertThat(service.activate(control)).isFalse();
        verify(store, never()).activateRun(any());
        verify(store, never()).event(any(), any(), anyString(), any());
    }

    @Test
    void lockTimeoutLeavesMaintenanceAndRunnerRecordsDiagnosticInNewTransaction() {
        var verification = mock(EmbeddingVerificationService.class);
        var scanner = mock(EmbeddingCoverageScanner.class);
        var activation = mock(EmbeddingActivationService.class);
        when(verification.begin()).thenReturn(control);
        when(activation.activate(control)).thenThrow(new CannotAcquireLockException("lock timeout"));
        new EmbeddingVerificationRunner(verification, scanner, activation).tick();
        var order = inOrder(scanner, activation);
        order.verify(scanner).scan(control);
        order.verify(activation).activate(control);
        order.verify(activation).block(control, "DATABASE_ERROR");
    }

    @Test
    void coordinatorWithoutTargetClientUsesFleetReadinessAndOtherCoordinatorDoesNotCompleteTwice() {
        var first = service();
        var second = new EmbeddingActivationService(state, store, verification, readiness, Clock.fixed(now,
                ZoneOffset.UTC));
        when(readiness.findFresh("p")).thenReturn(List.of(ready(EmbeddingRole.QUERY, now.plusSeconds(20)),
                ready(EmbeddingRole.DOCUMENT, now.plusSeconds(20))));
        var stored = new AtomicReference<>(control);
        when(state.lockExclusive()).thenAnswer(call -> stored.get());
        when(store.activateControl(control)).thenAnswer(call -> {
            stored.set(new EmbeddingControl("ACTIVE", "p", p.fingerprint(), null, null, 5, null));
            return 1;
        });
        assertThat(first.activate(control)).isTrue();
        assertThat(second.activate(control)).isFalse();
        verify(store, times(1)).activateControl(control);
        verify(store, times(1)).event(control, run, "SUCCEEDED", null);
        verify(store, never()).blockControl(any());
    }

    @Test
    void fleetReadinessStillRequiresBothMatchingFreshRolesAndProtocol() {
        var service = service();
        var query = ready(EmbeddingRole.QUERY, now.plusSeconds(20));
        var doc = ready(EmbeddingRole.DOCUMENT, now.plusSeconds(20));
        for (var rows : List.of(List.<EmbeddingReadiness>of(), List.of(query), List.of(doc),
                List.of(query, new EmbeddingReadiness(doc.instanceId(), doc.role(), "other", p.fingerprint(), 1, true
                        , now, doc.validUntil())),
                List.of(query, new EmbeddingReadiness(doc.instanceId(), doc.role(), "p", "wrong", 1, true, now,
                        doc.validUntil())),
                List.of(query, new EmbeddingReadiness(doc.instanceId(), doc.role(), "p", p.fingerprint(), 2, true,
                        now, doc.validUntil())),
                List.of(query, new EmbeddingReadiness(doc.instanceId(), doc.role(), "p", p.fingerprint(), 1, false,
                        now, doc.validUntil())),
                List.of(query, ready(EmbeddingRole.DOCUMENT, now)),
                List.of(query, new EmbeddingReadiness(doc.instanceId(), doc.role(), "p", p.fingerprint(), 1, true,
                        now.plusSeconds(1), doc.validUntil())))) {
            clearInvocations(store);
            when(readiness.findFresh("p")).thenReturn(rows);
            assertThat(service.activate(control)).isFalse();
            verify(store).blockRun(run, "READINESS_EXPIRED");
            verify(store, never()).activateRun(any());
        }
    }
}
