package com.zhishiku.kb.app.document.stewardship;

import com.zhishiku.kb.app.document.applicability.ValidityBoundaryCases;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipSchedule;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import static com.zhishiku.kb.app.document.applicability.ValidityBoundaryCases.date;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StewardshipScheduleTest {
    static Stream<List<String>> boundaries() throws Exception {
        return ValidityBoundaryCases.cases("stewardship");
    }

    @ParameterizedTest
    @MethodSource("boundaries")
    void evaluatesSharedBoundaryCases(List<String> row) {
        Long userId = row.get(5).isEmpty() ? null : Long.valueOf(row.get(5));
        assertThat(StewardshipState.of(userId, Boolean.parseBoolean(row.get(6)), date(row.get(7)), date(row.get(1))).name())
                .isEqualTo(row.get(8));
    }

    @Test
    void monthEndIsClippedAndReassignmentOrReassignmentAfterCancellationCannotRestartClock() {
        var first = StewardshipSchedule.assign(null, 3, LocalDate.of(2026, 11, 30));
        assertThat(first.dueDate()).isEqualTo(LocalDate.of(2027, 2, 28));
        var reassigned = StewardshipSchedule.assign(first, 3, LocalDate.of(2027, 1, 10));
        assertThat(reassigned).isEqualTo(first);
        var assignedAgain = StewardshipSchedule.assign(reassigned, 3, LocalDate.of(2027, 4, 1));
        assertThat(assignedAgain).isEqualTo(first);
        assertThat(assignedAgain.withCycle(6).firstAssignedOn()).isEqualTo(first.firstAssignedOn());
        assertThat(assignedAgain.withCycle(6).dueDate()).isEqualTo(LocalDate.of(2027, 5, 30));
    }

    @Test
    void cycleChangeUsesLastConfirmationAndConfirmationKeepsFirstAssignment() {
        var first = StewardshipSchedule.assign(null, 12, LocalDate.of(2025, 11, 30));
        var confirmed = first.confirm(LocalDate.of(2026, 3, 10));
        assertThat(confirmed.dueDate()).isEqualTo(LocalDate.of(2027, 3, 10));
        assertThat(confirmed.withCycle(6).dueDate()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(confirmed.withCycle(6).firstAssignedOn()).isEqualTo(first.firstAssignedOn());
        assertThat(confirmed.confirm(LocalDate.of(2026, 4, 10)).dueDate()).isEqualTo(LocalDate.of(2027, 4, 10));
    }

    @Test
    void onlyFixedCyclesAreAccepted() {
        for (int cycle : List.of(3, 6, 12, 24)) {
            assertThat(StewardshipSchedule.assign(null, cycle, LocalDate.of(2026, 1, 1)).cycleMonths()).isEqualTo(cycle);
        }
        for (int cycle : List.of(-1, 0, 1, 9, 25)) {
            assertThatThrownBy(() -> StewardshipSchedule.assign(null, cycle, LocalDate.of(2026, 1, 1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
