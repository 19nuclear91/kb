package com.zhishiku.kb.app.document;

import com.zhishiku.kb.app.document.mapper.DocumentPublicationMapper;
import com.zhishiku.kb.app.document.model.DocumentReviewRow;
import com.zhishiku.kb.app.document.model.IndexIntegrity;
import com.zhishiku.kb.app.document.service.PublicationReadinessService;
import com.zhishiku.kb.app.document.service.ReviewApprovalService;
import com.zhishiku.kb.app.embedding.service.EmbeddingControlService;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.review.CollaborationEvent;
import com.zhishiku.kb.common.review.CollaborationKind;
import com.zhishiku.kb.common.review.PublicationEvidence;
import com.zhishiku.kb.common.review.ReviewFailure;
import com.zhishiku.kb.common.review.ReviewState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewApprovalServiceTest {
    private final ReviewCommandFixture f = new ReviewCommandFixture();
    private final DocumentPublicationMapper publication = mock(DocumentPublicationMapper.class);
    private final EmbeddingControlService embedding = mock(EmbeddingControlService.class);
    private final ReviewApprovalService service = new ReviewApprovalService(f.mapper, publication, f.auth,
            new PublicationReadinessService(publication), embedding, f.receipts, f.events);
    private final DocumentReviewRow round = DocumentReviewRow.builder().id(UUID.randomUUID()).docId(10).versionId(20).roundNo(1)
            .submittedBy(8).submittedSpaceId(3).submittedAt(Instant.EPOCH).state(ReviewState.IN_REVIEW).build();
    private final IndexIntegrity integrity = new IndexIntegrity();

    @BeforeEach
    void setup() {
        f.version.setIndexedProfileId("p");
        f.version.setIndexedFingerprint("fp");
        f.version.setIndexedEpoch(2L);
        f.version.setIndexedChunkCount(2);
        f.version.setVersionNo(2);
        f.version.setCurrentVersionNo(1);
        integrity.setChunkCount(2);
        integrity.setValidCount(2);
        integrity.setDistinctIndexes(2);
        integrity.setFirstIndex(0);
        integrity.setLastIndex(1);
        when(publication.integrity(10, 20, "p")).thenReturn(integrity);
        when(embedding.lockShared()).thenReturn(new EmbeddingControl("ACTIVE", "p", "fp", null, null, 4, null));
        when(f.mapper.review(20, round.getId())).thenReturn(round);
        when(f.mapper.lockReview(20, round.getId())).thenReturn(round);
        when(publication.publish(eq(10L), eq(20L), eq(0L), eq("REVIEW_REQUIRED"), eq("p"), eq("fp"), any())).thenReturn(1);
        when(f.mapper.decide(eq(20L), eq(round.getId()), eq(0L), eq(ReviewState.APPROVED), eq(9L), any(), eq(null))).thenReturn(1);
    }

    @Test
    void approvalPublishesWithControlDocumentReviewLockOrderAndTwoFactsInOneTransaction() throws Exception {
        var receipt = service.approve(9, 10, 20, round.getId(), 0, "approve");
        assertThat(receipt.reviewState()).isEqualTo(ReviewState.APPROVED);
        assertThat(receipt.publicationEvidence()).isEqualTo(PublicationEvidence.PUBLISHED);
        var order = inOrder(f.receipts, embedding, f.mapper, publication, f.eventMapper);
        order.verify(f.receipts).claim(any());
        order.verify(embedding).lockShared();
        order.verify(f.mapper).lockVersion(10, 20);
        order.verify(f.mapper).lockReview(20, round.getId());
        order.verify(publication).integrity(10, 20, "p");
        order.verify(publication).publish(eq(10L), eq(20L), eq(0L), eq("REVIEW_REQUIRED"), eq("p"), eq("fp"), any());
        order.verify(f.mapper).decide(eq(20L), eq(round.getId()), eq(0L), eq(ReviewState.APPROVED), eq(9L), any(), eq(null));
        var facts = ArgumentCaptor.forClass(CollaborationEvent.class);
        verify(f.eventMapper, times(2)).append(facts.capture());
        assertThat(facts.getAllValues()).extracting(CollaborationEvent::kind)
                .containsExactly(CollaborationKind.REVIEW_APPROVED, CollaborationKind.VERSION_PUBLISHED);
        verify(f.receipts).complete(any(), eq(receipt));
        assertThat(ReviewApprovalService.class.getMethod("approve", long.class, long.class, long.class,
                UUID.class, long.class, String.class).getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void failedCompareAndSetCannotAcknowledgeCompetingApproval() {
        when(publication.publish(eq(10L), eq(20L), eq(0L), eq("REVIEW_REQUIRED"), eq("p"), eq("fp"), any())).thenReturn(0);
        assertThatThrownBy(() -> service.approve(9, 10, 20, round.getId(), 0, "lost-cas"))
                .isInstanceOf(ReviewFailure.class).hasMessage("STATE_CONFLICT");
        verify(f.mapper, never()).decide(anyLong(), any(), anyLong(), any(), anyLong(), any(), any());
        verify(f.receipts, never()).complete(any(), any());
        verify(f.eventMapper, never()).append(any());
    }

    @Test
    void maintenanceOrStaleProfileNeverSavesApprovalOrReceipt() {
        when(embedding.lockShared()).thenReturn(new EmbeddingControl("UNINITIALIZED", null, null, null, null, 4, null));
        assertThatThrownBy(() -> service.approve(9, 10, 20, round.getId(), 0, "maintenance"))
                .isInstanceOf(ReviewFailure.class).hasMessage("EMBEDDING_MAINTENANCE");
        when(embedding.lockShared()).thenReturn(new EmbeddingControl("ACTIVE", "other", "other", null, null, 4, null));
        assertThatThrownBy(() -> service.approve(9, 10, 20, round.getId(), 0, "stale"))
                .isInstanceOf(ReviewFailure.class).hasMessage("INDEX_REFRESH_REQUIRED");
        verify(f.mapper, never()).decide(anyLong(), any(), anyLong(), any(), anyLong(), any(), any());
        verify(f.receipts, never()).complete(any(), any());
    }

    @Test
    void incompleteWrongOwnershipAndNewerCurrentAreConflictsWithoutApproval() {
        for (String invalid : List.of("count", "identity", "indexes", "space", "version", "state")) {
            setup();
            switch (invalid) {
                case "count" -> integrity.setChunkCount(1);
                case "identity" -> integrity.setValidCount(1);
                case "indexes" -> integrity.setDistinctIndexes(1);
                case "space" -> round.setSubmittedSpaceId(5);
                case "version" -> f.version.setCurrentVersionNo(3);
                case "state" -> f.version.setStateVersion(2);
                default -> throw new IllegalStateException();
            }
            assertThatThrownBy(() -> service.approve(9, 10, 20, round.getId(), 0, invalid)).isInstanceOf(ReviewFailure.class);
            round.setSubmittedSpaceId(3);
            f.version.setStateVersion(0);
        }
        verify(f.mapper, never()).decide(anyLong(), any(), anyLong(), any(), anyLong(), any(), any());
    }
}
