package com.zhishiku.kb.app.auth.lifecycle;

import java.time.Instant;

public record LifecycleProof(String tokenHash, long actorUserId, long targetUserId, String operation,
                              String bindingDigest, String evidence, Instant expiresAt, Instant consumedAt,
                              Instant createdAt) {
    @Override
    public String toString() {
        return "LifecycleProof[redacted]";
    }
}
