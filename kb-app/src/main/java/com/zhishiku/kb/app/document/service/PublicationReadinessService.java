package com.zhishiku.kb.app.document.service;

import com.zhishiku.kb.app.document.mapper.DocumentPublicationMapper;
import com.zhishiku.kb.app.document.model.ReviewVersionState;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.review.PublicationCheck;
import com.zhishiku.kb.common.review.PublicationGate;
import com.zhishiku.kb.common.review.ReviewError;
import com.zhishiku.kb.common.review.ReviewFailure;
import com.zhishiku.kb.common.review.ReviewState;
import com.zhishiku.kb.common.review.VersionPublication;
import org.springframework.stereotype.Service;
import java.util.Objects;

@Service
public class PublicationReadinessService {
    private final DocumentPublicationMapper mapper;

    public PublicationReadinessService(DocumentPublicationMapper mapper) {
        this.mapper = mapper;
    }

    public void require(ReviewVersionState version, EmbeddingControl control, ReviewState decision) {
        if (control == null) throw new ReviewFailure(ReviewError.SERVICE_UNAVAILABLE);
        if (!control.active()) throw new ReviewFailure(ReviewError.EMBEDDING_MAINTENANCE);
        var integrity = mapper.integrity(version.getDocumentId(), version.getVersionId(), version.getIndexedProfileId());
        if (integrity == null) throw new ReviewFailure(ReviewError.SERVICE_UNAVAILABLE);
        var origin = new VersionPublication(version.getPublicationPolicy(), version.getPublicationProtocol(),
                version.getUploaderUserId(), version.getPublicationEvidence(), version.getPublishedAt());
        PublicationGate.require(origin, decision, new PublicationCheck(control.active(), control.activeProfileId(),
                control.activeFingerprint(), control.epoch(), version.getIndexedProfileId(), version.getIndexedFingerprint(),
                version.getIndexedEpoch(), integrity.complete(version.getIndexedChunkCount()), version.getIndexStatus(),
                version.live(), version.getVersionNo(), version.getCurrentVersionNo()));
    }

    public boolean ready(ReviewVersionState version, EmbeddingControl control, ReviewState decision) {
        try {
            require(version, control, decision);
            return true;
        } catch (ReviewFailure conflict) {
            if (conflict.error() == ReviewError.SERVICE_UNAVAILABLE) throw conflict;
            return false;
        }
    }

    public boolean needsRefresh(ReviewVersionState version, EmbeddingControl control) {
        if (control == null) throw new ReviewFailure(ReviewError.SERVICE_UNAVAILABLE);
        if (!control.active() || version.getIndexedProfileId() == null || version.getIndexedEpoch() == null
                || version.getIndexedEpoch() > control.epoch() || version.getIndexedEpoch() < 0
                || !Objects.equals(version.getIndexedProfileId(), control.activeProfileId())
                || !Objects.equals(version.getIndexedFingerprint(), control.activeFingerprint())) return true;
        var integrity = mapper.integrity(version.getDocumentId(), version.getVersionId(), version.getIndexedProfileId());
        if (integrity == null) throw new ReviewFailure(ReviewError.SERVICE_UNAVAILABLE);
        return !integrity.complete(version.getIndexedChunkCount());
    }
}
