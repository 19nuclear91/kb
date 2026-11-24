package com.zhishiku.kb.app.search.assistance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhishiku.kb.app.search.model.SearchFilters;
import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchSelectionContextTest {
    private static final String SECRET = "unit-test-only-context-secret-not-production";
    private static final Instant NOW = Instant.parse("2026-11-24T00:00:00.123456789Z");
    private final SearchContextKeys keys = new SearchContextKeys(new SearchAssistanceContextProperties("current", SECRET, null, null));
    private final SignedSearchContext envelope = new SignedSearchContext(keys);
    private final SearchSelectionContext codec = new SearchSelectionContext(keys, envelope);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void payloadOnlyContainsProtectedDigestAndVersionTimingNotIdentityQueryFiltersOrToken() throws Exception {
        var binding = binding(7, 9, "private-token", "private-query", filters(), 50, 20);
        String token = codec.issue(binding, 3, NOW);
        var verified = codec.verify(token, binding, 3, NOW);
        assertThat(verified.revision()).isEqualTo(3);
        assertThat(verified.expiresAt()).isEqualTo(NOW.plusSeconds(600));
        ObjectNode payload = envelope.open(token, SignedSearchContext.Purpose.SEARCH_SELECTION).payload();
        assertThat(payload.properties()).extracting(entry -> entry.getKey()).containsExactlyInAnyOrder("purpose", "revision", "issuedSeconds",
                "issuedNanos", "expiresSeconds", "expiresNanos", "digest");
        assertThat(json.writeValueAsString(payload)).doesNotContain("private-", "query", "token", "tagIds", "userId", "authEpoch");
        assertThat(binding.toString() + verified.toString() + envelope.open(token, SignedSearchContext.Purpose.SEARCH_SELECTION))
                .doesNotContain("private-", token);
    }

    @Test
    void sameUserNewTokenDifferentUserEpochAndExactOriginalBytesCannotReuseContext() {
        var original = binding(7, 9, "session-a", "E\u0301 OA", null, 50, 20);
        String token = codec.issue(original, 0, NOW);
        for (var changed : List.of(binding(7, 9, "session-b", "E\u0301 OA", null, 50, 20),
                binding(8, 9, "session-a", "E\u0301 OA", null, 50, 20), binding(7, 10, "session-a", "E\u0301 OA", null, 50, 20),
                binding(7, 9, "session-a", "É OA", null, 50, 20), binding(7, 9, "session-a", "E\u0301 oa", null, 50, 20),
                binding(7, 9, "session-a", " E\u0301 OA", null, 50, 20))) {
            invalid(() -> codec.verify(token, changed, 0, NOW));
        }
        assertThat(codec.verify(token, binding(7, 9, "session-a", "E\u0301 OA", SearchFilters.empty(), 50, 20), 0, NOW).revision()).isZero();
    }

    @Test
    void everyFullFilterAndFinalQuantityParticipatesInBindingIncludingLargeExactIds() {
        SearchFilters base = filters();
        var original = binding(Long.MAX_VALUE, Long.MAX_VALUE, "session", "PM", base, 50, 20);
        String token = codec.issue(original, Long.MAX_VALUE, NOW);
        List<SearchFilters> changed = List.of(new SearchFilters(3L, base.categoryId(), false, base.tagIds(), base.fileType(), base.updatedFrom(), base.updatedTo()),
                new SearchFilters(base.spaceId(), 3L, false, base.tagIds(), base.fileType(), base.updatedFrom(), base.updatedTo()),
                new SearchFilters(base.spaceId(), null, true, base.tagIds(), base.fileType(), base.updatedFrom(), base.updatedTo()),
                new SearchFilters(base.spaceId(), base.categoryId(), false, List.of(2L), base.fileType(), base.updatedFrom(), base.updatedTo()),
                new SearchFilters(base.spaceId(), base.categoryId(), false, base.tagIds(), SearchFilters.FileType.WORD, base.updatedFrom(), base.updatedTo()),
                new SearchFilters(base.spaceId(), base.categoryId(), false, base.tagIds(), base.fileType(), base.updatedFrom().plusNanos(1), base.updatedTo()),
                new SearchFilters(base.spaceId(), base.categoryId(), false, base.tagIds(), base.fileType(), base.updatedFrom(), base.updatedTo().plusNanos(1)),
                SearchFilters.empty());
        for (SearchFilters filters : changed) {
            invalid(() -> codec.verify(token, binding(Long.MAX_VALUE, Long.MAX_VALUE, "session", "PM", filters, 50, 20), Long.MAX_VALUE, NOW));
        }
        invalid(() -> codec.verify(token, binding(Long.MAX_VALUE, Long.MAX_VALUE, "session", "PM", base, 49, 20), Long.MAX_VALUE, NOW));
        invalid(() -> codec.verify(token, binding(Long.MAX_VALUE, Long.MAX_VALUE, "session", "PM", base, 50, 21), Long.MAX_VALUE, NOW));
    }

    @Test
    void tagOrderAndDifferentOffsetsForSameInstantAreEquivalentButNanosecondsAreNotLost() {
        var original = binding(7, 9, "session", "PM", filters(), 50, 20);
        String token = codec.issue(original, 3, NOW);
        var equivalent = new SearchFilters(Long.MAX_VALUE, 9007199254740993L, false, List.of(2L, Long.MAX_VALUE), SearchFilters.FileType.PDF,
                OffsetDateTime.parse("2026-11-01T08:00:00.123456789+08:00"), OffsetDateTime.parse("2026-11-30T08:00:00.123456789+08:00"));
        assertThat(codec.verify(token, binding(7, 9, "session", "PM", equivalent, 50, 20), 3, NOW).revision()).isEqualTo(3);
    }

    @Test
    void purposeSwapMalformedVersionsUnknownKeyIdsOrTamperedSignatureAreInvalidNotStale() {
        var binding = binding(7, 9, "session", "PM", null, 50, 20);
        String token = codec.issue(binding, 3, NOW);
        invalid(() -> envelope.open(token, SignedSearchContext.Purpose.TERM_DIRECTORY));
        String swapped = envelope.seal(SignedSearchContext.Purpose.TERM_DIRECTORY,
                envelope.open(token, SignedSearchContext.Purpose.SEARCH_SELECTION).payload());
        invalid(() -> codec.verify(swapped, binding, 3, NOW));
        String[] parts = token.split("\\.");
        for (String broken : List.of("2." + parts[1] + "." + parts[2] + "." + parts[3],
                "1.unknown." + parts[2] + "." + parts[3], token + ".extra", token + "=", "x".repeat(4096), "x".repeat(4097),
                parts[0] + "." + parts[1] + "." + parts[2] + "." + "A".repeat(43))) {
            invalid(() -> codec.verify(broken, binding, 999, NOW.plusSeconds(1000)));
        }
    }

    @Test
    void authenticatedDuplicateUnknownTrailingOrWrongTypedPayloadStillRejectsStrictly() throws Exception {
        var binding = binding(7, 9, "session", "PM", null, 50, 20);
        ObjectNode payload = envelope.open(codec.issue(binding, 3, NOW), SignedSearchContext.Purpose.SEARCH_SELECTION).payload();
        String raw = json.writeValueAsString(payload);
        invalid(() -> codec.verify(forge(raw.replace("\"revision\":3", "\"revision\":3,\"revision\":3")), binding, 3, NOW));
        invalid(() -> codec.verify(forge(raw + " {}"), binding, 3, NOW));
        for (ObjectNode malformed : List.of(payload.deepCopy().put("revision", "3"), payload.deepCopy().put("revision", 3.0),
                payload.deepCopy().put("query", "private-query"), payload.deepCopy().put("issuedNanos", 1000000000),
                payload.deepCopy().put("expiresSeconds", NOW.getEpochSecond() + 601), payload.deepCopy().put("digest", "not-a-valid-digest"))) {
            String token = envelope.seal(SignedSearchContext.Purpose.SEARCH_SELECTION, malformed);
            invalid(() -> codec.verify(token, binding, 3, NOW));
        }
    }

    @Test
    void checksActualDecodedUtf8BytesBeforeParsingNotJustJavaStringLength() throws Exception {
        var binding = binding(7, 9, "session", "PM", null, 50, 20);
        ObjectNode payload = envelope.open(codec.issue(binding, 3, NOW), SignedSearchContext.Purpose.SEARCH_SELECTION).payload();
        String raw = json.writeValueAsString(payload);
        String atLimit = raw + " ".repeat(SignedSearchContext.PAYLOAD_LIMIT - raw.getBytes(StandardCharsets.UTF_8).length);
        assertThat(codec.verify(forge(atLimit), binding, 3, NOW).revision()).isEqualTo(3);
        invalid(() -> codec.verify(forge(atLimit + " "), binding, 3, NOW));
        String multibyte = json.writeValueAsString(payload.deepCopy().put("padding", "é".repeat(1050)));
        assertThat(multibyte.length()).isLessThan(2048);
        assertThat(multibyte.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(2048);
        invalid(() -> codec.verify(forge(multibyte), binding, 3, NOW));
    }

    private static SearchFilters filters() {
        return new SearchFilters(Long.MAX_VALUE, 9007199254740993L, false, List.of(Long.MAX_VALUE, 2L), SearchFilters.FileType.PDF,
                OffsetDateTime.parse("2026-11-01T00:00:00.123456789Z"), OffsetDateTime.parse("2026-11-30T00:00:00.123456789Z"));
    }

    private static SearchSelectionContext.Binding binding(long user, long epoch, String session, String query,
                                                          SearchFilters filters, int candidates, int topK) {
        return new SearchSelectionContext.Binding(user, epoch, session, query, filters, candidates, topK);
    }

    private static String forge(String raw) {
        var base64 = Base64.getUrlEncoder().withoutPadding();
        String prefix = "1.current." + base64.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        byte[] signature = ContextEncoding.mac(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"),
                "search-context-envelope-v1", "SEARCH_SELECTION".getBytes(StandardCharsets.US_ASCII), prefix.getBytes(StandardCharsets.US_ASCII));
        return prefix + "." + base64.encodeToString(signature);
    }

    private static void invalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SearchContextFailure.class, failure -> {
            assertThat(failure.kind()).isEqualTo(SearchContextFailure.Kind.INVALID_INPUT);
            assertThat(failure.status()).isEqualTo(400);
        });
    }
}
