package com.zhishiku.kb.app.domain.answercard;

import java.time.LocalDate;

/** kb_answer_card_confirmation 表：责任人确认准确的不可变业务事实。 */
public record AnswerCardConfirmation(
        /** 确认事实稳定 ID。 */
        long id,
        /** 所属卡片 ID。 */
        long cardId,
        /** 本次确认的已发布内容修订 ID。 */
        long revisionId,
        /** 实际确认的责任人用户 ID。 */
        long confirmedBy,
        /** 确认业务日期，Asia/Shanghai。 */
        LocalDate confirmedOn,
        /** 确认前复审版本。 */
        long beforeVersion,
        /** 确认后复审版本，等于原版本加一。 */
        long afterVersion
) {
}
