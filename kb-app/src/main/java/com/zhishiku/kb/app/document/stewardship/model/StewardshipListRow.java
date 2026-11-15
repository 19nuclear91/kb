package com.zhishiku.kb.app.document.stewardship.model;

import com.zhishiku.kb.app.document.applicability.model.UntilKind;

import java.time.LocalDate;

/** 清单查询结果；空间清单不查询标题和版本内容。 */
public record StewardshipListRow(long documentId, String title, Long responsibleUserId, String displayName,
                                 Integer cycleMonths, LocalDate lastConfirmedOn, LocalDate dueDate,
                                 long stewardshipVersion, boolean eligible, Long versionId, LocalDate effectiveFrom,
                                 UntilKind untilKind, LocalDate effectiveUntil, boolean corrected) {
}
