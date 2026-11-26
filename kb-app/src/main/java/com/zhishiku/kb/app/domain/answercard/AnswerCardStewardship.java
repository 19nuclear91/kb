package com.zhishiku.kb.app.domain.answercard;

import java.time.LocalDate;

/** kb_answer_card_stewardship 表：卡片责任人和独立周期复审设置。 */
public record AnswerCardStewardship(
        /** 所属卡片 ID。 */
        long cardId,
        /** 当前维护责任人用户 ID，不构成授权。 */
        long responsibleUserId,
        /** 复审周期，单位月，仅允许 3、6、12、24。 */
        int cycleMonths,
        /** 首次指定的业务日期，改派不重置。 */
        LocalDate firstAssignedOn,
        /** 最近确认准确的业务日期，无确认为空。 */
        LocalDate lastConfirmedOn,
        /** 最近确认所针对的发布修订 ID，无确认为空。 */
        Long lastConfirmedRevisionId,
        /** 复审并发版本，首次指定为 1。 */
        long stewardshipVersion
) {
}
