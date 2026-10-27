package com.zhishiku.kb.app.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zhishiku.kb.app.notification.model.ClaimedEvent;
import com.zhishiku.kb.app.notification.model.NotificationRow;
import com.zhishiku.kb.app.notification.model.NotificationTarget;
import com.zhishiku.kb.app.notification.model.NotificationType;
import com.zhishiku.kb.app.notification.model.NotificationViews;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 结构上不保存或输出标题、正文、意见、原因或凭据；只执行反射与序列化，不连接数据库。 */
class NotificationModelTest {
    private static final Set<String> FORBIDDEN = Set.of("title", "body", "content", "text", "reason", "opinion",
            "comment", "token", "secret", "password", "credential", "url", "link", "filename", "storage", "payload", "name");

    @Test
    void notificationModelsHaveNoContentOrCredentialComponents() {
        for (Class<?> type : List.of(NotificationViews.Item.class, NotificationViews.Page.class,
                NotificationViews.UnreadCount.class, NotificationViews.ReadReceipt.class, NotificationTarget.class,
                NotificationRow.class, ClaimedEvent.class)) {
            for (RecordComponent component : type.getRecordComponents()) {
                String name = component.getName().toLowerCase();
                // claimToken 是租约代次而非凭据，且只在派生内部使用，不进入 API。
                if (name.equals("claimtoken")) {
                    continue;
                }
                assertThat(FORBIDDEN).as("%s.%s", type.getSimpleName(), component.getName())
                        .noneMatch(word -> name.equals(word) || (name.contains(word) && !name.endsWith("id")));
            }
        }
    }

    @Test
    void serializedItemContainsOnlyFixedFieldsAndStructuredTarget() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        UUID review = UUID.fromString("11111111-2222-3333-4444-555555555555");
        var item = new NotificationViews.Item(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
                NotificationType.REVIEW_SUBMITTED, NotificationType.REVIEW_SUBMITTED.prompt(), Instant.EPOCH, null,
                NotificationTarget.review(9007199254740993L, 9007199254740995L, review));
        var tree = json.readTree(json.writeValueAsString(item));
        assertThat(tree.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("notificationId", "type", "prompt", "createdAt", "readAt", "target");
        assertThat(tree.get("target").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("kind", "documentId", "versionId", "reviewId", "commentId");
        assertThat(tree.get("target").get("documentId").asLong()).isEqualTo(9007199254740993L);
        assertThat(tree.get("prompt").asText()).isEqualTo("有版本等待你审核");
    }

    @Test
    void targetsAcceptOnlyFixedKindsAndExactIdentifiers() {
        UUID review = UUID.randomUUID();
        assertThat(NotificationTarget.review(1, 2, review).kind()).isEqualTo(NotificationTarget.REVIEW);
        UUID comment = UUID.randomUUID();
        for (Runnable invalid : List.<Runnable>of(
                () -> new NotificationTarget("https://evil.test", 1, 2, review, null),
                () -> new NotificationTarget(NotificationTarget.REVIEW, 0, 2, review, null),
                () -> new NotificationTarget(NotificationTarget.REVIEW, 1, -2, review, null),
                () -> new NotificationTarget(NotificationTarget.REVIEW, 1, 2, null, null),
                () -> new NotificationTarget(NotificationTarget.REVIEW, 1, 2, review, comment),
                () -> new NotificationTarget(NotificationTarget.COMMENT, 1, 2, review, comment),
                () -> new NotificationTarget(NotificationTarget.COMMENT, 1, 2, null, null),
                () -> new NotificationTarget(NotificationTarget.COMMENT, 0, 2, null, comment))) {
            assertThatThrownBy(invalid::run).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(NotificationTarget.comment(1, 2, comment).commentId()).isEqualTo(comment);
        assertThat(NotificationTarget.comment(1, 2, comment).kind()).isEqualTo(NotificationTarget.COMMENT);
    }

    @Test
    void sourceKindsMapToFixedTypesAndNonRemindersAreIgnored() {
        assertThat(NotificationType.fromSource("REVIEW_SUBMITTED")).contains(NotificationType.REVIEW_SUBMITTED);
        assertThat(NotificationType.fromSource("REVIEW_APPROVED")).contains(NotificationType.REVIEW_APPROVED);
        assertThat(NotificationType.fromSource("REVIEW_REJECTED")).contains(NotificationType.REVIEW_REJECTED);
        assertThat(NotificationType.fromSource("COMMENT_REPLIED")).contains(NotificationType.COMMENT_REPLIED);
        for (String kind : List.of("REVIEW_WITHDRAWN", "REVIEW_CANCELLED", "VERSION_PUBLISHED", "COMMENT_CREATED", "COMMENT_DELETED")) {
            assertThat(NotificationType.ignored(kind)).isTrue();
            assertThat(NotificationType.fromSource(kind)).isEmpty();
        }
        for (String kind : Arrays.asList("FUTURE_KIND", "review_submitted", null)) {
            assertThat(NotificationType.ignored(kind)).isFalse();
            assertThat(NotificationType.fromSource(kind)).isEmpty();
        }
        assertThat(InMemoryNotificationStore.NOTIFICATION_TYPES)
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(NotificationType.values()).map(Enum::name).toList());
    }

    @Test
    void runtimeDefaultsAreBoundedAndBackoffIsCapped() {
        var properties = new NotificationProperties();
        properties.validate();
        assertThat(properties.getLeaseSeconds()).isEqualTo(60);
        assertThat(properties.getRecipientBatchSize()).isEqualTo(100);
        assertThat(properties.getClaimLimit()).isEqualTo(50);
        assertThat(properties.backoffSeconds(0)).isEqualTo(30);
        assertThat(properties.backoffSeconds(1)).isEqualTo(60);
        assertThat(properties.backoffSeconds(Integer.MAX_VALUE)).isEqualTo(900);
        properties.setLeaseSeconds(20);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
        properties.setLeaseSeconds(60);
        properties.setBackoffMaxSeconds(10);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
        properties.setBackoffMaxSeconds(900);
        properties.setRecipientBatchSize(0);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }
}
