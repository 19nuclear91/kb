package com.zhishiku.kb.app.domain.dto.answercard;

import com.zhishiku.kb.app.enums.answercard.AnswerCardOperation;

/** 经严格解码的写入意图，字段是否适用由操作解码器确定。 */
public record AnswerCardIntent(
        AnswerCardOperation operation,
        long spaceId,
        Long cardId,
        Long branchId,
        Long revisionId,
        Long reviewId,
        Long expectedCardVersion,
        Long expectedReviewVersion,
        Long expectedStewardshipVersion,
        Long responsibleUserId,
        Integer cycleMonths,
        String reason,
        AnswerCardContent content
) {
    @Override
    public String toString() {
        return "AnswerCardIntent[operation=" + operation + "]";
    }
}
