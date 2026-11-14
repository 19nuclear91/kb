package com.zhishiku.kb.app.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.notification.mapper.UserNotificationMapper;
import com.zhishiku.kb.app.notification.model.GapNotificationFact;
import com.zhishiku.kb.app.notification.model.NotificationRow;
import com.zhishiku.kb.app.notification.model.NotificationTarget;
import com.zhishiku.kb.app.notification.model.NotificationType;
import com.zhishiku.kb.app.notification.model.NotificationViews;
import com.zhishiku.kb.app.notification.service.GapNotificationDerivationTransaction;
import com.zhishiku.kb.app.notification.service.GapNotificationTargets;
import com.zhishiku.kb.app.notification.service.NotificationMetrics;
import com.zhishiku.kb.app.notification.service.NotificationTargetRegistry;
import com.zhishiku.kb.app.notification.service.UserNotificationService;
import com.zhishiku.kb.app.notification.web.NotificationController;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 三类目标的精确 JSON、问题固定工作身份和本人已读单测；不连接资料或源业务。 */
class GapNotificationTargetTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void gapTargetSerializesOnlyKindAndExactGapIdWhileLegacyFiveFieldsStayIdentical() throws Exception {
        var gap = NotificationTarget.knowledgeGap(9007199254740993L);
        assertThat(json.readTree(json.writeValueAsString(gap)).fieldNames()).toIterable().containsExactly("kind", "gapId");
        assertThat(json.readValue(json.writeValueAsString(gap), NotificationTarget.class)).isEqualTo(gap);
        for (NotificationTarget target : List.of(NotificationTarget.review(11, 21, UUID.randomUUID()),
                NotificationTarget.comment(11, 21, UUID.randomUUID()))) {
            var tree = json.readTree(json.writeValueAsString(target));
            assertThat(tree.fieldNames()).toIterable().containsExactly("kind", "documentId", "versionId", "reviewId", "commentId");
            assertThat(json.readValue(tree.toString(), NotificationTarget.class)).isEqualTo(target);
        }
    }

    @Test
    void unknownExtraDuplicateAndMixedIdentityFieldsAreRejected() {
        for (String body : List.of("{\"kind\":\"KNOWLEDGE_GAP\",\"gapId\":11,\"description\":\"secret\"}",
                "{\"kind\":\"KNOWLEDGE_GAP\",\"gapId\":11,\"gapId\":12}",
                "{\"kind\":\"KNOWLEDGE_GAP\",\"gapId\":11,\"documentId\":null}",
                "{\"kind\":\"KNOWLEDGE_GAP\",\"gapId\":1.0}", "{\"kind\":\"KNOWLEDGE_GAP\",\"gapId\":\"11\"}",
                "{\"kind\":\"KNOWLEDGE_GAP\",\"gapId\":0}", "{\"kind\":\"https://evil.test\",\"gapId\":11}",
                "{\"kind\":\"DOCUMENT_VERSION_REVIEW\",\"gapId\":11}")) {
            assertThatThrownBy(() -> json.readValue(body, NotificationTarget.class)).isInstanceOf(Exception.class);
        }
        assertThatThrownBy(() -> new NotificationTarget(NotificationTarget.KNOWLEDGE_GAP, 11L, 21L, null, null, 31L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void typesAndFixedPromptsMatchExactlyEightSourceKindsAndNoteHasNoReminder() {
        Set<String> kinds = Arrays.stream(NotificationType.values()).filter(NotificationType::knowledgeGap).map(Enum::name).collect(Collectors.toSet());
        assertThat(kinds).containsExactlyInAnyOrderElementsOf(GapNotificationDerivationTransaction.SOURCE_KINDS);
        for (String kind : kinds) {
            var type = NotificationType.fromSource(kind).orElseThrow();
            assertThat(type.prompt()).isNotBlank();
            assertThat(type.prompt()).doesNotContain("http", "<", "description");
        }
        assertThat(NotificationType.ignored("NOTE_ADDED")).isTrue();
        assertThat(NotificationType.fromSource("NOTE_ADDED")).isEmpty();
    }

    @Test
    void appendedNoteDoesNotInvalidateCurrentAssignmentAndReassignmentDoes() {
        var resolver = new GapNotificationTargets();
        UUID event = UUID.randomUUID();
        var current = fact(event, "GAP_ASSIGNED", "IN_PROGRESS", 5, 8L, 1, 1, null, null, null, 1, true);
        assertThat(resolver.resolve(row(current, 8))).contains(NotificationTarget.knowledgeGap(11));
        var changed = fact(event, "GAP_ASSIGNED", "IN_PROGRESS", 6, 9L, 2, 1, null, null, null, 1, true);
        assertThat(resolver.resolve(row(changed, 8))).isEmpty();
        var hidden = fact(event, "GAP_ASSIGNED", "IN_PROGRESS", 5, 8L, 1, 1, null, null, null, 1, false);
        assertThat(resolver.resolve(row(hidden, 8))).isEmpty();
    }

    @Test
    void queueSolutionAndTerminalTargetsNeverRepresentAnOldWorkIdentity() {
        var resolver = new GapNotificationTargets();
        UUID event = UUID.randomUUID();
        UUID solution = UUID.randomUUID();
        assertThat(resolver.resolve(row(fact(event, "GAP_CREATED", "SUBMITTED", 0, null, 0, 0, event, null, null, 0, true), 7))).isPresent();
        assertThat(resolver.resolve(row(fact(event, "GAP_CREATED", "SUBMITTED", 1, null, 0, 0, UUID.randomUUID(), null, null, 0, true), 7))).isEmpty();
        assertThat(resolver.resolve(row(fact(event, "GAP_SOLUTION_SUBMITTED", "AWAITING_CONFIRMATION", 2, 8L, 1, 1,
                null, solution, solution, 2, true), 10))).isPresent();
        assertThat(resolver.resolve(row(fact(event, "GAP_SOLUTION_SUBMITTED", "AWAITING_CONFIRMATION", 4, 8L, 1, 1,
                null, UUID.randomUUID(), solution, 2, true), 10))).isEmpty();
        assertThat(resolver.resolve(row(fact(event, "GAP_CLOSED", "CLOSED", 3, 8L, 1, 1, null, null, null, 3, true), 10))).isPresent();
        assertThat(resolver.resolve(row(fact(event, "GAP_CLOSED", "RESOLVED", 3, 8L, 1, 1, null, null, null, 3, true), 10))).isEmpty();
    }

    @Test
    void hiddenGapNotificationCanBeMarkedReadIdempotentlyWithoutTargetQualificationOrContract() throws Exception {
        var access = mock(AdminQueryAccess.class);
        var actor = mock(PermissionService.PrincipalHandle.class);
        when(actor.userId()).thenReturn(10L);
        when(access.current()).thenReturn(actor);
        var mapper = mock(UserNotificationMapper.class);
        var permissions = mock(PermissionService.class);
        UUID id = UUID.randomUUID();
        when(mapper.markRead(10, id)).thenReturn(new NotificationViews.ReadReceipt(id, Instant.EPOCH));
        var service = new UserNotificationService(access, permissions, mapper,
                new NotificationTargetRegistry(List.of(new GapNotificationTargets())), new NotificationMetrics(new SimpleMeterRegistry()),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        var controller = new NotificationController(service);
        var request = new MockHttpServletRequest("PUT", "/api/notifications/" + id + "/read");
        request.addHeader("X-Kb-Notification-Contract", "UNKNOWN");
        assertThat(controller.read(id.toString(), request).data().readAt()).isEqualTo(Instant.EPOCH);
        assertThat(service.markRead(id).readAt()).isEqualTo(Instant.EPOCH);
        verifyNoInteractions(permissions);
        assertThat(service.list(Map.of()).items()).isEmpty();
    }

    private static NotificationRow row(GapNotificationFact fact, long recipient) {
        return NotificationRow.knowledgeGap(1, UUID.randomUUID(), recipient, fact.eventKind(), Instant.EPOCH, null, fact);
    }

    private static GapNotificationFact fact(UUID event, String kind, String state, long version, Long assignee, long epoch,
                                            long eventEpoch, UUID queue, UUID pending, UUID solution, long resultVersion, boolean visible) {
        return new GapNotificationFact(11L, event, kind, state, version, 10L, assignee, epoch, eventEpoch, queue, pending, solution,
                resultVersion, visible, true);
    }
}
