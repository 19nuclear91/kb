package com.zhishiku.kb.app.domain.answercard;

/** kb_answer_card_branch 表：同一卡片至多一个未关闭的草稿分支。 */
public record AnswerCardBranch(
        /** 草稿分支 ID。 */
        long id,
        /** 所属答案卡 ID。 */
        long cardId,
        /** 卡片内递增分支号，从 1 开始。 */
        long branchNo,
        /** 分支最新内容修订 ID。 */
        Long latestRevisionId,
        /** 是否已经批准关闭：true 为关闭，false 为可编辑或审核中。 */
        boolean closed
) {
}
