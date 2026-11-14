package com.zhishiku.kb.app.notification.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 双来源收件箱的不可变事实；仅有一个来源，首次已读时间不会被重试覆盖。 */
public record NotificationSourceFact(UUID eventId, UUID gapEventId, Instant readAt) {
    public NotificationSourceFact {
        if ((eventId == null) == (gapEventId == null)) {
            throw new IllegalArgumentException("Notification requires exactly one source");
        }
    }

    public NotificationSourceFact markRead(Instant now) {
        Objects.requireNonNull(now);
        if (readAt != null) {
            return this;
        }
        return new NotificationSourceFact(eventId, gapEventId, now);
    }
}
