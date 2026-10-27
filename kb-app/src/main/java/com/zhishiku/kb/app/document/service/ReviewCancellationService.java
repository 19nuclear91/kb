package com.zhishiku.kb.app.document.service;

import com.zhishiku.kb.app.collaboration.service.CollaborationEventService;
import com.zhishiku.kb.app.document.mapper.DocumentReviewMapper;
import com.zhishiku.kb.app.document.model.DocumentReviewRow;
import com.zhishiku.kb.app.document.model.ReviewVersionState;
import com.zhishiku.kb.common.review.CollaborationEvent;
import com.zhishiku.kb.common.review.CollaborationKind;
import com.zhishiku.kb.common.review.ReviewError;
import com.zhishiku.kb.common.review.ReviewFailure;
import com.zhishiku.kb.common.review.ReviewState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** 调用方必须已取得文档／版本锁；不反向获取 control 或 task 锁。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ReviewCancellationService {
    private final DocumentReviewMapper mapper;
    private final CollaborationEventService events;

    public ReviewCancellationService(DocumentReviewMapper mapper, CollaborationEventService events) {
        this.mapper = mapper;
        this.events = events;
    }

    public DocumentReviewRow cancelVersion(ReviewVersionState version, long actor) {
        var latest = mapper.latest(version.getVersionId());
        if (latest == null || latest.getState() != ReviewState.IN_REVIEW) return null;
        var round = mapper.lockReview(version.getVersionId(), latest.getId());
        if (round == null || round.getState() != ReviewState.IN_REVIEW) throw new ReviewFailure(ReviewError.STATE_CONFLICT);
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (mapper.decide(version.getVersionId(), round.getId(), round.getStateVersion(), ReviewState.CANCELLED, actor, now, null) != 1
                || mapper.bumpVersion(version.getDocumentId(), version.getVersionId(), version.getStateVersion()) != 1)
            throw new ReviewFailure(ReviewError.STATE_CONFLICT);
        events.append(CollaborationEvent.create(CollaborationKind.REVIEW_CANCELLED, version.getDocumentId(), version.getVersionId(),
                round.round(), actor, now, now), List.of(round.getSubmittedBy()));
        version.setStateVersion(Math.incrementExact(version.getStateVersion()));
        return round;
    }

    public void cancelDocument(long documentId, long actor) {
        for (var version : mapper.lockVersionsForDocument(documentId)) cancelVersion(version, actor);
    }
}
