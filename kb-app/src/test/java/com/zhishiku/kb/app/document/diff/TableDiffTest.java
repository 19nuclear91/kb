package com.zhishiku.kb.app.document.diff;

import com.zhishiku.kb.app.document.diff.service.TableDiff;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TableDiffTest {
    @Test
    void reportsOnlyChangedCellAndRestructuresDifferentWidths() {
        var result = TableDiff.compare("[[\"项目\",\"金额\"],[\"甲\",\"5000\"]]",
                "[[\"项目\",\"金额\"],[\"甲\",\"8000\"]]", BlockDiffTest.deadline());
        assertThat(result.status()).isEqualTo("CELLS");
        assertThat(result.rows()).extracting(row -> row.op()).containsExactly("EQUAL", "MODIFIED");
        assertThat(result.rows().get(1).cells()).hasSize(1);
        assertThat(result.rows().get(1).cells().getFirst().column()).isEqualTo(1);
        var restructured = TableDiff.compare("[[\"甲\",\"乙\"]]", "[[\"甲\",\"乙\",\"丙\"]]",
                BlockDiffTest.deadline());
        assertThat(restructured.status()).isEqualTo("RESTRUCTURED");
        assertThat(restructured.rows()).isEmpty();
        assertThat(restructured.base()).hasSize(1);
        assertThat(restructured.target().getFirst()).hasSize(3);
    }
}
