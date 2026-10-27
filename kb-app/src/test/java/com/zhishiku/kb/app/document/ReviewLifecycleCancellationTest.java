package com.zhishiku.kb.app.document;

import com.zhishiku.kb.app.document.model.DocumentReviewRow;
import com.zhishiku.kb.app.document.service.DocumentManagementService;
import com.zhishiku.kb.app.document.service.ReviewCancellationService;
import com.zhishiku.kb.app.space.service.SpaceWriteService;
import com.zhishiku.kb.common.review.CollaborationEvent;
import com.zhishiku.kb.common.review.CollaborationKind;
import com.zhishiku.kb.common.review.PublicationPolicy;
import com.zhishiku.kb.common.review.ReviewState;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewLifecycleCancellationTest {
    @Test
    void cancellationRetainsOriginalSubmissionAndPolicyWithoutTakingControlOrTaskLocks() throws Exception {
        var f = new ReviewCommandFixture();
        f.version.setDocumentStatus("deleted");
        var round = DocumentReviewRow.builder().id(UUID.randomUUID()).docId(10).versionId(20).roundNo(3)
                .submittedBy(8).submittedSpaceId(2).submittedAt(Instant.EPOCH).state(ReviewState.IN_REVIEW).build();
        when(f.mapper.lockVersionsForDocument(10)).thenReturn(List.of(f.version));
        when(f.mapper.latest(20)).thenReturn(round);
        when(f.mapper.lockReview(20, round.getId())).thenReturn(round);
        when(f.mapper.decide(anyLong(), any(), anyLong(), any(), anyLong(), any(), any())).thenReturn(1);
        new ReviewCancellationService(f.mapper, f.events).cancelDocument(10, 7);
        assertThat(f.version.getPublicationPolicy()).isEqualTo(PublicationPolicy.REVIEW_REQUIRED);
        var fact = ArgumentCaptor.forClass(CollaborationEvent.class);
        verify(f.eventMapper).append(fact.capture());
        assertThat(fact.getValue().kind()).isEqualTo(CollaborationKind.REVIEW_CANCELLED);
        assertThat(fact.getValue().submittedAt()).isEqualTo(Instant.EPOCH);
        assertThat(round.getSubmittedSpaceId()).isEqualTo(2);
        assertThat(ReviewCancellationService.class.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(DocumentManagementService.class.getMethod("delete", long.class, long.class)
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(Arrays.stream(SpaceWriteService.class.getDeclaredFields())
                .noneMatch(field -> field.getType() == ReviewCancellationService.class)).isTrue();
    }

    @Test
    void terminatedRoundNeverGeneratesAnotherCancellationFact() {
        var f = new ReviewCommandFixture();
        var round = DocumentReviewRow.builder().id(UUID.randomUUID()).docId(10).versionId(20).roundNo(1)
                .submittedBy(8).submittedSpaceId(3).submittedAt(Instant.EPOCH).state(ReviewState.CANCELLED).build();
        when(f.mapper.latest(20)).thenReturn(round);
        assertThat(new ReviewCancellationService(f.mapper, f.events).cancelVersion(f.version, 7)).isNull();
        verify(f.eventMapper, never()).append(any());
    }
}
