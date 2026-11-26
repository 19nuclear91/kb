package com.zhishiku.kb.app.domain.dto.answercard;

import com.zhishiku.kb.app.domain.answercard.AnswerCardCommand;
import com.zhishiku.kb.app.enums.answercard.AnswerCardOperation;
import com.zhishiku.kb.app.service.answercard.AnswerCardFailure;
import java.util.Objects;

/** 一次原意图的精确身份与摘要；只在当前调用栈内使用。 */
public record AnswerCardBinding(
        AnswerCardOperation operation,
        String requestId,
        long actorId,
        long spaceId,
        Long cardId,
        Long branchId,
        Long revisionId,
        Long reviewId,
        Long expectedCardVersion,
        String digest
) {
    public void requireMatches(AnswerCardCommand previous) {
        if (previous != null && (previous.digestVersion() != 1 || previous.operation() != operation
                || !Objects.equals(previous.requestId(), requestId) || previous.actorId() != actorId
                || previous.spaceId() != spaceId || !Objects.equals(previous.cardId(), cardId)
                || !Objects.equals(previous.branchId(), branchId) || !Objects.equals(previous.revisionId(), revisionId)
                || !Objects.equals(previous.reviewId(), reviewId)
                || !Objects.equals(previous.expectedCardVersion(), expectedCardVersion)
                || !Objects.equals(previous.inputDigest(), digest))) {
            throw AnswerCardFailure.conflict("REQUEST_MISMATCH");
        }
    }

    @Override
    public String toString() {
        return "AnswerCardBinding[operation=" + operation + "]";
    }
}
