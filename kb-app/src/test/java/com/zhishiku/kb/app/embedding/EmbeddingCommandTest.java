package com.zhishiku.kb.app.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.embedding.mapper.EmbeddingCommandMapper;
import com.zhishiku.kb.app.embedding.mapper.EmbeddingProfileMapper;
import com.zhishiku.kb.app.embedding.mapper.EmbeddingStateMapper;
import com.zhishiku.kb.app.embedding.model.EmbeddingCommand;
import com.zhishiku.kb.app.embedding.model.EmbeddingReceipt;
import com.zhishiku.kb.app.embedding.service.EmbeddingCommandService;
import com.zhishiku.kb.app.embedding.service.EmbeddingCommandTransaction;
import com.zhishiku.kb.app.embedding.service.EmbeddingReadinessService;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import com.zhishiku.kb.common.embedding.EmbeddingRun;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmbeddingCommandTest {
    private final EmbeddingStateMapper state = mock(EmbeddingStateMapper.class);
    private final EmbeddingProfileMapper profiles = mock(EmbeddingProfileMapper.class);
    private final EmbeddingCommandMapper commands = mock(EmbeddingCommandMapper.class);
    private final EmbeddingReadinessService readiness = mock(EmbeddingReadinessService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final EmbeddingCommandTransaction transaction = new EmbeddingCommandTransaction(state, profiles, commands
            , readiness, json);
    private final EmbeddingCommandService service = new EmbeddingCommandService(transaction, profiles, state,
            readiness);
    private final EmbeddingProfile profile = new EmbeddingProfile("p", "remote", "source", "model", "v1", 1024,
            "cosine", "identity-v1", "identity-v1", "query", "document", "embeddings-v1");
    private final EmbeddingCommand start = new EmbeddingCommand("START", "p", null, 0, " repair ", "request-1");

    private void ready() {
        when(profiles.find("p")).thenReturn(profile);
        when(readiness.ready(profile)).thenReturn(true);
        when(state.lockExclusive()).thenReturn(new EmbeddingControl("UNINITIALIZED", null, null, null, null, 0, null));
        when(commands.createRun(any(), eq("p"), eq(7L))).thenReturn(1);
        when(commands.start(any(), eq("p"), eq(0L))).thenReturn(1);
        when(commands.record(any(), anyString(), anyString(), anyLong(), anyString(), any(), anyString(), anyString(),
                anyString(), anyString(), anyLong())).thenReturn(1);
    }

    @Test
    void startChecksReadinessOutsideAndInsideLockThenStoresFirstReceipt() throws Exception {
        ready();
        var receipt = service.execute(7, start);
        assertThat(receipt.epoch()).isEqualTo(1);
        assertThat(receipt.state()).isEqualTo("QUIESCING");
        assertThat(receipt.version()).isZero();
        assertThat(receipt.replayed()).isFalse();
        var order = inOrder(state, readiness, commands);
        order.verify(commands).operation("START", "request-1");
        order.verify(readiness).ready(profile);
        order.verify(state).lockExclusive();
        order.verify(commands).operation("START", "request-1");
        order.verify(readiness).ready(profile);
        order.verify(commands).createRun(receipt.runId(), "p", 7);
        order.verify(commands).start(receipt.runId(), "p", 0);
        var stored = ArgumentCaptor.forClass(String.class);
        order.verify(commands).record(eq(receipt.operationId()), eq("START"), eq("request-1"), eq(7L),
                eq(start.digest(7)), eq(receipt.runId()), eq("repair"), stored.capture(), eq("UNINITIALIZED"),
                eq("QUIESCING"), eq(1L));
        assertThat(json.readValue(stored.getValue(), EmbeddingReceipt.class)).isEqualTo(receipt);
    }

    @Test
    void replaySurvivesLaterStateAndUnavailableProfileButRejectsOtherActorOrInput() throws Exception {
        var receipt = new EmbeddingReceipt(UUID.randomUUID(), UUID.randomUUID(), "p", "QUIESCING", 0, 1, false);
        when(commands.operation("START", "request-1")).thenReturn(new EmbeddingCommandMapper.Operation(
                7, start.digest(7), json.writeValueAsString(receipt)));
        assertThat(service.execute(7, start)).isEqualTo(receipt.replay());
        assertStatus(409, () -> service.execute(8, start));
        assertStatus(409, () -> service.execute(7, new EmbeddingCommand("START", "p", null, 1, "repair", "request-1")));
        verifyNoInteractions(state, profiles, readiness);
    }

    @Test
    void concurrentCommittedRequestWinsBeforeVersionAndReadinessChecksInsideLock() throws Exception {
        ready();
        var receipt = new EmbeddingReceipt(UUID.randomUUID(), UUID.randomUUID(), "p", "QUIESCING", 0, 1, false);
        when(commands.operation("START", "request-1")).thenReturn(null, new EmbeddingCommandMapper.Operation(
                7, start.digest(7), json.writeValueAsString(receipt)));
        when(state.lockExclusive()).thenReturn(new EmbeddingControl("QUIESCING", null, null, "p",
                profile.fingerprint(), 1, receipt.runId()));
        assertThat(service.execute(7, start)).isEqualTo(receipt.replay());
        verify(commands, never()).createRun(any(), anyString(), anyLong());
        verify(readiness, times(1)).ready(profile);
    }

    @Test
    void rejectsUnknownProfileStaleEpochAndNotReadyWithoutWrites() {
        assertStatus(400, () -> service.execute(7, start));
        ready();
        when(readiness.ready(profile)).thenReturn(false);
        assertStatus(503, () -> service.execute(7, start));
        verify(state, never()).lockExclusive();
        when(readiness.ready(profile)).thenReturn(true, false);
        assertStatus(503, () -> service.execute(7, start));
        when(readiness.ready(profile)).thenReturn(true);
        when(state.lockExclusive()).thenReturn(new EmbeddingControl("ACTIVE", "p", profile.fingerprint(), null, null,
                9, null));
        assertStatus(409, () -> service.execute(7, start));
        verify(commands, never()).createRun(any(), anyString(), anyLong());
        verify(commands, never()).start(any(), anyString(), anyLong());
    }

    @Test
    void sameProfileRepairIsAllowedAndAuditFailurePropagates() {
        ready();
        when(state.lockExclusive()).thenReturn(new EmbeddingControl("ACTIVE", "p", profile.fingerprint(), null, null,
                0, null));
        when(commands.record(any(), anyString(), anyString(), anyLong(), anyString(), any(), anyString(), anyString(),
                anyString(), anyString(), anyLong())).thenThrow(new IllegalStateException("audit unavailable"));
        assertThatThrownBy(() -> service.execute(7, start)).hasMessage("audit unavailable");
        verify(commands).start(any(), eq("p"), eq(0L));
    }

    @Test
    void resumeOnlyRestoresFixedTargetAndSavedPhaseWithNewEpochAndVersion() {
        ready();
        UUID id = UUID.randomUUID();
        var resume = new EmbeddingCommand("RESUME", null, id, 6, "fixed", "resume-1");
        when(state.run(id)).thenReturn(new EmbeddingRun(id, "p", 7, "BLOCKED", 6, "REBUILDING", 9,
                null, null, 3, 3, null, 10, 4, 1, "MODEL_UNAVAILABLE"));
        when(state.lockExclusive()).thenReturn(new EmbeddingControl("BLOCKED", null, null, "p", profile.fingerprint()
                , 8, id));
        when(commands.resumeRun(id, 6)).thenReturn(1);
        when(commands.resumeControl(id, 8, "REBUILDING")).thenReturn(1);
        var result = service.execute(7, resume);
        assertThat(result.runId()).isEqualTo(id);
        assertThat(result.profileId()).isEqualTo("p");
        assertThat(result.version()).isEqualTo(7);
        assertThat(result.epoch()).isEqualTo(9);
        var order = inOrder(commands);
        order.verify(commands).resumeRun(id, 6);
        order.verify(commands).resumeItems(id);
        order.verify(commands).resumeControl(id, 8, "REBUILDING");
        verify(commands, never()).createRun(any(), anyString(), anyLong());
        assertStatus(409, () -> service.execute(7, new EmbeddingCommand("RESUME", null, id, 5, "fixed", "resume-2")));
        assertStatus(404, () -> service.execute(7, new EmbeddingCommand("RESUME", null, UUID.randomUUID(), 0, "fixed"
                , "unknown")));
    }

    @Test
    void transactionAndResumeSqlPreserveSuccessfulItemsAndHistoricalAttempts() throws Exception {
        var annotation = EmbeddingCommandTransaction.class.getMethod("execute", long.class, EmbeddingCommand.class,
                EmbeddingProfile.class).getAnnotation(Transactional.class);
        assertThat(annotation.timeout()).isEqualTo(10);
        assertThat(EmbeddingCommandService.class.getMethod("execute", long.class, EmbeddingCommand.class)
                .getAnnotation(Transactional.class)).isNull();
        String sql = String.join(" ", EmbeddingCommandMapper.class.getMethod("resumeItems", UUID.class)
                .getAnnotation(Update.class).value());
        assertThat(sql).contains("claim_token = claim_token + 1", "state IN ('FAILED', 'RUNNING', 'PENDING')")
                .doesNotContain("total_attempts =", "content_digest =", "SUCCEEDED", "SKIPPED");
        assertThat(String.join(" ", EmbeddingCommandMapper.class.getMethod("resumeRun", UUID.class, long.class)
                .getAnnotation(Update.class).value()))
                .contains("claim_token = claim_token + 1", "state = resume_phase", "lease_owner = NULL")
                .doesNotContain("succeeded_count =", "skipped_count =");
        assertThat(String.join(" ", EmbeddingCommandMapper.class.getMethod("resumeControl", UUID.class, long.class,
                        String.class)
                .getAnnotation(Update.class).value())).contains("epoch = epoch + 1");
        assertThat(start.reason()).isEqualTo("repair");
        assertThat(start.toString()).doesNotContain("repair", "request-1");
        assertThat(start.digest(7)).isEqualTo(new EmbeddingCommand("START", "p", null, 0, "repair", "another").digest(7));
    }

    private void assertStatus(int status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.httpStatus()).isEqualTo(status));
    }
}
