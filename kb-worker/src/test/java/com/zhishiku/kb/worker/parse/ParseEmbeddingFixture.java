package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingConnection;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import com.zhishiku.kb.common.embedding.EmbeddingProfileDefinition;
import com.zhishiku.kb.common.embedding.EmbeddingRole;
import com.zhishiku.kb.common.embedding.LocalProfileEmbeddingClient;
import com.zhishiku.kb.worker.parse.model.ParseExecution;
import com.zhishiku.kb.worker.parse.model.ParseTask;
import com.zhishiku.kb.worker.parse.model.WorkerPublicationState;
import com.zhishiku.kb.common.review.PublicationPolicy;
import com.zhishiku.kb.common.review.PublicationProtocol;
import com.zhishiku.kb.common.review.PublicationEvidence;

import java.time.OffsetDateTime;
import java.util.List;

final class ParseEmbeddingFixture {
    static final EmbeddingProfile PROFILE = new EmbeddingProfile("local-v1", "local", "kb/local",
            "local-hash-v1", "1", 1024, "cosine", "identity-v1", "identity-v1",
            "query", "document", "local-hash-v1");

    static EmbeddingClientRegistry clients() {
        return new EmbeddingClientRegistry(List.of(new EmbeddingProfileDefinition(PROFILE,
                new EmbeddingConnection(null, null, 30, 16))), EmbeddingRole.DOCUMENT,
                definition -> new LocalProfileEmbeddingClient(definition, EmbeddingRole.DOCUMENT));
    }

    static ParseTask task() {
        return task(1L, 2L, "key", "a.txt", 100L, "text/plain");
    }

    static ParseTask task(long id, long versionId, String key, String fileName, long size, String mime) {
        return new ParseTask(id, versionId, 1, 3, OffsetDateTime.now().plusMinutes(5), "owner",
                null, null, null, null, null, 0L, key, fileName, size, mime,
                PROFILE.profileId(), PROFILE.fingerprint(), 4L, 9L);
    }

    static ParseExecution execution(ParseTask task) {
        return new ParseExecution(task.id(), task.versionId(), task.leaseOwner(), task.embeddingEpoch(),
                task.claimToken(), PROFILE);
    }

    static EmbeddingControl active() {
        return new EmbeddingControl("ACTIVE", PROFILE.profileId(), PROFILE.fingerprint(), null, null, 4L, null);
    }

    static float[] vector() {
        float[] vector = new float[1024];
        vector[0] = 0.1f;
        return vector;
    }

    static WorkerPublicationState publication() {
        return WorkerPublicationState.builder().documentId(42).versionId(2).versionNo(1).documentStatus("published")
                .publicationPolicy(PublicationPolicy.AUTO).publicationProtocol(PublicationProtocol.VERSIONED_REVIEW)
                .publicationEvidence(PublicationEvidence.NONE).uploaderUserId(7L).build();
    }
}
