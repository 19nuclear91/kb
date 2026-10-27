package com.zhishiku.kb.app.notification;

import com.zhishiku.kb.app.notification.model.ClaimedEvent;
import com.zhishiku.kb.app.notification.model.NotificationType;
import com.zhishiku.kb.app.notification.service.NotificationDerivationService;
import com.zhishiku.kb.app.notification.service.NotificationLeaseLost;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 短租约、claim token 接管、有界分批与同事务提交的分支测试。
 * 使用虚拟时间、内存替身与真实事务拦截器；不证明真实提交原子性或多实例互斥。
 */
class NotificationLeaseTest {
    private final DerivationHarness harness = new DerivationHarness();
    private final InMemoryNotificationStore store = harness.store;

    @Test
    void failureInsideABatchRollsBackBothCursorAndNotificationsAndKeepsCommittedPrefix() {
        UUID event = largeEvent(250);
        int[] deliveries = {0};
        store.fault = call -> {
            if (call.equals("deliver") && ++deliveries[0] == 2) throw new IllegalStateException("db timeout");
        };
        harness.instance().runOnce();
        assertThat(store.delivered(event)).hasSize(100);
        var projection = store.projection(event);
        assertThat(projection.cursor).isEqualTo(100);
        assertThat(projection.status).isEqualTo("PENDING");
        assertThat(projection.errorClass).isEqualTo("DERIVATION_FAILED");
        assertThat(projection.owner).isNull();
        assertThat(harness.transactions.rollbacks).isEqualTo(1);
        store.fault = ignored -> { };
        harness.instance().runOnce();
        assertThat(store.delivered(event)).hasSize(100);
        store.advanceTime(Duration.ofSeconds(30));
        harness.instance().runOnce();
        assertThat(store.delivered(event)).hasSize(250);
        assertThat(store.delivered(event).stream().map(InMemoryNotificationStore.Delivered::recipient).distinct()).hasSize(250);
        assertThat(store.projection(event).status).isEqualTo("DONE");
    }

    @Test
    void crashedExecutorIsTakenOverAfterLeaseExpiryAndItsOldTokenCannotAdvance() {
        UUID event = largeEvent(150);
        var a = harness.boundary();
        var claimed = a.claim("a-instance");
        store.discover(50);
        claimed = a.claim("a-instance");
        assertThat(claimed).hasSize(1);
        ClaimedEvent old = claimed.getFirst();
        var first = a.batch(old, "a-instance", NotificationType.REVIEW_SUBMITTED, old.recipientCursor());
        assertThat(first.done()).isFalse();
        // A 在此后退出，不释放租约；有效租约内其他实例不能领取。
        var b = harness.instance();
        b.runOnce();
        assertThat(store.projection(event).owner).isEqualTo("a-instance");
        store.advanceTime(Duration.ofSeconds(61));
        b.runOnce();
        assertThat(store.delivered(event)).hasSize(150);
        assertThat(store.projection(event).status).isEqualTo("DONE");
        assertThat(store.projection(event).token).isEqualTo(old.claimToken() + 1);
        assertThat(harness.counter("kb.notification.derivation.takeovers", null)).isEqualTo(1);
        int before = store.delivered().size();
        assertThatThrownBy(() -> a.batch(old, "a-instance", NotificationType.REVIEW_SUBMITTED, first.cursor()))
                .isInstanceOf(NotificationLeaseLost.class);
        assertThat(store.delivered()).hasSize(before);
        assertThat(store.projection(event).cursor).isEqualTo(150);
    }

    @Test
    void staleTokenFromSameOwnerIsRejectedAfterReclaimAndWritesNothing() {
        UUID event = largeEvent(150);
        store.discover(50);
        var boundary = harness.boundary();
        var old = boundary.claim("same").getFirst();
        store.advanceTime(Duration.ofSeconds(61));
        var fresh = boundary.claim("same").getFirst();
        assertThat(fresh.claimToken()).isEqualTo(old.claimToken() + 1);
        assertThat(fresh.takeover()).isTrue();
        assertThatThrownBy(() -> boundary.batch(old, "same", NotificationType.REVIEW_SUBMITTED, 0))
                .isInstanceOf(NotificationLeaseLost.class);
        assertThat(store.delivered(event)).isEmpty();
        assertThat(store.projection(event).cursor).isZero();
        assertThat(boundary.batch(fresh, "same", NotificationType.REVIEW_SUBMITTED, 0).cursor()).isEqualTo(100);
    }

    @Test
    void rowLockedByAnotherBatchIsSkippedInsteadOfDoubleClaimed() {
        UUID event = largeEvent(10);
        store.discover(50);
        store.locked.add(event);
        assertThat(harness.boundary().claim("other")).isEmpty();
        store.locked.clear();
        assertThat(harness.boundary().claim("other")).hasSize(1);
    }

    @Test
    void perEventBatchBudgetReleasesWorkWithoutCountingFailureOrTakeover() {
        harness.properties.setMaxBatchesPerEvent(1);
        UUID event = largeEvent(250);
        var service = harness.instance();
        service.runOnce();
        assertThat(store.delivered(event)).hasSize(100);
        assertThat(store.projection(event).owner).isNull();
        assertThat(store.projection(event).attempts).isZero();
        service.runOnce();
        service.runOnce();
        assertThat(store.delivered(event)).hasSize(250);
        assertThat(store.projection(event).status).isEqualTo("DONE");
        assertThat(harness.counter("kb.notification.derivation.takeovers", null)).isZero();
        assertThat(harness.counter("kb.notification.derivation.failures", "batch")).isZero();
    }

    @Test
    void conflictingRedeliveryKeepsOriginalNotificationTimeAndReadState() {
        UUID event = largeEvent(2);
        harness.instance().runOnce();
        var original = store.delivered(event);
        store.advanceTime(Duration.ofMinutes(5));
        store.read(event, 1);
        var read = store.delivered(event).getFirst().readAt();
        store.advanceTime(Duration.ofMinutes(5));
        store.loseProjection(event);
        harness.instance().runOnce();
        var after = store.delivered(event);
        assertThat(after).hasSize(2);
        assertThat(after.getFirst().readAt()).isEqualTo(read);
        assertThat(after).extracting(InMemoryNotificationStore.Delivered::createdAt)
                .containsExactlyElementsOf(original.stream().map(InMemoryNotificationStore.Delivered::createdAt).toList());
        assertThat(after).extracting(InMemoryNotificationStore.Delivered::notificationId)
                .containsExactlyElementsOf(original.stream().map(InMemoryNotificationStore.Delivered::notificationId).toList());
    }

    @Test
    void everyWriteRunsInsideAShortTransactionAndTheOrchestratorHasNone() {
        largeEvent(120);
        harness.instance().runOnce();
        assertThat(harness.transactions.timeouts).isNotEmpty().allMatch(timeout -> timeout == 10);
        assertThat(harness.transactions.commits).isEqualTo(harness.transactions.timeouts.size());
        assertThat(NotificationDerivationService.class.isAnnotationPresent(Transactional.class)).isFalse();
        int advance = store.calls.indexOf("advance");
        assertThat(advance).isPositive().isLessThan(store.calls.indexOf("deliver"));
    }

    private UUID largeEvent(int recipients) {
        return store.addRaw("REVIEW_SUBMITTED", 0L, store.now,
                LongStream.rangeClosed(1, recipients).boxed().toArray(Long[]::new));
    }
}
