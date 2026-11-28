package com.zhishiku.kb.app.domain.dto.health;

/** 数据库聚合数量，以精确十进制文本传递。 */
public record HealthTotals(
        String stewardUnassigned,
        String stewardNeedsReassign,
        String reviewOverdue,
        String reviewDueSoon,
        String applicabilityExpired,
        String applicabilityExpiringSoon,
        String affectedDocumentCount
) {
}
