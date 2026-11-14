package com.zhishiku.kb.app.knowledgegap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.knowledgegap.mapper.KnowledgeGapMapper;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapAction;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapCategory;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapInputs;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapRows;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapSource;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapState;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapAccess;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapCommandService;
import com.zhishiku.kb.common.api.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 命令载荷绑定与当前重放资格的离线验证，仓储及访问规则为受控替身。 */
class KnowledgeGapReceiptTest {
    private final KnowledgeGapMapper mapper = mock(KnowledgeGapMapper.class);
    private final KnowledgeGapAccess access = mock(KnowledgeGapAccess.class);
    private final KnowledgeGapCommandService service = new KnowledgeGapCommandService(mapper, access);
    private final Map<String, KnowledgeGapRows.Command> rows = new HashMap<>();
    private final UUID eventId = UUID.randomUUID();
    private final UUID solutionId = UUID.randomUUID();
    private PermissionService.PrincipalHandle actor;

    @BeforeEach
    void setUp() {
        actor = principal(7);
        when(mapper.claim(any())).thenAnswer(call -> {
            KnowledgeGapRows.Binding binding = call.getArgument(0);
            var row = new KnowledgeGapRows.Command(binding.requestId(), binding.actorUserId(), binding.operation(),
                    binding.gapId(), binding.expectedVersion(), binding.inputDigest(), null, null, null, null, null);
            return rows.putIfAbsent(binding.requestId(), row) == null ? 1 : 0;
        });
        when(mapper.command(anyString())).thenAnswer(call -> rows.get(call.getArgument(0)));
        when(mapper.complete(anyString(), any())).thenAnswer(call -> {
            String request = call.getArgument(0);
            KnowledgeGapRows.Receipt receipt = call.getArgument(1);
            var old = rows.get(request);
            if (old.resultEventId() != null) {
                return 0;
            }
            rows.put(request, new KnowledgeGapRows.Command(old.requestId(), old.actorUserId(), old.operation(),
                    old.gapId(), old.expectedVersion(), old.inputDigest(), receipt.gapId(), receipt.resultVersion(),
                    receipt.state(), receipt.eventId(), receipt.solutionId()));
            return 1;
        });
        when(access.requireVisible(any(), anyLong())).thenReturn(gap());
    }

    @Test
    void normalizedCreationBindsCollectionNotGeneratedIdentityAndReplayDoesNotCompleteAgain() {
        var first = creation(" 共享描述 ");
        assertThat(service.claim(actor, KnowledgeGapAction.CREATE, null, null, first, "create")).isNull();
        service.complete("create", new KnowledgeGapRows.Receipt(11, 0, KnowledgeGapState.SUBMITTED, eventId, null, false));
        var replay = service.claim(actor, KnowledgeGapAction.CREATE, null, null, creation("共享描述"), "create");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.gapId()).isEqualTo(11);
        assertThat(rows.get("create").gapId()).isNull();
        assertThat(rows.get("create").expectedVersion()).isNull();
        assertThat(rows.get("create").inputDigest()).matches("[0-9a-f]{64}");
        verify(mapper, times(1)).complete(anyString(), any());
    }

    @Test
    void changedActorActionTargetExpectedVersionOrPayloadConflictsWithoutReturningReceipt() {
        var payload = new KnowledgeGapInputs.Assignment(1, 9);
        service.claim(actor, KnowledgeGapAction.ASSIGN, 11L, 1L, payload, "bound");
        service.complete("bound", receipt(KnowledgeGapState.IN_PROGRESS, null));
        assertStatus(409, () -> service.claim(principal(8), KnowledgeGapAction.ASSIGN, 11L, 1L, payload, "bound"));
        assertStatus(409, () -> service.claim(actor, KnowledgeGapAction.CLOSE, 11L, 1L,
                new KnowledgeGapInputs.Message(1, "关闭原因"), "bound"));
        assertStatus(409, () -> service.claim(actor, KnowledgeGapAction.ASSIGN, 12L, 1L, payload, "bound"));
        assertStatus(409, () -> service.claim(actor, KnowledgeGapAction.ASSIGN, 11L, 2L,
                new KnowledgeGapInputs.Assignment(2, 9), "bound"));
        assertStatus(409, () -> service.claim(actor, KnowledgeGapAction.ASSIGN, 11L, 1L,
                new KnowledgeGapInputs.Assignment(1, 10), "bound"));
        verify(access, never()).requireVisible(any(), anyLong());
    }

    @Test
    void currentActionQualificationIsCheckedBeforeClaimAndAgainBeforeReplayProjection() {
        var input = new KnowledgeGapInputs.Solution(1, "方案", null, null);
        service.claim(actor, KnowledgeGapAction.SOLUTION, 11L, 1L, input, "solution");
        service.complete("solution", receipt(KnowledgeGapState.AWAITING_CONFIRMATION, solutionId));
        var replay = service.claim(actor, KnowledgeGapAction.SOLUTION, 11L, 1L, input, "solution");
        assertThat(replay.solutionId()).isEqualTo(solutionId);
        var order = inOrder(access, mapper);
        order.verify(access).requireForAction(actor, 11, KnowledgeGapAction.SOLUTION);
        order.verify(mapper).configureLockTimeout();
        order.verify(mapper).claim(any());
        order.verify(mapper).complete(anyString(), any());
        order.verify(access).requireForAction(actor, 11, KnowledgeGapAction.SOLUTION);
        order.verify(mapper).configureLockTimeout();
        order.verify(mapper).claim(any());
        order.verify(mapper).command("solution");
        order.verify(access).requireVisible(actor, 11);
        order.verify(access).requireAction(actor, gap(), KnowledgeGapAction.SOLUTION);
    }

    @Test
    void reassignedOrReturnedHandlerCannotUseOldSuccessfulReceipt() {
        var input = new KnowledgeGapInputs.Message(1, "退回原因");
        service.claim(actor, KnowledgeGapAction.RETURN, 11L, 1L, input, "return");
        service.complete("return", receipt(KnowledgeGapState.SUBMITTED, null));
        doThrow(ApiException.notFound("知识问题单不存在或不可访问"))
                .when(access).requireForAction(actor, 11, KnowledgeGapAction.RETURN);
        assertStatus(404, () -> service.claim(actor, KnowledgeGapAction.RETURN, 11L, 1L, input, "return"));
        verify(mapper, times(1)).claim(any());
        verify(mapper, never()).command("return");
    }

    @Test
    void missingCompletionCannotMasqueradeAsSuccessfulReplayAndReceiptIsWrittenOnce() {
        var input = creation("描述");
        service.claim(actor, KnowledgeGapAction.CREATE, null, null, input, "pending");
        assertStatus(503, () -> service.claim(actor, KnowledgeGapAction.CREATE, null, null, input, "pending"));
        var receipt = new KnowledgeGapRows.Receipt(11, 0, KnowledgeGapState.SUBMITTED, eventId, null, false);
        service.complete("pending", receipt);
        assertStatus(503, () -> service.complete("pending", receipt));
    }

    @Test
    void responseAndPersistenceBindingContainNoSharedTextOrDocumentIdentity() throws Exception {
        var input = new KnowledgeGapInputs.Solution(1, "敏感方案正文", 9007199254740993L, 9007199254740994L);
        service.claim(actor, KnowledgeGapAction.SOLUTION, 11L, 1L, input, "minimal");
        var receipt = receipt(KnowledgeGapState.AWAITING_CONFIRMATION, solutionId);
        service.complete("minimal", receipt);
        var json = new ObjectMapper();
        assertThat(json.writeValueAsString(rows.get("minimal"))).doesNotContain("敏感方案正文", "9007199254740993", "9007199254740994");
        var view = json.valueToTree(receipt);
        assertThat(view.size()).isEqualTo(6);
        for (String field : List.of("gapId", "resultVersion", "state", "eventId", "solutionId", "replayed")) {
            assertThat(view.has(field)).isTrue();
        }
        assertThat(view.toString()).doesNotContain("敏感方案正文", "documentId", "versionId");
    }

    @Test
    void payloadTypeVersionAndRequestIdAreStrictBeforePersistence() {
        var input = new KnowledgeGapInputs.Assignment(2, 9);
        assertStatus(400, () -> service.claim(actor, KnowledgeGapAction.ASSIGN, 11L, 1L, input, "invalid"));
        assertStatus(400, () -> service.claim(actor, KnowledgeGapAction.CREATE, 11L, null, creation("描述"), "invalid"));
        assertStatus(400, () -> service.claim(actor, KnowledgeGapAction.CREATE, null, null, creation("描述"), "bad\nrequest"));
        verify(mapper, never()).claim(any());
    }

    private KnowledgeGapRows.Receipt receipt(KnowledgeGapState state, UUID solution) {
        return new KnowledgeGapRows.Receipt(11, 2, state, eventId, solution, false);
    }

    private static KnowledgeGapInputs.Creation creation(String text) {
        return new KnowledgeGapInputs.Creation(KnowledgeGapCategory.OTHER, text, KnowledgeGapSource.MANUAL, true);
    }

    private static KnowledgeGapRows.Gap gap() {
        return new KnowledgeGapRows.Gap(11, 8, KnowledgeGapCategory.OTHER, KnowledgeGapSource.MANUAL,
                "描述", "GAP_SHARE_V1", KnowledgeGapState.RESOLVED, 3, 7L, 1, null, null, Instant.EPOCH, Instant.EPOCH);
    }

    private static PermissionService.PrincipalHandle principal(long id) {
        var principals = mock(PermissionMapper.class);
        when(principals.findClearanceLevel(id)).thenReturn((short) 1);
        return new PermissionService(principals, null, mock(RolePermissionMapper.class)).principalOf(id);
    }

    private static void assertStatus(int status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                failure -> assertThat(failure.httpStatus()).isEqualTo(status));
    }
}
