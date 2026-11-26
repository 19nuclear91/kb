package com.zhishiku.kb.app.domain.answercard;

import java.time.OffsetDateTime;

/** kb_answer_card 表：标准答案的稳定身份、所属空间及当前修订指针。 */
public record AnswerCard(
        /** 卡片稳定 ID。 */
        long id,
        /** 关联知识空间 ID，创建后固定。 */
        long spaceId,
        /** 卡片并发版本，初始为 0，每次成功写入递增。 */
        long cardVersion,
        /** 本卡片当前已批准修订 ID，未发布为 null。 */
        Long publishedRevisionId,
        /** 本卡片活动草稿分支 ID，无草稿为 null。 */
        Long activeBranchId,
        /** 卡片创建时间，带时区。 */
        OffsetDateTime createdAt
) {
}
