package com.zhishiku.kb.common.review;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class VersionPublicationTest {
    @Test
    void indexedIsNotPublishedAndReviewDoesNotChangeIndexAxis() {
        var version = VersionPublication.create(PublicationPolicy.REVIEW_REQUIRED, 42L);
        assertThat(version.evidence()).isEqualTo(PublicationEvidence.NONE);
        for (ReviewState state : ReviewState.values()) {
            assertThat(version.permitsPublication("indexed", state)).isEqualTo(state == ReviewState.APPROVED);
            assertThat(version.permitsPublication("pending", state)).isFalse();
            assertThat(version.permitsPublication("parsing", state)).isFalse();
            assertThat(version.permitsPublication("failed", state)).isFalse();
        }
        assertThat(version.permitsPublication("indexed", null)).isFalse();
        assertThat(version.evidence()).isEqualTo(PublicationEvidence.NONE);
    }

    @Test
    void autoStillRequiresIndexAndExplicitPublicationFact() {
        var version = VersionPublication.create(PublicationPolicy.AUTO, 42L);
        assertThat(version.evidence().isPublished()).isFalse();
        assertThat(version.permitsPublication("indexed", null)).isTrue();
        var now = Instant.parse("2026-10-25T00:00:00Z");
        var published = version.publish("indexed", null, now);
        assertThat(published.evidence()).isEqualTo(PublicationEvidence.PUBLISHED);
        assertThat(published.publishedAt()).isEqualTo(now);
        assertThat(published.policy()).isEqualTo(version.policy());
        assertThat(published.permitsPublication("indexed", null)).isFalse();
        assertThatIllegalStateException().isThrownBy(() -> published.publish("indexed", null, now));
    }

    @Test
    void terminalRoundsCannotBeReopenedOrDecidedAgain() {
        for (ReviewState decision : ReviewState.values()) {
            if (decision == ReviewState.IN_REVIEW) {
                continue;
            }
            assertThat(ReviewState.IN_REVIEW.decide(decision)).isEqualTo(decision);
            assertThatIllegalStateException().isThrownBy(() -> decision.decide(ReviewState.APPROVED));
            assertThatIllegalStateException().isThrownBy(() -> decision.decide(ReviewState.IN_REVIEW));
        }
        assertThatIllegalStateException().isThrownBy(() -> ReviewState.IN_REVIEW.decide(ReviewState.IN_REVIEW));
    }
}
