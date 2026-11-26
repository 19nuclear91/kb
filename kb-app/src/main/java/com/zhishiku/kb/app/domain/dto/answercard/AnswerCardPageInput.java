package com.zhishiku.kb.app.domain.dto.answercard;

import com.zhishiku.kb.app.service.answercard.AnswerCardFailure;

/** 已解析的有界查询条件，游标仍需在服务中绑定当前主体与用途。 */
public record AnswerCardPageInput(
        Long spaceId,
        String view,
        Long revisionId,
        String name,
        int limit,
        String cursor
) {
    public AnswerCardPageInput {
        if (spaceId != null && spaceId <= 0 || revisionId != null && revisionId <= 0
                || limit < 1 || limit > 100 || cursor != null && cursor.length() > 4096) {
            throw AnswerCardFailure.invalid();
        }
    }

    @Override
    public String toString() {
        return "AnswerCardPageInput[redacted]";
    }
}
