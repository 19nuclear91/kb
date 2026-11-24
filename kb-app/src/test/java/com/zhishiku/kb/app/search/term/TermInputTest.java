package com.zhishiku.kb.app.search.term;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TermInputTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void normalizesUnicodeWhitespaceNfcAndAsciiOnlyWithoutChangingDisplay() {
        ObjectNode body = term("\u00a0\u2003OA\u3000", "\u00a0E\u0301  说明\u3000");
        aliases(body).add(alias("\u2003旧  OA\u2003", "FORMER_NAME"));
        var value = TermInput.term(body, true);
        assertThat(value.canonicalName()).isEqualTo("OA");
        assertThat(value.canonicalKey()).isEqualTo("oa");
        assertThat(value.description()).isEqualTo("É  说明");
        assertThat(value.aliases()).containsExactly(new TermInputs.Alias("旧  OA", "旧  oa", TermRows.AliasKind.FORMER_NAME));
        assertThat(TermInput.key(TermInput.expression("ÉİＡＡ繁體"))).isEqualTo("ÉİＡＡ繁體");
        assertThat(TermInput.expression("E\u0301")).isEqualTo("É");
    }

    @Test
    void countsSupplementaryCodePointsAtExactExpressionAndDescriptionLimits() {
        ObjectNode body = term("😀".repeat(64), "😀".repeat(256));
        assertThat(TermInput.term(body, true).canonicalName()).isEqualTo("😀".repeat(64));
        body.put("canonicalName", "😀".repeat(65));
        invalidTerm(body);
        body.put("canonicalName", "合法");
        body.put("description", "😀".repeat(257));
        invalidTerm(body);
        body.put("description", "合法");
        aliases(body).add(alias("😀".repeat(64), "SYNONYM"));
        assertThat(TermInput.term(body, true).aliases()).hasSize(1);
        aliases(body).removeAll().add(alias("😀".repeat(65), "SYNONYM"));
        invalidTerm(body);
        assertThat(TermInput.correction(correction("😀".repeat(64)), true).wrongExpression()).isEqualTo("😀".repeat(64));
        assertThatThrownBy(() -> TermInput.correction(correction("😀".repeat(65)), true)).isInstanceOf(TermException.class);
    }

    @Test
    void boundsAndSortsAliasSetAndRejectsNormalizedDuplicatesIncludingCanonicalName() {
        ObjectNode body = term("名称", "释义");
        for (int i = 0; i < 20; i++) {
            aliases(body).add(alias("别名" + i, "ABBREVIATION"));
        }
        var value = TermInput.term(body, true);
        assertThat(value.aliases()).hasSize(20);
        assertThatThrownBy(() -> value.aliases().clear()).isInstanceOf(UnsupportedOperationException.class);
        aliases(body).add(alias("第21项", "SYNONYM"));
        invalidTerm(body);
        for (List<String> names : List.of(List.of("OA", " oa "), List.of("É", "E\u0301"), List.of("名称"))) {
            aliases(body).removeAll();
            for (String name : names) {
                aliases(body).add(alias(name, "SYNONYM"));
            }
            invalidTerm(body);
        }
        aliases(body).removeAll().add(alias("Z", "FORMER_NAME")).add(alias("a", "ABBREVIATION"));
        assertThat(TermInput.term(body, true).aliases()).extracting(TermInputs.Alias::text).containsExactly("a", "Z");
    }

    @Test
    void rejectsControlBrokenSurrogatesEmptyTextAndUnknownKindsWithoutEcho() {
        for (String text : List.of("\uD800", "\uDC00", "a\uD800b", "a\u0000b", "a\u0085b", "a\nb", "a\tb", "", "\u00a0\u3000")) {
            invalidTerm(term(text, "释义"));
            invalidTerm(term("名称", text));
            assertThatThrownBy(() -> TermInput.correction(correction(text), true)).isInstanceOfSatisfying(
                    TermException.class, error -> assertThat(error.reason()).isEqualTo(TermException.Reason.INVALID_INPUT));
        }
        ObjectNode body = term("名称", "释义");
        for (String kind : List.of("TYPO", "synonym", "", "SYNONYM ")) {
            aliases(body).removeAll().add(alias("别名", kind));
            invalidTerm(body);
        }
        aliases(body).removeAll().add(alias("别名", "SYNONYM").putNull("kind"));
        invalidTerm(body);
    }

    @Test
    void rejectsUnknownMissingAndWrongTypedFieldsInEveryWriteShape() {
        for (String field : List.of("enabled", "termId", "canonicalKey", "userId")) {
            invalidTerm(term("名称", "释义").put(field, 7));
        }
        for (String field : List.of("canonicalName", "description", "aliases")) {
            ObjectNode body = term("名称", "释义");
            body.remove(field);
            invalidTerm(body);
            invalidTerm(term("名称", "释义").putNull(field));
        }
        invalidTerm(term("名称", "释义").put("aliases", "[]"));
        ObjectNode body = term("名称", "释义");
        aliases(body).add(alias("别名", "SYNONYM").put("lookupKey", "别名"));
        invalidTerm(body);
        aliases(body).removeAll().addNull();
        invalidTerm(body);
        assertThatThrownBy(() -> TermInput.correction(correction("报消").put("targetTermId", 1), true))
                .isInstanceOf(TermException.class);
        assertThatThrownBy(() -> TermInput.enabled(body("{\"enabled\":\"true\",\"expectedVersion\":0}")))
                .isInstanceOf(TermException.class);
        assertThatThrownBy(() -> TermInput.enabled(body("{\"enabled\":true,\"expectedVersion\":0,\"termId\":1}")))
                .isInstanceOf(TermException.class);
    }

    @Test
    void keepsIdsAndVersionsExactAndRequiresNonnegativeUpdateVersion() {
        var update = TermInput.term(term("名称", "释义").put("expectedVersion", Long.MAX_VALUE), false);
        assertThat(update.expectedVersion()).isEqualTo(Long.MAX_VALUE);
        assertThat(TermInput.correction(correction("报消"), true).termId()).isEqualTo(Long.MAX_VALUE);
        for (String raw : List.of("-1", "1.0", "\"1\"", "9223372036854775808", "null")) {
            ObjectNode body = term("名称", "释义");
            body.set("expectedVersion", body("{\"value\":" + raw + "}").get("value"));
            assertThatThrownBy(() -> TermInput.term(body, false)).isInstanceOf(TermException.class);
            assertThatThrownBy(() -> TermInput.correction(body("{\"wrongExpression\":\"报消\",\"termId\":" + raw + "}"), true))
                    .isInstanceOf(TermException.class);
        }
        assertThatThrownBy(() -> TermInput.term(term("名称", "释义"), false)).isInstanceOf(TermException.class);
        assertThatThrownBy(() -> TermInput.correction(correction("报消").put("termId", 0), true)).isInstanceOf(TermException.class);
        assertThat(TermInput.enabled(body("{\"enabled\":false,\"expectedVersion\":0}")))
                .isEqualTo(new TermInputs.EnabledWrite(false, 0));
    }

    @Test
    void strictlyDecodesBoundedBodyAndRejectsDuplicateFieldsOrTrailingJson() {
        for (String raw : List.of("{\"canonicalName\":1,\"canonicalName\":2}", "{\"aliases\":[{\"kind\":1,\"kind\":2}]}",
                "{} {}", "[]", "null", "{", "")) {
            assertThatThrownBy(() -> body(raw)).isInstanceOf(TermException.class);
        }
        assertThat(body("{}" + " ".repeat(TermInput.BODY_LIMIT - 2))).isEmpty();
        assertThatThrownBy(() -> body("{}" + " ".repeat(TermInput.BODY_LIMIT - 1)))
                .isInstanceOfSatisfying(TermException.class,
                        error -> assertThat(error.reason()).isEqualTo(TermException.Reason.PAYLOAD_TOO_LARGE));
    }

    private ObjectNode term(String name, String description) {
        ObjectNode body = json.createObjectNode().put("canonicalName", name).put("description", description);
        body.putArray("aliases");
        return body;
    }

    private ArrayNode aliases(ObjectNode body) {
        return (ArrayNode) body.get("aliases");
    }

    private ObjectNode alias(String text, String kind) {
        return json.createObjectNode().put("text", text).put("kind", kind);
    }

    private ObjectNode correction(String text) {
        return json.createObjectNode().put("wrongExpression", text).put("termId", Long.MAX_VALUE);
    }

    private void invalidTerm(ObjectNode body) {
        assertThatThrownBy(() -> TermInput.term(body, true)).isInstanceOfSatisfying(TermException.class,
                error -> assertThat(error.reason()).isEqualTo(TermException.Reason.INVALID_INPUT));
    }

    private static ObjectNode body(String raw) {
        return TermInput.body(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }
}
