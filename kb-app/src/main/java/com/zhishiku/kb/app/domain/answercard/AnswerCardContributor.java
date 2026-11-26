package com.zhishiku.kb.app.domain.answercard;

/** kb_answer_card_contributor 表：分支全部实际内容贡献者，用于防止自审。 */
public record AnswerCardContributor(
        /** 所属草稿分支 ID。 */
        long branchId,
        /** 曾为本分支保存内容的用户 ID，记录不可删除。 */
        long userId
) {
}
