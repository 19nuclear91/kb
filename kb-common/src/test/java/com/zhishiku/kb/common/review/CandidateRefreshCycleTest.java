package com.zhishiku.kb.common.review;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CandidateRefreshCycleTest {
    @Test
    void newCycleAdvancesBothFencesAndNeverReusesOldExecutionIdentity() {
        var old = new CandidateRefreshCycle(3, 5);
        var next = old.next();
        assertThat(next.generation()).isEqualTo(4);
        assertThat(next.claimToken()).isEqualTo(6);
        assertThat(next).isNotEqualTo(old);
        assertThat(old.generation()).isEqualTo(3);
        assertThatThrownBy(() -> new CandidateRefreshCycle(Long.MAX_VALUE, 1).next()).isInstanceOf(ArithmeticException.class);
    }
}
