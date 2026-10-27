package com.zhishiku.kb.app.embedding;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zhishiku.kb.app.search.embedding.EmbeddingInstanceReadiness;
import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingConnection;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import com.zhishiku.kb.common.embedding.EmbeddingProfileDefinition;
import com.zhishiku.kb.common.embedding.EmbeddingReadiness;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.common.embedding.LocalProfileEmbeddingClient;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingInstanceReadinessTest {
    private final EmbeddingProfile p = new EmbeddingProfile("p", "local", "source", "local-hash-v1", "1", 1024,
            "cosine", "identity-v1", "identity-v1", "query", "document", "local-hash-v1");
    private final MutableClock clock = new MutableClock();
    private final EmbeddingClientRegistry clients =
            new EmbeddingClientRegistry(List.of(new EmbeddingProfileDefinition(p,
            new EmbeddingConnection(null, null, 30, 16))), EmbeddingRole.QUERY,
                    d -> new LocalProfileEmbeddingClient(d, EmbeddingRole.QUERY));
    private final EmbeddingInstanceReadiness instance = new EmbeddingInstanceReadiness(clients, clock);

    private EmbeddingControl active(long epoch) {
        return new EmbeddingControl("ACTIVE", "p", p.fingerprint(), null, null, epoch, null);
    }

    private EmbeddingReadiness probe(boolean ready, Instant expires) {
        return new EmbeddingReadiness(instance.instanceId(), EmbeddingRole.QUERY, "p", p.fingerprint(), 1,
                ready, clock.instant(), expires);
    }

    @Test
    void missingProfileIsReportedEvenWhenOldProfileHasFreshSuccessfulProbe() {
        instance.record(probe(true, clock.instant().plusSeconds(90)));
        assertThat(instance.observe(active(1)).ready()).isTrue();
        var result = instance.observe(new EmbeddingControl("ACTIVE", "new", "new-fingerprint", null, null, 2, null));
        assertThat(result.ready()).isFalse();
        assertThat(result.reason()).isEqualTo("MISSING_PROFILE");
        assertThat(result.profileId()).isEqualTo("new");
        assertThat(result.fingerprint()).isEqualTo("new-fingerprint");
        assertThat(result.instanceId()).isEqualTo(instance.instanceId());
        assertThat(instance.observe(active(1))).isEqualTo(result);
        assertThat(instance.observe(active(3)).ready()).isTrue();
    }

    @Test
    void freshMatchingLocalProbeIsRequiredAndExpiryFailureAndMaintenanceClearReady() {
        assertThat(instance.observe(active(1)).reason()).isEqualTo("PROBE_UNAVAILABLE");
        instance.record(probe(true, clock.instant().plusSeconds(90)));
        assertThat(instance.snapshot().ready()).isTrue();
        clock.now = clock.now.plusSeconds(90);
        assertThat(instance.snapshot().reason()).isEqualTo("PROBE_UNAVAILABLE");
        instance.record(probe(false, clock.instant().plusSeconds(90)));
        assertThat(instance.snapshot().ready()).isFalse();
        instance.record(probe(true, clock.instant().plusSeconds(90)));
        assertThat(instance.snapshot().ready()).isTrue();
        var maintenance = new EmbeddingControl("QUIESCING", "p", p.fingerprint(), "new", "fp", 2, UUID.randomUUID());
        assertThat(instance.observe(maintenance).reason()).isEqualTo("EMBEDDING_MAINTENANCE");
        assertThat(instance.observe(active(3)).ready()).isTrue();
        assertThat(instance.observe(new EmbeddingControl("ACTIVE", "p", "other-fingerprint", null, null, 4, null)).reason())
                .isEqualTo("MISSING_PROFILE");
    }

    @Test
    void unavailableControlNeverReportsCachedReadyAndNewBootDoesNotReuseOldProbes() {
        assertThat(instance.snapshot().reason()).isEqualTo("CONTROL_UNAVAILABLE");
        instance.record(probe(true, clock.instant().plusSeconds(90)));
        instance.observe(active(2));
        instance.controlUnavailable();
        assertThat(instance.snapshot().ready()).isFalse();
        assertThat(instance.observe(active(1)).reason()).isEqualTo("CONTROL_UNAVAILABLE");
        assertThat(instance.observe(active(2)).ready()).isTrue();
        var restarted = new EmbeddingInstanceReadiness(clients, clock);
        assertThat(restarted.instanceId()).isNotEqualTo(instance.instanceId());
        assertThat(restarted.observe(active(2)).reason()).isEqualTo("PROBE_UNAVAILABLE");
        assertThatThrownBy(() -> restarted.record(probe(true, clock.instant().plusSeconds(90))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wrongFingerprintProtocolRoleAndFutureProbeCannotReportReady() {
        instance.observe(active(1));
        for (var row : List.of(
                new EmbeddingReadiness(instance.instanceId(), EmbeddingRole.QUERY, "p", "wrong", 1, true,
                        clock.instant(), clock.instant().plusSeconds(90)),
                new EmbeddingReadiness(instance.instanceId(), EmbeddingRole.QUERY, "p", p.fingerprint(), 2, true,
                        clock.instant(), clock.instant().plusSeconds(90)),
                new EmbeddingReadiness(instance.instanceId(), EmbeddingRole.QUERY, "p", p.fingerprint(), 1, true,
                        clock.instant().plusSeconds(1), clock.instant().plusSeconds(90)))) {
            instance.record(row);
            assertThat(instance.snapshot().ready()).isFalse();
        }
        assertThatThrownBy(() -> instance.record(new EmbeddingReadiness(instance.instanceId(), EmbeddingRole.DOCUMENT,
                "p", p.fingerprint(), 1, true, clock.instant(), clock.instant().plusSeconds(90))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void logsOnlyFixedReasonsOncePerTransitionWithoutProfileOrQueryData() {
        var logger = (Logger) LoggerFactory.getLogger(EmbeddingInstanceReadiness.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            instance.observe(new EmbeddingControl("ACTIVE", "private-profile", "private-fingerprint", null, null, 2,
                    null));
            instance.snapshot();
            instance.observe(new EmbeddingControl("ACTIVE", "another-private-profile", "secret", null, null, 3, null));
            instance.controlUnavailable();
            instance.controlUnavailable();
            assertThat(appender.list).hasSize(2);
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("private", "secret",
                        instance.instanceId().toString());
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
        }
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
