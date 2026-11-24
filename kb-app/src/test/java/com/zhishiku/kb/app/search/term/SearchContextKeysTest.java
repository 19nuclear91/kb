package com.zhishiku.kb.app.search.term;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.search.assistance.SearchAssistanceContextProperties;
import com.zhishiku.kb.app.search.assistance.SearchContextFailure;
import com.zhishiku.kb.app.search.assistance.SearchContextKeys;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchContextKeysTest {
    private static final String SECRET = "unit-test-only-secret-not-for-production";
    private static final String OLD = "unit-test-only-previous-secret-not-for-production";

    @Test
    void missingConfigurationDoesNotFailConstructionAndOnlyExplicitCredentialUseFails503() {
        assertThatCode(() -> new SearchContextKeys(null)).doesNotThrowAnyException();
        var keys = new SearchContextKeys(new SearchAssistanceContextProperties(null, null, null, null));
        assertThat(keys.available()).isFalse();
        assertThat(keys.state()).isEqualTo(SearchContextKeys.State.MISSING_CURRENT);
        assertThatThrownBy(keys::currentKeyId).isInstanceOfSatisfying(SearchContextFailure.class, failure -> {
            assertThat(failure.kind()).isEqualTo(SearchContextFailure.Kind.ASSISTANCE_UNAVAILABLE);
            assertThat(failure.status()).isEqualTo(503);
        });
    }

    @Test
    void minimumSecretIsMeasuredInUtf8BytesAndNeverSilentlyTrimmedOrReplaced() {
        assertThat(new SearchContextKeys(properties("a".repeat(31))).available()).isFalse();
        assertThat(new SearchContextKeys(properties("a".repeat(32))).available()).isTrue();
        assertThat(new SearchContextKeys(properties("😀".repeat(8))).available()).isTrue();
        assertThat(new SearchContextKeys(properties("😀".repeat(7))).available()).isFalse();
        assertThat(new SearchContextKeys(properties("a".repeat(32) + "\uD800")).available()).isFalse();
        assertThat(new SearchContextKeys(properties(" ".repeat(32))).available()).isFalse();
        var keys = new SearchContextKeys(properties(" " + SECRET + " "));
        assertThat(keys.verificationKey("current").getEncoded()).isEqualTo((" " + SECRET + " ").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void invalidAndDuplicateKeyIdsAreSafeUnavailableStates() {
        for (String id : List.of("a.b", "a b", "中文", "a".repeat(33), "a\n", "a/")) {
            var keys = new SearchContextKeys(new SearchAssistanceContextProperties(id, SECRET, null, null));
            assertThat(keys.state()).isEqualTo(SearchContextKeys.State.INVALID_KEY_ID);
        }
        var duplicate = new SearchContextKeys(new SearchAssistanceContextProperties("current", SECRET, "current", OLD));
        assertThat(duplicate.state()).isEqualTo(SearchContextKeys.State.DUPLICATE_KEY_ID);
        for (SearchAssistanceContextProperties invalid : List.of(new SearchAssistanceContextProperties("current", SECRET, "old", null),
                new SearchAssistanceContextProperties("current", SECRET, null, OLD), new SearchAssistanceContextProperties("current", SECRET, "old", "short"))) {
            assertThat(new SearchContextKeys(invalid).state()).isEqualTo(SearchContextKeys.State.INVALID_PREVIOUS);
        }
    }

    @Test
    void rotationUsesConfiguredTrustedKeysAndRejectsUnknownIdsWithoutRandomFallback() {
        var keys = new SearchContextKeys(new SearchAssistanceContextProperties("current", SECRET, "old", OLD));
        assertThat(keys.available()).isTrue();
        assertThat(keys.currentKeyId()).isEqualTo("current");
        assertThat(keys.verificationKey("current").getEncoded()).isNotEqualTo(keys.verificationKey("old").getEncoded());
        assertThatThrownBy(() -> keys.verificationKey("future")).isInstanceOfSatisfying(SearchContextFailure.class,
                failure -> assertThat(failure.kind()).isEqualTo(SearchContextFailure.Kind.INVALID_INPUT));
        byte[] changed = keys.verificationKey("current").getEncoded();
        changed[0] = 0;
        assertThat(keys.verificationKey("current").getEncoded()[0]).isNotZero();
    }

    @Test
    void binderUsesIndependentPrefixAndAutomaticProjectionNeverContainsSecretValues() throws Exception {
        var source = new MapConfigurationPropertySource(Map.of("kb.search.assistance.context.key-id", "current",
                "kb.search.assistance.context.secret", SECRET, "kb.search.assistance.context.previous-key-id", "old",
                "kb.search.assistance.context.previous-secret", OLD));
        var properties = new Binder(source).bind("kb.search.assistance.context", SearchAssistanceContextProperties.class).get();
        var keys = new SearchContextKeys(properties);
        assertThat(keys.available()).isTrue();
        assertThat(properties.toString() + keys.toString()).doesNotContain(SECRET, OLD);
        assertThat(new ObjectMapper().writeValueAsString(properties)).doesNotContain(SECRET, OLD);
    }

    private static SearchAssistanceContextProperties properties(String secret) {
        return new SearchAssistanceContextProperties("current", secret, null, null);
    }
}
