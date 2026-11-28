package com.zhishiku.kb.app.domain.dto.health;

import com.zhishiku.kb.app.document.applicability.model.UntilKind;
import java.time.LocalDate;

/** 一份文档的最小查询投影，不读取标题、正文或原件位置。 */
public record HealthRow(
        long documentId,
        Long responsibleUserId,
        String displayName,
        LocalDate dueDate,
        boolean eligible,
        String stewardshipState,
        Long versionId,
        LocalDate effectiveFrom,
        UntilKind untilKind,
        LocalDate effectiveUntil,
        String applicabilityState
) {
    @Override
    public String toString() {
        return "HealthRow[redacted]";
    }
}
