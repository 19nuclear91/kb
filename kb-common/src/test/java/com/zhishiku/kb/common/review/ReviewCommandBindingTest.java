package com.zhishiku.kb.common.review;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewCommandBindingTest {
    @Test
    void allDomainsBindTheirExactTargetsAndPayloadWithoutStoringReason() {
        UUID round = UUID.randomUUID();
        for (ReviewCommandDomain domain : ReviewCommandDomain.values()) {
            var command = domain == ReviewCommandDomain.POLICY
                    ? ReviewCommandBinding.policy("req", 7, 3, 9, PublicationPolicy.REVIEW_REQUIRED)
                    : ReviewCommandBinding.version(domain, "req", 7, 10, 20,
                    domain == ReviewCommandDomain.SUBMIT || domain == ReviewCommandDomain.INDEX_REFRESH ? null : round,
                    9, domain == ReviewCommandDomain.REJECT ? "私有理由" : null);
            assertThat(command.domain()).isEqualTo(domain);
            assertThat(command.actorUserId()).isEqualTo(7);
            assertThat(command.expectedVersion()).isEqualTo(9);
            assertThat(command.toString()).doesNotContain("私有理由");
        }
        assertThatThrownBy(() -> ReviewCommandBinding.version(ReviewCommandDomain.APPROVE, "req", 7, 10, 20, null, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReviewCommandBinding.version(ReviewCommandDomain.SUBMIT, "req", 7, 10, 20, round, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void canonicalReasonAndLengthDelimitedHashAvoidAmbiguity() {
        UUID round = UUID.randomUUID();
        assertThat(ReviewCommandBinding.version(ReviewCommandDomain.REJECT, "req", 7, 10, 20, round, 0, " e\u0301\r\n原因 ")
                .inputDigest()).isEqualTo(ReviewCommandBinding.version(ReviewCommandDomain.REJECT,
                "req", 7, 10, 20, round, 0, "é\n原因").inputDigest());
        assertThat(ReviewCommandBinding.digest("a", "bc")).isNotEqualTo(ReviewCommandBinding.digest("ab", "c"));
        assertThat(ReviewCommandBinding.digest("a", "b")).isNotEqualTo(ReviewCommandBinding.digest("ab"));
    }

    @Test
    void requestIdsUseUnicodeCodePointsAndRejectControlsOrMalformedUnicode() {
        assertThat(ReviewCommandBinding.requireRequestId("\uD83D\uDE00".repeat(128))).hasSize(256);
        for (String value : new String[]{"", " ", "x\n", "\uD800", "x".repeat(129)})
            assertThatThrownBy(() -> ReviewCommandBinding.requireRequestId(value)).isInstanceOf(IllegalArgumentException.class);
    }
}
