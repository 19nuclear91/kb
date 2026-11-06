package com.zhishiku.kb.app.auth.lifecycle;

import java.util.Objects;

/** 业务去重与异常隔离；传输 ACK 由外层提交结果决定。 */
public final class LifecycleEventRegistry {
    public enum Decision {
        NEW, DUPLICATE, CONFLICT
    }

    public record Registration(LifecycleEventRecord event, Decision decision) {
    }

    private final LifecycleInbox inbox;

    public LifecycleEventRegistry(LifecycleInbox inbox) {
        this.inbox = Objects.requireNonNull(inbox);
    }

    public Registration register(LifecycleEventRecord incoming) {
        LifecycleEventRecord previous = inbox.findEvent(incoming.sourceScope(), incoming.eventId());
        if (previous == null) {
            if (inbox.insertEvent(incoming) != 1) {
                throw new IllegalStateException("生命周期事件持久化失败");
            }
            return new Registration(incoming, Decision.NEW);
        }
        if (!Objects.equals(previous.contentDigest(), incoming.contentDigest())) {
            if (inbox.markEventConflict(previous.id()) != 1) {
                throw new IllegalStateException("生命周期事件隔离失败");
            }
            return new Registration(previous, Decision.CONFLICT);
        }
        return new Registration(previous, previous.conflicted() ? Decision.CONFLICT : Decision.DUPLICATE);
    }
}
