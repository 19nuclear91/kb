package com.zhishiku.kb.common.review;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ReviewModelTest {
    @Test
    void ownershipIsCheckedBeforeOpeningRound() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReviewRound.start(1, 2, 10, null, 3, 4, Instant.EPOCH));
        var otherVersion = ReviewRound.start(1, 1, 11, null, 3, 4, Instant.EPOCH);
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReviewRound.start(1, 1, 10, otherVersion, 3, 4, Instant.EPOCH));
    }

    @Test
    void onlyOneActiveRoundAndNewRoundPreservesPreviousFacts() {
        var active = ReviewRound.start(1, 1, 10, null, 3, 4, Instant.EPOCH);
        assertThatIllegalStateException().isThrownBy(() ->
                ReviewRound.start(1, 1, 10, active, 3, 4, Instant.EPOCH));
        var rejected = new ReviewRound(UUID.randomUUID(), 1, 10, 1, 3, 4, Instant.EPOCH, ReviewState.REJECTED);
        var next = ReviewRound.start(1, 1, 10, rejected, 5, 6, Instant.EPOCH.plusSeconds(1));
        assertThat(next.roundNo()).isEqualTo(2);
        assertThat(next.id()).isNotEqualTo(rejected.id());
        assertThat(next.submittedBy()).isEqualTo(5);
        assertThat(next.submittedSpaceId()).isEqualTo(6);
        assertThat(rejected.state()).isEqualTo(ReviewState.REJECTED);
        assertThat(rejected.submittedBy()).isEqualTo(3);
    }

    @Test
    void newWritesRequireExplicitPolicyAndActualUploaderNeverDefaultLegacy() {
        assertThatNullPointerException().isThrownBy(() -> VersionPublication.create(null, 3));
        assertThatIllegalArgumentException().isThrownBy(() -> VersionPublication.create(PublicationPolicy.AUTO, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new VersionPublication(PublicationPolicy.AUTO,
                PublicationProtocol.VERSIONED_REVIEW, null, PublicationEvidence.NONE, null));
        var created = VersionPublication.create(PublicationPolicy.AUTO, 3);
        assertThat(created.protocol()).isEqualTo(PublicationProtocol.VERSIONED_REVIEW);
        assertThat(created.evidence()).isEqualTo(PublicationEvidence.NONE);
    }
}
