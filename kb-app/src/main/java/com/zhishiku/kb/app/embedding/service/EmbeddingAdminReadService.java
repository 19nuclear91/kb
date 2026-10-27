package com.zhishiku.kb.app.embedding.service;

import com.zhishiku.kb.app.embedding.mapper.EmbeddingProfileMapper;
import com.zhishiku.kb.app.embedding.model.EmbeddingProfileView;
import com.zhishiku.kb.app.embedding.model.EmbeddingViews;
import com.zhishiku.kb.app.search.embedding.EmbeddingInstanceReadiness;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class EmbeddingAdminReadService {
    private final EmbeddingProfileMapper profiles;
    private final EmbeddingControlService control;
    private final EmbeddingReadinessService readiness;
    private final EmbeddingInstanceReadiness instance;

    public EmbeddingAdminReadService(EmbeddingProfileMapper profiles, EmbeddingControlService control,
                                     EmbeddingReadinessService readiness, EmbeddingInstanceReadiness instance) {
        this.profiles = profiles;
        this.control = control;
        this.readiness = readiness;
        this.instance = instance;
    }

    public List<EmbeddingViews.Profile> profiles() {
        return profiles.list().stream().map(p -> new EmbeddingViews.Profile(EmbeddingProfileView.from(p),
                p.fingerprint(), readiness.ready(p))).toList();
    }

    public EmbeddingViews.Status status() {
        EmbeddingControl current;
        try {
            current = control.current();
        } catch (RuntimeException unavailable) {
            instance.controlUnavailable();
            throw unavailable;
        }
        return new EmbeddingViews.Status(current.state(), current.activeProfileId(), current.targetProfileId(),
                current.epoch(), current.runId(), current.active() ? null : "EMBEDDING_MAINTENANCE",
                instance.observe(current));
    }

    public EmbeddingViews.Run run(UUID id) {
        var run = control.run(id);
        if (run == null) throw ApiException.notFound("embedding run not found");
        String error = run.errorCode();
        if (error != null && !Set.of("MODEL_UNAVAILABLE", "INVALID_RESPONSE", "LEASE_LOST", "CONTENT_CHANGED",
                "MISSING_CHUNKS", "COVERAGE_INVALID", "READINESS_EXPIRED", "RETRY_EXHAUSTED", "DATABASE_ERROR").contains(error)) {
            error = "INTERNAL_ERROR";
        }
        return new EmbeddingViews.Run(run.runId(), run.targetProfileId(), run.state(), run.version(),
                run.totalCount(), run.succeededCount(), run.skippedCount(), run.nextAttemptAt(), error);
    }
}
