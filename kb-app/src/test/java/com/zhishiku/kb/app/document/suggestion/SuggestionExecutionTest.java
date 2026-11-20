package com.zhishiku.kb.app.document.suggestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.document.metadata.service.MetadataException;
import com.zhishiku.kb.app.document.model.DocumentEntity;
import com.zhishiku.kb.common.conversation.UsageSettlement;
import com.zhishiku.kb.common.suggestion.SuggestionData;
import com.zhishiku.kb.common.suggestion.SuggestionHttp;
import com.zhishiku.kb.common.suggestion.SuggestionProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SuggestionExecutionTest {
    private final UUID id = UUID.randomUUID(), execution = UUID.randomUUID();
    private final OffsetDateTime deadline = OffsetDateTime.now().plusMinutes(1);
    private final SuggestionStore store = mock(SuggestionStore.class);
    private final SuggestionMaterials materials = mock(SuggestionMaterials.class);
    private final SuggestionExecution service = new SuggestionExecution(store, materials, 30);
    private SuggestionJob job(String state, boolean sent, String settlement, OffsetDateTime end) {
        return new SuggestionJob(id, execution, 1, 2, 3, 4, 5, 6, 7, 8, end, state, null, sent ? end.minusMinutes(1) : null,
                100L, null, null, null, null, null, settlement);
    }

    @Test
    void onlyOneExecutorGetsPermitAndRevocationOrStaleExecutionBlocksSend() throws Exception {
        var job = job("RUNNING", false, "RESERVED", deadline);
        var used = new AtomicBoolean();
        when(store.lock(id)).thenReturn(job);
        when(store.activeSession(id, "token")).thenReturn(true);
        when(store.activeSession(id, "revoked")).thenReturn(true);
        when(store.dispatch(id)).thenAnswer(call -> used.compareAndSet(false, true));
        var action = new SuggestionProtocol.Action(SuggestionExecution.key(job), 100);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> service.action("permit", "token", action));
            var two = pool.submit(() -> service.action("permit", "token", action));
            assertThat(List.of(one.get(), two.get())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(service.action("permit", "token", action)).isFalse();
        assertThat(service.action("permit", "other-session", action)).isFalse();
        doThrow(new MetadataException(MetadataException.Reason.FORBIDDEN)).when(materials).authorizePermit("revoked", job);
        assertThatThrownBy(() -> service.action("permit", "revoked", action)).isInstanceOf(MetadataException.class);
        var expired = job("RUNNING", false, "RESERVED", deadline.minusMinutes(2));
        when(store.lock(id)).thenReturn(expired);
        assertThat(service.action("permit", "token", action)).isFalse();
        assertThat(service.action("permit", "token", new SuggestionProtocol.Action(SuggestionExecution.key(expired), 100))).isFalse();
    }

    @Test
    void cancelWinnerSuppressesLateContentButFreezesKnownOrUnknownUsage() {
        var result = new SuggestionData.Result(null, null, null);
        when(store.lock(id)).thenReturn(job("CANCELLED", true, "RESERVED", deadline));
        for (long count : List.of(0L, 10L, 150L)) {
            service.finish(id, execution, null, new SuggestionProtocol.Outcome(null, count, 0L, result));
            verify(store).freeze(id, new UsageSettlement.Value("KNOWN", count, 0L, count));
        }
        service.finish(id, execution, null, SuggestionProtocol.Outcome.failure("TIMEOUT"));
        verify(store).freeze(id, new UsageSettlement.Value("UNKNOWN", null, null, 100));
        when(store.lock(id)).thenReturn(job("CANCELLED", false, "RESERVING", deadline));
        service.finish(id, execution, null, SuggestionProtocol.Outcome.failure("TIMEOUT"));
        verify(store).freeze(id, new UsageSettlement.Value("NOT_INVOKED", 0L, 0L, 0));
        verify(store, never()).saveResult(any(), any(), any());
        verify(store, never()).terminate(any(), any(), any());
    }

    @Test
    void completionWinsCancelAndInvalidOutputRetainsReliableUsage() {
        var completed = job("COMPLETED", true, "RESERVED", deadline);
        var success = new SuggestionData.Result(null, null, null);
        when(store.lock(id)).thenReturn(job("RUNNING", true, "RESERVED", deadline));
        when(store.lockOwned(id, 1, 4)).thenReturn(completed);
        service.finish(id, execution, "token", new SuggestionProtocol.Outcome(null, 800L, 120L, success));
        verify(store).saveResult(eq(id), any(), eq(success));
        verify(store).terminate(id, "COMPLETED", null);
        var access = mock(SuggestionAccess.class);
        when(access.current("token", 4)).thenReturn(new SuggestionAccess.Context(1, 2, 3, 4, 5, 6L, 7, 8));
        var requests = new SuggestionRequests(access, store, mock(org.springframework.context.ApplicationEventPublisher.class), true);
        assertThat(requests.cancel("token", 4, id).status()).isEqualTo("COMPLETED");
        verify(store, never()).terminate(id, "CANCELLED", "CANCELLED");
        when(store.lock(id)).thenReturn(job("RUNNING", true, "RESERVED", deadline));
        service.finish(id, execution, "token", new SuggestionProtocol.Outcome("INVALID_MODEL_OUTPUT", 800L, 120L, null));
        verify(store).terminate(id, "FAILED", "INVALID_MODEL_OUTPUT");
        verify(store, times(2)).freeze(id, new UsageSettlement.Value("KNOWN", 800L, 120L, 920));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 100})
    void recoveryRetriesOnlyFrozenChargeAndFlagsMissingEvidence(long charge) throws Exception {
        var pending = new SuggestionJob(id, execution, 1, 2, 3, 4, 5, 6, 7, 8, deadline, "CANCELLED", "CANCELLED",
                deadline.minusMinutes(1), 100L, "KNOWN", charge, 0L, charge, null, "PENDING");
        when(store.pendingCharges()).thenReturn(List.of(id));
        when(store.find(id)).thenReturn(pending);
        var http = mock(SuggestionHttp.class);
        var json = new ObjectMapper();
        byte[] intent = json.writeValueAsBytes(new SuggestionProtocol.Charge(SuggestionExecution.key(pending), 100, charge));
        when(http.post(eq("settle"), eq(intent), isNull(), any(), eq(64))).thenThrow(new IllegalStateException("确认丢失"))
                .thenReturn("true".getBytes(), "false".getBytes());
        var runner = new SuggestionRunner(materials, service, store, json, http);
        try {
            runner.recover();
            verify(store, never()).settle(id, true);
            runner.recover();
            verify(store).settle(id, true);
            runner.recover();
            verify(store).settle(id, false);
            verify(http, times(3)).post(eq("settle"), eq(intent), isNull(), any(), eq(64));
            verify(http, never()).post(eq("run"), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
            verify(store, never()).terminate(any(), any(), any());
        } finally {
            runner.close();
        }
    }

    @Test
    void guardedSaveChecksLockedStateAndReplayOnlyRequiresCurrentAuthorization() {
        var access = mock(SuggestionAccess.class);
        var guard = new SuggestionSaveGuard(store, access);
        var adoption = new SuggestionSaveGuard.Adoption(id, List.of("summary"));
        var doc = DocumentEntity.builder().id(4L).spaceId(5L).currentVersionId(6L).metadataVersion(7L).build();
        when(store.find(id)).thenReturn(job("COMPLETED", true, "SETTLED", deadline));
        assertThatThrownBy(() -> guard.check(adoption, 2, doc, 8, false)).isInstanceOf(MetadataException.class);
        assertThatThrownBy(() -> guard.check(adoption, 1, doc, 8, false)).hasMessageContaining("失效");
        when(store.result(eq(id), any())).thenReturn(new SuggestionStore.StoredResult(deadline, "{}"));
        guard.check(adoption, 1, doc, 8, false);
        assertThatThrownBy(() -> guard.check(adoption, 1, doc, 9, false)).hasMessageContaining("目录");
        doc.setSpaceId(9L);
        assertThatThrownBy(() -> guard.check(adoption, 1, doc, 8, false)).hasMessageContaining("资料");
        doc.setCurrentVersionId(22L);
        assertThatThrownBy(() -> guard.check(adoption, 1, doc, 8, false)).hasMessageContaining("发布版本");
        guard.check(adoption, 1, doc, 10, true);
        doThrow(new MetadataException(MetadataException.Reason.NOT_FOUND)).when(access).requireSource(1, 4, 6);
        assertThatThrownBy(() -> guard.check(adoption, 1, doc, 10, true)).isInstanceOf(MetadataException.class);
    }
}
