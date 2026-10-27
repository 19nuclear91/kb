package com.zhishiku.kb.app.document;

import com.zhishiku.kb.app.document.mapper.CandidateIndexRefreshMapper;
import com.zhishiku.kb.app.document.mapper.DocumentPublicationMapper;
import com.zhishiku.kb.app.document.model.CandidateRefreshTask;
import com.zhishiku.kb.app.document.model.DocumentReviewRow;
import com.zhishiku.kb.app.document.service.CandidateIndexRefreshService;
import com.zhishiku.kb.app.document.service.PublicationReadinessService;
import com.zhishiku.kb.app.document.service.ReviewCancellationService;
import com.zhishiku.kb.app.embedding.service.EmbeddingControlService;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.review.CollaborationEvent;
import com.zhishiku.kb.common.review.CollaborationKind;
import com.zhishiku.kb.common.review.PublicationEvidence;
import com.zhishiku.kb.common.review.PublicationPolicy;
import com.zhishiku.kb.common.review.ReviewFailure;
import com.zhishiku.kb.common.review.ReviewState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CandidateIndexRefreshServiceTest {
    private final ReviewCommandFixture f = new ReviewCommandFixture();
    private final CandidateIndexRefreshMapper tasks = mock(CandidateIndexRefreshMapper.class);
    private final EmbeddingControlService embedding = mock(EmbeddingControlService.class);
    private final CandidateIndexRefreshService service = new CandidateIndexRefreshService(f.mapper, tasks, f.auth,
            new PublicationReadinessService(mock(DocumentPublicationMapper.class)), embedding,
            new ReviewCancellationService(f.mapper, f.events), f.receipts);
    private final CandidateRefreshTask task = new CandidateRefreshTask();

    @BeforeEach
    void setup() {
        task.setId(30);
        task.setVersionId(20);
        task.setExecutionGeneration(3);
        task.setClaimToken(5);
        when(tasks.lockTask(20)).thenReturn(task);
        when(tasks.restart(task, 4, 6)).thenReturn(1);
        when(tasks.resetVersion(eq(20L), anyLong())).thenReturn(1);
        when(tasks.event(30, 7, "refresh")).thenReturn(1);
        when(embedding.lockShared()).thenReturn(new EmbeddingControl("UNINITIALIZED", null, null, null, null, 4, null));
    }

    @Test
    void maintenanceOnlyQueuesNewCycleWithControlTaskDocumentLockOrder() {
        var receipt = service.refresh(7, 10, 20, 0, "refresh");
        assertThat(receipt.indexStatus()).isEqualTo("pending");
        assertThat(receipt.publicationEvidence()).isEqualTo(PublicationEvidence.NONE);
        var order = inOrder(embedding, tasks, f.mapper);
        order.verify(embedding).lockShared();
        order.verify(tasks).lockTask(20);
        order.verify(tasks).lockDeadLetters(30);
        order.verify(f.mapper).lockVersion(10, 20);
        order.verify(tasks).restart(task, 4, 6);
        order.verify(tasks).resetVersion(20, 0);
        assertThat(task.getClaimToken()).isEqualTo(5);
        // 新周期 token=6，旧执行持有的 token=5 不再匹配；不在这里执行解析或网络请求。
    }

    @Test
    void activeReviewIsCancelledAndIndexedCompletionRequiresAnExplicitNewRound() {
        var round = DocumentReviewRow.builder().id(UUID.randomUUID()).docId(10).versionId(20).roundNo(1)
                .submittedBy(8).submittedSpaceId(3).submittedAt(Instant.EPOCH).state(ReviewState.IN_REVIEW).build();
        when(f.mapper.latest(20)).thenReturn(round);
        when(f.mapper.lockReview(20, round.getId())).thenReturn(round);
        doAnswer(call -> {
            round.setState(call.getArgument(3));
            round.setDecidedAt(call.getArgument(5));
            return 1;
        }).when(f.mapper).decide(eq(20L), eq(round.getId()), eq(0L), eq(ReviewState.CANCELLED), eq(7L), any(), eq(null));
        var receipt = service.refresh(7, 10, 20, 0, "refresh");
        assertThat(receipt.reviewState()).isEqualTo(ReviewState.CANCELLED);
        assertThat(round.getState()).isEqualTo(ReviewState.CANCELLED);
        assertThat(receipt.stateVersion()).isEqualTo(2);
        var event = ArgumentCaptor.forClass(CollaborationEvent.class);
        verify(f.eventMapper).append(event.capture());
        assertThat(event.getValue().kind()).isEqualTo(CollaborationKind.REVIEW_CANCELLED);
        f.version.setStateVersion(receipt.stateVersion());
        f.version.setIndexStatus("indexed");
        when(f.mapper.bumpVersion(10, 20, receipt.stateVersion())).thenReturn(1);
        var next = f.service.submit(8, 10, 20, receipt.stateVersion(), "new-round");
        assertThat(next.roundNo()).isEqualTo(2);
        assertThat(next.reviewState()).isEqualTo(ReviewState.IN_REVIEW);
        assertThat(next.policy()).isEqualTo(PublicationPolicy.REVIEW_REQUIRED);
        assertThat(next.publicationEvidence()).isEqualTo(PublicationEvidence.NONE);
    }

    @Test
    void publishedAndObservedVersionsRejectCandidateRefreshWithoutRestart() {
        for (var evidence : new PublicationEvidence[]{PublicationEvidence.PUBLISHED, PublicationEvidence.OBSERVED_CURRENT}) {
            f.version.setPublicationEvidence(evidence);
            assertThatThrownBy(() -> service.refresh(7, 10, 20, 0, "refresh"))
                    .isInstanceOf(ReviewFailure.class).hasMessage("STATE_CONFLICT");
        }
        verify(tasks, never()).restart(any(), anyLong(), anyLong());
    }
}
