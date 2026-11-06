package com.zhishiku.kb.app.auth.lifecycle;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleEventRegistryTest {
    private final MemoryInbox inbox = new MemoryInbox();
    private final LifecycleEventRegistry registry = new LifecycleEventRegistry(inbox);

    @Test
    void replayKeepsFirstEventIdentityAndDoesNotInsertAgain() {
        var first = registry.register(event(1, "scope", "digest"));
        var replay = registry.register(event(2, "scope", "digest"));
        assertThat(first.decision()).isEqualTo(LifecycleEventRegistry.Decision.NEW);
        assertThat(replay.decision()).isEqualTo(LifecycleEventRegistry.Decision.DUPLICATE);
        assertThat(replay.event().id()).isEqualTo(1);
        assertThat(inbox.rows).hasSize(1);
    }

    @Test
    void conflictingReplayDoesNotReplaceOriginalContentOrClearIsolation() {
        registry.register(event(1, "scope", "original"));
        assertThat(registry.register(event(2, "scope", "changed")).decision())
                .isEqualTo(LifecycleEventRegistry.Decision.CONFLICT);
        assertThat(registry.register(event(3, "scope", "original")).decision())
                .isEqualTo(LifecycleEventRegistry.Decision.CONFLICT);
        assertThat(inbox.rows.values()).singleElement()
                .extracting(LifecycleEventRecord::contentDigest).isEqualTo("original");
    }

    @Test
    void scopeIsPartOfBusinessIdentity() {
        registry.register(event(1, "first", "digest"));
        assertThat(registry.register(event(2, "second", "digest")).decision())
                .isEqualTo(LifecycleEventRegistry.Decision.NEW);
        assertThat(inbox.rows).hasSize(2);
    }

    @Test
    void unresolvedItemsStayVisibleAndOnlyFailuresAreAutomaticallyRetried() {
        assertThat(LifecycleItemStatus.UNMAPPED.unresolved()).isTrue();
        assertThat(LifecycleItemStatus.REVIEW.unresolved()).isTrue();
        assertThat(LifecycleItemStatus.FAILED.unresolved()).isTrue();
        assertThat(LifecycleItemStatus.REVIEW.automaticRetry()).isFalse();
        assertThat(LifecycleItemStatus.UNMAPPED.automaticRetry()).isFalse();
        assertThat(LifecycleItemStatus.FAILED.automaticRetry()).isTrue();
    }

    private static LifecycleEventRecord event(long id, String scope, String digest) {
        return new LifecycleEventRecord(id, scope, "event", "user_leave_org", "corp", "connection",
                Instant.EPOCH, digest, false, Instant.EPOCH);
    }

    private static final class MemoryInbox implements LifecycleInbox {
        private final Map<String, LifecycleEventRecord> rows = new HashMap<>();

        @Override
        public LifecycleEventRecord findEvent(String sourceScope, String eventId) {
            return rows.get(sourceScope + ":" + eventId);
        }

        @Override
        public int insertEvent(LifecycleEventRecord event) {
            return rows.putIfAbsent(event.sourceScope() + ":" + event.eventId(), event) == null ? 1 : 0;
        }

        @Override
        public int markEventConflict(long id) {
            for (var entry : rows.entrySet()) {
                LifecycleEventRecord row = entry.getValue();
                if (row.id() == id) {
                    entry.setValue(new LifecycleEventRecord(row.id(), row.sourceScope(), row.eventId(), row.eventType(),
                            row.enterpriseId(), row.connectionId(), row.bornAt(), row.contentDigest(), true,
                            row.receivedAt()));
                    return 1;
                }
            }
            return 0;
        }
    }
}
