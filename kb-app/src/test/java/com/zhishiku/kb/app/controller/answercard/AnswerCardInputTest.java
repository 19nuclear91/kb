package com.zhishiku.kb.app.controller.answercard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.enums.answercard.AnswerCardOperation;
import com.zhishiku.kb.app.service.answercard.AnswerCardFailure;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnswerCardInputTest {
    private static final String CREATE = """
            {"spaceId":"9007199254740993","content":{"question":"问题","aliases":[],"answer":"<script>文本</script>",
            "applicabilityText":"","dates":{"effectiveFrom":null,"untilKind":"UNDECLARED","effectiveUntil":null},"sources":[]}}
            """;

    @Test
    void exactLargeIdentityAndLiteralMarkupSurviveDecoding() {
        var value = AnswerCardInput.intent(AnswerCardOperation.ANSWER_CARD_CREATE, null, null, body(CREATE));
        assertThat(value.spaceId()).isEqualTo(9007199254740993L);
        assertThat(value.content().answer()).isEqualTo("<script>文本</script>");
        assertThat(value.content().dates().effectiveFrom()).isNull();
    }

    @Test
    void duplicateTrailingUnknownAndInvalidUnicodeNeverBecomeCommands() {
        for (String raw : List.of(CREATE + " {}", CREATE.replace("\"spaceId\":", "\"spaceId\":\"1\",\"spaceId\":"),
                CREATE.replace("问题", "bad\\uD800"), CREATE.replace("问题", "bad\\u0000"),
                CREATE.replace("\"spaceId\":", "\"owner\":\"1\",\"spaceId\":"))) {
            assertThatThrownBy(() -> AnswerCardInput.intent(AnswerCardOperation.ANSWER_CARD_CREATE, null, null, body(raw)))
                    .isInstanceOfSatisfying(AnswerCardFailure.class, failure -> assertThat(failure.status()).isEqualTo(400));
        }
    }

    @Test
    void identitiesAreCanonicalStringsAndNumbersAreActualIntegers() throws Exception {
        var json = new ObjectMapper();
        for (String raw : List.of("1", "1.0", "1e0", "\"01\"", "\"+1\"", "\"9223372036854775808\"", "null")) {
            var node = json.readTree(raw);
            assertThatThrownBy(() -> AnswerCardInput.number(node, false)).isInstanceOf(AnswerCardFailure.class);
        }
        assertThat(AnswerCardInput.number(json.readTree("\"9223372036854775807\""), false)).isEqualTo(Long.MAX_VALUE);
        for (String raw : List.of("\"10\"", "10.0", "10e0", "21", "null")) {
            var node = json.readTree(raw);
            assertThatThrownBy(() -> AnswerCardInput.integer(node, 1, 20)).isInstanceOf(AnswerCardFailure.class);
        }
    }

    @Test
    void datesAndFieldsAreRequiredAndBodyBudgetIsStrict() {
        for (String raw : List.of(CREATE.replace("\"effectiveFrom\":null,", ""),
                CREATE.replace("\"effectiveFrom\":null", "\"effectiveFrom\":\"2026-02-30\""),
                CREATE.replace("UNDECLARED", "DATE"), CREATE.replace("\"aliases\":[]", "\"aliases\":null"))) {
            assertThatThrownBy(() -> AnswerCardInput.intent(AnswerCardOperation.ANSWER_CARD_CREATE, null, null, body(raw)))
                    .isInstanceOf(AnswerCardFailure.class);
        }
        assertThatThrownBy(() -> AnswerCardInput.body(new ByteArrayInputStream(new byte[16385]), 16384))
                .isInstanceOfSatisfying(AnswerCardFailure.class, failure -> assertThat(failure.status()).isEqualTo(413));
    }

    private static com.fasterxml.jackson.databind.JsonNode body(String value) {
        return AnswerCardInput.body(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)), 131072);
    }
}
