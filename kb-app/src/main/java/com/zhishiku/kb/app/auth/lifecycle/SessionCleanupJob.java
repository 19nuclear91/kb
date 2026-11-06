package com.zhishiku.kb.app.auth.lifecycle;

public record SessionCleanupJob(long userId, long revokedThroughEpoch, int attempts) {
}
