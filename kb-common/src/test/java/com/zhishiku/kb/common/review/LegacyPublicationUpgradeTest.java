package com.zhishiku.kb.common.review;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class LegacyPublicationUpgradeTest {
    @Test
    void nonCurrentIndexedVersionKeepsCompatibilityWithoutFabricatedPublicationOrCommentEligibility() {
        var indexedLegacy = LegacyPublicationUpgrade.map(1, 1, 10, 11L, "published");
        assertThat(indexedLegacy.protocol()).isEqualTo(PublicationProtocol.LEGACY_COMPAT);
        assertThat(indexedLegacy.policy()).isEqualTo(PublicationPolicy.AUTO);
        assertThat(indexedLegacy.evidence()).isEqualTo(PublicationEvidence.NONE);
        assertThat(indexedLegacy.publishedAt()).isNull();
        assertThat(indexedLegacy.uploaderUserId()).isNull();
        assertThat(indexedLegacy.permitsModelSource()).isTrue();
        assertThat(indexedLegacy.permitsCommentTarget()).isFalse();
    }

    @Test
    void onlyActualPublishedCurrentPointerIsObservedWithoutInventingTimestamp() {
        var current = LegacyPublicationUpgrade.map(1, 1, 10, 10L, "published");
        assertThat(current.evidence()).isEqualTo(PublicationEvidence.OBSERVED_CURRENT);
        assertThat(current.publishedAt()).isNull();
        assertThat(current.uploaderUserId()).isNull();
        assertThat(current.permitsCommentTarget()).isTrue();
        for (String status : new String[]{"draft", "in_review", "archived", "deleted"}) {
            assertThat(LegacyPublicationUpgrade.map(1, 1, 10, 10L, status).evidence())
                    .isEqualTo(PublicationEvidence.NONE);
        }
        assertThat(LegacyPublicationUpgrade.map(1, 1, 10, null, "published").evidence())
                .isEqualTo(PublicationEvidence.NONE);
    }

    @Test
    void wrongOwnershipIsNeverObservedAndNewCandidatesDoNotInheritLegacyPrivileges() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                LegacyPublicationUpgrade.map(1, 2, 10, 10L, "published"));
        var candidate = VersionPublication.create(PublicationPolicy.REVIEW_REQUIRED, 3);
        assertThat(candidate.permitsModelSource()).isFalse();
        assertThat(candidate.permitsCommentTarget()).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> new VersionPublication(PublicationPolicy.AUTO,
                PublicationProtocol.VERSIONED_REVIEW, 3L, PublicationEvidence.OBSERVED_CURRENT, null));
    }
}
