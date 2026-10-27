package com.zhishiku.kb.app.document;

import com.zhishiku.kb.app.document.model.DocumentReviewRow;
import com.zhishiku.kb.app.document.model.ReviewReceipt;
import com.zhishiku.kb.app.document.service.DocumentReviewService;
import com.zhishiku.kb.common.review.CollaborationEvent;
import com.zhishiku.kb.common.review.CollaborationKind;
import com.zhishiku.kb.common.review.PublicationEvidence;
import com.zhishiku.kb.common.review.PublicationPolicy;
import com.zhishiku.kb.common.review.ReviewError;
import com.zhishiku.kb.common.review.ReviewFailure;
import com.zhishiku.kb.common.review.ReviewState;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class DocumentReviewSubmitTest {
    @Test
    void onlyIndexedUnpublishedReviewRequiredVersionCanBeSubmitted() {
        for (String index : List.of("pending", "parsing", "failed")) {
            var f = new ReviewCommandFixture();
            f.version.setIndexStatus(index);
            assertConflict(f);
        }
        var auto = new ReviewCommandFixture();
        auto.version.setPublicationPolicy(PublicationPolicy.AUTO);
        assertConflict(auto);
        var published = new ReviewCommandFixture();
        published.version.setPublicationEvidence(PublicationEvidence.PUBLISHED);
        assertConflict(published);
        var stale = new ReviewCommandFixture();
        stale.version.setStateVersion(1);
        assertConflict(stale);
    }

    @Test
    void submitCapturesCurrentSubmitterSpaceAndDeduplicatedRecipientsInSameTransaction() throws Exception {
        var f = new ReviewCommandFixture();
        var receipt = f.service.submit(8, 10, 20, 0, "submit-1");
        assertThat(receipt.reviewState()).isEqualTo(ReviewState.IN_REVIEW);
        assertThat(receipt.publicationEvidence()).isEqualTo(PublicationEvidence.NONE);
        assertThat(receipt.stateVersion()).isEqualTo(1);
        var round = ArgumentCaptor.forClass(DocumentReviewRow.class);
        verify(f.mapper).insert(round.capture());
        assertThat(round.getValue().getSubmittedBy()).isEqualTo(8);
        assertThat(round.getValue().getSubmittedSpaceId()).isEqualTo(3);
        assertThat(round.getValue().getRoundNo()).isEqualTo(1);
        var event = ArgumentCaptor.forClass(CollaborationEvent.class);
        verify(f.eventMapper).append(event.capture());
        assertThat(event.getValue().kind()).isEqualTo(CollaborationKind.REVIEW_SUBMITTED);
        verify(f.eventMapper).recipient(event.getValue().eventId(), 9);
        verify(f.eventMapper).recipient(event.getValue().eventId(), 10);
        var order = inOrder(f.auth, f.receipts, f.mapper, f.eventMapper);
        order.verify(f.auth).requireEdit(8, f.version);
        order.verify(f.receipts).claim(any());
        order.verify(f.mapper).lockVersion(10, 20);
        order.verify(f.mapper).insert(any());
        order.verify(f.mapper).bumpVersion(10, 20, 0);
        order.verify(f.eventMapper).append(any());
        order.verify(f.receipts).complete(any(), any());
        assertThat(DocumentReviewService.class.getMethod("submit", long.class, long.class, long.class,
                long.class, String.class).getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void successfulReplayReauthorizesAndDoesNotTreatOriginalExpectedVersionAsNewCommand() {
        var f = new ReviewCommandFixture();
        var original = f.service.submit(8, 10, 20, 0, "same");
        when(f.receipts.claim(any())).thenReturn("saved");
        when(f.receipts.read("saved", ReviewReceipt.class)).thenReturn(original);
        f.version.setStateVersion(99);
        assertThat(f.service.submit(8, 10, 20, 0, "same")).isEqualTo(original);
        verify(f.mapper, times(1)).lockVersion(10, 20);
        clearInvocations(f.receipts);
        doThrow(new ReviewFailure(ReviewError.NOT_FOUND)).when(f.auth).requireEdit(8, f.version);
        assertThatThrownBy(() -> f.service.submit(8, 10, 20, 0, "same")).isInstanceOf(ReviewFailure.class);
        verifyNoInteractions(f.receipts);
    }

    @Test
    void noEligiblePoolDoesNotCreateRoundOrSuccessFact() {
        var f = new ReviewCommandFixture();
        when(f.eligible.candidates(10, 7L, 8L, null)).thenReturn(List.of());
        assertThatThrownBy(() -> f.service.submit(8, 10, 20, 0, "no-pool"))
                .isInstanceOf(ReviewFailure.class).extracting(error -> ((ReviewFailure) error).error())
                .isEqualTo(ReviewError.NO_ELIGIBLE_REVIEWER);
        verify(f.mapper, never()).insert(any());
        verifyNoInteractions(f.eventMapper);
        verify(f.receipts, never()).complete(any(), any());
    }

    @Test
    void resubmissionCreatesNewRoundAndPreservesPreviousPrivateFacts() {
        var f = new ReviewCommandFixture();
        var old = DocumentReviewRow.builder().id(UUID.randomUUID()).docId(10).versionId(20).roundNo(3)
                .submittedBy(6).submittedSpaceId(2).submittedAt(Instant.EPOCH).state(ReviewState.REJECTED)
                .decidedAt(Instant.EPOCH.plusSeconds(1)).decisionBy(9L).reason("私有旧意见").build();
        when(f.mapper.latest(20)).thenReturn(old);
        var result = f.service.submit(8, 10, 20, 0, "next-round");
        assertThat(result.roundNo()).isEqualTo(4);
        assertThat(result.reviewId()).isNotEqualTo(old.getId());
        assertThat(old.getState()).isEqualTo(ReviewState.REJECTED);
        assertThat(old.getReason()).isEqualTo("私有旧意见");
        assertThat(old.getSubmittedBy()).isEqualTo(6);
        old.setState(ReviewState.IN_REVIEW);
        assertConflict(f);
    }

    private static void assertConflict(ReviewCommandFixture f) {
        assertThatThrownBy(() -> f.service.submit(8, 10, 20, 0, "invalid"))
                .isInstanceOf(ReviewFailure.class).extracting(error -> ((ReviewFailure) error).error())
                .isEqualTo(ReviewError.STATE_CONFLICT);
    }
}
