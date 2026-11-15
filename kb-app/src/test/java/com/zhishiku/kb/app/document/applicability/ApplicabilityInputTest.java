package com.zhishiku.kb.app.document.applicability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.document.applicability.model.UntilKind;
import com.zhishiku.kb.app.document.applicability.service.ValidityException;
import com.zhishiku.kb.app.document.applicability.support.ValidityInput;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplicabilityInputTest {
    private static final String VALID = "{\"effectiveFrom\":null,\"untilKind\":\"UNDECLARED\",\"effectiveUntil\":null,\"reason\":null,\"expectedApplicabilityVersion\":0}";

    @Test
    void requiresExactlyFiveFieldsAndRejectsTrailingJsonDuplicatesAndInvalidUnicode() throws Exception {
        var json = new ObjectMapper();
        for (String key : List.of("effectiveFrom", "untilKind", "effectiveUntil", "reason", "expectedApplicabilityVersion")) {
            var body = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(VALID);
            body.remove(key);
            invalid(body.toString());
        }
        for (String raw : List.of(VALID + " {}", VALID.replace("\"reason\":null", "\"reason\":null,\"reason\":null"),
                VALID.replace("\"reason\":null", "\"unknown\":{},\"reason\":null"), "[]", "null",
                VALID.replace("\"reason\":null", "\"reason\":\"\\u0000\""),
                VALID.replace("\"reason\":null", "\"reason\":\"\\uD800\""),
                VALID.replace("\"reason\":null", "\"reason\":\"\\uDC00\""))) {
            invalid(raw);
        }
        assertThatThrownBy(() -> ValidityInput.body(new ByteArrayInputStream(new byte[]{(byte) 0xc3, 0x28})))
                .isInstanceOf(ValidityException.class);
    }

    @Test
    void enforcesDateRangeKindAgreementOrderAndIntegerVersion() {
        for (String raw : List.of(VALID.replace("\"effectiveFrom\":null", "\"effectiveFrom\":\"1899-12-31\""),
                VALID.replace("\"effectiveFrom\":null", "\"effectiveFrom\":\"3000-01-01\""),
                VALID.replace("\"effectiveFrom\":null", "\"effectiveFrom\":\"2026-02-30\""),
                VALID.replace("\"effectiveFrom\":null", "\"effectiveFrom\":\"2026-1-01\""),
                VALID.replace("UNDECLARED", "DATE"), VALID.replace("UNDECLARED", "forever"),
                VALID.replace("\"effectiveUntil\":null", "\"effectiveUntil\":\"2026-12-31\""),
                VALID.replace(":0}", ":-1}"), VALID.replace(":0}", ":0.0}"), VALID.replace(":0}", ":\"0\"}"),
                VALID.replace(":0}", ":9223372036854775808}"))) {
            invalid(raw);
        }
        invalid("{\"effectiveFrom\":\"2027-01-01\",\"untilKind\":\"DATE\",\"effectiveUntil\":\"2026-12-31\",\"reason\":null,\"expectedApplicabilityVersion\":0}");
        assertThat(parse(VALID.replace("UNDECLARED", "OPEN_ENDED")).untilKind()).isEqualTo(UntilKind.OPEN_ENDED);
        assertThat(parse(VALID.replace("\"effectiveFrom\":null", "\"effectiveFrom\":\"1900-01-01\"")).effectiveFrom()).hasYear(1900);
        assertThat(parse(VALID.replace("\"effectiveFrom\":null", "\"effectiveFrom\":\"2999-12-31\"")).effectiveFrom()).hasYear(2999);
    }

    @Test
    void reasonIsTrimmedButNeverTruncatedAndCountsUnicodeCodePoints() {
        invalid(VALID.replace("\"reason\":null", "\"reason\":\" \u00a0\""));
        invalid(VALID.replace("\"reason\":null", "\"reason\":\"" + "a".repeat(2001) + "\""));
        String supplementary = "😀".repeat(2000);
        assertThat(parse(VALID.replace("\"reason\":null", "\"reason\":\" " + supplementary + " \"" )).reason()).isEqualTo(supplementary);
        assertThat(parse(VALID).reason()).isNull();
        assertThat(ValidityInput.requestId(" request ")).isEqualTo("request");
        assertThatThrownBy(() -> ValidityInput.requestId("a\nb")).isInstanceOf(ValidityException.class);
        assertThatThrownBy(() -> ValidityInput.requestId("a".repeat(129))).isInstanceOf(ValidityException.class);
    }

    @Test
    void bodyOverSixteenKiBHasDistinct413WithoutParsingOrEchoingText() {
        assertThatThrownBy(() -> parse("x".repeat(16385))).isInstanceOfSatisfying(ValidityException.class,
                error -> assertThat(error.reason()).isEqualTo(ValidityException.Reason.PAYLOAD_TOO_LARGE));
    }

    private static com.zhishiku.kb.app.document.applicability.model.ApplicabilityUpdate parse(String raw) {
        return ValidityInput.applicability(ValidityInput.body(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8))));
    }

    private static void invalid(String raw) {
        assertThatThrownBy(() -> parse(raw)).isInstanceOfSatisfying(ValidityException.class,
                error -> assertThat(error.reason()).isEqualTo(ValidityException.Reason.INVALID_INPUT));
    }
}
