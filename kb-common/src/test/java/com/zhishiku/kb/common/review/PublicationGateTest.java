package com.zhishiku.kb.common.review;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicationGateTest {
    @Test
    void matchingProfileAllowsOlderGenerationButNotFutureOrRelabelledIndex() {
        var publication = VersionPublication.create(PublicationPolicy.REVIEW_REQUIRED, 7);
        assertThatCode(() -> PublicationGate.require(publication, ReviewState.APPROVED, check(true, "p", "fp", 2L, true, 2, 1)))
                .doesNotThrowAnyException();
        for (var invalid : new PublicationCheck[]{check(true, "old", "fp", 2L, true, 2, 1),
                check(true, "p", "old", 2L, true, 2, 1), check(true, "p", "fp", 5L, true, 2, 1),
                check(true, "p", "fp", null, true, 2, 1), check(true, "p", "fp", 2L, false, 2, 1)}) {
            assertThatThrownBy(() -> PublicationGate.require(publication, ReviewState.APPROVED, invalid))
                    .isInstanceOf(ReviewFailure.class).hasMessage("INDEX_REFRESH_REQUIRED");
        }
    }

    @Test
    void maintenanceOldVersionAndMissingApprovalNeverCreatePublishedState() {
        var publication = VersionPublication.create(PublicationPolicy.REVIEW_REQUIRED, 7);
        assertThatThrownBy(() -> PublicationGate.require(publication, ReviewState.APPROVED,
                check(false, "p", "fp", 2L, true, 2, 1))).hasMessage("EMBEDDING_MAINTENANCE");
        assertThatThrownBy(() -> PublicationGate.require(publication, ReviewState.APPROVED,
                check(true, "p", "fp", 2L, true, 2, 3))).hasMessage("STATE_CONFLICT");
        assertThatThrownBy(() -> PublicationGate.require(publication, ReviewState.IN_REVIEW,
                check(true, "p", "fp", 2L, true, 2, 1))).hasMessage("STATE_CONFLICT");
    }

    private static PublicationCheck check(boolean active, String profile, String fingerprint, Long epoch,
                                           boolean intact, int version, Integer current) {
        return new PublicationCheck(active, "p", "fp", 4, profile, fingerprint, epoch, intact, "indexed", true, version, current);
    }
}
