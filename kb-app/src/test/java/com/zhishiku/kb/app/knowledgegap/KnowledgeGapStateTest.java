package com.zhishiku.kb.app.knowledgegap;

import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapAction;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapCategory;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapInputs;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapSource;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapState;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapWorkflow;
import com.zhishiku.kb.common.api.ApiException;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 纯领域单测，不启动 Spring、数据库或其他服务。 */
class KnowledgeGapStateTest {
    private static final UUID QUEUE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SOLUTION = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID EVENT = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void completeTransitionMatrixHasOnlyExplicitLegalActions() {
        Map<KnowledgeGapState, EnumSet<KnowledgeGapAction>> allowed = Map.of(
                KnowledgeGapState.SUBMITTED, EnumSet.of(KnowledgeGapAction.ASSIGN, KnowledgeGapAction.NOTE,
                        KnowledgeGapAction.WITHDRAW, KnowledgeGapAction.CLOSE),
                KnowledgeGapState.IN_PROGRESS, EnumSet.of(KnowledgeGapAction.ASSIGN, KnowledgeGapAction.NOTE,
                        KnowledgeGapAction.RETURN, KnowledgeGapAction.SOLUTION, KnowledgeGapAction.WITHDRAW,
                        KnowledgeGapAction.CLOSE),
                KnowledgeGapState.AWAITING_CONFIRMATION, EnumSet.of(KnowledgeGapAction.ASSIGN,
                        KnowledgeGapAction.NOTE, KnowledgeGapAction.RETURN, KnowledgeGapAction.CONFIRM,
                        KnowledgeGapAction.REJECT_SOLUTION, KnowledgeGapAction.WITHDRAW, KnowledgeGapAction.CLOSE));
        for (KnowledgeGapState state : KnowledgeGapState.values()) {
            var workflow = workflow(state);
            for (KnowledgeGapAction action : KnowledgeGapAction.values()) {
                if (allowed.getOrDefault(state, EnumSet.noneOf(KnowledgeGapAction.class)).contains(action)) {
                    var result = workflow.advance(action, workflow.version(), 12L, SOLUTION, EVENT);
                    assertThat(result.version()).isEqualTo(workflow.version() + 1);
                    assertThat(result.state()).isEqualTo(expectedState(state, action));
                } else {
                    assertConflict(() -> workflow.advance(action, workflow.version(), 12L, SOLUTION, EVENT));
                }
            }
        }
    }

    @Test
    void submissionAndSolutionAreNeverAutomaticallyResolved() {
        var submitted = KnowledgeGapWorkflow.submitted(QUEUE);
        assertThat(submitted.version()).isZero();
        assertThat(submitted.state()).isEqualTo(KnowledgeGapState.SUBMITTED);
        var assigned = submitted.advance(KnowledgeGapAction.ASSIGN, 0, 11L, null, EVENT);
        var awaiting = assigned.advance(KnowledgeGapAction.SOLUTION, 1, null, SOLUTION, EVENT);
        assertThat(awaiting.state()).isEqualTo(KnowledgeGapState.AWAITING_CONFIRMATION);
        assertThat(awaiting.state().terminal()).isFalse();
        var rejected = awaiting.advance(KnowledgeGapAction.REJECT_SOLUTION, 2, null, SOLUTION, EVENT);
        assertThat(rejected.assigneeUserId()).isEqualTo(11L);
        assertThat(rejected.pendingSolutionId()).isNull();
    }

    @Test
    void notesPreserveWorkIdentitiesAndReassignmentInvalidatesOldSolution() {
        for (KnowledgeGapState state : EnumSet.of(KnowledgeGapState.SUBMITTED, KnowledgeGapState.IN_PROGRESS,
                KnowledgeGapState.AWAITING_CONFIRMATION)) {
            var before = workflow(state);
            var after = before.advance(KnowledgeGapAction.NOTE, before.version(), null, null, EVENT);
            assertThat(after.assigneeUserId()).isEqualTo(before.assigneeUserId());
            assertThat(after.assignmentEpoch()).isEqualTo(before.assignmentEpoch());
            assertThat(after.pendingSolutionId()).isEqualTo(before.pendingSolutionId());
            assertThat(after.queueEventId()).isEqualTo(before.queueEventId());
        }
        var reassigned = workflow(KnowledgeGapState.AWAITING_CONFIRMATION)
                .advance(KnowledgeGapAction.ASSIGN, 2, 12L, null, EVENT);
        assertThat(reassigned.assigneeUserId()).isEqualTo(12L);
        assertThat(reassigned.assignmentEpoch()).isEqualTo(2);
        assertThat(reassigned.pendingSolutionId()).isNull();
        assertConflict(() -> reassigned.advance(KnowledgeGapAction.CONFIRM, 3, null, SOLUTION, EVENT));
    }

    @Test
    void sameAssigneeStaleVersionAndStaleSolutionConflict() {
        var inProgress = workflow(KnowledgeGapState.IN_PROGRESS);
        assertConflict(() -> inProgress.advance(KnowledgeGapAction.ASSIGN, 1, 11L, null, EVENT));
        assertConflict(() -> inProgress.advance(KnowledgeGapAction.NOTE, 0, null, null, EVENT));
        var awaiting = workflow(KnowledgeGapState.AWAITING_CONFIRMATION);
        assertConflict(() -> awaiting.advance(KnowledgeGapAction.CONFIRM, 2, null, QUEUE, EVENT));
        assertConflict(() -> awaiting.advance(KnowledgeGapAction.REJECT_SOLUTION, 2, null, null, EVENT));
    }

    @Test
    void returnClearsParticipationAndTerminalActionsKeepLastAssignment() {
        var awaiting = workflow(KnowledgeGapState.AWAITING_CONFIRMATION);
        var returned = awaiting.advance(KnowledgeGapAction.RETURN, 2, null, null, EVENT);
        assertThat(returned.state()).isEqualTo(KnowledgeGapState.SUBMITTED);
        assertThat(returned.assigneeUserId()).isNull();
        assertThat(returned.pendingSolutionId()).isNull();
        assertThat(returned.queueEventId()).isEqualTo(EVENT);
        for (KnowledgeGapAction action : EnumSet.of(KnowledgeGapAction.CONFIRM, KnowledgeGapAction.WITHDRAW,
                KnowledgeGapAction.CLOSE)) {
            var terminal = awaiting.advance(action, 2, null, SOLUTION, EVENT);
            assertThat(terminal.state().terminal()).isTrue();
            assertThat(terminal.assigneeUserId()).isEqualTo(11L);
            assertThat(terminal.pendingSolutionId()).isNull();
            assertThat(terminal.queueEventId()).isNull();
        }
    }

    @Test
    void unicodeLimitsCountCodePointsAndRejectMalformedOrNulInput() {
        String supplementary = "😀";
        assertThat(new KnowledgeGapInputs.Creation(KnowledgeGapCategory.OTHER,
                "  " + supplementary.repeat(4000) + "\n", KnowledgeGapSource.MANUAL, true).description())
                .isEqualTo(supplementary.repeat(4000));
        assertBadInput(() -> KnowledgeGapInputs.text(supplementary.repeat(4001), 4000));
        assertThat(new KnowledgeGapInputs.Message(0, supplementary.repeat(2000)).text())
                .isEqualTo(supplementary.repeat(2000));
        assertBadInput(() -> new KnowledgeGapInputs.Message(0, supplementary.repeat(2001)));
        for (String input : new String[]{"", " \n\t", "a\0b", "\uD800", "\uDC00", "\uD800x"}) {
            assertBadInput(() -> KnowledgeGapInputs.text(input, 4000));
        }
        assertBadInput(() -> KnowledgeGapInputs.text(null, 4000));
        assertBadInput(() -> new KnowledgeGapInputs.Creation(KnowledgeGapCategory.OTHER, "问题",
                KnowledgeGapSource.MANUAL, false));
    }

    @Test
    void inputsRequireExactIdentitiesVersionsAndPairedDocumentReference() {
        assertThat(KnowledgeGapInputs.requestId("😀".repeat(128))).hasSize(256);
        for (String input : new String[]{"", "a\nb", "x".repeat(129), "\uD800"}) {
            assertBadInput(() -> KnowledgeGapInputs.requestId(input));
        }
        assertBadInput(() -> new KnowledgeGapInputs.Assignment(-1, 11));
        assertBadInput(() -> new KnowledgeGapInputs.Assignment(0, 0));
        assertBadInput(() -> new KnowledgeGapInputs.Confirmation(0, null));
        assertBadInput(() -> new KnowledgeGapInputs.Solution(0, "方案", 1L, null));
        assertBadInput(() -> new KnowledgeGapInputs.Solution(0, "方案", null, 1L));
        assertBadInput(() -> new KnowledgeGapInputs.Solution(0, "方案", 0L, 1L));
        assertThat(new KnowledgeGapInputs.Solution(Long.MAX_VALUE, "方案", 9007199254740993L,
                9007199254740994L).documentId()).isEqualTo(9007199254740993L);
        assertThat(new KnowledgeGapInputs.Solution(0, "方案", null, null).versionId()).isNull();
    }

    @Test
    void diagnosticStringsAndErrorsDoNotExposeText() {
        String secret = "共享的敏感业务描述";
        assertThat(new KnowledgeGapInputs.Creation(KnowledgeGapCategory.OTHER, secret,
                KnowledgeGapSource.SEARCH, true).toString()).doesNotContain(secret);
        assertThat(new KnowledgeGapInputs.Message(0, secret).toString()).doesNotContain(secret);
        assertThat(new KnowledgeGapInputs.Solution(0, secret, null, null).toString()).doesNotContain(secret);
        assertThat(new KnowledgeGapInputs.Rejection(0, SOLUTION, secret).toString()).doesNotContain(secret);
        assertThatThrownBy(() -> KnowledgeGapInputs.text(secret + "\0", 4000))
                .isInstanceOf(ApiException.class).hasMessage("知识问题单输入无效");
    }

    private static KnowledgeGapWorkflow workflow(KnowledgeGapState state) {
        return switch (state) {
            case SUBMITTED -> KnowledgeGapWorkflow.submitted(QUEUE);
            case IN_PROGRESS -> new KnowledgeGapWorkflow(state, 1, 11L, 1, null, null);
            case AWAITING_CONFIRMATION -> new KnowledgeGapWorkflow(state, 2, 11L, 1, SOLUTION, null);
            default -> new KnowledgeGapWorkflow(state, 3, 11L, 1, null, null);
        };
    }

    private static KnowledgeGapState expectedState(KnowledgeGapState before, KnowledgeGapAction action) {
        return switch (action) {
            case ASSIGN, REJECT_SOLUTION -> KnowledgeGapState.IN_PROGRESS;
            case NOTE -> before;
            case RETURN -> KnowledgeGapState.SUBMITTED;
            case SOLUTION -> KnowledgeGapState.AWAITING_CONFIRMATION;
            case CONFIRM -> KnowledgeGapState.RESOLVED;
            case WITHDRAW -> KnowledgeGapState.WITHDRAWN;
            case CLOSE -> KnowledgeGapState.CLOSED;
            default -> throw new IllegalArgumentException("Unexpected action");
        };
    }

    private static void assertConflict(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                failure -> assertThat(failure.httpStatus()).isEqualTo(409));
    }

    private static void assertBadInput(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                failure -> assertThat(failure.httpStatus()).isEqualTo(400));
    }
}
