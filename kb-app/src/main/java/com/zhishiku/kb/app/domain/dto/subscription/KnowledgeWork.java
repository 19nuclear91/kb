package com.zhishiku.kb.app.domain.dto.subscription;

import com.zhishiku.kb.app.enums.subscription.SubscriptionMode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** 后台领取和匹配的无正文投影，全部执行边界来自数据库租约。 */
public final class KnowledgeWork {
    private KnowledgeWork() {
    }

    public record Claim(
            UUID id,
            LocalDate weekStart,
            long token,
            long recipientCursor,
            int attempts,
            boolean takeover
    ) {
    }

    public record Match(
            long userId,
            UUID generationId,
            SubscriptionMode mode,
            LocalDate weekStart
    ) {
    }

    public record Update(
            UUID id,
            long userId,
            UUID eventId,
            SubscriptionMode mode,
            LocalDate weekStart
    ) {
    }

    public record Backlog(
            long matching,
            long weekly,
            long delivery,
            long oldestSeconds,
            Instant sampledAt
    ) {
        public Backlog {
            if (matching < 0 || weekly < 0 || delivery < 0 || oldestSeconds < 0 || sampledAt == null) {
                throw new IllegalArgumentException("Invalid knowledge backlog snapshot");
            }
        }
    }
}
