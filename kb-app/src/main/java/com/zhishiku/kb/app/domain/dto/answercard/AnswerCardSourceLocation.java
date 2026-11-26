package com.zhishiku.kb.app.domain.dto.answercard;

/** 来源文档与当前空间的锁提示，锁后必须逐文档一致，不是访问许可。 */
public record AnswerCardSourceLocation(
        long documentId,
        long spaceId
) {
}
