package com.zhishiku.kb.app.document.applicability;

import com.zhishiku.kb.app.document.applicability.model.ApplicabilityDeclaration;
import com.zhishiku.kb.app.document.applicability.model.ApplicabilityState;
import com.zhishiku.kb.app.document.applicability.model.UntilKind;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static com.zhishiku.kb.app.document.applicability.ValidityBoundaryCases.date;
import static org.assertj.core.api.Assertions.assertThat;

class ApplicabilityStateTest {
    static Stream<List<String>> boundaries() throws Exception {
        return ValidityBoundaryCases.cases("applicability");
    }

    @ParameterizedTest
    @MethodSource("boundaries")
    void evaluatesSharedBoundaryCases(List<String> row) {
        var declaration = new ApplicabilityDeclaration(date(row.get(2)), UntilKind.valueOf(row.get(3)), date(row.get(4)));
        assertThat(ApplicabilityState.of(declaration, date(row.get(1))).name()).isEqualTo(row.get(8));
        assertThat(declaration.effectiveUntil()).isEqualTo(date(row.get(4)));
        assertThat(declaration.untilKind()).isEqualTo(UntilKind.valueOf(row.get(3)));
    }
}
