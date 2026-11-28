package com.zhishiku.kb.app.domain.req.health;

import io.swagger.v3.oas.annotations.media.Schema;

/** 已规范化的只读雷达查询，不接受调用者指定业务日期。 */
@Schema(description = "单空间健康诊断查询")
public record HealthQuery(
        @Schema(description = "ALL全部，或STEWARD_UNASSIGNED未指定、STEWARD_NEEDS_REASSIGN需改派、REVIEW_OVERDUE复审逾期、REVIEW_DUE_SOON即将复审、APPLICABILITY_EXPIRED适用期届满、APPLICABILITY_EXPIRING_SOON即将届满")
        String signal,
        @Schema(description = "本页最多文档数，1至100，默认20")
        int limit,
        @Schema(description = "绑定当前身份、空间、筛选、页大小和业务日期的下一页游标，可空，最多2048字符")
        String cursor
) {
}
