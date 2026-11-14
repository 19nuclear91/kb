package com.zhishiku.kb.app.knowledgegap;

import com.zhishiku.kb.app.notification.model.NotificationSourceFact;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 仅 SQL 声明检查与纯模型单测，不宣称数据库实际拒绝或触发器执行通过。 */
class KnowledgeGapNotificationSchemaTest {
    @Test
    void migrationPreservesOriginalInboxAndAddsScopedSecondSource() throws Exception {
        String sql = migration();
        assertThat(sql).contains("alter column event_id drop not null", "add column gap_event_id uuid",
                "check ((event_id is null) <> (gap_event_id is null))",
                "unique (gap_event_id, recipient_user_id)",
                "foreign key (gap_event_id, recipient_user_id) references kb_knowledge_gap_event_recipient (event_id, user_id)",
                "event_id is not null and type in ('review_submitted', 'review_approved', 'review_rejected', 'comment_replied')");
        assertThat(sql).doesNotContain("drop table", "truncate ", "delete from", "update kb_user_notification",
                "insert into kb_user_notification", "drop column", "restart", "drop sequence",
                "drop constraint kb_user_notification_event_id_recipient_user_id");
    }

    @Test
    void projectionAndFirstReadGuardsRetainMonotonicFacts() throws Exception {
        String sql = migration();
        assertThat(sql).contains("event_id uuid primary key references kb_knowledge_gap_event (event_id)",
                "before update or delete on kb_knowledge_gap_notification_projection",
                "execute function protect_notification_projection()",
                "check ((status = 'done') = (completed_at is not null))",
                "new.event_id, new.gap_event_id", "old.event_id, old.gap_event_id",
                "old.read_at is not null and new.read_at is distinct from old.read_at");
    }

    @Test
    void modelRejectsEmptyOrDoubleSource() {
        UUID event = UUID.randomUUID();
        assertThatThrownBy(() -> new NotificationSourceFact(null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationSourceFact(event, event, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new NotificationSourceFact(event, null, null).eventId()).isEqualTo(event);
        assertThat(new NotificationSourceFact(null, event, null).gapEventId()).isEqualTo(event);
    }

    @Test
    void firstReadIsStableForEitherSource() {
        UUID event = UUID.randomUUID();
        for (var unread : new NotificationSourceFact[]{new NotificationSourceFact(event, null, null),
                new NotificationSourceFact(null, event, null)}) {
            var first = unread.markRead(Instant.EPOCH);
            assertThat(first.readAt()).isEqualTo(Instant.EPOCH);
            assertThat(first.markRead(Instant.EPOCH.plusSeconds(100))).isSameAs(first);
            assertThat(first.eventId()).isEqualTo(unread.eventId());
            assertThat(first.gapEventId()).isEqualTo(unread.gapEventId());
        }
    }

    private String migration() throws Exception {
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/V38__knowledge_gap_notifications.sql"))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replaceAll("--[^\r\n]*", "")
                    .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        }
    }
}
