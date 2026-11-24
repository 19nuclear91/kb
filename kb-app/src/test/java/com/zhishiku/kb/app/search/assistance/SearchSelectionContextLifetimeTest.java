package com.zhishiku.kb.app.search.assistance;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchSelectionContextLifetimeTest {
    private static final String OLD = "unit-test-only-old-context-secret-not-production";
    private static final String NEW = "unit-test-only-new-context-secret-not-production";
    private static final Instant NOW = Instant.parse("2026-11-24T00:00:00.123456789Z");
    private final SearchSelectionContext.Binding binding = new SearchSelectionContext.Binding(7, 9, "verified-session", "PM", null, 50, 20);

    @Test
    void databaseTimeStubDefinesInclusiveExpiryAtExactlyTenMinutes() {
        var codec = codec("old", OLD, null, null);
        var current = new AtomicReference<>(NOW);
        Supplier<Instant> databaseTime = current::get;
        String token = codec.issue(binding, 10, databaseTime.get());
        current.set(NOW.plusSeconds(600).minusNanos(1));
        assertThat(codec.verify(token, binding, 10, databaseTime.get()).expiresAt()).isEqualTo(NOW.plusSeconds(600));
        current.set(NOW.plusSeconds(600));
        failure(() -> codec.verify(token, binding, 10, databaseTime.get()), SearchContextFailure.Kind.ASSISTANCE_STALE);
        current.set(NOW.plusSeconds(600).plusNanos(1));
        failure(() -> codec.verify(token, binding, 10, databaseTime.get()), SearchContextFailure.Kind.ASSISTANCE_STALE);
    }

    @Test
    void anyRevisionAdvanceIncludingRestoredContentInvalidatesOldContext() {
        var codec = codec("old", OLD, null, null);
        String token = codec.issue(binding, 10, NOW);
        assertThat(codec.verify(token, binding, 10, NOW).revision()).isEqualTo(10);
        failure(() -> codec.verify(token, binding, 11, NOW), SearchContextFailure.Kind.ASSISTANCE_STALE);
        failure(() -> codec.verify(token, binding, 12, NOW), SearchContextFailure.Kind.ASSISTANCE_STALE);
    }

    @Test
    void invalidIdentityConditionsAndMacAre400BeforeTrustedStalenessIsConsidered() {
        var codec = codec("old", OLD, null, null);
        String token = codec.issue(binding, 10, NOW);
        var otherUser = new SearchSelectionContext.Binding(8, 9, "verified-session", "PM", null, 50, 20);
        var otherCondition = new SearchSelectionContext.Binding(7, 9, "verified-session", "PM", null, 50, 21);
        failure(() -> codec.verify(token, otherUser, 11, NOW.plusSeconds(600)), SearchContextFailure.Kind.INVALID_INPUT);
        failure(() -> codec.verify(token, otherCondition, 11, NOW.plusSeconds(600)), SearchContextFailure.Kind.INVALID_INPUT);
        String[] parts = token.split("\\.");
        String forged = parts[0] + "." + parts[1] + "." + parts[2] + "." + "A".repeat(43);
        failure(() -> codec.verify(forged, binding, 11, NOW.plusSeconds(600)), SearchContextFailure.Kind.INVALID_INPUT);
    }

    @Test
    void twoIndependentCodecObjectsWithSameKeysInteroperateWithoutApplicationInstances() {
        var issuing = codec("old", OLD, null, null);
        var verifying = codec("old", OLD, null, null);
        Instant databaseTime = Clock.fixed(NOW, ZoneOffset.UTC).instant();
        String token = issuing.issue(binding, Long.MAX_VALUE, databaseTime);
        assertThat(verifying.verify(token, binding, Long.MAX_VALUE, databaseTime).revision()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void oldKeyIdRecomputesOldSessionFingerprintAndSupportsStagedRotation() {
        var oldSigning = codec("old", OLD, "new", NEW);
        String oldToken = oldSigning.issue(binding, 10, NOW);
        var newSigning = codec("new", NEW, "old", OLD);
        assertThat(newSigning.verify(oldToken, binding, 10, NOW).keyId()).isEqualTo("old");
        String newToken = newSigning.issue(binding, 10, NOW);
        assertThat(oldSigning.verify(newToken, binding, 10, NOW).keyId()).isEqualTo("new");
        var removedOld = codec("new", NEW, null, null);
        failure(() -> removedOld.verify(oldToken, binding, 10, NOW), SearchContextFailure.Kind.INVALID_INPUT);
        failure(() -> newSigning.verify(oldToken, binding, 10, NOW.plusSeconds(600)), SearchContextFailure.Kind.ASSISTANCE_STALE);
    }

    @Test
    void missingKeysBadDatabaseFactsAndTimeOverflowCannotCreateUnsignedOrWrappedContexts() {
        var missing = codec(null, null, null, null);
        failure(() -> missing.issue(binding, 10, NOW), SearchContextFailure.Kind.ASSISTANCE_UNAVAILABLE);
        var live = codec("old", OLD, null, null);
        String token = live.issue(binding, 10, NOW);
        failure(() -> missing.verify(token, binding, 10, NOW), SearchContextFailure.Kind.ASSISTANCE_UNAVAILABLE);
        failure(() -> live.verify(token, binding, -1, NOW), SearchContextFailure.Kind.ASSISTANCE_UNAVAILABLE);
        failure(() -> live.verify(token, binding, 10, null), SearchContextFailure.Kind.ASSISTANCE_UNAVAILABLE);
        failure(() -> live.issue(binding, 10, Instant.MAX), SearchContextFailure.Kind.ASSISTANCE_UNAVAILABLE);
    }

    private static SearchSelectionContext codec(String currentId, String current, String previousId, String previous) {
        var keys = new SearchContextKeys(new SearchAssistanceContextProperties(currentId, current, previousId, previous));
        return new SearchSelectionContext(keys, new SignedSearchContext(keys));
    }

    private static void failure(Runnable action, SearchContextFailure.Kind kind) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SearchContextFailure.class, error -> {
            assertThat(error.kind()).isEqualTo(kind);
            assertThat(error.status()).isEqualTo(kind.status());
        });
    }
}
