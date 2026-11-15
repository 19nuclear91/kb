package com.zhishiku.kb.app.document.stewardship.model;

import java.time.LocalDate;
import java.util.Objects;

public enum StewardshipState {
    UNASSIGNED,
    NEEDS_REASSIGN,
    OVERDUE,
    DUE_SOON,
    OK;

    public static StewardshipState of(Long responsibleUserId, boolean eligible, LocalDate dueDate, LocalDate today) {
        Objects.requireNonNull(today);
        if (responsibleUserId == null) {
            return UNASSIGNED;
        }
        if (!eligible) {
            return NEEDS_REASSIGN;
        }
        Objects.requireNonNull(dueDate);
        if (today.isAfter(dueDate)) {
            return OVERDUE;
        }
        if (!dueDate.isAfter(today.plusDays(30))) {
            return DUE_SOON;
        }
        return OK;
    }
}
