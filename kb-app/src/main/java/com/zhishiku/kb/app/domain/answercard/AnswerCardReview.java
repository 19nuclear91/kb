package com.zhishiku.kb.app.domain.answercard;

import com.zhishiku.kb.app.enums.answercard.AnswerCardReviewState;
import java.time.OffsetDateTime;

/** kb_answer_card_review 表：绑定固定内容修订的人工审核轮次，终态不可覆盖。 */
public record AnswerCardReview(
        /** 审核轮次稳定 ID。 */
        long id,
        /** 所属卡片 ID。 */
        long cardId,
        /** 审核所属分支 ID。 */
        long branchId,
        /** 本轮冻结的内容修订 ID。 */
        long revisionId,
        /** 卡片内递增审核轮次号。 */
        long roundNo,
        /** 状态：IN_REVIEW 审核中、APPROVED 通过、REJECTED 驳回、WITHDRAWN 撤回。 */
        AnswerCardReviewState state,
        /** 本轮并发版本，初始 0，决定后递增。 */
        long reviewVersion,
        /** 实际送审用户 ID。 */
        long submittedBy,
        /** 送审时间，带时区。 */
        OffsetDateTime submittedAt,
        /** 作出最终决定的用户 ID，审核中为空。 */
        Long decidedBy,
        /** 最终决定时间，带时区；审核中为空。 */
        OffsetDateTime decidedAt,
        /** 私有驳回原因，仅 REJECTED 时非空。 */
        String reason
) {
    @Override
    public String toString() {
        return "AnswerCardReview[id=" + id + ", state=" + state + "]";
    }
}
