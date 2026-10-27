package com.zhishiku.kb.app.notification;

import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.notification.mapper.UserNotificationMapper;
import com.zhishiku.kb.app.notification.model.NotificationRow;
import com.zhishiku.kb.app.notification.model.NotificationTarget;
import com.zhishiku.kb.app.notification.model.NotificationType;
import com.zhishiku.kb.app.notification.model.NotificationViews;
import com.zhishiku.kb.app.notification.service.NotificationMetrics;
import com.zhishiku.kb.app.notification.service.NotificationTargetRegistry;
import com.zhishiku.kb.app.notification.service.ReviewNotificationTargets;
import com.zhishiku.kb.app.notification.service.UserNotificationService;
import com.zhishiku.kb.app.query.QueryCursor;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.query.QueryParameters;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.PermissionScope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 来源依赖全部 Mock：列表、计数与已读服务分支，不连接数据库。 */
class UserNotificationServiceTest {
    private static final List<String> TYPES = List.of("REVIEW_SUBMITTED", "REVIEW_APPROVED", "REVIEW_REJECTED");
    private static final Instant NOW = Instant.parse("2026-10-25T08:00:00Z");
    private final AdminQueryAccess access = mock(AdminQueryAccess.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final PermissionService.PrincipalHandle handle = mock(PermissionService.PrincipalHandle.class);
    private final UserNotificationMapper mapper = mock(UserNotificationMapper.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final UserNotificationService service = service(DerivationHarness.reviewRegistry());

    @BeforeEach
    void setUp() {
        when(access.current()).thenReturn(handle);
        when(handle.userId()).thenReturn(7L);
        when(permissions.resolve(handle, "view")).thenReturn(PermissionScope.of(7, (short) 2, List.of(3L), List.of("1:7", "2:4")));
    }

    @Test
    void sparseVisiblePageIsFilledByTheFilteredQueryAndCursorUsesLastVisiblePosition() {
        // 较新的 100–98 已不可见：SQL 过滤后返回 97、60、12，服务只截取 limit+1，不在内存中丢弃。
        when(mapper.list(7, TYPES, List.of(3L), List.of("1:7", "2:4"), (short) 2, null, 3))
                .thenReturn(List.of(row(97, "REVIEW_SUBMITTED"), row(60, "REVIEW_APPROVED"), row(12, "REVIEW_REJECTED")));
        var first = service.list(Map.of("limit", List.of("2")));
        assertThat(first.items()).extracting(NotificationViews.Item::type)
                .containsExactly(NotificationType.REVIEW_SUBMITTED, NotificationType.REVIEW_APPROVED);
        assertThat(first.items().getFirst().prompt()).isEqualTo("有版本等待你审核");
        assertThat(first.items().getFirst().target()).isEqualTo(NotificationTarget.review(10, 20, review(97)));
        assertThat(first.hasMore()).isTrue();
        assertThat(first.asOf()).isEqualTo(NOW);
        var cursor = QueryCursor.decode(first.nextCursor(), "user-notifications");
        assertThat(cursor.id()).isEqualTo(60);
        when(mapper.list(7, TYPES, List.of(3L), List.of("1:7", "2:4"), (short) 2, 60L, 3))
                .thenReturn(List.of(row(12, "REVIEW_REJECTED")));
        var next = service.list(Map.of("limit", List.of("2"), "cursor", List.of(first.nextCursor())));
        assertThat(next.items()).hasSize(1);
        assertThat(next.hasMore()).isFalse();
        assertThat(next.nextCursor()).isNull();
    }

    @Test
    void defaultAndMaximumLimitsOnlyAddOneLookahead() {
        when(mapper.list(anyLong(), anyList(), anyList(), anyList(), anyShort(), any(), anyInt())).thenReturn(List.of());
        assertThat(service.list(Map.of()).items()).isEmpty();
        service.list(Map.of("limit", List.of("100")));
        verify(mapper).list(7, TYPES, List.of(3L), List.of("1:7", "2:4"), (short) 2, null, 21);
        verify(mapper).list(7, TYPES, List.of(3L), List.of("1:7", "2:4"), (short) 2, null, 101);
    }

    @Test
    void foreignBrokenOrOtherQueryCursorsAndUnknownParametersAreRejectedBeforeQuery() {
        for (String cursor : List.of("broken!", QueryCursor.encode("user-notifications", QueryParameters.filters("userId", 8), 5),
                QueryCursor.encode("document-favorites", QueryParameters.filters("userId", 7), 5),
                QueryCursor.encode("document-review-mine", QueryParameters.filters("actor", 7, "kind", "mine"), 5))) {
            assertThatThrownBy(() -> service.list(Map.of("cursor", List.of(cursor)))).isInstanceOf(QueryException.class);
        }
        for (var input : List.of(Map.of("userId", List.of("8")), Map.of("recipient", List.of("8")),
                Map.of("limit", List.of("0")), Map.of("limit", List.of("101")), Map.of("limit", List.of("1", "2")))) {
            assertThatThrownBy(() -> service.list(input)).isInstanceOf(QueryException.class);
        }
        assertThatThrownBy(() -> service.unreadCount(Map.of("userId", List.of("8")))).isInstanceOf(QueryException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void countIsExactDecimalTrueZeroAndUsesTheSameScopeAsTheList() {
        when(mapper.countUnread(7, TYPES, List.of(3L), List.of("1:7", "2:4"), (short) 2)).thenReturn(Long.MAX_VALUE, 0L);
        assertThat(service.unreadCount(Map.of())).isEqualTo(new NotificationViews.UnreadCount("9223372036854775807", NOW));
        assertThat(service.unreadCount(Map.of()).count()).isEqualTo("0");
    }

    @Test
    void dependencyFailuresAreUnavailableNotEmptyPagesOrFalseZero() {
        when(mapper.list(anyLong(), anyList(), anyList(), anyList(), anyShort(), any(), anyInt()))
                .thenThrow(new IllegalStateException("private sql title"));
        when(mapper.countUnread(anyLong(), anyList(), anyList(), anyList(), anyShort()))
                .thenThrow(new IllegalStateException("private sql")).thenReturn(-1L);
        assertUnavailable(() -> service.list(Map.of()));
        assertUnavailable(() -> service.unreadCount(Map.of()));
        assertUnavailable(() -> service.unreadCount(Map.of()));
        when(permissions.resolve(handle, "view")).thenThrow(new IllegalStateException("acl down"));
        assertUnavailable(() -> service.list(Map.of()));
        // 依赖故障不是 SQL 与 Java 判定不一致，不计入 mismatch。
        assertThat(mismatches()).isZero();
    }

    @Test
    void rowsThatFailJavaRecheckOrBelongToSomeoneElseAreUnavailableAndCounted() {
        var hidden = new NotificationRow(5, UUID.randomUUID(), 7, "REVIEW_SUBMITTED", NOW, null, 10, 20, review(5), 1,
                true, true, true, false, "IN_REVIEW", 8L, true, 9L, "REVIEW_REQUIRED", "NONE", null, false, false);
        var foreign = new NotificationRow(6, UUID.randomUUID(), 8, "REVIEW_APPROVED", NOW, null, 10, 20, review(6), 1,
                true, true, true, false, "APPROVED", 8L, true, 9L, "REVIEW_REQUIRED", "PUBLISHED", null, false, false);
        var noRound = new NotificationRow(4, UUID.randomUUID(), 7, "REVIEW_APPROVED", NOW, null, 10, 20, null, 1,
                true, true, true, false, "APPROVED", 7L, true, 9L, "REVIEW_REQUIRED", "PUBLISHED", null, false, false);
        when(mapper.list(anyLong(), anyList(), anyList(), anyList(), anyShort(), any(), anyInt()))
                .thenReturn(List.of(hidden), List.of(foreign), List.of(row(5, "REVIEW_SUBMITTED"), row(5, "REVIEW_APPROVED")),
                        List.of(noRound));
        for (int i = 0; i < 4; i++) {
            assertUnavailable(() -> service.list(Map.of()));
        }
        assertThat(mismatches()).isEqualTo(4);
        assertThat(meters.get("kb.notification.read.mismatch").counter().getId().getTags()).isEmpty();
    }

    @Test
    void identityRejectionsPropagateWithoutQuerying() {
        when(access.current()).thenThrow(IdentityException.unauthorized("x"), IdentityException.passwordChangeRequired("x"),
                QueryException.forbidden());
        assertThatThrownBy(() -> service.list(Map.of())).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.httpStatus()).isEqualTo(401));
        assertThatThrownBy(() -> service.unreadCount(Map.of())).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.httpStatus()).isEqualTo(403));
        assertThatThrownBy(() -> service.markRead(UUID.randomUUID())).isInstanceOfSatisfying(QueryException.class,
                error -> assertThat(error.status()).isEqualTo(403));
        verifyNoInteractions(mapper);
    }

    @Test
    void noInstalledResolverMeansNothingIsReadableWithoutQuerying() {
        var empty = service(new NotificationTargetRegistry(List.of()));
        assertThat(empty.list(Map.of()).items()).isEmpty();
        assertThat(empty.unreadCount(Map.of()).count()).isEqualTo("0");
        verifyNoInteractions(mapper);
    }

    @Test
    void markReadIsOwnerOnlyIdempotentAndDoesNotResolveTargetsOrPermissions() {
        UUID id = UUID.randomUUID();
        var first = Instant.parse("2026-10-25T07:00:00Z");
        when(mapper.markRead(7, id)).thenReturn(new NotificationViews.ReadReceipt(id, first),
                new NotificationViews.ReadReceipt(id, first));
        assertThat(service.markRead(id).readAt()).isEqualTo(first);
        assertThat(service.markRead(id).readAt()).isEqualTo(first);
        UUID foreign = UUID.randomUUID();
        when(mapper.markRead(7, foreign)).thenReturn(null);
        assertThatThrownBy(() -> service.markRead(foreign)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.httpStatus()).isEqualTo(404));
        verify(permissions, never()).resolve(any(PermissionService.PrincipalHandle.class), any());
        verify(mapper, never()).list(anyLong(), anyList(), anyList(), anyList(), anyShort(), any(), anyInt());
        UUID mismatch = UUID.randomUUID();
        when(mapper.markRead(7, mismatch)).thenReturn(new NotificationViews.ReadReceipt(id, first));
        assertUnavailable(() -> service.markRead(mismatch));
    }

    private UserNotificationService service(NotificationTargetRegistry registry) {
        return new UserNotificationService(access, permissions, mapper, registry, new NotificationMetrics(meters),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static NotificationRow row(long id, String type) {
        boolean pending = type.equals("REVIEW_SUBMITTED");
        return new NotificationRow(id, UUID.nameUUIDFromBytes(("n" + id + type).getBytes()), 7, type, NOW, null, 10, 20,
                review(id), 1, true, true, true, pending, pending ? "IN_REVIEW" : type.substring(7),
                pending ? 8L : 7L, true, 9L, "REVIEW_REQUIRED", type.equals("REVIEW_APPROVED") ? "PUBLISHED" : "NONE",
                null, false, false);
    }

    private static UUID review(long id) {
        return UUID.nameUUIDFromBytes(("r" + id).getBytes());
    }

    private double mismatches() {
        return meters.get("kb.notification.read.mismatch").counter().count();
    }

    private static void assertUnavailable(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.httpStatus()).isEqualTo(503));
    }

    @Test
    void unusedTargetRegistryTypeIsNotReadable() {
        assertThat(new ReviewNotificationTargets().types()).doesNotContain(NotificationType.COMMENT_REPLIED);
    }
}
