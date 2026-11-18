package com.zhishiku.kb.app.document.suggestion;

import com.zhishiku.kb.app.document.metadata.service.MetadataException;
import com.zhishiku.kb.common.suggestion.SuggestionProtocol;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Service
public class SuggestionRequests {
    public record Start(UUID id, UUID execution, String token) {
        @Override
        public String toString() {
            return "整理请求 " + id;
        }
    }

    public record Stop(SuggestionProtocol.Key key) {
    }

    private final SuggestionAccess access;
    private final SuggestionStore store;
    private final ApplicationEventPublisher events;
    private final boolean enabled;
    private final Clock clock;

    @Autowired
    public SuggestionRequests(SuggestionAccess access, SuggestionStore store, ApplicationEventPublisher events,
                              @Value("${kb.metadata-suggestions.enabled:false}") boolean enabled) {
        this(access, store, events, enabled, Clock.systemUTC());
    }

    SuggestionRequests(SuggestionAccess access, SuggestionStore store, ApplicationEventPublisher events,
                       boolean enabled, Clock clock) {
        this.access = access;
        this.store = store;
        this.events = events;
        this.enabled = enabled;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SuggestionJob.View create(String token, long doc, Long source, long metadataVersion, UUID id) {
        var old = store.find(id);
        var current = access.current(token, doc);
        if (old != null) {
            return replay(current, old, source, metadataVersion);
        }
        if (!enabled) {
            throw new MetadataException(MetadataException.Reason.SUGGESTIONS_DISABLED);
        }
        if (current.versionId() == null) {
            throw new MetadataException(MetadataException.Reason.NO_PUBLISHED_VERSION);
        }
        if (!Objects.equals(source, current.versionId())) {
            throw new MetadataException(MetadataException.Reason.SOURCE_CHANGED);
        }
        if (metadataVersion != current.metadataVersion()) {
            throw new MetadataException(MetadataException.Reason.METADATA_VERSION_CONFLICT);
        }
        UUID execution = UUID.randomUUID();
        boolean first = store.insert(id, execution, current, OffsetDateTime.now(clock).plusMinutes(2),
                com.zhishiku.kb.common.conversation.RequestSignature.digest(token));
        var registered = store.find(id);
        if (first) {
            events.publishEvent(new Start(id, execution, token));
        } else {
            current = access.current(token, doc);
        }
        return replay(current, registered, source, metadataVersion);
    }

    private SuggestionJob.View replay(SuggestionAccess.Context current, SuggestionJob job, Long version, long metadata) {
        if (job.actorId() != current.actorId() || job.docId() != current.docId()
                || !Objects.equals(version, job.sourceVersionId()) || job.metadataVersion() != metadata) {
            throw new MetadataException(MetadataException.Reason.REQUEST_MISMATCH);
        }
        return project(current, job);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SuggestionJob.View get(String token, long doc, UUID id) {
        var current = access.current(token, doc);
        return project(current, store.find(id));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SuggestionJob.View cancel(String token, long doc, UUID id) {
        var current = access.current(token, doc);
        var job = store.lockOwned(id, current.actorId(), doc);
        if (job == null) {
            throw new MetadataException(MetadataException.Reason.NOT_FOUND);
        }
        current = access.current(token, doc);
        if (job.running()) {
            owned(current, job);
            if (store.terminate(id, "CANCELLED", "CANCELLED")) {
                events.publishEvent(new Stop(SuggestionExecution.key(job)));
            }
            job = store.find(id);
        }
        return project(current, job);
    }

    private SuggestionJob owned(SuggestionAccess.Context current, SuggestionJob job) {
        if (job == null || job.docId() != current.docId() || job.actorId() != current.actorId()) {
            throw new MetadataException(MetadataException.Reason.NOT_FOUND);
        }
        access.requireSource(current.actorId(), current.docId(), job.sourceVersionId());
        return job;
    }

    private SuggestionJob.View project(SuggestionAccess.Context current, SuggestionJob job) {
        owned(current, job);
        if (!"COMPLETED".equals(job.generationStatus())) {
            return job.view(job.reasonCode(), null, null);
        }
        String conflict = job.conflict(current);
        if (conflict != null) {
            return job.view(conflict, null, null);
        }
        var result = store.result(job.requestId(), OffsetDateTime.now(clock));
        if (result == null) {
            return job.view("SUGGESTION_EXPIRED", null, null);
        }
        return job.view(null, result.expiresAt(), store.decode(result));
    }

    @Scheduled(fixedDelayString = "${kb.metadata-suggestions.cleanup-ms:60000}")
    public void cleanExpired() {
        store.clean(OffsetDateTime.now(clock));
    }
}
