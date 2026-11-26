package com.zhishiku.kb.app.domain.vo.answercard;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 标准答案公开与工作区响应；所有身份使用精确十进制字符串。 */
public final class AnswerCardViews {
    private AnswerCardViews() {
    }

    @Schema(description = "答案或来源的固定日历适用声明")
    public record Dates(
            @Schema(description = "生效日期，YYYY-MM-DD，空表示未声明，包含当天")
            LocalDate effectiveFrom,
            @Schema(description = "UNDECLARED未声明、OPEN_ENDED长期有效、DATE指定截止日")
            String untilKind,
            @Schema(description = "截止日期，YYYY-MM-DD，包含当天，仅DATE有值")
            LocalDate effectiveUntil,
            @Schema(description = "UNDECLARED未声明、NOT_YET_EFFECTIVE未生效、EXPIRED过期、EXPIRING_SOON三十天内到期、EFFECTIVE适用")
            String state
    ) {
    }

    @Schema(description = "经授权的责任人最小身份")
    public record Person(
            @Schema(description = "责任人用户ID，精确十进制字符串")
            String userId,
            @Schema(description = "当前显示名，不包含账号或部门资料")
            String displayName
    ) {
    }

    @Schema(description = "卡片复审信息，责任人身份仅管理工作区提供")
    public record Stewardship(
            @Schema(description = "UNASSIGNED未指定、NEEDS_REASSIGN需改派、DUE_SOON临近复审、OVERDUE复审逾期、OK正常")
            String state,
            @Schema(description = "当前责任人，仅有最小身份读取资格时提供")
            Person responsible,
            @Schema(description = "复审周期月数，3、6、12、24；未指定为空")
            Integer cycleMonths,
            @Schema(description = "首次指定业务日期，YYYY-MM-DD")
            LocalDate firstAssignedOn,
            @Schema(description = "最近明确确认准确的日期，YYYY-MM-DD，无确认为空")
            LocalDate lastConfirmedOn,
            @Schema(description = "复审截止日期，含当天，YYYY-MM-DD")
            LocalDate dueDate,
            @Schema(description = "复审设置并发版本，未指定为0，精确十进制字符串")
            String version
    ) {
    }

    @Schema(description = "卡片原固定依据，按当前来源权限读取")
    public record Source(
            @Schema(description = "来源文档ID，精确十进制字符串")
            String documentId,
            @Schema(description = "原固定来源版本ID，精确十进制字符串")
            String versionId,
            @Schema(description = "固定版本号，精确十进制字符串")
            String versionNo,
            @Schema(description = "当前有权读取的来源文档标题")
            String title,
            @Schema(description = "原绑定日期修订号，精确十进制字符串")
            String applicabilityVersion,
            @Schema(description = "原选择时的日期声明")
            Dates boundDates,
            @Schema(description = "当前固定版本日期声明，用于与原绑定核对")
            Dates currentDates,
            @Schema(description = "原固定版本的当前日期修订号")
            String currentApplicabilityVersion,
            @Schema(description = "来源当前发布版本ID，不能据此自动改写绑定")
            String currentVersionId,
            @Schema(description = "受保护的原固定版本预览API路径，访问时重新鉴权")
            String previewPath
    ) {
    }

    @Schema(description = "单个已授权答案内容修订")
    public record Revision(
            @Schema(description = "所属卡片ID")
            String cardId,
            @Schema(description = "内容修订ID")
            String revisionId,
            @Schema(description = "内容修订序号，从1递增")
            String revisionNo,
            @Schema(description = "所属草稿分支ID，普通详情为空")
            String branchId,
            @Schema(description = "审定或当前草稿的标准业务问题，纯文本")
            String question,
            @Schema(description = "常见问法列表，每项为纯文本")
            List<String> aliases,
            @Schema(description = "答案全文，纯文本，不执行标记")
            String answer,
            @Schema(description = "适用业务范围，纯文本")
            String applicabilityText,
            @Schema(description = "本修订的日历适用声明")
            Dates dates,
            @Schema(description = "全部当前可读的固定来源")
            List<Source> sources,
            @Schema(description = "AVAILABLE当前可展示、SOURCE_CHANGED来源变更、NOT_YET_EFFECTIVE未生效、EXPIRED过期、DRAFT未批准、HISTORICAL历史批准版、UNAVAILABLE尚不满足展示条件")
            String availability
    ) {
    }

    @Schema(description = "普通搜索与浏览中的标准答案摘要")
    public record Item(
            @Schema(description = "答案卡ID")
            String cardId,
            @Schema(description = "命中的固定发布修订ID")
            String revisionId,
            @Schema(description = "命中的内容修订序号")
            String revisionNo,
            @Schema(description = "审定标准问题原文")
            String question,
            @Schema(description = "答案原文前缀，最多400个Unicode码点")
            String answerExcerpt,
            @Schema(description = "摘要是否截断原答案")
            boolean truncated,
            @Schema(description = "审定适用范围原文")
            String applicabilityText,
            @Schema(description = "审定卡片适用日期")
            Dates dates,
            @Schema(description = "当前复审状态提示")
            String stewardshipState,
            @Schema(description = "固定修订详情的受保护相对页面路径")
            String detailPath
    ) {
    }

    @Schema(description = "有界当前授权分页，不提供隐藏总数")
    public record Page<T>(
            @Schema(description = "当前页已授权条目")
            List<T> items,
            @Schema(description = "当前授权范围是否仍有下一页")
            boolean hasMore,
            @Schema(description = "绑定用户、用途和条件的下一页游标，无下一页为空")
            String nextCursor,
            @Schema(description = "本次判断的业务日期，YYYY-MM-DD")
            LocalDate asOfDate
    ) {
    }

    @Schema(description = "独立卡片搜索结果与实际应用条件")
    public record Search(
            @Schema(description = "当前可展示的相关答案卡摘要，最多20项")
            List<Item> items,
            @Schema(description = "本次业务日期，YYYY-MM-DD")
            LocalDate asOfDate,
            @Schema(description = "本次事实核对时间，ISO8601带时区")
            OffsetDateTime evaluatedAt,
            @Schema(description = "实际应用的原卡片查询文本")
            String appliedQuery,
            @Schema(description = "实际限定的卡片所属空间ID，空为不限")
            String appliedSpaceId
    ) {
    }

    @Schema(description = "固定发布修订详情")
    public record Detail(
            @Schema(description = "本次可展示的固定修订正文与依据")
            Revision revision,
            @Schema(description = "当前周期复审提示")
            Stewardship stewardship,
            @Schema(description = "当前业务日期，YYYY-MM-DD")
            LocalDate asOfDate
    ) {
    }

    @Schema(description = "私有审核轮次信息，仅合格操作人员可读")
    public record Review(
            @Schema(description = "审核轮次ID")
            String reviewId,
            @Schema(description = "冻结的修订ID")
            String revisionId,
            @Schema(description = "审核轮次序号")
            String roundNo,
            @Schema(description = "IN_REVIEW、APPROVED、REJECTED、WITHDRAWN")
            String state,
            @Schema(description = "审核并发版本")
            String version,
            @Schema(description = "送审用户ID")
            String submittedBy,
            @Schema(description = "送审时间，ISO8601带时区")
            OffsetDateTime submittedAt,
            @Schema(description = "决定时间，审核中为空")
            OffsetDateTime decidedAt,
            @Schema(description = "私有驳回原因，其他状态为空")
            String reason
    ) {
    }

    @Schema(description = "当前动作资格，每次实际操作仍需重新核验")
    public record Capabilities(
            @Schema(description = "当前能否保存草稿")
            boolean canEdit,
            @Schema(description = "当前能否尝试送审，提交时仍检查完整性与审核人员")
            boolean canSubmit,
            @Schema(description = "当前能否对活动审核作出非自审决定")
            boolean canReview,
            @Schema(description = "当前是否为可撤回的送审者")
            boolean canWithdraw,
            @Schema(description = "当前责任人能否确认发布版仍准确")
            boolean canConfirm
    ) {
    }

    @Schema(description = "卡片当前工作区，草稿与发布版分别授权")
    public record Workspace(
            @Schema(description = "卡片ID")
            String cardId,
            @Schema(description = "所属空间ID")
            String spaceId,
            @Schema(description = "当前卡片并发版本")
            String cardVersion,
            @Schema(description = "当前有权核对的发布修订，无发布或不可访问为空")
            Revision published,
            @Schema(description = "当前有权核对的草稿修订，无草稿或不可访问为空")
            Revision draft,
            @Schema(description = "活动审核，无审核或其修订不可访问为空")
            Review review,
            @Schema(description = "当前责任人复审信息")
            Stewardship stewardship,
            @Schema(description = "当前可执行动作")
            Capabilities capabilities,
            @Schema(description = "本次业务日期")
            LocalDate asOfDate
    ) {
    }

    @Schema(description = "管理者最小卡片信息，不包含正文和来源身份")
    public record Management(
            @Schema(description = "卡片ID")
            String cardId,
            @Schema(description = "所属空间ID")
            String spaceId,
            @Schema(description = "当前卡片并发版本")
            String cardVersion,
            @Schema(description = "DRAFT未发布、IN_REVIEW审核中、PUBLISHED有发布记录")
            String workflowState,
            @Schema(description = "空间是否归档，归档期间禁止配置")
            boolean spaceArchived,
            @Schema(description = "责任人及周期最小投影")
            Stewardship stewardship,
            @Schema(description = "当前业务日期")
            LocalDate asOfDate
    ) {
    }

    @Schema(description = "已授权来源候选，选择不授予持续访问")
    public record SourceOption(
            @Schema(description = "当前来源文档ID")
            String documentId,
            @Schema(description = "当前已发布版本ID")
            String versionId,
            @Schema(description = "当前来源标题")
            String title,
            @Schema(description = "当前文件版本序号")
            String versionNo,
            @Schema(description = "当前日期声明修订号")
            String applicabilityVersion,
            @Schema(description = "当前来源日期声明")
            Dates dates
    ) {
    }

    @Schema(description = "本人原意图的持久提交核对")
    public record CommandResult(
            @Schema(description = "COMMITTED已提交、NOT_FOUND本次未查到，不代表在途请求不会提交")
            String status,
            @Schema(description = "已提交时返回原无正文回执，否则为空")
            AnswerCardReceipt receipt
    ) {
    }

    @Schema(description = "私有历史条目，按内容修订或审核轮次分页")
    public record History(
            @Schema(description = "内容修订页为完整授权修订，审核轮次页为空")
            Revision revision,
            @Schema(description = "内容修订页为该修订最近轮次；审核轮次页为所选历史轮次")
            Review review
    ) {
    }
}
