package com.zhishiku.kb.app.service.impl.health;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.document.applicability.model.UntilKind;
import com.zhishiku.kb.app.domain.dto.health.HealthRow;
import com.zhishiku.kb.app.domain.dto.health.HealthTotals;
import com.zhishiku.kb.app.domain.req.health.HealthQuery;
import com.zhishiku.kb.app.enums.health.HealthSignal;
import com.zhishiku.kb.app.service.health.HealthFailure;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HealthRulesTest {
    private final LocalDate today = LocalDate.of(2027, 1, 1);

    @Test
    void priorityRetainsOldDatesWithoutInventingOverdueOrPrivateReasons() throws Exception {
        var unassigned = new HealthRow(Long.MAX_VALUE, null, null, today.minusDays(10), false, "UNASSIGNED", null, null, null, null, null);
        var value = HealthProjection.item(unassigned, today);
        assertThat(value.signals()).containsExactly(HealthSignal.STEWARD_UNASSIGNED);
        assertThat(value.stewardship().dueDate()).isEqualTo(today.minusDays(10));
        var lost = new HealthRow(2, 7L, "负责人", today.minusDays(10), false, "NEEDS_REASSIGN", null, null, null, null, null);
        assertThat(HealthProjection.item(lost, today).signals()).containsExactly(HealthSignal.STEWARD_NEEDS_REASSIGN);
        var restored = new HealthRow(2, 7L, "负责人", today.minusDays(10), true, "OVERDUE", null, null, null, null, null);
        assertThat(HealthProjection.item(restored, today).signals()).containsExactly(HealthSignal.REVIEW_OVERDUE);
        var json = new ObjectMapper().findAndRegisterModules().valueToTree(value);
        assertThat(json.path("documentId").textValue()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(json.path("applicability").isNull()).isTrue();
        assertThat(json.has("title")).isFalse();
        assertThat(json.path("stewardship").has("reason")).isFalse();
    }

    @Test
    void reviewAndApplicabilityUseInclusiveThirtyDayBoundary() {
        for (int offset : List.of(-1, 0, 30, 31)) {
            String review = offset < 0 ? "OVERDUE" : offset <= 30 ? "DUE_SOON" : "OK";
            String applicability = offset < 0 ? "EXPIRED" : offset <= 30 ? "EXPIRING_SOON" : "EFFECTIVE";
            var row = new HealthRow(1, 7L, "负责人", today.plusDays(offset), true, review,
                    10L, null, UntilKind.DATE, today.plusDays(offset), applicability);
            if (offset == 31) {
                assertThatThrownBy(() -> HealthProjection.item(row, today)).isInstanceOf(HealthFailure.class);
            } else {
                var item = HealthProjection.item(row, today);
                assertThat(item.signals()).containsExactly(offset < 0 ? HealthSignal.REVIEW_OVERDUE : HealthSignal.REVIEW_DUE_SOON,
                        offset < 0 ? HealthSignal.APPLICABILITY_EXPIRED : HealthSignal.APPLICABILITY_EXPIRING_SOON);
            }
        }
    }

    @Test
    void undeclaredOpenEndedAndFutureEffectiveAreNotProblems() {
        for (var row : List.of(
                new HealthRow(1, null, null, null, false, "UNASSIGNED", 10L, null, UntilKind.UNDECLARED, null, "UNDECLARED"),
                new HealthRow(2, null, null, null, false, "UNASSIGNED", 11L, null, UntilKind.OPEN_ENDED, null, "EFFECTIVE"),
                new HealthRow(3, null, null, null, false, "UNASSIGNED", 12L, today.plusDays(1), UntilKind.DATE, today.plusDays(5), "NOT_YET_EFFECTIVE"))) {
            var item = HealthProjection.item(row, today);
            assertThat(item.signals()).containsExactly(HealthSignal.STEWARD_UNASSIGNED);
            assertThat(item.applicability()).isNull();
        }
    }

    @Test
    void mismatchedStateOrLeakedHiddenDatesFailEntireProjection() {
        var mismatched = new HealthRow(1, null, null, today.minusDays(1), false, "OVERDUE", null, null, null, null, null);
        assertThatThrownBy(() -> HealthProjection.item(mismatched, today)).isInstanceOf(HealthFailure.class);
        var leaked = new HealthRow(1, null, null, null, false, "UNASSIGNED", null, today, UntilKind.DATE, today, "EXPIRING_SOON");
        assertThatThrownBy(() -> HealthProjection.item(leaked, today)).isInstanceOf(HealthFailure.class);
    }

    @Test
    void countPrecisionAndUnionAreNotAFloatOrSimpleSum() {
        String large = "9007199254740993";
        var counts = HealthProjection.counts(new HealthTotals(large, "0", "0", "0", large, "0", large));
        assertThat(counts.stewardUnassigned()).isEqualTo(large);
        assertThatThrownBy(() -> HealthProjection.counts(new HealthTotals("1", "0", "0", "0", "0", "0", "0")))
                .isInstanceOf(HealthFailure.class);
        assertThatThrownBy(() -> HealthProjection.number("01")).isInstanceOf(HealthFailure.class);
    }

    @Test
    void parametersAndIdsAreStrict() {
        assertThat(HealthQueries.parse(Map.of())).isEqualTo(new HealthQuery("ALL", 20, null));
        for (String limit : List.of("1", "20", "100")) {
            assertThat(HealthQueries.parse(Map.of("limit", new String[]{limit})).limit()).isEqualTo(Integer.parseInt(limit));
        }
        assertThat(HealthQueries.validate(new HealthQuery("ALL", 20, "x".repeat(2048))).cursor()).hasSize(2048);
        assertThat(HealthQueries.id("9223372036854775807")).isEqualTo(Long.MAX_VALUE);
        for (String value : List.of("0", "-1", "01", "1.0", "9223372036854775808", " 1")) {
            assertThatThrownBy(() -> HealthQueries.id(value)).isInstanceOf(HealthFailure.class);
        }
        for (var input : List.of(Map.of("signal", new String[]{"ALL", "ALL"}), Map.of("sort", new String[]{"id"}),
                Map.of("limit", new String[]{"0"}), Map.of("limit", new String[]{"101"}), Map.of("signal", new String[]{"UNKNOWN"}),
                Map.of("cursor", new String[]{""}), Map.of("cursor", new String[]{"x".repeat(2049)}))) {
            assertThatThrownBy(() -> HealthQueries.parse(input)).isInstanceOf(HealthFailure.class);
        }
    }

    @Test
    void cursorBindsEveryContextAndDateWithoutExtendingAuthorization() {
        var query = new HealthQuery("ALL", 20, null);
        String token = HealthCursor.encode(7, 2, query, today, Long.MAX_VALUE);
        var bound = new HealthQuery("ALL", 20, token);
        assertThat(HealthCursor.decode(7, 2, bound, today)).isEqualTo(Long.MAX_VALUE);
        assertThatThrownBy(() -> HealthCursor.decode(8, 2, bound, today)).isInstanceOf(HealthFailure.class);
        assertThatThrownBy(() -> HealthCursor.decode(7, 3, bound, today)).isInstanceOf(HealthFailure.class);
        assertThatThrownBy(() -> HealthCursor.decode(7, 2, new HealthQuery("REVIEW_OVERDUE", 20, token), today)).isInstanceOf(HealthFailure.class);
        assertThatThrownBy(() -> HealthCursor.decode(7, 2, new HealthQuery("ALL", 100, token), today)).isInstanceOf(HealthFailure.class);
        assertThatThrownBy(() -> HealthCursor.decode(7, 2, bound, today.plusDays(1))).isInstanceOfSatisfying(HealthFailure.class,
                error -> assertThat(error.reason()).isEqualTo("RADAR_DATE_CHANGED"));
        assertThatThrownBy(() -> HealthCursor.decode(7, 2, new HealthQuery("ALL", 20, "x".repeat(2049)), today)).isInstanceOf(HealthFailure.class);
    }
}
