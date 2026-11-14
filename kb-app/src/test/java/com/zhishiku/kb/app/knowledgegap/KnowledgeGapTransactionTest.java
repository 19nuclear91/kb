package com.zhishiku.kb.app.knowledgegap;

import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapAction;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapCategory;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapInputs;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapRows;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapSource;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapState;
import com.zhishiku.kb.common.api.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.transaction.IllegalTransactionStateException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;

/** 用真实 Spring 事务代理与受控仓储检查原子编排和串行化竞争，不启动应用或数据库。 */
class KnowledgeGapTransactionTest {
    @ParameterizedTest(name = "{0} 在 {1} 失败时整体撤销")
    @MethodSource("failureStages")
    void everyFailureRollsBackAllSourceRecordsAndAllowsSameRequestRetry(KnowledgeGapAction action, String stage) {
        var fixture = new KnowledgeGapTransactionFixture();
        UUID solution = prepare(fixture, action);
        var before = fixture.snapshot();
        int commits = fixture.transactions.commits;
        fixture.failureAt = stage;
        assertStatus(503, () -> write(fixture, action, solution, "request"));
        assertThat(fixture.snapshot()).isEqualTo(before);
        assertThat(fixture.transactions.commits).isEqualTo(commits);
        assertThat(fixture.transactions.rollbacks).isEqualTo(1);
        assertThat(fixture.transactions.timeouts).allMatch(timeout -> timeout == 5);
        fixture.failureAt = null;
        assertThat(write(fixture, action, solution, "request").replayed()).isFalse();
        assertThat(fixture.transactions.commits).isEqualTo(commits + 1);
    }

    @ParameterizedTest(name = "{0} 的 {1} 异常行数不是成功")
    @MethodSource("zeroStages")
    void missingWriteResultDoesNotCommit(KnowledgeGapAction action, String stage) {
        var fixture = new KnowledgeGapTransactionFixture();
        UUID solution = prepare(fixture, action);
        var before = fixture.snapshot();
        fixture.failureAt = stage;
        fixture.zeroResult = true;
        assertStatus(stage.equals("advance") ? 409 : 503, () -> write(fixture, action, solution, "zero"));
        assertThat(fixture.snapshot()).isEqualTo(before);
        assertThat(fixture.transactions.rollbacks).isEqualTo(1);
    }

    @Test
    void createAndOtherWritesUseCommandFirstAndOneOuterShortTransaction() {
        for (KnowledgeGapAction action : KnowledgeGapAction.values()) {
            var fixture = new KnowledgeGapTransactionFixture();
            UUID solution = prepare(fixture, action);
            fixture.calls.clear();
            int commits = fixture.transactions.commits;
            write(fixture, action, solution, "ordered");
            var calls = fixture.calls;
            assertThat(calls.subList(0, 2)).containsExactly("configureLockTimeout", "claim");
            if (action == KnowledgeGapAction.CREATE) {
                assertThat(calls).containsSubsequence("claim", "ensureActorGuard", "lockActorGuard", "countOpen", "create", "insertEvent",
                        "insertReceptionRecipients", "complete");
                assertThat(calls).doesNotContain("lock", "advance");
            } else {
                assertThat(calls).containsSubsequence("claim", "lock", "advance", "insertEvent", "complete");
                assertThat(calls).doesNotContain("ensureActorGuard", "lockActorGuard", "countOpen");
                if (action != KnowledgeGapAction.CONFIRM && action != KnowledgeGapAction.ASSIGN) {
                    assertThat(calls).containsSubsequence("advance", "insertEntry", "insertEvent");
                }
            }
            assertThat(fixture.transactions.commits).isEqualTo(commits + 1);
            assertThat(fixture.transactions.timeouts).allMatch(timeout -> timeout == 5);
        }
    }

    @ParameterizedTest(name = "同版本 {0} 先于 {1}")
    @MethodSource("competitors")
    void eachSerializationOrderHasOnlyOneLegalWinner(KnowledgeGapAction first, KnowledgeGapAction second) {
        var fixture = new KnowledgeGapTransactionFixture();
        UUID solution = prepare(fixture, KnowledgeGapAction.CONFIRM);
        int eventCount = fixture.memory.events.size();
        int commits = fixture.transactions.commits;
        var winner = write(fixture, first, solution, "winner");
        var afterWinner = fixture.snapshot();
        assertStatus(409, () -> write(fixture, second, solution, "loser"));
        assertThat(fixture.snapshot()).isEqualTo(afterWinner);
        assertThat(fixture.memory.events).hasSize(eventCount + 1);
        assertThat(fixture.transactions.commits).isEqualTo(commits + 1);
        assertThat(fixture.memory.bindings).doesNotContainKey("loser");
        assertThat(fixture.memory.gap.state()).isEqualTo(winner.state());
        var replay = write(fixture, first, solution, "winner");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.eventId()).isEqualTo(winner.eventId());
        assertThat(fixture.snapshot()).isEqualTo(afterWinner);
    }

    @Test
    void lockRechecksCurrentHandlerAfterPreflightInsteadOfTrustingOldRead() {
        var fixture = new KnowledgeGapTransactionFixture();
        var old = fixture.memory.gap;
        var reassigned = new KnowledgeGapRows.Gap(old.id(), old.reporterUserId(), old.category(), old.source(), old.description(),
                old.consentVersion(), old.state(), 2, 9L, 2, null, null, old.createdAt(), old.updatedAt());
        doReturn(reassigned).when(fixture.memory.mapper).lock(anyLong(), anyLong());
        var before = fixture.snapshot();
        assertStatus(404, () -> fixture.solutions.submit(11, new KnowledgeGapInputs.Solution(1, "迟到方案", null, null), "late"));
        assertThat(fixture.calls).doesNotContain("advance", "insertEntry", "insertEvent", "complete");
        assertThat(fixture.snapshot()).isEqualTo(before);
    }

    @Test
    void mandatoryHelpersCannotCreateIndependentTransactions() {
        var fixture = new KnowledgeGapTransactionFixture();
        assertThatThrownBy(() -> fixture.commands.claim(fixture.memory.access.current(), KnowledgeGapAction.CREATE,
                null, null, creation(), "outside")).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> fixture.events.record(new KnowledgeGapRows.Event(UUID.randomUUID(), 11,
                "GAP_CREATED", 0, 10, null, 0, null, null, null), 10)).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(fixture.calls).isEmpty();
    }

    private static Stream<Arguments> failureStages() {
        var cases = new ArrayList<Arguments>();
        for (KnowledgeGapAction action : KnowledgeGapAction.values()) {
            for (String stage : stages(action)) {
                cases.add(Arguments.of(action, stage));
            }
        }
        return cases.stream();
    }

    private static Stream<Arguments> zeroStages() {
        return failureStages().filter(arguments -> List.of("create", "advance", "insertEntry", "insertEvent", "complete")
                .contains(arguments.get()[1]));
    }

    private static List<String> stages(KnowledgeGapAction action) {
        var stages = new ArrayList<>(List.of("configureLockTimeout", "claim"));
        if (action == KnowledgeGapAction.CREATE) {
            stages.addAll(List.of("ensureActorGuard", "lockActorGuard", "countOpen", "create"));
        } else {
            stages.addAll(List.of("lock", "advance"));
            if (action != KnowledgeGapAction.ASSIGN && action != KnowledgeGapAction.CONFIRM) {
                stages.add("insertEntry");
            }
            if (action == KnowledgeGapAction.CONFIRM) {
                stages.add("entry");
            }
        }
        stages.add("insertEvent");
        if (action != KnowledgeGapAction.NOTE) {
            stages.add(action == KnowledgeGapAction.CREATE || action == KnowledgeGapAction.RETURN
                    ? "insertReceptionRecipients" : "insertRecipients");
        }
        stages.add("complete");
        return stages;
    }

    private static Stream<Arguments> competitors() {
        var actions = List.of(KnowledgeGapAction.CONFIRM, KnowledgeGapAction.ASSIGN, KnowledgeGapAction.WITHDRAW, KnowledgeGapAction.CLOSE);
        return actions.stream().flatMap(first -> actions.stream().filter(second -> first != second)
                .map(second -> Arguments.of(first, second)));
    }

    private static UUID prepare(KnowledgeGapTransactionFixture fixture, KnowledgeGapAction action) {
        if (action == KnowledgeGapAction.CREATE) {
            fixture.memory.gap = null;
        }
        if (action == KnowledgeGapAction.CONFIRM || action == KnowledgeGapAction.REJECT_SOLUTION) {
            return fixture.solutions.submit(11, new KnowledgeGapInputs.Solution(1, "现有方案", null, null), "prepare").solutionId();
        }
        return null;
    }

    private static KnowledgeGapRows.Receipt write(KnowledgeGapTransactionFixture fixture, KnowledgeGapAction action,
                                                   UUID solution, String requestId) {
        long version = solution == null ? 1 : 2;
        fixture.memory.actorId = switch (action) {
            case ASSIGN, CLOSE -> 7;
            case CREATE, NOTE, CONFIRM, REJECT_SOLUTION, WITHDRAW -> 10;
            default -> 8;
        };
        return switch (action) {
            case CREATE -> fixture.creates.create(creation(), requestId);
            case ASSIGN -> fixture.assignments.assign(11, new KnowledgeGapInputs.Assignment(version, 9), requestId);
            case NOTE -> fixture.assignments.note(11, new KnowledgeGapInputs.Message(version, "共享说明"), requestId);
            case RETURN -> fixture.assignments.returnToReception(11, new KnowledgeGapInputs.Message(version, "退回原因"), requestId);
            case SOLUTION -> fixture.solutions.submit(11, new KnowledgeGapInputs.Solution(version, "方案", null, null), requestId);
            case CONFIRM -> fixture.solutions.confirm(11, new KnowledgeGapInputs.Confirmation(version, solution), requestId);
            case REJECT_SOLUTION -> fixture.solutions.reject(11, new KnowledgeGapInputs.Rejection(version, solution, "仍未解决"), requestId);
            case WITHDRAW -> fixture.closures.withdraw(11, new KnowledgeGapInputs.Message(version, "撤回原因"), requestId);
            case CLOSE -> fixture.closures.close(11, new KnowledgeGapInputs.Message(version, "关闭原因"), requestId);
        };
    }

    private static KnowledgeGapInputs.Creation creation() {
        return new KnowledgeGapInputs.Creation(KnowledgeGapCategory.OTHER, "明确分享问题", KnowledgeGapSource.MANUAL, true);
    }

    private static void assertStatus(int status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                failure -> assertThat(failure.httpStatus()).isEqualTo(status));
    }
}
