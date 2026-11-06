package com.zhishiku.kb.app.auth.lifecycle;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleObservationsTest {
    @Test
    void onlyProvenSameCommitRestrictionCreatesAccessLatencySample() {
        var registry = new SimpleMeterRegistry();
        var observations = new LifecycleObservations(registry);
        var now = Instant.parse("2026-02-01T00:00:00Z");
        observations.received(new LifecycleReception.Result(1, false, false, 0, 0, 1), now);
        observations.received(new LifecycleReception.Result(2, false, false, 0, 1, 0), now);
        observations.received(new LifecycleReception.Result(3, true, true, 0, 0, 0), now);
        observations.receptionFailed();
        observations.applied(new LifecycleReception.Result(1, false, false, 1, 0, 0));
        assertThat(registry.find("kb.lifecycle.access_barrier_delay").timer()).isNull();
        observations.received(new LifecycleReception.Result(4, false, false, 1, 0, 0), now);
        assertThat(registry.get("kb.lifecycle.access_barrier_delay").timer().count()).isEqualTo(1);
        observations.cleanup(LifecycleObservations.Cleanup.FAILED);
        assertThat(registry.get("kb.lifecycle.access_barrier_delay").timer().count()).isEqualTo(1);
        assertThat(registry.get("kb.identity.session_cleanup").tag("outcome", "FAILED").counter().count()).isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).allSatisfy(tag -> {
            assertThat(tag.getKey()).isIn("source", "outcome", "reason");
            if (tag.getKey().equals("reason")) {
                assertThat(DingTalkInputRejected.Reason.valueOf(tag.getValue())).isNotNull();
            } else {
                assertThat(tag.getValue()).isIn("dingtalk", "COMMITTED", "DUPLICATE", "CONFLICT", "FAILED", "REVIEW", "UNMAPPED", "OFFBOARDED");
            }
        }));
    }
}
