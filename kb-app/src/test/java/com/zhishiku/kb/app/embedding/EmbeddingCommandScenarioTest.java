package com.zhishiku.kb.app.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.config.GlobalExceptionHandler;
import com.zhishiku.kb.app.embedding.mapper.EmbeddingCommandMapper;
import com.zhishiku.kb.app.embedding.mapper.EmbeddingProfileMapper;
import com.zhishiku.kb.app.embedding.mapper.EmbeddingReadinessMapper;
import com.zhishiku.kb.app.embedding.mapper.EmbeddingStateMapper;
import com.zhishiku.kb.app.embedding.model.EmbeddingCommand;
import com.zhishiku.kb.app.embedding.model.EmbeddingReceipt;
import com.zhishiku.kb.app.embedding.service.EmbeddingAuthorizer;
import com.zhishiku.kb.app.embedding.service.EmbeddingCommandService;
import com.zhishiku.kb.app.embedding.service.EmbeddingCommandTransaction;
import com.zhishiku.kb.app.embedding.service.EmbeddingReadinessService;
import com.zhishiku.kb.app.embedding.web.EmbeddingCommandController;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import com.zhishiku.kb.common.embedding.EmbeddingReadiness;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.common.embedding.EmbeddingRun;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EmbeddingCommandScenarioTest {
    private final EmbeddingStateMapper state = mock(EmbeddingStateMapper.class);
    private final EmbeddingProfileMapper profiles = mock(EmbeddingProfileMapper.class);
    private final EmbeddingCommandMapper commands = mock(EmbeddingCommandMapper.class);
    private final EmbeddingReadinessService readiness = mock(EmbeddingReadinessService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final EmbeddingProfile old = profile("old");
    private final EmbeddingProfile next = profile("next");
    private final Map<UUID, EmbeddingRun> runs = new HashMap<>();
    private final Map<String, EmbeddingCommandMapper.Operation> operations = new HashMap<>();
    private EmbeddingControl control = new EmbeddingControl("ACTIVE", "old", old.fingerprint(), null, null, 8, null);

    private EmbeddingCommandService service(EmbeddingReadinessService checks) {
        when(profiles.find("old")).thenReturn(old);
        when(profiles.find("next")).thenReturn(next);
        when(state.lockExclusive()).thenAnswer(call -> control);
        when(state.run(any())).thenAnswer(call -> runs.get(call.getArgument(0)));
        when(commands.operation(anyString(), anyString()))
                .thenAnswer(call -> operations.get(call.getArgument(0) + ":" + call.getArgument(1)));
        return new EmbeddingCommandService(new EmbeddingCommandTransaction(state, profiles, commands, checks, json),
                profiles, state, checks);
    }

    private MockMvc mvc(EmbeddingCommandService service) {
        var authorizer = mock(EmbeddingAuthorizer.class);
        when(authorizer.require(Permission.EMBEDDING_REBUILD)).thenReturn(7L);
        return MockMvcBuilders.standaloneSetup(new EmbeddingCommandController(authorizer, service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @ParameterizedTest
    @CsvSource({"next, old", "old, next"})
    void competingDifferentRequestsAndTargetsHaveOneWinnerAfterInterleavedPreflight(String winnerId, String loserId) throws Exception {
        var service = service(readiness);
        var mvc = mvc(service);
        EmbeddingProfile winnerProfile = profiles.find(winnerId);
        EmbeddingProfile loserProfile = profiles.find(loserId);
        when(readiness.ready(winnerProfile)).thenReturn(true);
        doAnswer(call -> {
            UUID id = call.getArgument(0);
            assertThat(runs).isEmpty();
            runs.put(id, new EmbeddingRun(id, call.getArgument(1), call.getArgument(2), "QUIESCING", 0,
                    null, 0, null, null, 0, 3, null, 0, 0, 0, null));
            return 1;
        }).when(commands).createRun(any(), anyString(), anyLong());
        doAnswer(call -> {
            assertThat(control.active()).isTrue();
            assertThat((Long) call.getArgument(2)).isEqualTo(control.epoch());
            control = new EmbeddingControl("QUIESCING", old.profileId(), old.fingerprint(),
                    call.getArgument(1), winnerProfile.fingerprint(), control.epoch() + 1, call.getArgument(0));
            return 1;
        }).when(commands).start(any(), anyString(), anyLong());
        doAnswer(call -> {
            String key = call.getArgument(1) + ":" + call.getArgument(2);
            assertThat(operations).doesNotContainKey(key);
            operations.put(key, new EmbeddingCommandMapper.Operation(call.getArgument(3), call.getArgument(4),
                    call.getArgument(7)));
            return 1;
        }).when(commands).record(any(), anyString(), anyString(), anyLong(), anyString(), any(), anyString(),
                anyString(), anyString(), anyString(), anyLong());
        var winner = new AtomicReference<EmbeddingReceipt>();
        var interleaved = new AtomicBoolean();
        when(readiness.ready(loserProfile)).thenAnswer(call -> {
            if (interleaved.compareAndSet(false, true)) {
                // 两个请求均已进入锁前路径；用确定性交错模拟另一事务先提交，不验证真实锁互斥。
                assertThat(control.epoch()).isEqualTo(8);
                assertThat(operations).isEmpty();
                var response = mvc.perform(post("/api/admin/embedding/rebuilds").contentType(MediaType.APPLICATION_JSON)
                                .content(startBody(winnerId, 8, "winner")))
                        .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.replayed").value(false))
                        .andReturn().getResponse();
                winner.set(json.treeToValue(json.readTree(response.getContentAsString()).get("data"),
                        EmbeddingReceipt.class));
            }
            return true;
        });

        mvc.perform(post("/api/admin/embedding/rebuilds").contentType(MediaType.APPLICATION_JSON)
                .content(startBody(loserId, 8, "loser"))).andExpect(status().isConflict());
        assertThat(interleaved).isTrue();
        var committed = control;
        var savedRuns = Map.copyOf(runs);
        var savedOperations = Map.copyOf(operations);
        // 即使后来者改用当前 epoch，也不能绕过未完成作业门禁。
        mvc.perform(post("/api/admin/embedding/rebuilds").contentType(MediaType.APPLICATION_JSON)
                .content(startBody(loserId, control.epoch(), "loser-current"))).andExpect(status().isConflict());
        assertThat(service.execute(7, new EmbeddingCommand("START", winnerId, null, 8, "repair", "winner")))
                .isEqualTo(winner.get().replay());
        assertThat(control).isEqualTo(committed);
        assertThat(control.targetProfileId()).isEqualTo(winnerId);
        assertThat(control.runId()).isEqualTo(winner.get().runId());
        assertThat(runs).hasSize(1).containsExactlyInAnyOrderEntriesOf(savedRuns);
        assertThat(operations).hasSize(1).containsExactlyInAnyOrderEntriesOf(savedOperations);
        verify(commands).createRun(winner.get().runId(), winnerId, 7L);
        verify(commands).start(winner.get().runId(), winnerId, 8L);
        verify(commands).record(eq(winner.get().operationId()), eq("START"), eq("winner"), eq(7L), anyString(),
                eq(winner.get().runId()), eq("repair"), anyString(), eq("ACTIVE"), eq("QUIESCING"), eq(9L));
        verify(commands, times(3)).operation("START", "winner");
        verify(commands, times(2)).operation("START", "loser");
        verify(commands, times(2)).operation("START", "loser-current");
        verifyNoMoreInteractions(commands);
        verify(state, times(3)).lockExclusive();
        verify(readiness, times(2)).ready(winnerProfile);
        verify(readiness, times(2)).ready(loserProfile);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REBUILDING", "VERIFYING", "BLOCKED"})
    void partiallyRebuiltRunRejectsOldTargetWithoutChangingProgressOrMaintenance(String mode) throws Exception {
        var service = service(readiness);
        seedRun(mode, "REBUILDING");
        when(readiness.ready(old)).thenReturn(true);
        var before = control;
        var savedRuns = Map.copyOf(runs);
        var savedOperations = Map.copyOf(operations);

        var mvc = mvc(service);
        mvc.perform(post("/api/admin/embedding/rebuilds").contentType(MediaType.APPLICATION_JSON)
                        .content(startBody("old", control.epoch(), "rollback-old")))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/admin/embedding/rebuilds/" + control.runId() + "/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profileId\":\"old\",\"expectedVersion\":6,\"reason\":\"rollback\"," +
                                "\"requestId\":\"retarget\"}"))
                .andExpect(status().isBadRequest());

        assertRetained(before, savedRuns, savedOperations);
        verify(state).lockExclusive();
        verify(readiness).ready(old);
        verify(commands, times(2)).operation("START", "rollback-old");
        verifyNoMoreInteractions(commands);
    }

    @ParameterizedTest
    @CsvSource({"QUIESCING, false", "QUIESCING, true", "REBUILDING, false", "REBUILDING, true", "VERIFYING, false",
            "VERIFYING, true"})
    void resumePreflightFailureBeforeOrInsideLockReturns503AndPreservesBlockedRun(String phase,
                                                                                  boolean expiresInsideLock) throws Exception {
        var rows = mock(EmbeddingReadinessMapper.class);
        var clock = mock(Clock.class);
        Instant now = Instant.parse("2026-10-07T07:00:00Z");
        Instant expiry = now.plusSeconds(30);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(clock.instant()).thenReturn(expiresInsideLock ? now : expiry);
        when(rows.findFresh("next")).thenReturn(List.of(
                new EmbeddingReadiness(UUID.randomUUID(), EmbeddingRole.QUERY, "next", next.fingerprint(), 1, true,
                        now, expiry),
                new EmbeddingReadiness(UUID.randomUUID(), EmbeddingRole.DOCUMENT, "next", next.fingerprint(), 1, true
                        , now, expiry)));
        var service = service(new EmbeddingReadinessService(rows, clock));
        seedRun("BLOCKED", phase);
        var before = control;
        var savedRuns = Map.copyOf(runs);
        var savedOperations = Map.copyOf(operations);
        if (expiresInsideLock) {
            when(state.lockExclusive()).thenAnswer(call -> {
                when(clock.instant()).thenReturn(expiry);
                return control;
            });
        }

        mvc(service).perform(post("/api/admin/embedding/rebuilds/" + control.runId() + "/resume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":6,\"reason\":\"fixed\",\"requestId\":\"resume-failed\"}"))
                .andExpect(status().isServiceUnavailable());

        assertRetained(before, savedRuns, savedOperations);
        assertThat(runs.get(control.runId()).resumePhase()).isEqualTo(phase);
        var order = inOrder(commands, rows, state);
        order.verify(commands).operation("RESUME", "resume-failed");
        order.verify(state).run(control.runId());
        order.verify(rows).findFresh("next");
        if (expiresInsideLock) {
            order.verify(state).lockExclusive();
            order.verify(commands).operation("RESUME", "resume-failed");
            order.verify(state).run(control.runId());
            order.verify(rows).findFresh("next");
        } else {
            verify(state, never()).lockExclusive();
        }
        // 包括 resumeItems、计数/目标修改和成功审计在内，拒绝分支不能调用任何写方法。
        verifyNoMoreInteractions(commands, rows, state);
    }

    private void seedRun(String mode, String phase) {
        UUID id = UUID.randomUUID();
        control = new EmbeddingControl(mode, "old", old.fingerprint(), "next", next.fingerprint(), 8, id);
        long succeeded = "QUIESCING".equals(phase) ? 0 : 4;
        long skipped = "QUIESCING".equals(phase) ? 0 : 1;
        long total = "QUIESCING".equals(phase) ? 0 : "VERIFYING".equals(phase) || "VERIFYING".equals(mode) ? 5 : 10;
        runs.put(id, new EmbeddingRun(id, "next", 7, mode, 6, "BLOCKED".equals(mode) ? phase : null,
                9, null, null, 3, 3, null, total, succeeded, skipped, "BLOCKED".equals(mode) ? "MODEL_UNAVAILABLE" :
                null));
        operations.put("START:original", new EmbeddingCommandMapper.Operation(7, "original-digest", "original-receipt"
        ));
    }

    private void assertRetained(EmbeddingControl before, Map<UUID, EmbeddingRun> savedRuns,
                                Map<String, EmbeddingCommandMapper.Operation> savedOperations) {
        assertThat(control).isEqualTo(before);
        assertThat(control.active()).isFalse();
        assertThat(control.activeProfileId()).isEqualTo("old");
        assertThat(control.targetProfileId()).isEqualTo("next");
        assertThat(runs).containsExactlyInAnyOrderEntriesOf(savedRuns);
        assertThat(operations).containsExactlyInAnyOrderEntriesOf(savedOperations);
    }

    private String startBody(String target, long epoch, String requestId) throws Exception {
        return json.writeValueAsString(Map.of("profileId", target, "expectedEpoch", epoch, "reason", "repair",
                "requestId", requestId));
    }

    private EmbeddingProfile profile(String id) {
        return new EmbeddingProfile(id, "remote", "source", "model", id, 1024,
                "cosine", "identity-v1", "identity-v1", "query", "document", "embeddings-v1");
    }
}
