package com.zhishiku.kb.app.document.service;

import com.zhishiku.kb.app.collaboration.service.CommandReceiptService;
import com.zhishiku.kb.app.document.mapper.CandidateIndexRefreshMapper;
import com.zhishiku.kb.app.document.mapper.DocumentReviewMapper;
import com.zhishiku.kb.app.document.model.ReviewReceipt;
import com.zhishiku.kb.app.embedding.service.EmbeddingControlService;
import com.zhishiku.kb.common.review.CandidateRefreshCycle;
import com.zhishiku.kb.common.review.PublicationEvidence;
import com.zhishiku.kb.common.review.ReviewCommandBinding;
import com.zhishiku.kb.common.review.ReviewCommandDomain;
import com.zhishiku.kb.common.review.ReviewError;
import com.zhishiku.kb.common.review.ReviewFailure;
import com.zhishiku.kb.common.review.ReviewState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class CandidateIndexRefreshService {
    private final DocumentReviewMapper versions;
    private final CandidateIndexRefreshMapper tasks;
    private final ReviewAuthorizationService authorization;
    private final PublicationReadinessService readiness;
    private final EmbeddingControlService embedding;
    private final ReviewCancellationService cancellation;
    private final CommandReceiptService receipts;

    public CandidateIndexRefreshService(DocumentReviewMapper versions, CandidateIndexRefreshMapper tasks,
                                          ReviewAuthorizationService authorization, PublicationReadinessService readiness,
                                          EmbeddingControlService embedding, ReviewCancellationService cancellation,
                                          CommandReceiptService receipts) {
        this.versions = versions;
        this.tasks = tasks;
        this.authorization = authorization;
        this.readiness = readiness;
        this.embedding = embedding;
        this.cancellation = cancellation;
        this.receipts = receipts;
    }

    @Transactional(timeout = 10)
    public ReviewReceipt refresh(long actor, long documentId, long versionId, long expectedVersion, String requestId) {
        var binding = ReviewCommandBinding.version(ReviewCommandDomain.INDEX_REFRESH, requestId, actor, documentId, versionId,
                null, expectedVersion, null);
        authorization.requireEdit(actor, versions.version(documentId, versionId));
        String previous = receipts.claim(binding);
        if (previous != null) return receipts.read(previous, ReviewReceipt.class);
        var control = embedding.lockShared();
        var task = tasks.lockTask(versionId);
        if (task == null || task.getVersionId() != versionId) throw new ReviewFailure(ReviewError.STATE_CONFLICT);
        tasks.lockDeadLetters(task.getId());
        var version = versions.lockVersion(documentId, versionId);
        authorization.requireEdit(actor, version);
        if (version.getStateVersion() != expectedVersion || version.getPublicationEvidence() != PublicationEvidence.NONE
                || !"indexed".equals(version.getIndexStatus()) || !readiness.needsRefresh(version, control))
            throw new ReviewFailure(ReviewError.STATE_CONFLICT);
        var cycle = new CandidateRefreshCycle(task.getExecutionGeneration(), task.getClaimToken()).next();
        var cancelled = cancellation.cancelVersion(version, actor);
        tasks.closeDeadLetters(task.getId(), actor);
        if (tasks.restart(task, cycle.generation(), cycle.claimToken()) != 1
                || tasks.resetVersion(versionId, version.getStateVersion()) != 1
                || tasks.event(task.getId(), actor, requestId) != 1) throw new ReviewFailure(ReviewError.STATE_CONFLICT);
        var receipt = new ReviewReceipt(documentId, versionId, cancelled == null ? null : cancelled.getId(),
                cancelled == null ? null : cancelled.getRoundNo(), Math.incrementExact(version.getStateVersion()),
                "pending", version.getPublicationPolicy(), cancelled == null ? null : ReviewState.CANCELLED,
                PublicationEvidence.NONE, Instant.now().truncatedTo(ChronoUnit.MICROS));
        receipts.complete(binding, receipt);
        return receipt;
    }
}
