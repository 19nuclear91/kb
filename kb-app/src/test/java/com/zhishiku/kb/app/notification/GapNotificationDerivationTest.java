package com.zhishiku.kb.app.notification;

import com.zhishiku.kb.app.notification.mapper.GapNotificationDerivationMapper;
import com.zhishiku.kb.app.notification.model.ClaimedEvent;
import com.zhishiku.kb.app.notification.service.GapNotificationDerivationService;
import com.zhishiku.kb.app.notification.service.GapNotificationDerivationTransaction;
import com.zhishiku.kb.app.notification.service.NotificationDerivationScheduler;
import com.zhishiku.kb.app.notification.service.NotificationDerivationService;
import com.zhishiku.kb.app.notification.service.NotificationLeaseLost;
import com.zhishiku.kb.app.support.MapperRowStub;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 独立问题派生和旧来源回归的离线验证；虚拟时钟不证明数据库锁、租约或真实并发。 */
class GapNotificationDerivationTest {
    private final GapDerivationHarness harness = new GapDerivationHarness();
    private final InMemoryNotificationStore store = harness.store;

    @Test
    void repeatedDiscoveryAndLateSmallerPositionAreNeverLost() {
        UUID later = store.addRaw("GAP_ASSIGNED", 7L, store.now.plusSeconds(10), 8L);
        harness.instance().runOnce();
        UUID earlier = store.addRaw("GAP_CREATED", 10L, store.now.plusSeconds(1), 7L);
        harness.instance().runOnce();
        harness.instance().runOnce();
        assertThat(store.delivered(later)).hasSize(1);
        assertThat(store.delivered(earlier)).hasSize(1);
        assertThat(store.discover(50)).isZero();
    }

    @Test
    void committedPrefixSurvivesBatchFailureAndRemainingIntentCanBeTakenOver() {
        UUID event = largeEvent(250);
        var original = store.events.get(event);
        int[] deliveries = {0};
        store.fault = call -> {
            if (call.equals("deliver") && ++deliveries[0] == 2) {
                throw new IllegalStateException("secret jdbc text");
            }
        };
        harness.instance().runOnce();
        assertThat(store.delivered(event)).hasSize(100);
        assertThat(store.projection(event).cursor).isEqualTo(100);
        assertThat(store.projection(event).status).isEqualTo("PENDING");
        assertThat(store.projection(event).errorClass).isEqualTo("DERIVATION_FAILED");
        assertThat(harness.transactions.rollbacks).isEqualTo(1);
        assertThat(store.events.get(event)).isEqualTo(original);
        assertThat(store.recipients.get(event)).hasSize(250);
        store.fault = call -> {
            // 故障恢复，不改变已提交源事件。
        };
        harness.instance().runOnce();
        assertThat(store.delivered(event)).hasSize(100);
        store.advanceTime(Duration.ofSeconds(30));
        harness.instance().runOnce();
        assertThat(store.delivered(event)).hasSize(250);
        assertThat(store.projection(event).status).isEqualTo("DONE");
        assertThat(store.events.get(event)).isEqualTo(original);
    }

    @Test
    void expiredExecutorCannotWriteAfterFreshClaimAndPartialProgressIsPreserved() {
        UUID event = largeEvent(150);
        var boundary = harness.boundary();
        boundary.discover();
        var old = boundary.claim("old-process").getFirst();
        var first = boundary.batchGap(old, "old-process", 0);
        assertThat(first.cursor()).isEqualTo(100);
        harness.instance().runOnce();
        assertThat(store.projection(event).owner).isEqualTo("old-process");
        store.advanceTime(Duration.ofSeconds(61));
        harness.instance().runOnce();
        assertThat(store.delivered(event)).hasSize(150);
        assertThat(store.projection(event).token).isEqualTo(old.claimToken() + 1);
        assertThatThrownBy(() -> boundary.batchGap(old, "old-process", first.cursor())).isInstanceOf(NotificationLeaseLost.class);
        assertThat(store.projection(event).cursor).isEqualTo(150);
        assertThat(store.delivered(event)).hasSize(150);
    }

    @Test
    void sourceNotReadyWaitsWithBackoffAndNotesCompleteWithoutNotification() {
        UUID unknown = store.addRaw("GAP_FUTURE", 10L, store.now, 8L);
        UUID note = store.addRaw("NOTE_ADDED", 10L, store.now, 8L);
        harness.instance().runOnce();
        assertThat(store.projection(unknown).status).isEqualTo("WAITING_SOURCE");
        assertThat(store.projection(unknown).errorClass).isEqualTo("SOURCE_NOT_READY");
        assertThat(store.projection(unknown).attempts).isEqualTo(1);
        assertThat(store.projection(note).status).isEqualTo("DONE");
        assertThat(store.delivered()).isEmpty();
        assertThat(harness.instance().runOnce()).isZero();
    }

    @Test
    void eachRoundAndEachEventAreBoundedAndReleasedRatherThanFailed() {
        harness.properties.setDiscoverLimit(2);
        harness.properties.setClaimLimit(2);
        for (int index = 0; index < 5; index++) {
            store.addRaw("GAP_ASSIGNED", 7L, store.now.plusSeconds(index), 8L);
        }
        var service = harness.instance();
        assertThat(service.runOnce()).isEqualTo(2);
        assertThat(service.runOnce()).isEqualTo(2);
        assertThat(service.runOnce()).isEqualTo(1);
        harness.properties.setMaxBatchesPerEvent(1);
        UUID event = largeEvent(250);
        service.runOnce();
        assertThat(store.delivered(event)).hasSize(100);
        assertThat(store.projection(event).owner).isNull();
        assertThat(store.projection(event).attempts).isZero();
        service.runOnce();
        service.runOnce();
        assertThat(store.delivered(event)).hasSize(250);
    }

    @Test
    void duplicateDeliveryKeepsNotificationIdentityTimeAndFirstRead() {
        UUID event = largeEvent(2);
        harness.instance().runOnce();
        var before = store.delivered(event);
        store.advanceTime(Duration.ofMinutes(5));
        store.read(event, 1);
        var read = store.delivered(event).getFirst().readAt();
        store.loseProjection(event);
        harness.instance().runOnce();
        var after = store.delivered(event);
        assertThat(after).hasSize(2);
        assertThat(after.getFirst().readAt()).isEqualTo(read);
        assertThat(after).extracting(InMemoryNotificationStore.Delivered::notificationId)
                .containsExactlyElementsOf(before.stream().map(InMemoryNotificationStore.Delivered::notificationId).toList());
        assertThat(after).extracting(InMemoryNotificationStore.Delivered::createdAt)
                .containsExactlyElementsOf(before.stream().map(InMemoryNotificationStore.Delivered::createdAt).toList());
    }

    @Test
    void discoveryFailureStillClaimsAlreadyKnownWorkAndShortTransactionsAreInherited() {
        UUID event = largeEvent(150);
        harness.boundary().discover();
        store.fault = call -> {
            if (call.equals("discover")) {
                throw new IllegalStateException("secret discovery error");
            }
        };
        harness.instance().runOnce();
        assertThat(store.delivered(event)).hasSize(150);
        assertThat(harness.transactions.timeouts).allMatch(timeout -> timeout == NotificationProperties.BATCH_TIMEOUT_SECONDS);
        assertThat(GapNotificationDerivationService.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(store.calls.indexOf("advance")).isLessThan(store.calls.indexOf("deliver"));
    }

    @Test
    void schedulerDoesNotSkipEitherSourceWhenOtherSourceThrows() {
        var legacy = mock(NotificationDerivationService.class);
        var gaps = mock(GapNotificationDerivationService.class);
        var scheduler = new NotificationDerivationScheduler(legacy, gaps);
        doThrow(new IllegalStateException("secret legacy error")).when(legacy).runOnce();
        scheduler.tick();
        verify(gaps).runOnce();
        doThrow(new IllegalStateException("secret gap error")).when(gaps).runOnce();
        scheduler.tick();
        verify(legacy, times(2)).runOnce();
        verify(gaps, times(2)).runOnce();
    }

    @Test
    void mapperIsIndependentBoundedAndCannotWriteSourceStateOrReadBody() throws Exception {
        var configuration = MapperRowStub.configuration("/mapper/GapNotificationDerivationMapper.xml");
        String namespace = GapNotificationDerivationMapper.class.getName() + ".";
        var parameters = new HashMap<String, Object>();
        parameters.put("limit", 2);
        parameters.put("owner", "process");
        parameters.put("leaseSeconds", 60);
        parameters.put("eventId", UUID.randomUUID());
        parameters.put("afterUserId", 0L);
        parameters.put("token", 1L);
        parameters.put("cursor", 100L);
        parameters.put("type", "GAP_ASSIGNED");
        parameters.put("recipients", List.of(8L));
        parameters.put("status", "PENDING");
        parameters.put("errorClass", "DERIVATION_FAILED");
        parameters.put("delaySeconds", 30L);
        for (String method : List.of("discover", "claim", "recipients", "advance", "deliver", "complete", "release", "postpone", "backlog")) {
            String sql = configuration.getMappedStatement(namespace + method).getBoundSql(parameters).getSql()
                    .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            assertThat(sql).doesNotContain("kb_collaboration_event", "kb_notification_projection", "kb_knowledge_gap_entry",
                    "body", "description", "update kb_knowledge_gap ", "insert into kb_knowledge_gap_event ");
        }
        String discovery = configuration.getMappedStatement(namespace + "discover").getBoundSql(parameters).getSql().toLowerCase(Locale.ROOT);
        assertThat(discovery).contains("not exists", "limit ?", "on conflict (event_id) do nothing");
        String delivery = configuration.getMappedStatement(namespace + "deliver").getBoundSql(parameters).getSql().toLowerCase(Locale.ROOT);
        assertThat(delivery).contains("gap_event_id", "on conflict (gap_event_id, recipient_user_id) do nothing");
        var eventId = (UUID) parameters.get("eventId");
        var claim = MapperRowStub.readOne(configuration, namespace + "claim", parameters,
                Map.of("event_id", eventId, "kind", "GAP_ASSIGNED", "claim_token", 3L, "recipient_cursor", 100L,
                        "attempts", 1, "takeover", true), ClaimedEvent.class);
        assertThat(claim.claimToken()).isEqualTo(3);
        assertThat(claim.takeover()).isTrue();
    }

    private UUID largeEvent(int size) {
        return store.addRaw("GAP_SOLUTION_SUBMITTED", 1000L, store.now,
                LongStream.rangeClosed(1, size).boxed().toArray(Long[]::new));
    }
}
