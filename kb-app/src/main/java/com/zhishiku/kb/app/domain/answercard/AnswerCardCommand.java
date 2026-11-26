package com.zhishiku.kb.app.domain.answercard;

import com.zhishiku.kb.app.enums.answercard.AnswerCardOperation;
import java.time.OffsetDateTime;

/** kb_answer_card_command 表：原意图绑定和无正文回执，不保存原请求正文。 */
public record AnswerCardCommand(
        /** 八项 ANSWER_CARD 操作域之一。 */
        AnswerCardOperation operation,
        /** 原意图请求标识，1至128个非控制字符。 */
        String requestId,
        /** 原操作者用户 ID。 */
        long actorId,
        /** 卡片所属空间 ID。 */
        long spaceId,
        /** 原请求卡片 ID，创建为 null。 */
        Long cardId,
        /** 原操作草稿分支 ID，无分支为 null。 */
        Long branchId,
        /** 原操作内容修订 ID，无修订为 null。 */
        Long revisionId,
        /** 原操作审核轮次 ID，无审核为 null。 */
        Long reviewId,
        /** 原预期卡片版本，创建为 null。 */
        Long expectedCardVersion,
        /** 摘要协议版本，当前为1。 */
        int digestVersion,
        /** 原规范化输入的 SHA256 摘要。 */
        String inputDigest,
        /** 不含正文的 JSON 提交回执。 */
        String receipt,
        /** 回执持久化时间，带时区。 */
        OffsetDateTime createdAt
) {
    @Override
    public String toString() {
        return "AnswerCardCommand[operation=" + operation + "]";
    }
}
