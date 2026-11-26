package com.zhishiku.kb.app.service.impl.answercard;

import com.zhishiku.kb.app.domain.answercard.AnswerCardSource;
import com.zhishiku.kb.app.domain.answercard.AnswerCardStewardship;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardContent;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardDates;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardSourceFact;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardSourceKey;
import com.zhishiku.kb.app.service.answercard.AnswerCardFailure;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnswerCardRulesTest {
    private final LocalDate today = LocalDate.of(2026, 11, 26);
    private final AnswerCardDates undeclared = new AnswerCardDates(null, "UNDECLARED", null);
    private final AnswerCardSource binding = new AnswerCardSource(1, 2, 3, 0, null, "UNDECLARED", null);

    @Test
    void sourceVersionOrDeclarationRevisionRequiresNewApprovalEvenWhenDatesMatch() {
        assertThat(AnswerCardRules.availability(undeclared, List.of(binding), List.of(source(3L, 0, null, null)), today))
                .isEqualTo("AVAILABLE");
        assertThat(AnswerCardRules.availability(undeclared, List.of(binding), List.of(source(4L, 0, null, null)), today))
                .isEqualTo("SOURCE_CHANGED");
        assertThat(AnswerCardRules.availability(undeclared, List.of(binding), List.of(source(3L, 2, null, null)), today))
                .isEqualTo("SOURCE_CHANGED");
        assertThat(AnswerCardRules.availability(undeclared, List.of(), List.of(), today)).isEqualTo("UNAVAILABLE");
    }

    @Test
    void boundariesAreInclusiveAndFutureIntersectionMustExist() {
        var source = source(3L, 0, today, today);
        assertThat(AnswerCardRules.availability(undeclared, List.of(binding), List.of(source), today)).isEqualTo("AVAILABLE");
        assertThat(AnswerCardRules.availability(undeclared, List.of(binding), List.of(source), today.plusDays(1))).isEqualTo("EXPIRED");
        assertThat(AnswerCardRules.availability(undeclared, List.of(binding), List.of(source), today.minusDays(1))).isEqualTo("NOT_YET_EFFECTIVE");
        assertThatThrownBy(() -> AnswerCardRules.requireFutureIntersection(
                new AnswerCardDates(today.plusDays(1), "UNDECLARED", null), List.of(source), today))
                .isInstanceOf(AnswerCardFailure.class);
        AnswerCardRules.requireFutureIntersection(undeclared, List.of(source), today);
    }

    @Test
    void monthlyReviewKeepsOriginalBaselineAndDoesNotGrantAccess() {
        var setting = new AnswerCardStewardship(1, 4, 3, LocalDate.of(2026, 11, 30), null, null, 1);
        assertThat(AnswerCardRules.dueDate(setting)).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(AnswerCardRules.stewardship(setting, true, LocalDate.of(2027, 1, 28))).isEqualTo("OK");
        assertThat(AnswerCardRules.stewardship(setting, true, LocalDate.of(2027, 1, 29))).isEqualTo("DUE_SOON");
        assertThat(AnswerCardRules.stewardship(setting, true, LocalDate.of(2027, 2, 28))).isEqualTo("DUE_SOON");
        assertThat(AnswerCardRules.stewardship(setting, true, LocalDate.of(2027, 3, 1))).isEqualTo("OVERDUE");
        assertThat(AnswerCardRules.stewardship(setting, false, today)).isEqualTo("NEEDS_REASSIGN");
    }

    @Test
    void draftValidationRejectsAmbiguousQuestionsAndDuplicateDocuments() {
        assertThatThrownBy(() -> new AnswerCardContent("CAFÉ", List.of("cafe\u0301"), "", "", undeclared, List.of()))
                .isInstanceOf(AnswerCardFailure.class);
        assertThatThrownBy(() -> new AnswerCardContent("问题", List.of(), "", "", undeclared,
                List.of(new AnswerCardSourceKey(2, 3, 0), new AnswerCardSourceKey(2, 4, 0))))
                .isInstanceOf(AnswerCardFailure.class);
        var draft = new AnswerCardContent(" 问题 ", List.of(), "", "", undeclared, List.of());
        assertThat(draft.question()).isEqualTo("问题");
        assertThatThrownBy(draft::requireComplete).isInstanceOf(AnswerCardFailure.class);
        assertThatThrownBy(() -> AnswerCardContent.text("bad\uD800", 0, 20)).isInstanceOf(AnswerCardFailure.class);
    }

    private AnswerCardSourceFact source(Long current, long revision, LocalDate from, LocalDate until) {
        return new AnswerCardSourceFact(2, 3, 9, "制度", 1, current, revision, from,
                until == null ? "UNDECLARED" : "DATE", until);
    }
}
