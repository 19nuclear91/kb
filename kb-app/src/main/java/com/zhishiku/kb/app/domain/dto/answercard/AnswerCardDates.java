package com.zhishiku.kb.app.domain.dto.answercard;

import com.zhishiku.kb.app.service.answercard.AnswerCardFailure;
import java.time.LocalDate;
import java.util.Set;

/** 内容与来源使用相同的日历声明，未声明不是长期有效承诺。 */
public record AnswerCardDates(
        LocalDate effectiveFrom,
        String untilKind,
        LocalDate effectiveUntil
) {
    public AnswerCardDates {
        if (untilKind == null || !Set.of("UNDECLARED", "OPEN_ENDED", "DATE").contains(untilKind)
                || "DATE".equals(untilKind) != (effectiveUntil != null)
                || !inRange(effectiveFrom) || !inRange(effectiveUntil)
                || effectiveFrom != null && effectiveUntil != null && effectiveUntil.isBefore(effectiveFrom)) {
            throw AnswerCardFailure.invalid();
        }
    }

    public boolean notYetEffective(LocalDate today) {
        return effectiveFrom != null && effectiveFrom.isAfter(today);
    }

    public boolean expired(LocalDate today) {
        return effectiveUntil != null && effectiveUntil.isBefore(today);
    }

    public String state(LocalDate today) {
        if (effectiveFrom == null && "UNDECLARED".equals(untilKind)) {
            return "UNDECLARED";
        }
        if (notYetEffective(today)) {
            return "NOT_YET_EFFECTIVE";
        }
        if (expired(today)) {
            return "EXPIRED";
        }
        if (effectiveUntil != null && !effectiveUntil.isAfter(today.plusDays(30))) {
            return "EXPIRING_SOON";
        }
        return "EFFECTIVE";
    }

    private static boolean inRange(LocalDate date) {
        return date == null || date.getYear() >= 1900 && date.getYear() <= 2999;
    }
}
