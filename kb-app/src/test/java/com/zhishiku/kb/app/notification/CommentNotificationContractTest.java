package com.zhishiku.kb.app.notification;

import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.collaboration.service.CollaborationEventService;
import com.zhishiku.kb.app.notification.mapper.UserNotificationMapper;
import com.zhishiku.kb.app.notification.model.NotificationRow;
import com.zhishiku.kb.app.notification.model.NotificationTarget;
import com.zhishiku.kb.app.notification.model.NotificationType;
import com.zhishiku.kb.app.notification.service.CommentNotificationTargets;
import com.zhishiku.kb.app.notification.service.NotificationMetrics;
import com.zhishiku.kb.app.notification.service.UserNotificationService;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.review.CollaborationEvent;
import com.zhishiku.kb.common.review.CollaborationKind;
import com.zhishiku.kb.common.security.PermissionScope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回复提醒的独立契约：解析器按投影事实复核，读取／计数共用注册类型；派生以真实 CollaborationEventService
 * 写入的内存事实为源。版本、评论与通知依赖为 Mock 或内存替身，不运行来源联调。
 */
class CommentNotificationContractTest {
    private static final long DOCUMENT = 9007199254740993L, VERSION = 9007199254740995L;
    private static final long ROOT_AUTHOR = 7, REPLIER = 8;
    private static final UUID ROOT = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID REPLY = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private final CommentNotificationTargets targets = new CommentNotificationTargets();

    @Test
    void legalReplyLocatesTheFixedVersionReplyAndHiddenFactsHideIt() {
        assertThat(targets.types()).containsExactly(NotificationType.COMMENT_REPLIED);
        var base = reply(row -> row);
        assertThat(targets.resolve(base)).contains(NotificationTarget.comment(DOCUMENT, VERSION, REPLY));
        // 当前 view 由 discussable 表达；基准行的 viewable／actionable 均为假，审核状态不参与回复提醒。
        // 升级观察到的当前发布证据同样可讨论。
        assertThat(targets.resolve(reply(row -> with(row, true, true, true, true, "OBSERVED_CURRENT")))).isPresent();
        Map<String, UnaryOperator<NotificationRow>> hidden = Map.of(
                "回复已删除", row -> with(row, true, true, true, false, "PUBLISHED"),
                "撤销 view 或主体停用", row -> with(row, true, true, false, true, "PUBLISHED"),
                "升密", row -> with(row, true, false, true, true, "PUBLISHED"),
                "文档或空间归档／删除", row -> with(row, false, true, true, true, "PUBLISHED"),
                "版本无发布证据", row -> with(row, true, true, true, true, "NONE"));
        hidden.forEach((name, change) -> assertThat(targets.resolve(reply(change))).as(name).isEmpty());
        // 权限恢复后，仍存在的回复重新可见；不以最新版本替代原回复。
        var restored = targets.resolve(reply(row -> with(row, true, true, true, true, "PUBLISHED"))).orElseThrow();
        assertThat(restored.versionId()).isEqualTo(VERSION);
        assertThat(restored.reviewId()).isNull();
    }

    @Test
    void factsThatAreNotACommentReplyFailClosed() {
        var mismatches = List.of(row("COMMENT_REPLIED", null, null, null), row("COMMENT_REPLIED", REPLY, ROOT, null),
                row("COMMENT_REPLIED", REPLY, null, 1), row("REVIEW_APPROVED", REPLY, null, null));
        for (var row : mismatches) {
            assertThatThrownBy(() -> targets.resolve(row)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void installedResolverAddsRepliesToTheSameListAndCountScopeAndRechecksEachRow() {
        var registry = DerivationHarness.withCommentResolver();
        assertThat(registry.readableTypes()).containsExactly("REVIEW_SUBMITTED", "REVIEW_APPROVED", "REVIEW_REJECTED",
                "COMMENT_REPLIED");
        var access = mock(AdminQueryAccess.class);
        var permissions = mock(PermissionService.class);
        var handle = mock(PermissionService.PrincipalHandle.class);
        var mapper = mock(UserNotificationMapper.class);
        var meters = new SimpleMeterRegistry();
        when(access.current()).thenReturn(handle);
        when(handle.userId()).thenReturn(ROOT_AUTHOR);
        when(permissions.resolve(handle, "view")).thenReturn(PermissionScope.of(ROOT_AUTHOR, (short) 2, List.of(3L),
                List.of("1:7")));
        var service = new UserNotificationService(access, permissions, mapper, registry, new NotificationMetrics(meters),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        var types = registry.readableTypes();
        when(mapper.list(ROOT_AUTHOR, types, List.of(3L), List.of("1:7"), (short) 2, null, 21))
                .thenReturn(List.of(reply(row -> row)), List.of(reply(row -> with(row, true, true, true, false, "PUBLISHED"))));
        var item = service.list(Map.of()).items().getFirst();
        assertThat(item.type()).isEqualTo(NotificationType.COMMENT_REPLIED);
        assertThat(item.prompt()).isEqualTo("你的评论有新回复");
        assertThat(item.target()).isEqualTo(NotificationTarget.comment(DOCUMENT, VERSION, REPLY));
        // SQL 已过滤后 Java 复核仍不一致：安全失败并计数，不输出目标。
        assertThatThrownBy(() -> service.list(Map.of())).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.httpStatus()).isEqualTo(503));
        assertThat(meters.get("kb.notification.read.mismatch").counter().count()).isEqualTo(1);
        when(mapper.countUnread(anyLong(), anyList(), anyList(), anyList(), anyShort())).thenReturn(1L);
        assertThat(service.unreadCount(Map.of()).count()).isEqualTo("1");
        verify(mapper).countUnread(eq(ROOT_AUTHOR), eq(types), eq(List.of(3L)), eq(List.of("1:7")), eq((short) 2));
        verify(mapper, never()).list(anyLong(), eq(List.of("REVIEW_SUBMITTED", "REVIEW_APPROVED",
                "REVIEW_REJECTED")), anyList(), anyList(), anyShort(), any(), anyInt());
    }

    @Test
    void onlyRepliesFromOthersBecomeRemindersAndCreateOrDeleteFactsCompleteSilently() {
        var harness = new DerivationHarness();
        var events = new CollaborationEventService(harness.store.collaborationMapper());
        var now = harness.store.now;
        var created = fact(events, CollaborationKind.COMMENT_CREATED, ROOT, null, ROOT_AUTHOR, now, List.of());
        var replied = fact(events, CollaborationKind.COMMENT_REPLIED, REPLY, ROOT, REPLIER, now.plusSeconds(1),
                List.of(ROOT_AUTHOR));
        UUID self = UUID.randomUUID();
        var selfReply = fact(events, CollaborationKind.COMMENT_REPLIED, self, ROOT, ROOT_AUTHOR, now.plusSeconds(2),
                List.of(ROOT_AUTHOR));
        var deleted = fact(events, CollaborationKind.COMMENT_DELETED, REPLY, ROOT, REPLIER, now.plusSeconds(3), List.of());
        assertThat(harness.store.recipients.get(selfReply)).isEmpty();
        harness.instance(DerivationHarness.withCommentResolver()).runOnce();
        assertThat(harness.store.delivered()).singleElement().satisfies(item -> {
            assertThat(item.eventId()).isEqualTo(replied);
            assertThat(item.recipient()).isEqualTo(ROOT_AUTHOR);
            assertThat(item.type()).isEqualTo("COMMENT_REPLIED");
        });
        for (UUID event : List.of(created, replied, selfReply, deleted)) {
            assertThat(harness.store.projection(event).status).isEqualTo("DONE");
        }
        // 删除不撤销已送达提醒；读取时由“回复仍可读”过滤。
        assertThat(harness.store.delivered(replied)).hasSize(1);
    }

    @Test
    void replyFactWaitsForTheResolverAndRecoversWithoutTouchingTheSource() {
        var harness = new DerivationHarness();
        var events = new CollaborationEventService(harness.store.collaborationMapper());
        var replied = fact(events, CollaborationKind.COMMENT_REPLIED, REPLY, ROOT, REPLIER, harness.store.now,
                List.of(ROOT_AUTHOR));
        var source = new HashMap<>(harness.store.events);
        var recipients = new TreeSet<>(harness.store.recipients.get(replied));
        harness.instance().runOnce();
        assertThat(harness.store.projection(replied).status).isEqualTo("WAITING_SOURCE");
        assertThat(harness.store.delivered()).isEmpty();
        harness.store.now = harness.store.projection(replied).nextAttemptAt;
        harness.instance(DerivationHarness.withCommentResolver()).runOnce();
        assertThat(harness.store.delivered(replied)).extracting(InMemoryNotificationStore.Delivered::recipient)
                .containsExactly(ROOT_AUTHOR);
        assertThat(harness.store.events).isEqualTo(source);
        assertThat(harness.store.recipients.get(replied)).isEqualTo(recipients);
    }

    @Test
    void repeatedFactsAndRederivationKeepOneReminderAndItsReadState() {
        var harness = new DerivationHarness();
        var events = new CollaborationEventService(harness.store.collaborationMapper());
        var replied = fact(events, CollaborationKind.COMMENT_REPLIED, REPLY, ROOT, REPLIER, harness.store.now,
                List.of(ROOT_AUTHOR));
        var derivation = harness.instance(DerivationHarness.withCommentResolver());
        derivation.runOnce();
        harness.store.advanceTime(Duration.ofMinutes(1));
        harness.store.read(replied, ROOT_AUTHOR);
        var readAt = harness.store.delivered(replied).getFirst().readAt();
        assertThat(readAt).isNotNull();
        // 同一评论的重复事实被业务键去重，不写新收件人。
        assertThat(events.append(CollaborationEvent.comment(CollaborationKind.COMMENT_REPLIED, DOCUMENT, VERSION, REPLY,
                ROOT, REPLIER, harness.store.now), List.of(ROOT_AUTHOR, 99L))).isFalse();
        assertThat(harness.store.recipients.get(replied)).containsExactly(ROOT_AUTHOR);
        // 派生进度丢失后重放：不重复插入，也不重置已读。
        harness.store.loseProjection(replied);
        derivation.runOnce();
        harness.instance(DerivationHarness.withCommentResolver()).runOnce();
        assertThat(harness.store.delivered(replied)).singleElement()
                .satisfies(item -> assertThat(item.readAt()).isEqualTo(readAt));
    }

    @Test
    void derivationFailureKeepsTheCommittedReplyFactForRetry() {
        var harness = new DerivationHarness();
        var events = new CollaborationEventService(harness.store.collaborationMapper());
        var replied = fact(events, CollaborationKind.COMMENT_REPLIED, REPLY, ROOT, REPLIER, harness.store.now,
                List.of(ROOT_AUTHOR));
        harness.store.fault = call -> {
            if (call.equals("deliver")) {
                throw new IllegalStateException("notification store down");
            }
        };
        harness.instance(DerivationHarness.withCommentResolver()).runOnce();
        assertThat(harness.store.delivered()).isEmpty();
        assertThat(harness.store.events).containsKey(replied);
        assertThat(harness.store.recipients.get(replied)).containsExactly(ROOT_AUTHOR);
        assertThat(harness.store.projection(replied).status).isEqualTo("PENDING");
        harness.store.fault = ignored -> { };
        harness.store.now = harness.store.projection(replied).nextAttemptAt;
        harness.instance(DerivationHarness.withCommentResolver()).runOnce();
        assertThat(harness.store.delivered(replied)).hasSize(1);
    }

    private static UUID fact(CollaborationEventService events, CollaborationKind kind, UUID comment, UUID root,
                             long actor, Instant at, List<Long> recipients) {
        var event = CollaborationEvent.comment(kind, DOCUMENT, VERSION, comment, root, actor, at);
        assertThat(events.append(event, recipients)).isTrue();
        return event.eventId();
    }

    private static NotificationRow reply(UnaryOperator<NotificationRow> change) {
        return change.apply(row("COMMENT_REPLIED", REPLY, null, null));
    }

    private static NotificationRow row(String type, UUID comment, UUID review, Integer round) {
        return new NotificationRow(1, UUID.randomUUID(), ROOT_AUTHOR, type, Instant.EPOCH, null, DOCUMENT, VERSION,
                review, round, true, true, false, false, null, null, false, 9L, "AUTO", "PUBLISHED", comment, true, true);
    }

    private static NotificationRow with(NotificationRow row, boolean live, boolean cleared, boolean discussable,
                                        boolean replyReadable, String evidence) {
        return new NotificationRow(row.id(), row.notificationId(), row.recipientUserId(), row.type(), row.createdAt(),
                row.readAt(), row.documentId(), row.versionId(), row.reviewId(), row.roundNo(), live, cleared,
                row.viewable(), row.actionable(), row.roundState(), row.roundSubmittedBy(), row.roundInSpace(),
                row.uploaderUserId(), row.publicationPolicy(), evidence, row.commentId(), discussable, replyReadable);
    }
}
