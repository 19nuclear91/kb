package com.zhishiku.kb.app.domain.answercard;

import java.time.LocalDate;

/** kb_answer_card_source 表：不可变修订的固定发布来源及当时日期声明。 */
public record AnswerCardSource(
        /** 所属卡片内容修订 ID。 */
        long revisionId,
        /** 关联来源文档 ID。 */
        long documentId,
        /** 关联来源文件版本 ID，必须属于上述文档。 */
        long versionId,
        /** 来源适用期修订号，0 表示尚无声明行。 */
        long applicabilityVersion,
        /** 来源当时声明的生效业务日期，null 为未声明。 */
        LocalDate effectiveFrom,
        /** 来源截止类型：UNDECLARED、OPEN_ENDED 或 DATE。 */
        String untilKind,
        /** 来源当时声明的截止业务日期，仅 DATE 有值。 */
        LocalDate effectiveUntil
) {
}
