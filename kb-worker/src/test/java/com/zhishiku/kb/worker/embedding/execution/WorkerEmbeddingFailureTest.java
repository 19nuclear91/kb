package com.zhishiku.kb.worker.embedding.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerEmbeddingFailureTest {
    @ParameterizedTest
    @EnumSource(WorkerEmbeddingFailure.Reason.class)
    void onlyBoundedReasonsCanBecomeDiagnostics(WorkerEmbeddingFailure.Reason reason) {
        var failure = new WorkerEmbeddingFailure(reason);
        var secret = new RuntimeException("https://supplier.invalid/private Bearer credential document-body");
        failure.addSuppressed(secret);
        assertThatThrownBy(() -> failure.initCause(secret)).isInstanceOf(IllegalStateException.class);
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getSuppressed()).isEmpty();
        assertThat(failure.getMessage()).isEqualTo("Worker embedding: " + reason.name());
        assertThat(failure.parseCode()).isEqualTo("EMBEDDING_" + reason.name());
        assertThat(failure.retryable()).isEqualTo(Set.of(WorkerEmbeddingFailure.Reason.TIMEOUT,
                WorkerEmbeddingFailure.Reason.NETWORK, WorkerEmbeddingFailure.Reason.UPSTREAM_RETRYABLE).contains(reason));
    }

    @Test
    void timeoutIsRecoverableFailureRatherThanLostOwnership() {
        assertThat(new WorkerEmbeddingFailure(WorkerEmbeddingFailure.Reason.TIMEOUT).controlStop()).isFalse();
        assertThat(new WorkerEmbeddingFailure(WorkerEmbeddingFailure.Reason.LEASE_LOST).controlStop()).isTrue();
        assertThat(new WorkerEmbeddingFailure(WorkerEmbeddingFailure.Reason.LEASE_UNKNOWN).controlStop()).isTrue();
        assertThat(new WorkerEmbeddingFailure(WorkerEmbeddingFailure.Reason.REQUEST_LIMIT).rebuildCode())
                .isEqualTo("INVALID_RESPONSE");
    }

    @Test
    void httpStatusHasExplicitRetrySemantics() {
        for (int status : new int[]{408, 429, 500, 503, 599}) {
            assertThat(WorkerEmbeddingFailure.http(status).retryable()).isTrue();
        }
        for (int status : new int[]{201, 204, 302, 400, 401, 403, 413}) {
            assertThat(WorkerEmbeddingFailure.http(status).retryable()).isFalse();
        }
        assertThat(WorkerEmbeddingFailure.http(401).reason()).isEqualTo(WorkerEmbeddingFailure.Reason.AUTH_REJECTED);
    }
}
