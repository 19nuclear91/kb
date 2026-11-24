package com.zhishiku.kb.app.search.assistance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TermCursorTest {
    private static final String SECRET = "unit-test-only-context-secret-not-production";
    private final SearchContextKeys keys = new SearchContextKeys(new SearchAssistanceContextProperties("current", SECRET, null, null));
    private final SignedSearchContext envelope = new SignedSearchContext(keys);
    private final TermCursor cursor = new TermCursor(keys, envelope);

    @Test
    void stableIdAndRevisionStayExactAndPayloadContainsNoQueryOrFilterText() throws Exception {
        var conditions = terms(7, "private-query", false, 20);
        String token = cursor.issue(conditions, Long.MAX_VALUE, Long.MAX_VALUE);
        var position = cursor.read(token, conditions, Long.MAX_VALUE);
        assertThat(position.lastId()).isEqualTo(Long.MAX_VALUE);
        assertThat(position.revision()).isEqualTo(Long.MAX_VALUE);
        ObjectNode payload = envelope.open(token, SignedSearchContext.Purpose.TERM_DIRECTORY).payload();
        assertThat(payload.properties()).extracting(entry -> entry.getKey()).containsExactlyInAnyOrder("purpose", "revision", "lastId", "digest");
        assertThat(new ObjectMapper().writeValueAsString(payload)).doesNotContain("private-query", "nameQuery", "enabled", "userId");
        assertThat(conditions.toString() + position.toString()).doesNotContain("private-query", token);
        assertThat(cursor.read(null, terms(7, "", null, 20), 0)).isNull();
    }

    @Test
    void directoryUserQueryStateAndPageSizeCannotBeChangedWhileReusingCursor() {
        var conditions = terms(7, "OA", null, 20);
        String token = cursor.issue(conditions, 10, 9);
        for (var changed : List.of(terms(8, "OA", null, 20), terms(7, "PM", null, 20), terms(7, "OA", false, 20),
                terms(7, "OA", true, 20), terms(7, "OA", null, 100),
                new TermCursor.Conditions(7, SignedSearchContext.Purpose.CORRECTION_DIRECTORY, null, "OA", null, 20))) {
            failure(() -> cursor.read(token, changed, 11), SearchContextFailure.Kind.INVALID_INPUT);
        }
        assertThat(cursor.read(token, terms(7, "\u00a0oa\u3000", null, 20), 10).lastId()).isEqualTo(9);
        String nfc = cursor.issue(terms(7, "É", null, 20), 10, 9);
        assertThat(cursor.read(nfc, terms(7, "E\u0301", null, 20), 10).lastId()).isEqualTo(9);
    }

    @Test
    void sharedMeaningsBindTargetAndExpressionAndNeverInterchangeWithOtherDirectories() {
        var conditions = meanings(5, "OA");
        String token = cursor.issue(conditions, 10, 9);
        failure(() -> cursor.read(token, meanings(6, "OA"), 10), SearchContextFailure.Kind.INVALID_INPUT);
        failure(() -> cursor.read(token, meanings(5, "PM"), 10), SearchContextFailure.Kind.INVALID_INPUT);
        failure(() -> cursor.read(token, terms(7, "OA", true, 20), 10), SearchContextFailure.Kind.INVALID_INPUT);
        assertThat(cursor.read(token, meanings(5, "oa"), 10).lastId()).isEqualTo(9);
    }

    @Test
    void verifiedRevisionChangeIs409AndInvalidSignatureOrShapeRemains400() {
        var conditions = terms(7, "OA", null, 20);
        String token = cursor.issue(conditions, 10, 9);
        failure(() -> cursor.read(token, conditions, 11), SearchContextFailure.Kind.DICTIONARY_CHANGED);
        String[] parts = token.split("\\.");
        String invalidMac = parts[0] + "." + parts[1] + "." + parts[2] + "." + "A".repeat(43);
        for (String malformed : List.of(invalidMac, token + ".extra", "x".repeat(4096), "x".repeat(4097))) {
            failure(() -> cursor.read(malformed, conditions, 11), SearchContextFailure.Kind.INVALID_INPUT);
        }
        var payload = envelope.open(token, SignedSearchContext.Purpose.TERM_DIRECTORY).payload();
        for (ObjectNode malformed : List.of(payload.deepCopy().put("lastId", "9"), payload.deepCopy().put("lastId", 9.0),
                payload.deepCopy().put("lastId", 0), payload.deepCopy().put("revision", -1), payload.deepCopy().put("query", "private-query"))) {
            String signed = envelope.seal(SignedSearchContext.Purpose.TERM_DIRECTORY, malformed);
            failure(() -> cursor.read(signed, conditions, 11), SearchContextFailure.Kind.INVALID_INPUT);
        }
    }

    @Test
    void selectionContextAndDirectoryCursorAreMutuallyPurposeIsolatedAndMissingKeysFailFirstPage() {
        var conditions = terms(7, "OA", null, 20);
        var selection = new SearchSelectionContext(keys, envelope);
        var binding = new SearchSelectionContext.Binding(7, 9, "verified-session", "OA", null, 50, 20);
        Instant now = Instant.parse("2026-11-24T00:00:00Z");
        String selectionToken = selection.issue(binding, 10, now);
        failure(() -> cursor.read(selectionToken, conditions, 10), SearchContextFailure.Kind.INVALID_INPUT);
        String directoryToken = cursor.issue(conditions, 10, 9);
        failure(() -> selection.verify(directoryToken, binding, 10, now), SearchContextFailure.Kind.INVALID_INPUT);
        var missing = new SearchContextKeys(new SearchAssistanceContextProperties(null, null, null, null));
        failure(() -> new TermCursor(missing, new SignedSearchContext(missing)).read(null, conditions, 0),
                SearchContextFailure.Kind.ASSISTANCE_UNAVAILABLE);
    }

    @Test
    void previousKeysVerifyOldCursorDigestUsingTheKeyThatSignedIt() {
        var oldKeys = new SearchContextKeys(new SearchAssistanceContextProperties("old", SECRET, null, null));
        var original = new TermCursor(oldKeys, new SignedSearchContext(oldKeys));
        var conditions = terms(7, "OA", null, 20);
        String token = original.issue(conditions, 10, Long.MAX_VALUE);
        var nextKeys = new SearchContextKeys(new SearchAssistanceContextProperties("next", SECRET + "-next", "old", SECRET));
        var next = new TermCursor(nextKeys, new SignedSearchContext(nextKeys));
        assertThat(next.read(token, conditions, 10).keyId()).isEqualTo("old");
        assertThat(next.read(token, conditions, 10).lastId()).isEqualTo(Long.MAX_VALUE);
    }

    private static TermCursor.Conditions terms(long user, String name, Boolean enabled, int limit) {
        return new TermCursor.Conditions(user, SignedSearchContext.Purpose.TERM_DIRECTORY, null, name, enabled, limit);
    }

    private static TermCursor.Conditions meanings(long target, String expression) {
        return new TermCursor.Conditions(7, SignedSearchContext.Purpose.TERM_MEANINGS, target, expression, true, 20);
    }

    private static void failure(Runnable action, SearchContextFailure.Kind kind) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SearchContextFailure.class,
                error -> assertThat(error.kind()).isEqualTo(kind));
    }
}
