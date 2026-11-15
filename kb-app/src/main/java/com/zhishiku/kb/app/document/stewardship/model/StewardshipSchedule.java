package com.zhishiku.kb.app.document.stewardship.model;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

/** 取消和改派保留本对象；只有周期变化或真实确认才重算到期日。 */
public record StewardshipSchedule(int cycleMonths, LocalDate firstAssignedOn, LocalDate lastConfirmedOn,
                                 LocalDate dueDate) {
    public StewardshipSchedule {
        requireCycle(cycleMonths);
        Objects.requireNonNull(firstAssignedOn);
        LocalDate base = lastConfirmedOn == null ? firstAssignedOn : lastConfirmedOn;
        if (!base.plusMonths(cycleMonths).equals(dueDate)) {
            throw new IllegalArgumentException("复审到期日无效");
        }
    }

    public static StewardshipSchedule assign(StewardshipSchedule previous, int months, LocalDate today) {
        requireCycle(months);
        if (previous != null) {
            return previous.withCycle(months);
        }
        return new StewardshipSchedule(months, today, null, today.plusMonths(months));
    }

    public StewardshipSchedule withCycle(int months) {
        requireCycle(months);
        LocalDate base = lastConfirmedOn == null ? firstAssignedOn : lastConfirmedOn;
        return new StewardshipSchedule(months, firstAssignedOn, lastConfirmedOn, base.plusMonths(months));
    }

    public StewardshipSchedule confirm(LocalDate today) {
        return new StewardshipSchedule(cycleMonths, firstAssignedOn, today, today.plusMonths(cycleMonths));
    }

    public static void requireCycle(int months) {
        if (!Set.of(3, 6, 12, 24).contains(months)) {
            throw new IllegalArgumentException("复审周期无效");
        }
    }
}
