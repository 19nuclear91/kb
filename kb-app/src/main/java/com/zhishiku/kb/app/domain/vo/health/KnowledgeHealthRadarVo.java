package com.zhishiku.kb.app.domain.vo.health;

import com.zhishiku.kb.app.enums.health.HealthSignal;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "单空间知识健康诊断；各类数量有交集，总数按文档去重")
public record KnowledgeHealthRadarVo(
        @Schema(description = "固定协议版本KNOWLEDGE_HEALTH_V1")
        String definitionVersion,
        @Schema(description = "当前诊断空间ID，正十进制字符串")
        String spaceId,
        @Schema(description = "数据库本次统计时间，带时区偏移的ISO时间")
        OffsetDateTime asOf,
        @Schema(description = "本次业务日期，yyyy-MM-dd")
        LocalDate asOfDate,
        @Schema(description = "部署固定的业务地区时区，默认Asia/Shanghai")
        String dayTimeZone,
        @Schema(description = "本次清单筛选，ALL或六类信号代码；汇总不受筛选影响")
        String signal,
        @Schema(description = "本页条数上限，1至100")
        int limit,
        @Schema(description = "六类信号在当前完整授权范围内的文档数量")
        Counts counts,
        @Schema(description = "命中至少一个可见信号的去重文档数，非负十进制字符串")
        String affectedDocumentCount,
        @Schema(description = "当前页的最小维护信息，每文档一项，按ID升序")
        List<Item> items,
        @Schema(description = "当前读取范围内是否存在下一页")
        boolean hasMore,
        @Schema(description = "下一页上下文绑定游标；无下一页时为null")
        String nextCursor
) {
    @Schema(description = "独立计数，前四类覆盖活动文档，后两类仅覆盖操作者可读的当前发布版本")
    public record Counts(
            @Schema(description = "未指定责任人的文档数，非负十进制字符串")
            String stewardUnassigned,
            @Schema(description = "需重新指定责任人的文档数，非负十进制字符串")
            String stewardNeedsReassign,
            @Schema(description = "复审已逾期文档数，非负十进制字符串")
            String reviewOverdue,
            @Schema(description = "复审将在30天内到期的文档数，含当天，非负十进制字符串")
            String reviewDueSoon,
            @Schema(description = "当前可读发布版本适用期已届满的文档数，非负十进制字符串")
            String applicabilityExpired,
            @Schema(description = "当前可读发布版本适用期将在30天内届满的文档数，含当天，非负十进制字符串")
            String applicabilityExpiringSoon
    ) {
    }

    @Schema(description = "单份文档的维护信息，不含标题、正文或不合格原因")
    public record Item(
            @Schema(description = "文档ID，正十进制字符串")
            String documentId,
            @Schema(description = "全部可见原因，依次为未指定、需改派、复审逾期、即将复审、适用期届满、即将届满；同文档最多两项")
            List<HealthSignal> signals,
            @Schema(description = "当前责任人与复审的最小管理信息")
            Stewardship stewardship,
            @Schema(description = "命中适用期信号时的可读固定版本日期；其他情况统一为null")
            Applicability applicability
    ) {
    }

    @Schema(description = "当前复审管理信息，逾期不代表内容失效")
    public record Stewardship(
            @Schema(description = "UNASSIGNED未指定、NEEDS_REASSIGN需改派、OVERDUE已逾期、DUE_SOON30天内到期、OK正常")
            String state,
            @Schema(description = "当前责任人最小身份；未指定时为null")
            Responsible responsible,
            @Schema(description = "复审到期日期yyyy-MM-dd；未设置时为null，取消责任人后可保留旧日期")
            LocalDate dueDate
    ) {
    }

    @Schema(description = "仅用于维护管理的责任人信息，不表达身份或文档访问资格")
    public record Responsible(
            @Schema(description = "当前责任人用户ID，正十进制字符串")
            String userId,
            @Schema(description = "责任人显示名，不包含账号、部门或失去资格原因")
            String displayName
    ) {
    }

    @Schema(description = "本次诊断可读的固定当前发布版本适用期")
    public record Applicability(
            @Schema(description = "固定文档版本ID，正十进制字符串")
            String versionId,
            @Schema(description = "EXPIRED截止日已过；EXPIRING_SOON截止日在30天内，包含当天")
            String state,
            @Schema(description = "声明生效日期yyyy-MM-dd，可为null表示未声明")
            LocalDate effectiveFrom,
            @Schema(description = "声明截止日期yyyy-MM-dd，包含截止日当天")
            LocalDate effectiveUntil
    ) {
    }
}
