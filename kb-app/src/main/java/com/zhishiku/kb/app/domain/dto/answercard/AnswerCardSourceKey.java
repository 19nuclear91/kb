package com.zhishiku.kb.app.domain.dto.answercard;

/** 服务层固定来源身份；不能作为授权票据。 */
public record AnswerCardSourceKey(
        long documentId,
        long versionId,
        long applicabilityVersion
) {
}
