package com.zhishiku.kb.app.notification;

import com.zhishiku.kb.app.collaboration.service.CollaborationEventService;
import com.zhishiku.kb.app.notification.service.NotificationDerivationService;
import com.zhishiku.kb.common.review.CollaborationEvent;
import com.zhishiku.kb.common.review.CollaborationKind;
import com.zhishiku.kb.common.review.ReviewRound;
import com.zhishiku.kb.common.review.ReviewState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复用前置审核协作事件／收件人协议的契约：真实 CollaborationEventService 写入源事实，
 * 派生服务只按当时记录的提醒意图生成通知。仓储为内存替身。
 */
class ReviewNotificationContractTest {
    private static final long DOCUMENT = 9007199254740993L, VERSION = 9007199254740995L, SPACE = 3;
    private static final long OWNER = 11, SUBMITTER = 12, REVIEWER = 20, OTHER_REVIEWER = 21, LATE_REVIEWER = 22;
    private final DerivationHarness harness = new DerivationHarness();
    private final CollaborationEventService events = new CollaborationEventService(harness.store.collaborationMapper());
    private NotificationDerivationService derivation;
    private Instant now = Instant.parse("2026-10-25T00:00:00Z");

    @BeforeEach
    void setUp() {
        derivation = harness.instance();
    }

    @Test
    void submissionNotifiesRecordedPoolOnlyAndExcludesTheActor() {
        var round = round(null);
        // 合格池由源事务计算；即使名单含送审者自身，也不能产生自身提醒。
        UUID event = submit(round, List.of(SUBMITTER, REVIEWER, OTHER_REVIEWER));
        harness.store.recipients.get(event).add(SUBMITTER);
        derivation.runOnce();
        assertThat(recipients(event)).containsExactly(REVIEWER, OTHER_REVIEWER);
        assertThat(harness.store.delivered(event)).allMatch(item -> item.type().equals("REVIEW_SUBMITTED"));
        assertThat(harness.store.projection(event).status).isEqualTo("DONE");
    }

    @Test
    void approvalNotifiesThisRoundSubmitterNotOwnerAndPublicationIsNotAnotherReminder() {
        var round = round(null);
        submit(round, List.of(REVIEWER, OTHER_REVIEWER));
        UUID approved = decide(CollaborationKind.REVIEW_APPROVED, round, REVIEWER);
        UUID published = append(CollaborationEvent.create(CollaborationKind.VERSION_PUBLISHED, DOCUMENT, VERSION, round,
                REVIEWER, tick(), now), List.of());
        derivation.runOnce();
        assertThat(recipients(approved)).containsExactly(SUBMITTER);
        assertThat(harness.store.delivered()).noneMatch(item -> item.recipient() == OWNER);
        assertThat(harness.store.delivered(published)).isEmpty();
        assertThat(harness.store.projection(published).status).isEqualTo("DONE");
        assertThat(harness.store.delivered().stream().filter(item -> item.recipient() == SUBMITTER)).hasSize(1);
    }

    @Test
    void withdrawnCancelledAndPublishedFactsCompleteWithoutNotificationsEvenWithRecordedRecipients() {
        var first = round(null);
        submit(first, List.of(REVIEWER));
        UUID withdrawn = decide(CollaborationKind.REVIEW_WITHDRAWN, first, SUBMITTER);
        var second = new ReviewRound(UUID.randomUUID(), DOCUMENT, VERSION, 2, SUBMITTER, SPACE, tick(), ReviewState.IN_REVIEW);
        submit(second, List.of(REVIEWER));
        UUID cancelled = decide(CollaborationKind.REVIEW_CANCELLED, second, OWNER);
        assertThat(harness.store.recipients.get(withdrawn)).isEmpty();
        assertThat(harness.store.recipients.get(cancelled)).containsExactly(SUBMITTER);
        derivation.runOnce();
        for (UUID silent : List.of(withdrawn, cancelled)) {
            assertThat(harness.store.delivered(silent)).isEmpty();
            assertThat(harness.store.projection(silent).status).isEqualTo("DONE");
            assertThat(harness.store.projection(silent).errorClass).isNull();
        }
        assertThat(harness.store.delivered()).extracting(InMemoryNotificationStore.Delivered::type)
                .containsOnly("REVIEW_SUBMITTED");
    }

    @Test
    void repeatedDerivationAndRestartKeepAtMostOneNotificationPerRecipient() {
        var round = round(null);
        UUID event = submit(round, List.of(REVIEWER, OTHER_REVIEWER));
        events.append(CollaborationEvent.create(CollaborationKind.REVIEW_SUBMITTED, DOCUMENT, VERSION, round, SUBMITTER,
                now, null), List.of(REVIEWER, OTHER_REVIEWER, LATE_REVIEWER));
        derivation.runOnce();
        harness.instance().runOnce();
        derivation.runOnce();
        assertThat(recipients(event)).containsExactly(REVIEWER, OTHER_REVIEWER);
        assertThat(harness.store.events).hasSize(1);
    }

    @Test
    void laterRoundIsANewFactAndLateJoinerOnlyReceivesTheNewRoundsRecordedIntent() {
        var first = round(null);
        UUID firstEvent = submit(first, List.of(REVIEWER));
        UUID rejected = decide(CollaborationKind.REVIEW_REJECTED, first, REVIEWER);
        derivation.runOnce();
        var second = new ReviewRound(UUID.randomUUID(), DOCUMENT, VERSION, 2, SUBMITTER, SPACE, tick(), ReviewState.IN_REVIEW);
        UUID secondEvent = submit(second, List.of(REVIEWER, LATE_REVIEWER));
        derivation.runOnce();
        assertThat(recipients(firstEvent)).containsExactly(REVIEWER);
        assertThat(recipients(rejected)).containsExactly(SUBMITTER);
        assertThat(recipients(secondEvent)).containsExactly(REVIEWER, LATE_REVIEWER);
        assertThat(harness.store.delivered(firstEvent)).noneMatch(item -> item.recipient() == LATE_REVIEWER);
        assertThat(harness.store.delivered().stream().map(InMemoryNotificationStore.Delivered::notificationId).distinct())
                .hasSize(harness.store.delivered().size());
    }

    private ReviewRound round(ReviewRound previous) {
        return ReviewRound.start(DOCUMENT, DOCUMENT, VERSION, previous, SUBMITTER, SPACE, tick());
    }

    private UUID submit(ReviewRound round, List<Long> pool) {
        return append(CollaborationEvent.create(CollaborationKind.REVIEW_SUBMITTED, DOCUMENT, VERSION, round, SUBMITTER,
                round.submittedAt(), null), pool);
    }

    private UUID decide(CollaborationKind kind, ReviewRound round, long actor) {
        var at = tick();
        return append(CollaborationEvent.create(kind, DOCUMENT, VERSION, round, actor, at, at), List.of(round.submittedBy()));
    }

    private UUID append(CollaborationEvent event, List<Long> recipients) {
        events.append(event, recipients);
        return event.eventId();
    }

    private Instant tick() {
        now = now.plusSeconds(1);
        return now;
    }

    private List<Long> recipients(UUID event) {
        return harness.store.delivered(event).stream().map(InMemoryNotificationStore.Delivered::recipient).sorted().toList();
    }
}
