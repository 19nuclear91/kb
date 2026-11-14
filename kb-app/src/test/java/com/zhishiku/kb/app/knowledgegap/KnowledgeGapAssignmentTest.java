package com.zhishiku.kb.app.knowledgegap;

import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.KnowledgeGapAssigneeService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.knowledgegap.mapper.KnowledgeGapMapper;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapCategory;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapInputs;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapRows;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapSource;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapState;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapWorkflow;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapAccess;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapAssignmentService;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapCommandService;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapEventService;
import com.zhishiku.kb.app.knowledgegap.web.KnowledgeGapAssignmentController;
import com.zhishiku.kb.common.api.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 当前访问规则与可变内存仓储一起验证，不连接数据库或模拟真实锁证明。 */
class KnowledgeGapAssignmentTest {
    private final KnowledgeGapMapper mapper = mock(KnowledgeGapMapper.class);
    private final AdminQueryAccess identities = mock(AdminQueryAccess.class);
    private final KnowledgeGapAccess access = new KnowledgeGapAccess(identities, mapper);
    private final KnowledgeGapAssigneeService assignees = mock(KnowledgeGapAssigneeService.class);
    private final KnowledgeGapCommandService commands = new KnowledgeGapCommandService(mapper, access);
    private final KnowledgeGapAssignmentService service = new KnowledgeGapAssignmentService(access, mapper, commands,
            assignees, new KnowledgeGapEventService(mapper));
    private final KnowledgeGapAssignmentController controller = new KnowledgeGapAssignmentController(access, service);
    private final AtomicLong actorId = new AtomicLong(7);
    private final AtomicReference<KnowledgeGapRows.Gap> current = new AtomicReference<>();
    private final Map<String, KnowledgeGapRows.Command> bindings = new HashMap<>();
    private final List<KnowledgeGapRows.Entry> entries = new ArrayList<>();
    private final List<KnowledgeGapRows.Event> events = new ArrayList<>();
    private final UUID queueId = UUID.randomUUID();
    private final UUID solutionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        current.set(gap(KnowledgeGapState.SUBMITTED));
        when(identities.current()).thenAnswer(call -> principal(actorId.get()));
        when(mapper.findVisible(eq(11L), anyLong())).thenAnswer(call -> current.get());
        when(mapper.lock(eq(11L), anyLong())).thenAnswer(call -> current.get());
        when(mapper.claim(any())).thenAnswer(call -> {
            KnowledgeGapRows.Binding binding = call.getArgument(0);
            var row = new KnowledgeGapRows.Command(binding.requestId(), binding.actorUserId(), binding.operation(), binding.gapId(),
                    binding.expectedVersion(), binding.inputDigest(), null, null, null, null, null);
            return bindings.putIfAbsent(binding.requestId(), row) == null ? 1 : 0;
        });
        when(mapper.command(anyString())).thenAnswer(call -> bindings.get(call.getArgument(0)));
        when(mapper.complete(anyString(), any())).thenAnswer(call -> {
            String request = call.getArgument(0);
            KnowledgeGapRows.Receipt receipt = call.getArgument(1);
            var row = bindings.get(request);
            bindings.put(request, new KnowledgeGapRows.Command(row.requestId(), row.actorUserId(), row.operation(), row.gapId(),
                    row.expectedVersion(), row.inputDigest(), receipt.gapId(), receipt.resultVersion(), receipt.state(),
                    receipt.eventId(), receipt.solutionId()));
            return 1;
        });
        when(mapper.advance(eq(11L), anyLong(), anyLong(), any())).thenAnswer(call -> {
            long expected = call.getArgument(2);
            KnowledgeGapWorkflow workflow = call.getArgument(3);
            var old = current.get();
            if (old.version() != expected) {
                return 0;
            }
            current.set(new KnowledgeGapRows.Gap(old.id(), old.reporterUserId(), old.category(), old.source(), old.description(),
                    old.consentVersion(), workflow.state(), workflow.version(), workflow.assigneeUserId(), workflow.assignmentEpoch(),
                    workflow.pendingSolutionId(), workflow.queueEventId(), old.createdAt(), Instant.EPOCH));
            return 1;
        });
        when(mapper.insertEntry(any())).thenAnswer(call -> {
            entries.add(call.getArgument(0));
            return 1;
        });
        when(mapper.insertEvent(any())).thenAnswer(call -> {
            events.add(call.getArgument(0));
            return 1;
        });
    }

    @Test
    void assignmentRechecksCandidateAndProducesOneFactForMatchingReplay() throws Exception {
        var request = request("assign", "{\"expectedVersion\":0,\"assigneeUserId\":8}");
        var first = controller.assign("11", request).data();
        assertThat(first.state()).isEqualTo(KnowledgeGapState.IN_PROGRESS);
        assertThat(current.get().assigneeUserId()).isEqualTo(8);
        assertThat(current.get().assignmentEpoch()).isEqualTo(1);
        assertThat(service.assign(11, new KnowledgeGapInputs.Assignment(0, 8), "assign").replayed()).isTrue();
        verify(assignees, times(1)).requireEligible(8);
        verify(mapper, times(1)).insertEvent(any());
        verify(mapper).insertRecipients(first.eventId(), List.of(8L));
    }

    @Test
    void awaitingReassignmentInvalidatesSolutionAndOldHandlersReadAccess() {
        current.set(gap(KnowledgeGapState.AWAITING_CONFIRMATION));
        var result = service.assign(11, new KnowledgeGapInputs.Assignment(2, 9), "reassign");
        assertThat(result.state()).isEqualTo(KnowledgeGapState.IN_PROGRESS);
        assertThat(current.get().pendingSolutionId()).isNull();
        assertThat(current.get().assignmentEpoch()).isEqualTo(2);
        actorId.set(8);
        assertStatus(404, () -> access.requireVisible(access.current(), 11));
    }

    @Test
    void sharedNotesPreserveAllWorkIdentitiesWithoutNotificationIntents() {
        actorId.set(10);
        for (KnowledgeGapState state : List.of(KnowledgeGapState.SUBMITTED, KnowledgeGapState.IN_PROGRESS,
                KnowledgeGapState.AWAITING_CONFIRMATION)) {
            current.set(gap(state));
            var before = current.get();
            service.note(11, new KnowledgeGapInputs.Message(before.version(), " 补充说明 "), "note-" + state);
            assertThat(current.get().state()).isEqualTo(state);
            assertThat(current.get().queueEventId()).isEqualTo(before.queueEventId());
            assertThat(current.get().pendingSolutionId()).isEqualTo(before.pendingSolutionId());
            assertThat(current.get().assigneeUserId()).isEqualTo(before.assigneeUserId());
            assertThat(current.get().assignmentEpoch()).isEqualTo(before.assignmentEpoch());
        }
        assertThat(entries).allMatch(entry -> entry.kind().equals("NOTE") && entry.body().equals("补充说明"));
        assertThat(events).allMatch(event -> event.kind().equals("NOTE_ADDED"));
        verify(mapper, never()).insertRecipients(any(), any());
        verify(mapper, never()).insertReceptionRecipients(any());
    }

    @Test
    void returnKeepsHistoryButClearsAssignmentAndRefusesOldHandlersReplay() throws Exception {
        current.set(gap(KnowledgeGapState.AWAITING_CONFIRMATION));
        actorId.set(8);
        var result = controller.returnToReception("11", request("return", "{\"expectedVersion\":2,\"reason\":\"需要受理协调\"}")).data();
        assertThat(result.state()).isEqualTo(KnowledgeGapState.SUBMITTED);
        assertThat(current.get().assigneeUserId()).isNull();
        assertThat(current.get().pendingSolutionId()).isNull();
        assertThat(current.get().queueEventId()).isEqualTo(result.eventId());
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().body()).isEqualTo("需要受理协调");
        assertStatus(404, () -> access.requireVisible(access.current(), 11));
        assertStatus(404, () -> service.returnToReception(11, new KnowledgeGapInputs.Message(2, "需要受理协调"), "return"));
        verify(mapper, times(1)).claim(any());
        verify(mapper).insertReceptionRecipients(result.eventId());
    }

    @Test
    void invalidCandidateStaleVersionAndSameAssigneeCannotChangeBusinessFacts() {
        doThrow(ApiException.conflict("处理人候选已失效")).when(assignees).requireEligible(9);
        assertStatus(409, () -> service.assign(11, new KnowledgeGapInputs.Assignment(0, 9), "candidate"));
        actorId.set(10);
        assertStatus(409, () -> service.note(11, new KnowledgeGapInputs.Message(99, "迟到说明"), "stale"));
        actorId.set(7);
        current.set(gap(KnowledgeGapState.IN_PROGRESS));
        assertStatus(409, () -> service.assign(11, new KnowledgeGapInputs.Assignment(1, 8), "same-assignee"));
        verify(mapper, never()).advance(anyLong(), anyLong(), anyLong(), any());
        assertThat(events).isEmpty();
        assertThat(entries).isEmpty();
    }

    @Test
    void controllerRejectsFieldOverridesNonIntegerVersionAndMissingReason() {
        for (String body : List.of("{\"expectedVersion\":0.5,\"assigneeUserId\":8}",
                "{\"expectedVersion\":0,\"assigneeUserId\":8,\"actorUserId\":9}")) {
            assertStatus(400, () -> {
                try {
                    controller.assign("11", request("bad", body));
                } catch (java.io.IOException error) {
                    throw new IllegalStateException(error);
                }
            });
        }
        actorId.set(8);
        current.set(gap(KnowledgeGapState.IN_PROGRESS));
        assertStatus(400, () -> {
            try {
                controller.returnToReception("11", request("empty", "{\"expectedVersion\":1,\"reason\":\" \"}"));
            } catch (java.io.IOException error) {
                throw new IllegalStateException(error);
            }
        });
        verify(mapper, never()).claim(any());
    }

    private KnowledgeGapRows.Gap gap(KnowledgeGapState state) {
        long version = state == KnowledgeGapState.SUBMITTED ? 0 : state == KnowledgeGapState.IN_PROGRESS ? 1 : 2;
        return new KnowledgeGapRows.Gap(11, 10, KnowledgeGapCategory.OTHER, KnowledgeGapSource.MANUAL,
                "共享描述", "GAP_SHARE_V1", state, version, state == KnowledgeGapState.SUBMITTED ? null : 8L,
                state == KnowledgeGapState.SUBMITTED ? 0 : 1,
                state == KnowledgeGapState.AWAITING_CONFIRMATION ? solutionId : null,
                state == KnowledgeGapState.SUBMITTED ? queueId : null, Instant.EPOCH, Instant.EPOCH);
    }

    private static PermissionService.PrincipalHandle principal(long userId) {
        var principals = mock(PermissionMapper.class);
        var roles = mock(RolePermissionMapper.class);
        when(principals.findClearanceLevel(userId)).thenReturn((short) 1);
        when(roles.findUserPermissions(userId)).thenReturn(userId == 7
                ? List.of("knowledge:gap:read", "knowledge:gap:assign")
                : userId == 8 ? List.of("knowledge:gap:handle") : List.of());
        return new PermissionService(principals, null, roles).principalOf(userId);
    }

    private static MockHttpServletRequest request(String requestId, String body) {
        var request = new MockHttpServletRequest("POST", "/api/knowledge-gaps/11/return");
        request.setContentType("application/json");
        request.addHeader("X-Request-Id", requestId);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static void assertStatus(int status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                failure -> assertThat(failure.httpStatus()).isEqualTo(status));
    }
}
