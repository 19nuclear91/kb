package com.zhishiku.kb.app.knowledgegap.model;

import com.zhishiku.kb.common.api.ApiException;

import java.util.Objects;
import java.util.UUID;

/**
 * 无 I/O 的工作状态；不保存正文，不代表当前授权。
 * 版本检查针对新命令；匹配重放须由服务重新鉴权后返回原回执。
 */
public record KnowledgeGapWorkflow(KnowledgeGapState state, long version, Long assigneeUserId,
                                   long assignmentEpoch, UUID pendingSolutionId, UUID queueEventId) {
    public KnowledgeGapWorkflow {
        Objects.requireNonNull(state);
        if (version < 0 || assignmentEpoch < 0 || assignmentEpoch > version
                || (assigneeUserId != null && assigneeUserId <= 0)
                || ((state == KnowledgeGapState.SUBMITTED) != (queueEventId != null))
                || ((state == KnowledgeGapState.AWAITING_CONFIRMATION) != (pendingSolutionId != null))
                || (state == KnowledgeGapState.SUBMITTED && assigneeUserId != null)
                || ((state == KnowledgeGapState.IN_PROGRESS || state == KnowledgeGapState.AWAITING_CONFIRMATION)
                    && assigneeUserId == null)
                || (assigneeUserId != null && assignmentEpoch == 0)) {
            throw new IllegalArgumentException("Invalid knowledge gap workflow");
        }
    }

    public static KnowledgeGapWorkflow submitted(UUID eventId) {
        return new KnowledgeGapWorkflow(KnowledgeGapState.SUBMITTED, 0, null, 0,
                null, Objects.requireNonNull(eventId));
    }

    public KnowledgeGapWorkflow advance(KnowledgeGapAction action, long expectedVersion,
                                       Long nextAssignee, UUID solutionId, UUID eventId) {
        if (action == null || action == KnowledgeGapAction.CREATE || state.terminal()
                || expectedVersion != version) {
            throw conflict();
        }
        Objects.requireNonNull(eventId);
        KnowledgeGapState nextState = state;
        Long assignee = assigneeUserId;
        long epoch = assignmentEpoch;
        UUID pending = pendingSolutionId;
        UUID queue = queueEventId;
        switch (action) {
            case ASSIGN -> {
                if (nextAssignee == null || nextAssignee <= 0 || Objects.equals(assignee, nextAssignee)) {
                    throw conflict();
                }
                assignee = nextAssignee;
                epoch = increment(epoch);
                pending = null;
                queue = null;
                nextState = KnowledgeGapState.IN_PROGRESS;
            }
            case NOTE -> {
                // 共享说明不更改当前工作身份。
            }
            case RETURN -> {
                if (state != KnowledgeGapState.IN_PROGRESS && state != KnowledgeGapState.AWAITING_CONFIRMATION) {
                    throw conflict();
                }
                assignee = null;
                pending = null;
                queue = eventId;
                nextState = KnowledgeGapState.SUBMITTED;
            }
            case SOLUTION -> {
                if (state != KnowledgeGapState.IN_PROGRESS || solutionId == null) {
                    throw conflict();
                }
                pending = solutionId;
                nextState = KnowledgeGapState.AWAITING_CONFIRMATION;
            }
            case CONFIRM, REJECT_SOLUTION -> {
                if (state != KnowledgeGapState.AWAITING_CONFIRMATION || !Objects.equals(pending, solutionId)) {
                    throw conflict();
                }
                pending = null;
                nextState = action == KnowledgeGapAction.CONFIRM
                        ? KnowledgeGapState.RESOLVED : KnowledgeGapState.IN_PROGRESS;
            }
            case WITHDRAW, CLOSE -> {
                pending = null;
                queue = null;
                nextState = action == KnowledgeGapAction.WITHDRAW
                        ? KnowledgeGapState.WITHDRAWN : KnowledgeGapState.CLOSED;
            }
            default -> throw conflict();
        }
        return new KnowledgeGapWorkflow(nextState, increment(version), assignee, epoch, pending, queue);
    }

    private static long increment(long value) {
        try {
            return Math.incrementExact(value);
        } catch (ArithmeticException ignored) {
            throw ApiException.unavailable("知识问题单状态暂不可用");
        }
    }

    private static ApiException conflict() {
        return ApiException.conflict("知识问题单状态已变化");
    }
}
