package com.zhishiku.kb.app.document.service;

import com.zhishiku.kb.app.collaboration.service.CollaborationEventService;
import com.zhishiku.kb.app.document.mapper.DocumentPublicationMapper;
import com.zhishiku.kb.app.document.mapper.DocumentReviewMapper;
import com.zhishiku.kb.app.embedding.service.EmbeddingControlService;
import com.zhishiku.kb.common.conversation.SourceKey;
import com.zhishiku.kb.common.review.CollaborationEvent;
import com.zhishiku.kb.common.review.CollaborationKind;
import com.zhishiku.kb.common.review.PublicationEvidence;
import com.zhishiku.kb.common.review.PublicationPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** 单版本短事务巡检，不在一批文档之间持锁，不需要通知消费者。 */
@Service
public class AutoPublicationTransaction {
    private final EmbeddingControlService embedding;
    private final DocumentReviewMapper versions;
    private final DocumentPublicationMapper publication;
    private final PublicationReadinessService readiness;
    private final CollaborationEventService events;

    public AutoPublicationTransaction(EmbeddingControlService embedding, DocumentReviewMapper versions,
                                       DocumentPublicationMapper publication, PublicationReadinessService readiness,
                                       CollaborationEventService events) {
        this.embedding = embedding;
        this.versions = versions;
        this.publication = publication;
        this.readiness = readiness;
        this.events = events;
    }

    @Transactional(timeout = 10)
    public boolean publish(SourceKey source) {
        var control = embedding.lockShared();
        if (control == null || !control.active()) return false;
        var version = versions.lockVersion(source.documentId(), source.versionId());
        if (version == null || !version.live() || version.getPublicationPolicy() != PublicationPolicy.AUTO
                || version.getPublicationEvidence() != PublicationEvidence.NONE || !"indexed".equals(version.getIndexStatus())
                || !readiness.ready(version, control, null)) return false;
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (publication.publish(source.documentId(), source.versionId(), version.getStateVersion(), "AUTO",
                control.activeProfileId(), control.activeFingerprint(), now) != 1) return false;
        events.append(CollaborationEvent.create(CollaborationKind.VERSION_PUBLISHED, source.documentId(), source.versionId(),
                null, null, now, null), List.of());
        return true;
    }
}
