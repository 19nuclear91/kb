package com.zhishiku.kb.app.document.service;

import com.zhishiku.kb.app.collaboration.service.CollaborationEventService;
import com.zhishiku.kb.app.collaboration.service.CommandReceiptService;
import com.zhishiku.kb.app.document.mapper.DocumentReviewMapper;
import com.zhishiku.kb.app.document.model.DocumentReviewRow;
import com.zhishiku.kb.app.document.model.ReviewReceipt;
import com.zhishiku.kb.app.document.model.PrivateReviewView;
import com.zhishiku.kb.app.document.model.ReviewVersionState;
import com.zhishiku.kb.common.review.CollaborationEvent;
import com.zhishiku.kb.common.review.CollaborationKind;
import com.zhishiku.kb.common.review.PublicationEvidence;
import com.zhishiku.kb.common.review.PublicationPolicy;
import com.zhishiku.kb.common.review.ReviewCommandBinding;
import com.zhishiku.kb.common.review.ReviewCommandDomain;
import com.zhishiku.kb.common.review.ReviewError;
import com.zhishiku.kb.common.review.ReviewFailure;
import com.zhishiku.kb.common.review.ReviewRound;
import com.zhishiku.kb.common.review.ReviewReason;
import com.zhishiku.kb.common.review.ReviewState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class DocumentReviewService {
    private final DocumentReviewMapper mapper;
    private final ReviewAuthorizationService authorization;
    private final ReviewEligibilityService eligibility;
    private final CommandReceiptService receipts;
    private final CollaborationEventService events;
    private final Clock clock = Clock.systemUTC();

    public DocumentReviewService(DocumentReviewMapper mapper, ReviewAuthorizationService authorization,
                                  ReviewEligibilityService eligibility, CommandReceiptService receipts,
                                  CollaborationEventService events) {
        this.mapper = mapper;
        this.authorization = authorization;
        this.eligibility = eligibility;
        this.receipts = receipts;
        this.events = events;
    }

    @Transactional(timeout = 10)
    public ReviewReceipt submit(long actor, long documentId, long versionId, long expectedVersion, String requestId) {
        var binding = ReviewCommandBinding.version(ReviewCommandDomain.SUBMIT, requestId, actor, documentId, versionId,
                null, expectedVersion, null);
        authorization.requireEdit(actor, mapper.version(documentId, versionId));
        String previous = receipts.claim(binding);
        if (previous != null) return receipts.read(previous, ReviewReceipt.class);
        var version = mapper.lockVersion(documentId, versionId);
        authorization.requireEdit(actor, version);
        if (version.getStateVersion() != expectedVersion || !"indexed".equals(version.getIndexStatus())
                || version.getPublicationPolicy() != PublicationPolicy.REVIEW_REQUIRED
                || version.getPublicationEvidence() != PublicationEvidence.NONE)
            throw new ReviewFailure(ReviewError.STATE_CONFLICT);
        var last = mapper.latest(versionId);
        if (last != null && last.getState() == ReviewState.IN_REVIEW)
            throw new ReviewFailure(ReviewError.STATE_CONFLICT);
        var pool = eligibility.requirePool(documentId, version.getUploaderUserId(), actor);
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        ReviewRound round;
        try {
            round = ReviewRound.start(documentId, version.getDocumentId(), versionId,
                    last == null ? null : last.round(), actor, version.getSpaceId(), now);
        } catch (IllegalStateException conflict) {
            throw new ReviewFailure(ReviewError.STATE_CONFLICT);
        }
        if (mapper.insert(DocumentReviewRow.from(round)) != 1
                || mapper.bumpVersion(documentId, versionId, expectedVersion) != 1)
            throw new ReviewFailure(ReviewError.STATE_CONFLICT);
        events.append(CollaborationEvent.create(CollaborationKind.REVIEW_SUBMITTED, documentId, versionId,
                round, actor, now, null), pool);
        var receipt = new ReviewReceipt(documentId, versionId, round.id(), round.roundNo(), Math.incrementExact(expectedVersion),
                version.getIndexStatus(), version.getPublicationPolicy(), round.state(), version.getPublicationEvidence(), now);
        receipts.complete(binding, receipt);
        return receipt;
    }

    @Transactional(timeout = 10)
    public ReviewReceipt reject(long actor, long documentId, long versionId, UUID reviewId,
                                 long expectedVersion, String requestId, String reason) {
        String normalized = ReviewReason.normalize(reason);
        return decide(actor, documentId, versionId, reviewId, expectedVersion, requestId, normalized, ReviewState.REJECTED);
    }

    @Transactional(timeout = 10)
    public ReviewReceipt withdraw(long actor, long documentId, long versionId, UUID reviewId,
                                   long expectedVersion, String requestId) {
        return decide(actor, documentId, versionId, reviewId, expectedVersion, requestId, null, ReviewState.WITHDRAWN);
    }

    private ReviewReceipt decide(long actor, long documentId, long versionId, UUID reviewId,
                                  long expectedVersion, String requestId, String reason, ReviewState decision) {
        boolean withdraw = decision == ReviewState.WITHDRAWN;
        var domain = withdraw ? ReviewCommandDomain.WITHDRAW : ReviewCommandDomain.REJECT;
        var binding = ReviewCommandBinding.version(domain, requestId, actor, documentId, versionId,
                reviewId, expectedVersion, reason);
        var version = mapper.version(documentId, versionId);
        var round = mapper.review(versionId, reviewId);
        authorizeDecision(actor, version, round, withdraw);
        String replay = receipts.claim(binding);
        if (replay != null) return receipts.read(replay, ReviewReceipt.class);
        version = mapper.lockVersion(documentId, versionId);
        round = mapper.lockReview(versionId, reviewId);
        authorizeDecision(actor, version, round, withdraw);
        if (version.getStateVersion() != expectedVersion || round.getState() != ReviewState.IN_REVIEW
                || round.getSubmittedSpaceId() != version.getSpaceId()
                || version.getPublicationEvidence() != PublicationEvidence.NONE)
            throw new ReviewFailure(ReviewError.STATE_CONFLICT);
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (mapper.decide(versionId, reviewId, round.getStateVersion(), decision, actor, now, reason) != 1
                || mapper.bumpVersion(documentId, versionId, expectedVersion) != 1)
            throw new ReviewFailure(ReviewError.STATE_CONFLICT);
        events.append(CollaborationEvent.create(withdraw ? CollaborationKind.REVIEW_WITHDRAWN : CollaborationKind.REVIEW_REJECTED,
                documentId, versionId, round.round(), actor, now, now), List.of(round.getSubmittedBy()));
        var receipt = new ReviewReceipt(documentId, versionId, reviewId, round.getRoundNo(), Math.incrementExact(expectedVersion),
                version.getIndexStatus(), version.getPublicationPolicy(), decision, version.getPublicationEvidence(), now);
        receipts.complete(binding, receipt);
        return receipt;
    }

    private void authorizeDecision(long actor, ReviewVersionState version, DocumentReviewRow round, boolean withdraw) {
        if (version == null || round == null || round.getDocId() != version.getDocumentId()
                || round.getVersionId() != version.getVersionId()) throw new ReviewFailure(ReviewError.NOT_FOUND);
        if (withdraw) {
            authorization.requireEdit(actor, version);
            if (round.getSubmittedBy() != actor) throw new ReviewFailure(ReviewError.NOT_FOUND);
        } else {
            authorization.requireReviewer(actor, version, round);
        }
    }

    @Transactional(readOnly = true, timeout = 10)
    public List<PrivateReviewView> privateRounds(long actor, long documentId, long versionId, long beforeRound, int limit) {
        if (limit < 1 || limit > 100 || beforeRound <= 0) throw new ReviewFailure(ReviewError.INVALID_REQUEST);
        var version = mapper.version(documentId, versionId);
        if (!authorization.privateAccess(actor, version, mapper.latest(versionId)))
            throw new ReviewFailure(ReviewError.NOT_FOUND);
        return mapper.privateRounds(versionId, beforeRound, limit).stream().map(PrivateReviewView::from).toList();
    }
}
