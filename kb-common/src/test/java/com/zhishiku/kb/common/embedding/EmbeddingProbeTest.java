package com.zhishiku.kb.common.embedding;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingProbeTest {
    private final EmbeddingProfileDefinition definition = EmbeddingRegistryTest.definition("probe", "local",
            "identity-v1", "identity-v1");

    @Test
    void usesOnlySyntheticInputThenPersistsWithBootIdentityAndHonorsInterval() {
        MutableClock clock = new MutableClock();
        List<String> order = new ArrayList<>();
        var registry = registry(EmbeddingRole.QUERY, order, false);
        List<EmbeddingReadiness> saved = new ArrayList<>();
        var probe = new EmbeddingProbe(registry, clock, Duration.ofSeconds(30), Duration.ofSeconds(90));
        probe.poll(value -> {
            order.add("store");
            saved.add(value);
        });
        probe.poll(saved::add);
        assertThat(saved).hasSize(1);
        assertThat(order).containsExactly(EmbeddingProbe.SYNTHETIC_INPUT, "store");
        assertThat(saved.getFirst().usable(definition.profile(), EmbeddingRole.QUERY, clock.instant())).isTrue();
        assertThat(saved.getFirst().usable(definition.profile(), EmbeddingRole.DOCUMENT, clock.instant())).isFalse();
        clock.now = clock.now.plusSeconds(30);
        probe.poll(saved::add);
        assertThat(saved).hasSize(2);
        assertThat(saved.getFirst().instanceId()).isEqualTo(saved.get(1).instanceId());
        new EmbeddingProbe(registry, clock, Duration.ofSeconds(30), Duration.ofSeconds(90)).poll(saved::add);
        assertThat(saved.get(2).instanceId()).isNotEqualTo(saved.getFirst().instanceId());
        clock.now = clock.now.plusSeconds(60);
        assertThat(saved.getFirst().usable(definition.profile(), EmbeddingRole.QUERY, clock.instant())).isFalse();
    }

    @Test
    void failuresAndIncompatibleRecordsAreNotReady() {
        MutableClock clock = new MutableClock();
        List<EmbeddingReadiness> saved = new ArrayList<>();
        new EmbeddingProbe(registry(EmbeddingRole.DOCUMENT, new ArrayList<>(), true), clock,
                Duration.ofSeconds(30), Duration.ofSeconds(90)).poll(saved::add);
        var row = saved.getFirst();
        assertThat(row.ready()).isFalse();
        assertThat(row.usable(definition.profile(), EmbeddingRole.DOCUMENT, clock.instant())).isFalse();
        var mismatch = new EmbeddingReadiness(row.instanceId(), row.role(), row.profileId(), "other", 1,
                true, clock.instant(), clock.instant().plusSeconds(90));
        assertThat(mismatch.usable(definition.profile(), row.role(), clock.instant())).isFalse();
        var protocol = new EmbeddingReadiness(row.instanceId(), row.role(), row.profileId(), row.fingerprint(), 2,
                true, clock.instant(), clock.instant().plusSeconds(90));
        assertThat(protocol.usable(definition.profile(), row.role(), clock.instant())).isFalse();
        assertThatThrownBy(() -> new EmbeddingProbe(registry(EmbeddingRole.QUERY, new ArrayList<>(), false),
                clock, Duration.ofSeconds(90), Duration.ofSeconds(30))).isInstanceOf(IllegalArgumentException.class);
    }

    private EmbeddingClientRegistry registry(EmbeddingRole role, List<String> inputs, boolean fail) {
        return new EmbeddingClientRegistry(List.of(definition), role, ignored -> new ProfileEmbeddingClient() {
            public EmbeddingProfile profile() {
                return definition.profile();
            }

            public EmbeddingRole role() {
                return role;
            }

            public EmbeddingResult embed(List<String> texts) {
                inputs.addAll(texts);
                if (fail) throw new IllegalStateException("model unavailable");
                return new LocalProfileEmbeddingClient(definition, role).embed(texts);
            }
        });
    }

    private static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now;
        }
    }
}
