package com.zhishiku.kb.app.auth.lifecycle;

import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;

import static com.zhishiku.kb.app.auth.lifecycle.LifecycleAssignment.Restriction.CLEAR;
import static com.zhishiku.kb.app.auth.lifecycle.LifecycleAssignment.Restriction.OFFBOARDED;
import static com.zhishiku.kb.app.auth.lifecycle.LifecycleAssignment.Restriction.REVIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityAccessStateTest {
    @Test
    void manualDisableAndOffboardingRemainIndependentThroughRestoration() {
        var initial = new IdentityAccessState(true, CLEAR, 1);
        var stopped = initial.manual(false).restrict(OFFBOARDED);
        assertThat(stopped.manualEnabled()).isFalse();
        assertThat(stopped.enabled()).isFalse();
        assertThat(stopped.authEpoch()).isEqualTo(3);
        var restored = stopped.restore();
        assertThat(restored.enabled()).isFalse();
        assertThat(restored.authEpoch()).isEqualTo(4);
        assertThat(restored.manual(true).authEpoch()).isEqualTo(5);
    }

    @Test
    void restrictionCannotBeRemovedByOrdinaryEnableEvenWhenManualStateWasTrue() {
        for (var restriction : new LifecycleAssignment.Restriction[]{OFFBOARDED, REVIEW}) {
            assertThatThrownBy(() -> new IdentityAccessState(true, restriction, 2).manual(true))
                    .isInstanceOfSatisfying(IdentityException.class,
                            exception -> assertThat(exception.httpStatus()).isEqualTo(409));
        }
    }

    @Test
    void RepeatedRestrictionsDoNotAdvanceEpochAndNoOverflowIsAccepted() {
        var stopped = new IdentityAccessState(true, OFFBOARDED, 4);
        assertThat(stopped.restrict(OFFBOARDED)).isSameAs(stopped);
        assertThatThrownBy(() -> new IdentityAccessState(true, CLEAR, Long.MAX_VALUE).restrict(OFFBOARDED))
                .isInstanceOf(ArithmeticException.class);
    }
}
