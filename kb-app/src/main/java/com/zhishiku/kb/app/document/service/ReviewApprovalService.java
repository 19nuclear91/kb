package com.zhishiku.kb.app.document.service;

import com.zhishiku.kb.app.collaboration.service.CollaborationEventService;
import com.zhishiku.kb.app.collaboration.service.CommandReceiptService;
import com.zhishiku.kb.app.document.mapper.DocumentPublicationMapper;
import com.zhishiku.kb.app.document.mapper.DocumentReviewMapper;
import com.zhishiku.kb.app.document.model.ReviewReceipt;
import com.zhishiku.kb.app.embedding.service.EmbeddingControlService;
import com.zhishiku.kb.common.review.CollaborationEvent;
import com.zhishiku.kb.common.review.CollaborationKind;
import com.zhishiku.kb.common.review.PublicationEvidence;
import com.zhishiku.kb.common.review.PublicationPolicy;
import com.zhishiku.kb.common.review.ReviewCommandBinding;
import com.zhishiku.kb.common.review.ReviewCommandDomain;
import com.zhishiku.kb.common.review.ReviewError;
import com.zhishiku.kb.common.review.ReviewFailure;
import com.zhishiku.kb.common.review.ReviewState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** 短事务内通过即发布，不调用模型、解析、通知或其他网络客户端。 */
@Service
public class ReviewApprovalService {
    private final DocumentReviewMapper mapper;
    private final DocumentPublicationMapper publication;
    private final ReviewAuthorizationService authorization;
    private final PublicationReadinessService readiness;
    private final EmbeddingControlService embedding;
    private final CommandReceiptService receipts;
    private final CollaborationEventService events;

    public ReviewApprovalService(DocumentReviewMapper mapper, DocumentPublicationMapper publication,
                                   ReviewAuthorizationService authorization, PublicationReadinessService readiness,
                                   EmbeddingControlService embedding, CommandReceiptService receipts, CollaborationEventService events) {
        this.mapper = mapper;
        this.publication = publication;
        this.authorization = authorization;
        this.readiness = readiness;
        this.embedding = embedding;
        this.receipts = receipts;
        this.events = events;
    }

    @Transactional(timeout = 10)
    public ReviewReceipt approve(long actor, long documentId, long versionId, UUID reviewId,
                                  long expectedVersion, String requestId) {
        var binding = ReviewCommandBinding.version(ReviewCommandDomain.APPROVE, requestId, actor, documentId, versionId,
                reviewId, expectedVersion, null);
        authorization.requireReviewer(actor, mapper.version(documentId, versionId), mapper.review(versionId, reviewId));
        String previous = receipts.claim(binding);
        if (previous != null) return receipts.read(previous, ReviewReceipt.class);
        var control = embedding.lockShared();
        var version = mapper.lockVersion(documentId, versionId);
        var round = mapper.lockReview(versionId, reviewId);
        authorization.requireReviewer(actor, version, round);
        if (version.getStateVersion() != expectedVersion || round.getState() != ReviewState.IN_REVIEW
                || round.getSubmittedSpaceId() != version.getSpaceId()
                || version.getPublicationPolicy() != PublicationPolicy.REVIEW_REQUIRED
                || version.getPublicationEvidence() != PublicationEvidence.NONE)
            throw new ReviewFailure(ReviewError.STATE_CONFLICT);
        readiness.require(version, control, ReviewState.APPROVED);
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (publication.publish(documentId, versionId, expectedVersion, "REVIEW_REQUIRED", control.activeProfileId(),
                control.activeFingerprint(), now) != 1
                || mapper.decide(versionId, reviewId, round.getStateVersion(), ReviewState.APPROVED, actor, now, null) != 1)
            throw new ReviewFailure(ReviewError.STATE_CONFLICT);
        events.append(CollaborationEvent.create(CollaborationKind.REVIEW_APPROVED, documentId, versionId, round.round(),
                actor, now, now), List.of(round.getSubmittedBy()));
        events.append(CollaborationEvent.create(CollaborationKind.VERSION_PUBLISHED, documentId, versionId, round.round(),
                actor, now, now), List.of());
        var receipt = new ReviewReceipt(documentId, versionId, reviewId, round.getRoundNo(), Math.incrementExact(expectedVersion),
                version.getIndexStatus(), version.getPublicationPolicy(), ReviewState.APPROVED, PublicationEvidence.PUBLISHED, now);
        receipts.complete(binding, receipt);
        return receipt;
    }
}
