package com.zhishiku.kb.app.document.preview;

import java.util.HashMap;
import java.util.Map;

/** 测试替身只用于驱动业务状态机，不代表 Redis 原子脚本已经实测。 */
final class MemoryOfficePreviewStore implements OfficePreviewStore {
    private final Map<String, OfficePreviewSession> sessions = new HashMap<>();

    @Override
    public synchronized void create(OfficePreviewSession session, long now, int maxPerUser, int maxTotal) {
        sessions.values().removeIf(value -> value.expiresAt() <= now);
        if (sessions.size() >= maxTotal || sessions.values().stream().filter(value -> value.userId() == session.userId()).count() >= maxPerUser) {
            throw new OfficePreviewFailure(429);
        }
        sessions.put(session.id(), session);
    }

    @Override
    public synchronized OfficePreviewSession find(String id, long now) {
        var session = sessions.get(id);
        if (session == null || session.expiresAt() <= now || session.absoluteExpiresAt() <= now) {
            throw new OfficePreviewFailure(404);
        }
        return session;
    }

    @Override
    public synchronized OfficePreviewSession activate(OfficePreviewSession pending, String launchDigest,
                                                      String cookieDigest, long now, long expiresAt) {
        var current = find(pending.id(), now);
        if (!"PENDING".equals(current.state()) || !current.launchDigest().equals(launchDigest)) {
            throw new OfficePreviewFailure(404);
        }
        var active = current.active(cookieDigest, expiresAt);
        sessions.put(current.id(), active);
        return active;
    }

    @Override
    public synchronized OfficePreviewSession heartbeat(OfficePreviewSession active, long now, long expiresAt) {
        var current = find(active.id(), now);
        if (!"ACTIVE".equals(current.state()) || !current.cookieDigest().equals(active.cookieDigest())) {
            throw new OfficePreviewFailure(404);
        }
        var updated = current.active(current.cookieDigest(), expiresAt);
        sessions.put(current.id(), updated);
        return updated;
    }

    @Override
    public synchronized void close(OfficePreviewSession session) {
        sessions.remove(session.id());
    }
}
