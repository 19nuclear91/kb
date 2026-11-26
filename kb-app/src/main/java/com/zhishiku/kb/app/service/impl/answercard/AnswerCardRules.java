package com.zhishiku.kb.app.service.impl.answercard;

import com.zhishiku.kb.app.domain.answercard.AnswerCardSource;
import com.zhishiku.kb.app.domain.answercard.AnswerCardStewardship;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardDates;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardSourceFact;
import com.zhishiku.kb.app.service.answercard.AnswerCardFailure;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 卡片来源和日期的确定性业务规则，不访问数据库或系统时钟。 */
public final class AnswerCardRules {
    private AnswerCardRules() {
    }

    public static void requireFutureIntersection(AnswerCardDates card, List<AnswerCardSourceFact> sources, LocalDate today) {
        if (sources.isEmpty()) {
            throw AnswerCardFailure.conflict("INCOMPLETE_DRAFT");
        }
        LocalDate first = today;
        LocalDate last = LocalDate.of(2999, 12, 31);
        var dates = new java.util.ArrayList<AnswerCardDates>();
        dates.add(card);
        sources.forEach(source -> dates.add(new AnswerCardDates(source.effectiveFrom(), source.untilKind(), source.effectiveUntil())));
        for (var declaration : dates) {
            if (declaration.effectiveFrom() != null && declaration.effectiveFrom().isAfter(first)) {
                first = declaration.effectiveFrom();
            }
            if (declaration.effectiveUntil() != null && declaration.effectiveUntil().isBefore(last)) {
                last = declaration.effectiveUntil();
            }
        }
        if (first.isAfter(last)) {
            throw AnswerCardFailure.conflict("NO_APPLICABLE_DATE");
        }
    }

    public static String availability(AnswerCardDates card, List<AnswerCardSource> bindings,
                                      List<AnswerCardSourceFact> current, LocalDate today) {
        if (bindings.isEmpty() || bindings.size() != current.size()) {
            return "UNAVAILABLE";
        }
        boolean future = card.notYetEffective(today);
        boolean expired = card.expired(today);
        for (var binding : bindings) {
            var source = current.stream().filter(item -> item.documentId() == binding.documentId()
                    && item.versionId() == binding.versionId()).findFirst().orElse(null);
            if (source == null) {
                return "UNAVAILABLE";
            }
            if (!Objects.equals(source.currentVersionId(), binding.versionId())
                    || source.applicabilityVersion() != binding.applicabilityVersion()) {
                return "SOURCE_CHANGED";
            }
            var dates = new AnswerCardDates(source.effectiveFrom(), source.untilKind(), source.effectiveUntil());
            future |= dates.notYetEffective(today);
            expired |= dates.expired(today);
        }
        if (expired) {
            return "EXPIRED";
        }
        return future ? "NOT_YET_EFFECTIVE" : "AVAILABLE";
    }

    public static LocalDate dueDate(AnswerCardStewardship setting) {
        if (!Set.of(3, 6, 12, 24).contains(setting.cycleMonths())) {
            throw AnswerCardFailure.invalid();
        }
        LocalDate base = setting.lastConfirmedOn() == null ? setting.firstAssignedOn() : setting.lastConfirmedOn();
        return base.plusMonths(setting.cycleMonths());
    }

    public static String stewardship(AnswerCardStewardship setting, boolean eligible, LocalDate today) {
        if (setting == null) {
            return "UNASSIGNED";
        }
        if (!eligible) {
            return "NEEDS_REASSIGN";
        }
        LocalDate due = dueDate(setting);
        if (today.isAfter(due)) {
            return "OVERDUE";
        }
        return due.isAfter(today.plusDays(30)) ? "OK" : "DUE_SOON";
    }
}
