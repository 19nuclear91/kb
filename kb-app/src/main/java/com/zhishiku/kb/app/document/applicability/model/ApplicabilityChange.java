package com.zhishiku.kb.app.document.applicability.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 修改历史没有操作者字段。 */
public record ApplicabilityChange(long id, long documentId, long versionId, OffsetDateTime changedAt,
                                  LocalDate beforeEffectiveFrom, UntilKind beforeUntilKind, LocalDate beforeEffectiveUntil,
                                  LocalDate afterEffectiveFrom, UntilKind afterUntilKind, LocalDate afterEffectiveUntil,
                                  boolean visibleToReaders, String reason) {
}
