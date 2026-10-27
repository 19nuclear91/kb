package com.zhishiku.kb.app.document;

import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.mapper.DocumentReviewMapper;
import com.zhishiku.kb.app.document.model.DocumentReviewRow;
import com.zhishiku.kb.app.document.service.ReviewAuthorizationService;
import com.zhishiku.kb.app.document.service.ReviewEligibilityService;
import com.zhishiku.kb.common.review.CollaborationEvent;
import com.zhishiku.kb.common.review.CollaborationKind;
import com.zhishiku.kb.common.review.PublicationEvidence;
import com.zhishiku.kb.common.review.ReviewFailure;
import com.zhishiku.kb.common.review.ReviewState;
import com.zhishiku.kb.common.security.ResolvedPrincipal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentReviewDecisionTest {
    private static DocumentReviewRow active(ReviewCommandFixture f) {
        var round = DocumentReviewRow.builder().id(UUID.randomUUID()).docId(10).versionId(20).roundNo(2)
                .submittedBy(8).submittedSpaceId(3).state(ReviewState.IN_REVIEW).submittedAt(Instant.EPOCH).build();
        when(f.mapper.review(20, round.getId())).thenReturn(round);
        when(f.mapper.lockReview(20, round.getId())).thenReturn(round);
        when(f.mapper.decide(anyLong(), any(), anyLong(), any(), anyLong(), any(), any())).thenReturn(1);
        return round;
    }

    @Test
    void rejectionStoresOnlyPrivateNormalizedReasonAndNotifiesRoundSubmitter() {
        var f = new ReviewCommandFixture();
        var round = active(f);
        var receipt = f.service.reject(9, 10, 20, round.getId(), 0, "reject", "  原因\r\n第二行  ");
        assertThat(receipt.reviewState()).isEqualTo(ReviewState.REJECTED);
        assertThat(receipt.publicationEvidence()).isEqualTo(PublicationEvidence.NONE);
        verify(f.mapper).decide(eq(20L), eq(round.getId()), eq(0L), eq(ReviewState.REJECTED), eq(9L), any(), eq("原因\n第二行"));
        var fact = ArgumentCaptor.forClass(CollaborationEvent.class);
        verify(f.eventMapper).append(fact.capture());
        assertThat(fact.getValue().kind()).isEqualTo(CollaborationKind.REVIEW_REJECTED);
        assertThat(fact.getValue().toString()).doesNotContain("原因", "第二行");
        assertThat(receipt.toString()).doesNotContain("原因", "第二行");
        verify(f.eventMapper).recipient(fact.getValue().eventId(), 8);
        verify(f.eventMapper, never()).recipient(any(), eq(7L));
    }

    @Test
    void withdrawalIsOnlyForCurrentRoundSubmitterWithCurrentEdit() {
        var f = new ReviewCommandFixture();
        var round = active(f);
        assertThatThrownBy(() -> f.service.withdraw(7, 10, 20, round.getId(), 0, "not-submitter"))
                .isInstanceOf(ReviewFailure.class);
        verify(f.receipts, never()).claim(any());
        assertThat(f.service.withdraw(8, 10, 20, round.getId(), 0, "withdraw").reviewState())
                .isEqualTo(ReviewState.WITHDRAWN);
        verify(f.auth, times(2)).requireEdit(8, f.version);
        verify(f.eventMapper, never()).recipient(any(), anyLong());
    }

    @Test
    void oldRoundAndChangedSpaceConflictWithoutWritingDecision() {
        for (boolean moved : List.of(false, true)) {
            var f = new ReviewCommandFixture();
            var round = active(f);
            if (moved) round.setSubmittedSpaceId(2);
            else round.setState(ReviewState.REJECTED);
            assertThatThrownBy(() -> f.service.reject(9, 10, 20, round.getId(), 0, "old", "原因"))
                    .isInstanceOf(ReviewFailure.class).hasMessage("STATE_CONFLICT");
            verify(f.mapper, never()).decide(anyLong(), any(), anyLong(), any(), anyLong(), any(), any());
        }
    }

    @Test
    void privateRoundReadNeverReturnsReasonToOrdinaryPublishedReader() {
        var f = new ReviewCommandFixture();
        var round = active(f);
        round.setState(ReviewState.REJECTED);
        round.setReason("私有意见");
        f.version.setPublicationEvidence(PublicationEvidence.PUBLISHED);
        when(f.mapper.latest(20)).thenReturn(round);
        assertThatThrownBy(() -> f.service.privateRounds(9, 10, 20, Long.MAX_VALUE, 20))
                .isInstanceOf(ReviewFailure.class).hasMessage("NOT_FOUND");
        verify(f.mapper, never()).privateRounds(anyLong(), anyLong(), eq(20));
        when(f.auth.privateAccess(7, f.version, round)).thenReturn(true);
        when(f.mapper.privateRounds(20, Long.MAX_VALUE, 20)).thenReturn(List.of(round));
        assertThat(f.service.privateRounds(7, 10, 20, Long.MAX_VALUE, 20)).singleElement()
                .satisfies(view -> assertThat(view.reason()).isEqualTo("私有意见"));
    }

    @Test
    void publishedContentDoesNotMakePrivateOpinionsPublicAndAuthorizationIsCurrent() {
        var f = new ReviewCommandFixture();
        var identities = mock(IdentityService.class);
        var permissionMapper = mock(PermissionMapper.class);
        var reviews = mock(DocumentReviewMapper.class);
        var eligibility = mock(ReviewEligibilityService.class);
        when(identities.resolvePrincipal(anyLong())).thenAnswer(call ->
                new ResolvedPrincipal(call.getArgument(0), 1L, (short) 2, List.of(), List.of(1L)));
        when(permissionMapper.findVisibleSpaceIds(anyList(), eq("view"))).thenReturn(List.of(3L));
        when(reviews.hasPermission(eq(10L), anyList(), anyList(), eq((short) 2), eq("view"))).thenReturn(true);
        var authorization = new ReviewAuthorizationService(new PermissionService(permissionMapper, identities), reviews, eligibility);
        f.version.setPublicationEvidence(PublicationEvidence.PUBLISHED);
        assertThat(authorization.privateAccess(9, f.version, null)).isFalse();
        assertThat(authorization.privateAccess(7, f.version, null)).isTrue();
        when(reviews.hasSubmitted(20, 8)).thenReturn(true);
        assertThat(authorization.privateAccess(8, f.version, null)).isTrue();
        when(reviews.hasPermission(eq(10L), anyList(), anyList(), eq((short) 2), eq("view"))).thenReturn(false);
        assertThat(authorization.privateAccess(7, f.version, null)).isFalse();
        assertThat(authorization.privateAccess(8, f.version, null)).isFalse();
    }
}
