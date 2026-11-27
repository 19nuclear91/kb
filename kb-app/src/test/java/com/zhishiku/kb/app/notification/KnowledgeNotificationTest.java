package com.zhishiku.kb.app.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.domain.dto.subscription.KnowledgeRows;
import com.zhishiku.kb.app.notification.model.NotificationRow;
import com.zhishiku.kb.app.notification.model.NotificationSourceFact;
import com.zhishiku.kb.app.notification.model.NotificationTarget;
import com.zhishiku.kb.app.service.impl.subscription.KnowledgeNotificationTargets;
import com.zhishiku.kb.app.notification.mapper.UserNotificationMapper;
import com.zhishiku.kb.app.support.MapperRowStub;
import com.zhishiku.kb.common.security.NotificationContract;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeNotificationTest {
    @Test
    void thirdSourceIsExclusiveAndFirstReadTimeIsStable() {
        UUID id = UUID.randomUUID();
        var source = new NotificationSourceFact(null, null, id, null);
        var read = source.markRead(Instant.EPOCH);
        assertThat(read.markRead(Instant.now())).isSameAs(read);
        assertThatThrownBy(() -> new NotificationSourceFact(id, null, id, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationSourceFact(null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void newTargetsContainOnlyKindAndOwnIdentityAndRejectMixedOrMalformedJson() throws Exception {
        var json = new ObjectMapper();
        UUID id = UUID.randomUUID();
        for (var target : List.of(NotificationTarget.knowledgeUpdate(id), NotificationTarget.knowledgeDigest(id))) {
            String encoded = json.writeValueAsString(target);
            assertThat(json.readTree(encoded).size()).isEqualTo(2);
            assertThat(json.readValue(encoded, NotificationTarget.class)).isEqualTo(target);
            assertThatThrownBy(() -> json.readValue(encoded.replace("}", ",\"title\":\"private\"}"), NotificationTarget.class)).isInstanceOf(Exception.class);
        }
        assertThatThrownBy(() -> json.readValue("{\"kind\":\"KNOWLEDGE_UPDATE\",\"updateId\":\"1-1-1-1-1\"}", NotificationTarget.class)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> NotificationContract.parse(List.of("KNOWLEDGE_SUBSCRIPTIONS_V1", "KNOWLEDGE_GAPS_V1"))).isInstanceOf(RuntimeException.class);
    }

    @Test
    void nestedMappingKeepsThirdSourceAndNullableOldSourcesDistinct() throws Exception {
        var configuration = GapInboxHarness.configuration();
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 1L);
        row.put("notification_id", UUID.randomUUID());
        row.put("recipient_user_id", 10L);
        row.put("type", "KNOWLEDGE_WEEKLY_DIGEST");
        row.put("created_at", Instant.EPOCH);
        row.put("read_at", null);
        for (String column : List.of("document_id", "version_id", "review_id", "round_no", "round_state", "round_submitted_by", "uploader_user_id",
                "publication_policy", "publication_evidence", "comment_id", "gap_id", "gap_event_id", "gap_event_kind", "gap_state", "gap_version",
                "gap_reporter_user_id", "gap_assignee_user_id", "gap_assignment_epoch", "gap_event_assignment_epoch", "gap_queue_event_id",
                "gap_pending_solution_id", "gap_event_solution_id", "gap_event_result_version", "gap_visible", "gap_work_current")) {
            row.put(column, null);
        }
        for (String column : List.of("live", "cleared", "viewable", "actionable", "round_in_space", "discussable", "reply_readable")) {
            row.put(column, false);
        }
        UUID delivery = UUID.randomUUID();
        row.put("knowledge_delivery_id", delivery);
        row.put("knowledge_user_id", 10L);
        row.put("knowledge_kind", "WEEKLY");
        row.put("knowledge_status", "NOTIFIED");
        row.put("knowledge_update_id", null);
        row.put("knowledge_week_start", LocalDate.of(2026, 11, 23));
        row.put("knowledge_visible", true);
        var mapped = MapperRowStub.readOne(configuration, UserNotificationMapper.class.getName() + ".list",
                Map.of("userId", 10L, "types", List.of("KNOWLEDGE_WEEKLY_DIGEST"), "spaceIds", List.of(3L),
                        "principalKeys", List.of("1:10"), "clearance", (short) 2, "limit", 21), row, NotificationRow.class);
        assertThat(mapped.gap()).isNull();
        assertThat(mapped.documentId()).isNull();
        assertThat(new KnowledgeNotificationTargets().resolve(mapped)).contains(NotificationTarget.knowledgeDigest(delivery));
    }

    @Test
    void threeContractsKeepLegacyScopesAndCursorsSeparateAndWeeklyCountsOnce() {
        var harness = new GapInboxHarness();
        harness.rows.add(GapInboxHarness.comment(1));
        harness.rows.add(GapInboxHarness.solution(2, true));
        harness.rows.add(knowledge(3, 10, true));
        assertThat(harness.service.list(Map.of()).items()).hasSize(1);
        assertThat(harness.service.list(Map.of(), NotificationContract.KNOWLEDGE_GAPS_V1).items()).hasSize(2);
        assertThat(harness.service.list(Map.of(), NotificationContract.KNOWLEDGE_SUBSCRIPTIONS_V1).items()).hasSize(3);
        assertThat(harness.service.unreadCount(Map.of(), NotificationContract.KNOWLEDGE_SUBSCRIPTIONS_V1).count()).isEqualTo("3");
        String cursor = harness.service.list(Map.of("limit", List.of("1")), NotificationContract.KNOWLEDGE_SUBSCRIPTIONS_V1).nextCursor();
        assertThatThrownBy(() -> harness.service.list(Map.of("cursor", List.of(cursor)), NotificationContract.KNOWLEDGE_GAPS_V1)).isInstanceOf(RuntimeException.class);
        harness.rows.set(2, knowledge(3, 10, false));
        assertThat(harness.service.unreadCount(Map.of(), NotificationContract.KNOWLEDGE_SUBSCRIPTIONS_V1).count()).isEqualTo("2");
        harness.rows.set(2, knowledge(3, 10, true));
        assertThat(harness.service.unreadCount(Map.of(), NotificationContract.KNOWLEDGE_SUBSCRIPTIONS_V1).count()).isEqualTo("3");
        assertThat(new KnowledgeNotificationTargets().resolve(knowledge(3, 11, true))).isEmpty();
    }

    private NotificationRow knowledge(long id, long owner, boolean visible) {
        var fact = new KnowledgeRows.Notification(UUID.randomUUID(), owner, "WEEKLY", "NOTIFIED", null,
                LocalDate.of(2026, 11, 23), visible);
        return new NotificationRow(id, UUID.randomUUID(), 10, "KNOWLEDGE_WEEKLY_DIGEST", Instant.EPOCH, null, null, null,
                null, null, false, false, false, false, null, null, false, null, null, null, null, false, false, null, fact);
    }
}
