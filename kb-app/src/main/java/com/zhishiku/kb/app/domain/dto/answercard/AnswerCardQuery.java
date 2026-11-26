package com.zhishiku.kb.app.domain.dto.answercard;

import java.time.LocalDate;

/** 从服务端当前身份与已校验条件形成的单次查询参数。 */
public record AnswerCardQuery(
        long actor,
        Long spaceId,
        Long cardId,
        Long revisionId,
        String view,
        Long afterId,
        int limit,
        LocalDate today,
        String query,
        String normalizedQuery
) {
    @Override
    public String toString() {
        return "AnswerCardQuery[redacted]";
    }
}
