package com.zhishiku.kb.app.aiusage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.aiusage.mapper.AiUsageMapper;
import com.zhishiku.kb.app.aiusage.model.AiCallRow;
import com.zhishiku.kb.app.aiusage.model.AiEventRow;
import com.zhishiku.kb.app.aiusage.service.AiUsageWriter;
import com.zhishiku.kb.common.aiusage.AiObservation;
import com.zhishiku.kb.common.api.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiUsageWriterTest {
    private final AiUsageMapper mapper = mock(AiUsageMapper.class);
    private final AiObservationProperties properties = new AiObservationProperties(new MockEnvironment());
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Instant now = Instant.parse("2026-10-24T00:00:00Z");
    private final Map<String, AiCallRow> calls = new HashMap<>();
    private final Map<String, AiEventRow> phases = new HashMap<>();
    private final UUID id = UUID.randomUUID();
    private final UUID lease = UUID.randomUUID();
    private final AiUsageWriter writer = new AiUsageWriter(mapper, properties, json, Clock.fixed(now, ZoneOffset.UTC));

    AiUsageWriterTest() {
        properties.setEnabled(true);
        when(mapper.lockCall(anyString())).thenAnswer(i -> copy(calls.get(i.getArgument(0))));
        when(mapper.phase(anyString(), anyString())).thenAnswer(i -> phases.get(i.getArgument(0) + ":" + i.getArgument(1)));
        when(mapper.insertCall(any())).thenAnswer(i -> { var r = (AiCallRow) i.getArgument(0); calls.put(r.getCallId(), copy(r)); return 1; });
        when(mapper.updateCall(any())).thenAnswer(i -> {
            var r = (AiCallRow) i.getArgument(0);
            var stored = copy(r);
            stored.setVersion(r.getVersion() + 1);
            calls.put(r.getCallId(), stored);
            return 1;
        });
        when(mapper.insertEvent(any())).thenAnswer(i -> { var e = (AiEventRow) i.getArgument(0); phases.put(e.getCallId() + ":" + e.getKind(), e); return 1; });
    }

    private AiCallRow copy(AiCallRow row) { return row == null ? null : json.convertValue(row, AiCallRow.class); }
    private void register() { writer.register(new AiObservation.Registration(1, id, now, "SINGLE", null), 1, 2); }
    private AiObservation.Event phase(String kind) { return event(kind, null, null, null, null, null, null, null, null, null); }
    private AiObservation.Event event(String kind, UUID quota, Long reserved, String gen, String result, String usage,
                                     Long prompt, Long completion, Long amount, String settlement) {
        return new AiObservation.Event(1, id, AiObservation.eventId(id, kind), 1, kind, now, quota, reserved,
                gen, result, usage, prompt, completion, amount, settlement);
    }

    @Test
    void duplicateRegistrationAndEventsOnlyPublishOnceAndConflictCannotOverwrite() {
        register();
        register();
        writer.accept(phase("ACQUIRE_INTENDED"));
        var reserved = event("RESERVED", lease, 12000L, null, null, null, null, null, null, null);
        writer.accept(reserved);
        writer.accept(reserved);
        writer.accept(phase("DISPATCHED"));
        var end = event("FINALIZED", null, null, "COMPLETED", "MODEL", "KNOWN", 10L, 5L, 15L, "PENDING");
        writer.accept(end);
        writer.accept(end);
        assertThat(phases).hasSize(5);
        assertThatThrownBy(() -> writer.accept(event("FINALIZED", null, null, "COMPLETED", "MODEL", "KNOWN", 10L, 6L, 16L, "PENDING")))
                .isInstanceOf(ApiException.class);
        var confirmed = writer.accept(event("SETTLED", lease, null, null, null, null, null, null, 15L, null));
        assertThat(AiUsageWriter.publicView(confirmed)).containsEntry("settledChargedTokens", "15")
                .doesNotContainKeys("leaseId", "registrationDigest", "principalFingerprint");
        assertThat(confirmed.getRetentionAnchorAt()).isEqualTo(now);
        var order = inOrder(mapper);
        order.verify(mapper).registrationLock(id.toString());
        order.verify(mapper).lockCall(id.toString());
        order.verify(mapper).insertCall(any());
        order.verify(mapper).publicationLock();
        order.verify(mapper).insertEvent(any());
    }

    @Test
    void unknownUsageKeepsExistingSingleAmountAndPendingIsNotSettled() {
        register();
        writer.accept(phase("ACQUIRE_INTENDED"));
        writer.accept(event("RESERVED", lease, 12000L, null, null, null, null, null, null, null));
        writer.accept(phase("DISPATCHED"));
        var row = writer.accept(event("FINALIZED", null, null, "CANCELLED", "CANCELLED", "UNKNOWN", null, null, 0L, "PENDING"));
        assertThat(row.getPromptTokens()).isNull();
        assertThat(row.getPlannedChargedTokens()).isZero();
        assertThat(row.getRetentionAnchorAt()).isNull();
        assertThat(AiUsageWriter.publicView(row).get("settledChargedTokens")).isNull();
        assertThat(writer.accept(phase("DISPATCHED")).getGenerationStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void expiredGenerationIsInterruptedWithoutCallingAnyExternalDependency() {
        register();
        writer.accept(phase("ACQUIRE_INTENDED"));
        var current = calls.get(id.toString());
        current.setLeaseUntil(now);
        writer.recover(id.toString());
        assertThat(calls.get(id.toString()).getGenerationStatus()).isEqualTo("INTERRUPTED");
        assertThat(calls.get(id.toString()).getSettlementStatus()).isEqualTo("REVIEW");
        assertThat(calls.get(id.toString()).getUsageStatus()).isEqualTo("NOT_INVOKED");
        assertThatThrownBy(() -> writer.renew(id, 1)).isInstanceOf(ApiException.class);
    }

    @Test
    void oldIntentAndMissingCallCannotRecreatePurgedRecords() {
        assertThatThrownBy(() -> writer.register(new AiObservation.Registration(1, id, now.minusSeconds(301), "SINGLE", null), 1, 2))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> writer.accept(phase("DISPATCHED"))).isInstanceOf(ApiException.class);
        verify(mapper, never()).insertCall(any());
    }

    @Test
    void multiRegistrationPublishesBothEventsAfterAllCallStateWrites() {
        writer.registerMulti(new AiObservation.Registration(1, id, now, "MULTI", id), 1, 2);
        assertThat(calls.get(id.toString()).isQuotaIntended()).isTrue();
        assertThat(phases).containsKeys(id + ":REGISTERED", id + ":ACQUIRE_INTENDED");
        var order = inOrder(mapper);
        order.verify(mapper).registrationLock(id.toString()); order.verify(mapper).lockCall(id.toString());
        order.verify(mapper).insertCall(any());
        order.verify(mapper).publicationLock(); order.verify(mapper).insertEvent(any());
        order.verify(mapper).publicationLock(); order.verify(mapper).insertEvent(any()); order.verifyNoMoreInteractions();
        writer.registerMulti(new AiObservation.Registration(1, id, now, "MULTI", id), 1, 2);
        assertThat(phases).hasSize(2);
    }

    @Test
    void terminalUnconfirmedSingleMovesToReviewAndAcceptsOnlyFixedConfirmation() {
        register(); writer.accept(phase("ACQUIRE_INTENDED"));
        writer.accept(event("RESERVED", lease, 12000L, null, null, null, null, null, null, null));
        writer.accept(event("FINALIZED", null, null, "COMPLETED", "NO_RESULTS", "NOT_INVOKED", 0L, 0L, 0L, "PENDING"));
        var recovery = new AiUsageWriter(mapper, properties, json, Clock.fixed(now.plusSeconds(180), ZoneOffset.UTC));
        recovery.recover(id.toString()); recovery.recover(id.toString());
        assertThat(calls.get(id.toString()).getGenerationStatus()).isEqualTo("COMPLETED");
        assertThat(calls.get(id.toString()).getSettlementStatus()).isEqualTo("REVIEW");
        assertThat(phases).hasSize(5);
        var settled = recovery.accept(event("SETTLED", lease, null, null, null, null, null, null, 0L, null));
        assertThat(settled.getSettlementStatus()).isEqualTo("SETTLED");
        assertThat(settled.getRetentionAnchorAt()).isEqualTo(now.plusSeconds(180));
    }
}
