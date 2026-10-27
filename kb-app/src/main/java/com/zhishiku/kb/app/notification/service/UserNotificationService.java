package com.zhishiku.kb.app.notification.service;

import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.notification.mapper.UserNotificationMapper;
import com.zhishiku.kb.app.notification.model.NotificationRow;
import com.zhishiku.kb.app.notification.model.NotificationTarget;
import com.zhishiku.kb.app.notification.model.NotificationType;
import com.zhishiku.kb.app.notification.model.NotificationViews;
import com.zhishiku.kb.app.query.QueryCursor;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.query.QueryParameters;
import com.zhishiku.kb.common.api.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 本人通知读取与已读；身份只取服务端会话，管理或审计资格不扩大范围。
 * 读取不创建审计、模型用量或新通知；依赖失败为 503，不伪装为空列表或零未读。
 */
@Service
@Transactional(readOnly = true, timeout = 10)
public class UserNotificationService {
    static final String CURSOR_KIND = "user-notifications";
    private final AdminQueryAccess access;
    private final PermissionService permissions;
    private final UserNotificationMapper mapper;
    private final NotificationTargetRegistry registry;
    private final NotificationMetrics metrics;
    private final Clock clock;

    public UserNotificationService(AdminQueryAccess access, PermissionService permissions, UserNotificationMapper mapper,
                                   NotificationTargetRegistry registry, NotificationMetrics metrics, Clock clock) {
        this.access = access;
        this.permissions = permissions;
        this.mapper = mapper;
        this.registry = registry;
        this.metrics = metrics;
        this.clock = clock;
    }

    public NotificationViews.Page list(Map<String, List<String>> input) {
        return guarded(() -> {
            var handle = access.current();
            var parameters = new QueryParameters(input, "limit", "cursor");
            int limit = parameters.limit();
            var filters = QueryParameters.filters("userId", handle.userId());
            var cursor = QueryCursor.decode(parameters.cursor(), CURSOR_KIND);
            if (cursor != null) cursor.verify(CURSOR_KIND, filters);
            var asOf = clock.instant();
            var types = registry.readableTypes();
            if (types.isEmpty()) return new NotificationViews.Page(List.of(), false, null, asOf);
            var scope = permissions.resolve(handle, PermissionService.PERM_VIEW);
            var rows = mapper.list(handle.userId(), types, scope.visibleSpaceIdList(), List.of(scope.principalKeys()),
                    scope.clearanceLevel(), cursor == null ? null : cursor.id(), limit + 1);
            if (rows == null || rows.size() > limit + 1) throw new IllegalStateException("Notification page is unavailable");
            boolean more = rows.size() > limit;
            var page = rows.subList(0, Math.min(limit, rows.size()));
            List<NotificationViews.Item> items = new ArrayList<>(page.size());
            var seen = new HashSet<UUID>();
            Long previous = cursor == null ? null : cursor.id();
            for (var row : page) {
                if (row.recipientUserId() != handle.userId() || !types.contains(row.type())
                        || (previous != null && row.id() >= previous) || !seen.add(row.notificationId()))
                    throw mismatch();
                previous = row.id();
                var type = NotificationType.valueOf(row.type());
                // SQL 已过滤；Java 复核不一致时安全失败，不输出未经确认的目标。
                var target = resolveTarget(row).orElseThrow(this::mismatch);
                items.add(new NotificationViews.Item(row.notificationId(), type, type.prompt(), row.createdAt(), row.readAt(), target));
            }
            String next = more ? QueryCursor.encode(CURSOR_KIND, filters, page.getLast().id()) : null;
            return new NotificationViews.Page(items, more, next, asOf);
        });
    }

    public NotificationViews.UnreadCount unreadCount(Map<String, List<String>> input) {
        return guarded(() -> {
            var handle = access.current();
            new QueryParameters(input);
            var asOf = clock.instant();
            var types = registry.readableTypes();
            if (types.isEmpty()) return new NotificationViews.UnreadCount("0", asOf);
            var scope = permissions.resolve(handle, PermissionService.PERM_VIEW);
            long count = mapper.countUnread(handle.userId(), types, scope.visibleSpaceIdList(),
                    List.of(scope.principalKeys()), scope.clearanceLevel());
            if (count < 0) throw new IllegalStateException("Unread count is unavailable");
            return new NotificationViews.UnreadCount(Long.toString(count), asOf);
        });
    }

    /** 隐藏目标的本人旧通知仍可标记；未知与他人通知统一 404，不回显目标。 */
    @Transactional(timeout = 5)
    public NotificationViews.ReadReceipt markRead(UUID notificationId) {
        return guarded(() -> {
            var handle = access.current();
            Objects.requireNonNull(notificationId, "notificationId");
            var receipt = mapper.markRead(handle.userId(), notificationId);
            if (receipt == null) throw ApiException.notFound("通知不存在");
            if (!notificationId.equals(receipt.notificationId()) || receipt.readAt() == null)
                throw new IllegalStateException("Read receipt is inconsistent");
            return receipt;
        });
    }

    private Optional<NotificationTarget> resolveTarget(NotificationRow row) {
        try {
            return registry.resolve(row);
        } catch (RuntimeException inconsistent) {
            throw mismatch();
        }
    }

    /** SQL 结果违背本人归属、排序、去重或 Java 可见性复核；计数后按 503 处理，不记录标识。 */
    private IllegalStateException mismatch() {
        metrics.readMismatch();
        return new IllegalStateException("Notification read is inconsistent");
    }

    private <T> T guarded(Supplier<T> action) {
        try {
            return action.get();
        } catch (ApiException | QueryException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw ApiException.unavailable("通知服务暂不可用");
        }
    }
}
