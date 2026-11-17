package com.zhishiku.kb.answereval;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrictJsonTest {
    @Test
    void rejectsDuplicateUnknownInvalidUnicodeAndTrailingValues() {
        for (String json : List.of(
                "{\"caseId\":\"a\",\"caseId\":\"b\"}",
                "{\"caseId\":\"a\",\"system\":\"secret\"}",
                "{\"caseId\":\"a\\u0000\"}",
                "{\"caseId\":\"a\\ud800\"}",
                "{}{}")) {
            assertThatThrownBy(() -> StrictJson.read(json.getBytes(StandardCharsets.UTF_8), DataModel.Case.class))
                    .isInstanceOf(EvaluationFailure.class).hasMessageNotContaining("secret");
        }
        assertThatThrownBy(() -> StrictJson.parse(new byte[]{'{', '"', 'x', '"', ':', '"', (byte) 0xc3, '"', '}'}))
                .isInstanceOf(EvaluationFailure.class);
    }

    @Test
    void preservesExactPositiveLongAndCanonicalUuid() {
        DataModel.Binding binding = DataFixtures.binding();
        assertThat(StrictJson.read(StrictJson.bytes(binding), DataModel.Binding.class)).isEqualTo(binding);
        assertThat(binding.userId()).isEqualTo(Long.MAX_VALUE);
        String valid = new String(StrictJson.bytes(binding), StandardCharsets.UTF_8);
        for (String malformed : List.of(
                valid.replace(Long.toString(Long.MAX_VALUE), "9223372036854775808"),
                valid.replace(Long.toString(Long.MAX_VALUE), "1.5"),
                valid.replace(Long.toString(Long.MAX_VALUE), "\"1\""),
                valid.replace(DataFixtures.UUID, "1-1-1-1-1"))) {
            assertThatThrownBy(() -> StrictJson.read(malformed.getBytes(StandardCharsets.UTF_8), DataModel.Binding.class))
                    .isInstanceOf(EvaluationFailure.class);
        }
    }

    @Test
    void validatesExpectedAnswerAndAbstentionLabels() {
        assertThat(StrictJson.read(StrictJson.bytes(DataFixtures.label()), DataModel.Label.class))
                .isEqualTo(DataFixtures.label());
        assertThatThrownBy(() -> new DataModel.Label("c", DataModel.Expected.ANSWER,
                List.of(), List.of(), null, List.of(), null)).isInstanceOf(EvaluationFailure.class);
        assertThatThrownBy(() -> new DataModel.Label("c", DataModel.Expected.ABSTAIN,
                DataFixtures.label().facts(), List.of(), "缺少依据", List.of(), null)).isInstanceOf(EvaluationFailure.class);
        assertThatThrownBy(() -> new DataModel.Label("c", DataModel.Expected.ABSTAIN,
                List.of(), List.of(), null, List.of(), null)).isInstanceOf(EvaluationFailure.class);
    }

    @Test
    void everyRunContractRejectsUnknownFieldsAndPreservesIdentities() {
        String json = new String(StrictJson.bytes(DataFixtures.binding()), StandardCharsets.UTF_8);
        assertThatThrownBy(() -> StrictJson.read(json.replace("{", "{\"extra\":true,")
                .getBytes(StandardCharsets.UTF_8), DataModel.Binding.class)).isInstanceOf(EvaluationFailure.class);
        assertThatThrownBy(() -> StrictJson.read("{\"schemaVersion\":2}".getBytes(StandardCharsets.UTF_8),
                DataModel.Collection.class)).isInstanceOf(EvaluationFailure.class);
        assertThatThrownBy(() -> StrictJson.read("{\"schemaVersion\":1,\"extra\":true}".getBytes(StandardCharsets.UTF_8),
                DataModel.InputSnapshot.class)).isInstanceOf(EvaluationFailure.class);
    }
}
