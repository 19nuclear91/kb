package com.zhishiku.kb.app.domain.vo.answercard;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "标准答案卡无正文写入回执，重放不延续访问资格")
public record AnswerCardReceipt(
        @Schema(description = "操作目标答案卡ID，精确十进制字符串")
        String cardId,
        @Schema(description = "写入后的卡片并发版本，精确非负十进制字符串")
        String cardVersion,
        @Schema(description = "本次相关草稿分支ID，无分支时为空")
        String branchId,
        @Schema(description = "本次相关内容修订ID，无修订时为空")
        String revisionId,
        @Schema(description = "本次相关审核轮次ID，无审核时为空")
        String reviewId,
        @Schema(description = "本次审核版本，无审核时为空")
        String reviewVersion,
        @Schema(description = "本次复审设置版本，无复审变更时为空")
        String stewardshipVersion,
        @Schema(description = "结果状态：DRAFT、IN_REVIEW、APPROVED、REJECTED、WITHDRAWN、ASSIGNED或CONFIRMED")
        String state,
        @Schema(description = "服务器提交时间，ISO8601带时区")
        OffsetDateTime committedAt,
        @Schema(description = "是否为原意图已提交结果的重放")
        boolean replayed
) {
    public AnswerCardReceipt replay() {
        return new AnswerCardReceipt(cardId, cardVersion, branchId, revisionId, reviewId, reviewVersion,
                stewardshipVersion, state, committedAt, true);
    }

    public static String id(Long value) {
        return value == null ? null : Long.toString(value);
    }
}
