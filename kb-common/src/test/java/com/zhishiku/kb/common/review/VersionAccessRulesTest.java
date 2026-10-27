package com.zhishiku.kb.common.review;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VersionAccessRulesTest {
    @Test
    void purposesKeepPublishedLegacyAndCandidatesDistinctUnderCurrentView() {
        var candidate = VersionPublication.create(PublicationPolicy.REVIEW_REQUIRED, 7);
        var published = candidate.publish("indexed", ReviewState.APPROVED, Instant.EPOCH);
        var oldHistory = LegacyPublicationUpgrade.map(1, 1, 2, 3L, "published");
        var observed = LegacyPublicationUpgrade.map(1, 1, 3, 3L, "published");
        for (var version : List.of(candidate, published, oldHistory, observed))
            for (var purpose : VersionAccessPurpose.values())
                for (boolean participant : List.of(false, true)) {
                    assertThat(VersionAccessRules.allows(version, purpose, false, participant)).isFalse();
                    boolean expected = version == candidate ? purpose == VersionAccessPurpose.INTERACTIVE && participant
                            : version == oldHistory ? purpose != VersionAccessPurpose.COMMENT_TARGET : true;
                    assertThat(VersionAccessRules.allows(version, purpose, true, participant)).isEqualTo(expected);
                }
    }
}
