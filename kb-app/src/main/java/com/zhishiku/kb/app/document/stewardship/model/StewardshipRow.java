package com.zhishiku.kb.app.document.stewardship.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record StewardshipRow(long documentId, Long responsibleUserId, int cycleMonths, LocalDate firstAssignedOn,
                             LocalDate lastConfirmedOn, Long lastConfirmedBy, LocalDate dueDate,
                             long stewardshipVersion, OffsetDateTime updatedAt) {
    public StewardshipSchedule schedule() {
        return new StewardshipSchedule(cycleMonths, firstAssignedOn, lastConfirmedOn, dueDate);
    }
}
