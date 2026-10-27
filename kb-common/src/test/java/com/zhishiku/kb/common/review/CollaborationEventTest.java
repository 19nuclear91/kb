package com.zhishiku.kb.common.review;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollaborationEventTest {
    @Test
    void businessIdentityIsStableAndApprovalAndPublicationAreSeparateFacts() {
        var round = ReviewRound.start(10, 10, 20, null, 7, 3, Instant.EPOCH);
        var approved = CollaborationEvent.create(CollaborationKind.REVIEW_APPROVED, 10, 20, round, 9L,
                Instant.EPOCH.plusSeconds(1), Instant.EPOCH.plusSeconds(1));
        var replay = CollaborationEvent.create(CollaborationKind.REVIEW_APPROVED, 10, 20, round, 9L,
                Instant.EPOCH.plusSeconds(2), Instant.EPOCH.plusSeconds(2));
        var published = CollaborationEvent.create(CollaborationKind.VERSION_PUBLISHED, 10, 20, round, 9L,
                approved.occurredAt(), approved.decidedAt());
        assertThat(replay.eventId()).isEqualTo(approved.eventId());
        assertThat(published.eventId()).isNotEqualTo(approved.eventId());
        assertThat(published.businessKey()).isEqualTo("VERSION_PUBLISHED:20");
        var fields = Arrays.stream(CollaborationEvent.class.getRecordComponents()).map(component -> component.getName()).toList();
        assertThat(fields).doesNotContainAnyElementsOf(List.of("reason", "title", "fileName", "content", "token", "hash"));
    }

    @Test
    void ownershipAndRequiredDecisionTimeAreChecked() {
        var round = ReviewRound.start(10, 10, 20, null, 7, 3, Instant.EPOCH);
        assertThatThrownBy(() -> CollaborationEvent.create(CollaborationKind.REVIEW_REJECTED, 10, 21,
                round, 9L, Instant.EPOCH, Instant.EPOCH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CollaborationEvent.create(CollaborationKind.REVIEW_REJECTED, 10, 20,
                round, 9L, Instant.EPOCH, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void commentFactsAreDedupedByKindAndCommentWithoutReviewOrBodyFields() {
        UUID root = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID reply = UUID.fromString("00000000-0000-0000-0000-000000000002");
        var created = CollaborationEvent.comment(CollaborationKind.COMMENT_CREATED, 10, 20, root, null, 7, Instant.EPOCH);
        var again = CollaborationEvent.comment(CollaborationKind.COMMENT_CREATED, 10, 20, root, null, 7, Instant.EPOCH.plusSeconds(9));
        var replied = CollaborationEvent.comment(CollaborationKind.COMMENT_REPLIED, 10, 20, reply, root, 8, Instant.EPOCH);
        var deleted = CollaborationEvent.comment(CollaborationKind.COMMENT_DELETED, 10, 20, reply, root, 8, Instant.EPOCH);
        assertThat(again.eventId()).isEqualTo(created.eventId());
        assertThat(created.businessKey()).isEqualTo("COMMENT_CREATED:" + root);
        assertThat(Set.of(created.eventId(), replied.eventId(), deleted.eventId())).hasSize(3);
        assertThat(replied.reviewId()).isNull();
        assertThat(replied.roundNo()).isNull();
        assertThat(replied.rootCommentId()).isEqualTo(root);
        var fields = Arrays.stream(CollaborationEvent.class.getRecordComponents()).map(component -> component.getName()).toList();
        assertThat(fields).doesNotContainAnyElementsOf(List.of("body", "text", "content", "label", "title"));
    }

    @Test
    void commentStructureAndOwnershipAreValidatedIndependentlyOfReviewRules() {
        UUID root = UUID.randomUUID(), reply = UUID.randomUUID();
        var round = ReviewRound.start(10, 10, 20, null, 7, 3, Instant.EPOCH);
        for (Runnable invalid : List.<Runnable>of(
                () -> CollaborationEvent.comment(CollaborationKind.COMMENT_CREATED, 10, 20, root, reply, 7, Instant.EPOCH),
                () -> CollaborationEvent.comment(CollaborationKind.COMMENT_REPLIED, 10, 20, reply, null, 7, Instant.EPOCH),
                () -> CollaborationEvent.comment(CollaborationKind.COMMENT_REPLIED, 10, 20, reply, reply, 7, Instant.EPOCH),
                () -> CollaborationEvent.comment(CollaborationKind.COMMENT_DELETED, 10, 20, null, null, 7, Instant.EPOCH),
                () -> CollaborationEvent.comment(CollaborationKind.REVIEW_SUBMITTED, 10, 20, root, null, 7, Instant.EPOCH),
                () -> CollaborationEvent.comment(CollaborationKind.COMMENT_CREATED, 10, 20, root, null, 0, Instant.EPOCH),
                () -> CollaborationEvent.create(CollaborationKind.COMMENT_REPLIED, 10, 20, round, 7L, Instant.EPOCH, null))) {
            assertThatThrownBy(invalid::run).isInstanceOf(IllegalArgumentException.class);
        }
        var valid = CollaborationEvent.comment(CollaborationKind.COMMENT_CREATED, 10, 20, root, null, 7, Instant.EPOCH);
        assertThatThrownBy(() -> new CollaborationEvent(valid.eventId(), valid.kind(), valid.businessKey(), 10, 20,
                round.id(), 1, 7L, Instant.EPOCH, Instant.EPOCH, null, root, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CollaborationEvent(valid.eventId(), valid.kind(), valid.businessKey(), 10, 20,
                null, null, null, Instant.EPOCH, null, null, root, null)).isInstanceOf(IllegalArgumentException.class);
        var review = CollaborationEvent.create(CollaborationKind.REVIEW_SUBMITTED, 10, 20, round, 7L, Instant.EPOCH, null);
        assertThatThrownBy(() -> new CollaborationEvent(review.eventId(), review.kind(), review.businessKey(), 10, 20,
                review.reviewId(), review.roundNo(), 7L, Instant.EPOCH, Instant.EPOCH, null, root, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
