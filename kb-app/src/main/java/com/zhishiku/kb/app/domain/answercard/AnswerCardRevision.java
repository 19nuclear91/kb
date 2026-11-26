package com.zhishiku.kb.app.domain.answercard;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** kb_answer_card_revision 表：每次保存追加的不可变答案内容，不输出正文到诊断字符串。 */
public record AnswerCardRevision(
        /** 内容修订 ID。 */
        long id,
        /** 所属答案卡 ID。 */
        long cardId,
        /** 所属草稿分支 ID。 */
        long branchId,
        /** 卡片内递增修订号，从 1 开始。 */
        long revisionNo,
        /** 标准业务问题，纯文本。 */
        String question,
        /** 常见问法的 JSON 字符串数组，最多 20 项。 */
        String aliasesJson,
        /** 经编辑的答案正文，纯文本；草稿允许为空串。 */
        String answer,
        /** 答案适用范围说明，纯文本。 */
        String applicabilityText,
        /** 生效业务日期，包含当天；null 表示未声明。 */
        LocalDate effectiveFrom,
        /** 截止类型：UNDECLARED 未声明、OPEN_ENDED 长期有效、DATE 指定日期。 */
        String untilKind,
        /** 截止业务日期，包含当天；仅 DATE 时有值。 */
        LocalDate effectiveUntil,
        /** 归一化问题及问法的 JSON 数组，用于文本精确匹配。 */
        String normalizedQuestionsJson,
        /** 标准问题与问法合并的全文索引文本。 */
        String questionText,
        /** 实际保存该修订的用户 ID。 */
        long createdBy,
        /** 修订创建时间，带时区。 */
        OffsetDateTime createdAt
) {
    @Override
    public String toString() {
        return "AnswerCardRevision[id=" + id + ", cardId=" + cardId + "]";
    }
}
