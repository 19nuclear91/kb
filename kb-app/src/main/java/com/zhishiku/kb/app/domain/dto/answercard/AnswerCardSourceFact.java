package com.zhishiku.kb.app.domain.dto.answercard;

import java.time.LocalDate;

/** 当前请求内已授权固定来源的最小投影，不携带原件和正文。 */
public record AnswerCardSourceFact(
        long documentId,
        long versionId,
        long spaceId,
        String title,
        Integer versionNo,
        Long currentVersionId,
        long applicabilityVersion,
        LocalDate effectiveFrom,
        String untilKind,
        LocalDate effectiveUntil
) {
    @Override
    public String toString() {
        return "AnswerCardSourceFact[documentId=" + documentId + ", versionId=" + versionId + "]";
    }
}
