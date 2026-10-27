package com.zhishiku.kb.app.document;

import com.zhishiku.kb.app.collaboration.mapper.CollaborationEventMapper;
import com.zhishiku.kb.app.collaboration.service.CollaborationEventService;
import com.zhishiku.kb.app.collaboration.service.CommandReceiptService;
import com.zhishiku.kb.app.document.mapper.DocumentReviewEligibilityMapper;
import com.zhishiku.kb.app.document.mapper.DocumentReviewMapper;
import com.zhishiku.kb.app.document.model.ReviewVersionState;
import com.zhishiku.kb.app.document.model.ReviewerQualification;
import com.zhishiku.kb.app.document.service.DocumentReviewService;
import com.zhishiku.kb.app.document.service.ReviewAuthorizationService;
import com.zhishiku.kb.app.document.service.ReviewEligibilityService;
import com.zhishiku.kb.common.review.PublicationEvidence;
import com.zhishiku.kb.common.review.PublicationPolicy;
import com.zhishiku.kb.common.review.PublicationProtocol;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 审核命令独立测试的仓储与鉴权替身，不启动事务或外部服务。 */
class ReviewCommandFixture {
    final DocumentReviewMapper mapper = mock(DocumentReviewMapper.class);
    final DocumentReviewEligibilityMapper eligible = mock(DocumentReviewEligibilityMapper.class);
    final ReviewAuthorizationService auth = mock(ReviewAuthorizationService.class);
    final CommandReceiptService receipts = mock(CommandReceiptService.class);
    final CollaborationEventMapper eventMapper = mock(CollaborationEventMapper.class);
    final CollaborationEventService events = new CollaborationEventService(eventMapper);
    final ReviewEligibilityService eligibility = new ReviewEligibilityService(eligible);
    final DocumentReviewService service = new DocumentReviewService(mapper, auth, eligibility, receipts, events);
    final ReviewVersionState version = ReviewVersionState.builder().documentId(10).versionId(20).spaceId(3)
            .documentStatus("draft").versionNo(1).uploaderUserId(7L).publicationPolicy(PublicationPolicy.REVIEW_REQUIRED)
            .publicationProtocol(PublicationProtocol.VERSIONED_REVIEW).publicationEvidence(PublicationEvidence.NONE)
            .indexStatus("indexed").build();

    ReviewCommandFixture() {
        when(mapper.version(10, 20)).thenReturn(version);
        when(mapper.lockVersion(10, 20)).thenReturn(version);
        when(mapper.insert(any())).thenReturn(1);
        when(mapper.bumpVersion(10, 20, 0)).thenReturn(1);
        when(eventMapper.append(any())).thenReturn(1);
        when(eventMapper.recipient(any(), anyLong())).thenReturn(1);
        when(eligible.candidates(10, 7L, 8L, null)).thenReturn(List.of(reviewer(9), reviewer(9), reviewer(10)));
    }

    static ReviewerQualification reviewer(long id) {
        return new ReviewerQualification(id, true, true, (short) 2, (short) 2, true, true);
    }
}
