package com.zhishiku.kb.app.notification;

import io.micrometer.core.instrument.Meter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 退避、WAITING_SOURCE 与固定低基数指标；虚拟时间，不连接数据库。 */
class NotificationRetryTest {
    private final DerivationHarness harness = new DerivationHarness();
    private final InMemoryNotificationStore store = harness.store;

    @Test
    void missingCommentResolverWaitsWithBackoffInsteadOfHotLoopingAndResumesWhenInstalled() {
        UUID reply = store.addRaw("COMMENT_REPLIED", 5L, store.now, 6L);
        var service = harness.instance();
        service.runOnce();
        var projection = store.projection(reply);
        assertThat(projection.status).isEqualTo("WAITING_SOURCE");
        assertThat(projection.errorClass).isEqualTo("SOURCE_NOT_READY");
        assertThat(projection.attempts).isEqualTo(1);
        for (int i = 0; i < 20; i++) service.runOnce();
        assertThat(store.calls.stream().filter("postpone"::equals)).hasSize(1);
        assertThat(store.calls).doesNotContain("recipients", "deliver");
        List<Long> delays = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            var due = store.projection(reply).nextAttemptAt;
            delays.add(Duration.between(store.now, due).toSeconds());
            store.now = due;
            service.runOnce();
        }
        assertThat(delays).containsExactly(30L, 60L, 120L, 240L, 480L, 900L, 900L);
        assertThat(store.delivered(reply)).isEmpty();
        store.now = store.projection(reply).nextAttemptAt;
        harness.instance(DerivationHarness.withCommentResolver()).runOnce();
        assertThat(store.delivered(reply)).extracting(InMemoryNotificationStore.Delivered::type).containsExactly("COMMENT_REPLIED");
        assertThat(store.projection(reply).status).isEqualTo("DONE");
    }

    @Test
    void unknownFutureKindsAreKeptWaitingNotTreatedAsEmptyRecipients() {
        UUID future = store.addRaw("FUTURE_KIND", null, store.now, 6L);
        harness.instance().runOnce();
        assertThat(store.projection(future).status).isEqualTo("WAITING_SOURCE");
        assertThat(store.delivered()).isEmpty();
    }

    @Test
    void derivationFailureKeepsWorkAndNeverTouchesSourceFacts() {
        UUID event = store.addRaw("REVIEW_APPROVED", 7L, store.now, 8L);
        var sourceEvents = new HashMap<>(store.events);
        var sourceRecipients = new TreeSet<>(store.recipients.get(event));
        store.fault = call -> {
            if (call.equals("recipients")) throw new IllegalStateException("private sql text");
        };
        harness.instance().runOnce();
        var projection = store.projection(event);
        assertThat(projection.status).isEqualTo("PENDING");
        assertThat(projection.errorClass).isEqualTo("DERIVATION_FAILED");
        assertThat(projection.attempts).isEqualTo(1);
        assertThat(projection.completedAt).isNull();
        assertThat(store.delivered()).isEmpty();
        assertThat(store.events).isEqualTo(sourceEvents);
        assertThat(store.recipients.get(event)).isEqualTo(sourceRecipients);
        assertThat(harness.counter("kb.notification.derivation.failures", "batch")).isEqualTo(1);
        store.fault = ignored -> { };
        store.advanceTime(Duration.ofSeconds(30));
        harness.instance().runOnce();
        assertThat(store.delivered(event)).hasSize(1);
    }

    @Test
    void postponeFailureLeavesLeaseToExpireAndAnotherInstanceRecovers() {
        UUID event = store.addRaw("REVIEW_APPROVED", 7L, store.now, 8L);
        store.fault = call -> {
            if (call.equals("recipients") || call.equals("postpone")) throw new IllegalStateException("db down");
        };
        harness.instance().runOnce();
        assertThat(harness.counter("kb.notification.derivation.failures", "postpone")).isEqualTo(1);
        assertThat(store.projection(event).owner).isNotNull();
        store.fault = ignored -> { };
        store.advanceTime(Duration.ofSeconds(61));
        harness.instance().runOnce();
        assertThat(store.delivered(event)).hasSize(1);
        assertThat(harness.counter("kb.notification.derivation.takeovers", null)).isEqualTo(1);
    }

    @Test
    void discoveryOrClaimFailureIsCountedWithoutStoppingOtherWork() {
        store.addRaw("REVIEW_APPROVED", 7L, store.now, 8L);
        store.fault = call -> {
            if (call.equals("discover")) throw new IllegalStateException("x");
        };
        assertThat(harness.instance().runOnce()).isZero();
        store.fault = call -> {
            if (call.equals("claim")) throw new IllegalStateException("x");
        };
        assertThat(harness.instance().runOnce()).isZero();
        assertThat(harness.counter("kb.notification.derivation.failures", "discover")).isEqualTo(1);
        assertThat(harness.counter("kb.notification.derivation.failures", "claim")).isEqualTo(1);
        store.fault = ignored -> { };
        assertThat(harness.instance().runOnce()).isEqualTo(1);
    }

    @Test
    void discoveryLatencyIsTimedForSuccessAndFailureWithoutTags() {
        store.addRaw("REVIEW_SUBMITTED", 1L, store.now, 2L);
        var service = harness.instance();
        store.fault = call -> {
            if (call.equals("discover")) harness.clock.add(Duration.ofMillis(40));
        };
        service.runOnce();
        store.fault = call -> {
            if (!call.equals("discover")) return;
            harness.clock.add(Duration.ofMillis(60));
            throw new IllegalStateException("db timeout");
        };
        service.runOnce();
        var timer = harness.meters.get("kb.notification.derivation.discover").timer();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(100);
        assertThat(timer.getId().getTags()).isEmpty();
        assertThat(harness.counter("kb.notification.derivation.failures", "discover")).isEqualTo(1);
    }

    @Test
    void repeatedDerivationNeverResetsReadAt() {
        UUID event = store.addRaw("REVIEW_REJECTED", 7L, store.now, 8L);
        harness.instance().runOnce();
        store.read(event, 8);
        var readAt = store.delivered(event).getFirst().readAt();
        store.loseProjection(event);
        store.advanceTime(Duration.ofHours(1));
        harness.instance().runOnce();
        harness.instance().runOnce();
        assertThat(store.delivered(event)).singleElement().satisfies(item -> assertThat(item.readAt()).isEqualTo(readAt));
    }

    @Test
    void metricsUseOnlyFixedLowCardinalityTags() {
        UUID waiting = store.addRaw("COMMENT_REPLIED", 5L, store.now.minusSeconds(120), 6L);
        store.addRaw("REVIEW_SUBMITTED", 1L, store.now, 2L);
        var service = harness.instance();
        service.runOnce();
        service.observe();
        assertThat(harness.meters.get("kb.notification.derivation.backlog").tag("state", "waiting_source").gauge().value())
                .isEqualTo(1);
        assertThat(harness.meters.get("kb.notification.derivation.backlog").tag("state", "pending").gauge().value()).isZero();
        assertThat(harness.meters.get("kb.notification.derivation.oldest_age_seconds").gauge().value()).isEqualTo(120);
        store.fault = call -> {
            if (call.equals("backlog")) throw new IllegalStateException("x");
        };
        service.observe();
        assertThat(harness.counter("kb.notification.derivation.failures", "backlog")).isEqualTo(1);
        Set<String> allowedKeys = Set.of("state", "stage");
        Set<String> allowedValues = Set.of("pending", "waiting_source", "discover", "claim", "lease", "batch", "postpone",
                "backlog");
        for (Meter meter : harness.meters.getMeters()) {
            assertThat(meter.getId().getName()).startsWith("kb.notification.");
            for (var tag : meter.getId().getTags()) {
                assertThat(tag.getKey()).isIn(allowedKeys);
                assertThat(tag.getValue()).isIn(allowedValues).doesNotContain(waiting.toString());
            }
        }
    }
}
