package com.zhishiku.kb.worker.embedding.execution;

import java.util.Objects;

/** 仅携带有限原因；不保存远程异常、地址、正文或凭据。 */
public final class WorkerEmbeddingFailure extends RuntimeException {
    private final Reason reason;

    public WorkerEmbeddingFailure(Reason reason) {
        super("Worker embedding: " + Objects.requireNonNull(reason, "reason").name(), null, false, true);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public boolean retryable() {
        return reason == Reason.TIMEOUT || reason == Reason.NETWORK || reason == Reason.UPSTREAM_RETRYABLE;
    }

    public boolean controlStop() {
        return reason == Reason.LEASE_LOST || reason == Reason.LEASE_UNKNOWN || reason == Reason.SHUTDOWN;
    }

    public String parseCode() {
        return "EMBEDDING_" + reason.name();
    }

    public String rebuildCode() {
        return switch (reason) {
            case REQUEST_LIMIT, RESPONSE_LIMIT, INVALID_RESPONSE -> "INVALID_RESPONSE";
            default -> "MODEL_UNAVAILABLE";
        };
    }

    public static WorkerEmbeddingFailure http(int status) {
        Reason reason;
        if (status == 408 || status == 429 || (status >= 500 && status <= 599)) {
            reason = Reason.UPSTREAM_RETRYABLE;
        } else if (status == 401 || status == 403) {
            reason = Reason.AUTH_REJECTED;
        } else {
            reason = Reason.HTTP_REJECTED;
        }
        return new WorkerEmbeddingFailure(reason);
    }

    public enum Reason {
        TIMEOUT, NETWORK, UPSTREAM_RETRYABLE, REQUEST_LIMIT, RESPONSE_LIMIT,
        INVALID_RESPONSE, AUTH_REJECTED, HTTP_REJECTED, LEASE_LOST, LEASE_UNKNOWN, SHUTDOWN
    }
}
