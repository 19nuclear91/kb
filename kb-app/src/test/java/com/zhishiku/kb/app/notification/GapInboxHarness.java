package com.zhishiku.kb.app.notification;

import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.config.UuidTypeHandler;
import com.zhishiku.kb.app.notification.mapper.UserNotificationMapper;
import com.zhishiku.kb.app.notification.model.GapNotificationFact;
import com.zhishiku.kb.app.notification.model.NotificationRow;
import com.zhishiku.kb.app.notification.service.CommentNotificationTargets;
import com.zhishiku.kb.app.notification.service.GapNotificationTargets;
import com.zhishiku.kb.app.notification.service.NotificationMetrics;
import com.zhishiku.kb.app.notification.service.NotificationTargetRegistry;
import com.zhishiku.kb.app.notification.service.ReviewNotificationTargets;
import com.zhishiku.kb.app.notification.service.UserNotificationService;
import com.zhishiku.kb.app.notification.web.NotificationController;
import com.zhishiku.kb.app.support.MapperRowStub;
import com.zhishiku.kb.common.security.PermissionScope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 服务与内存过滤仓储：两类来源先按当次事实过滤再排序分页，不代替 SQL 执行证据。 */
final class GapInboxHarness {
    final AdminQueryAccess access = mock(AdminQueryAccess.class);
    final PermissionService permissions = mock(PermissionService.class);
    final PermissionService.PrincipalHandle actor = mock(PermissionService.PrincipalHandle.class);
    final UserNotificationMapper mapper = mock(UserNotificationMapper.class);
    final NotificationTargetRegistry registry = new NotificationTargetRegistry(List.of(new ReviewNotificationTargets(),
            new CommentNotificationTargets(), new GapNotificationTargets()));
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final UserNotificationService service = new UserNotificationService(access, permissions, mapper, registry,
            new NotificationMetrics(meters), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    final NotificationController controller = new NotificationController(service);
    final List<NotificationRow> rows = new ArrayList<>();
    final List<List<String>> listTypes = new ArrayList<>();
    final List<List<String>> countTypes = new ArrayList<>();
    long userId = 10;
    boolean documentReadable = true;

    GapInboxHarness() {
        when(access.current()).thenReturn(actor);
        when(actor.userId()).thenAnswer(call -> userId);
        when(permissions.resolve(actor, "view")).thenAnswer(call -> PermissionScope.of(userId, (short) 2,
                documentReadable ? List.of(3L) : List.of(), List.of("1:" + userId)));
        when(mapper.list(anyLong(), anyList(), anyList(), anyList(), anyShort(), any(), anyInt())).thenAnswer(call -> {
            long user = call.getArgument(0);
            List<String> types = call.getArgument(1);
            Long before = call.getArgument(5);
            int size = call.getArgument(6);
            listTypes.add(List.copyOf(types));
            return visible(user, types).stream().filter(row -> before == null || row.id() < before).limit(size).toList();
        });
        when(mapper.countUnread(anyLong(), anyList(), anyList(), anyList(), anyShort())).thenAnswer(call -> {
            List<String> types = call.getArgument(1);
            countTypes.add(List.copyOf(types));
            return visible(call.getArgument(0), types).stream().filter(row -> row.readAt() == null).count();
        });
    }

    private List<NotificationRow> visible(long user, List<String> types) {
        return rows.stream().filter(row -> row.recipientUserId() == user && types.contains(row.type()))
                .filter(row -> row.gap() != null || documentReadable)
                .filter(row -> registry.resolve(row).isPresent()).sorted(Comparator.comparingLong(NotificationRow::id).reversed()).toList();
    }

    static Configuration configuration() throws Exception {
        var configuration = MapperRowStub.configuration("/mapper/DocumentMapper.xml");
        configuration.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
        for (String resource : List.of("/mapper/DocumentReviewEligibilityMapper.xml", "/mapper/DocumentCommentMapper.xml",
                "/mapper/KnowledgeGapMapper.xml", "/mapper/UserNotificationMapper.xml")) {
            try (var input = GapInboxHarness.class.getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        return configuration;
    }

    static NotificationRow comment(long id) {
        return new NotificationRow(id, UUID.randomUUID(), 10, "COMMENT_REPLIED", Instant.EPOCH, null, 11, 21, null, null,
                true, true, true, false, null, null, false, 8L, "AUTO", "PUBLISHED", UUID.randomUUID(), true, true);
    }

    static NotificationRow solution(long id, boolean visible) {
        UUID solution = UUID.randomUUID();
        var fact = new GapNotificationFact(11L, UUID.randomUUID(), "GAP_SOLUTION_SUBMITTED", "AWAITING_CONFIRMATION", 2L,
                10L, 8L, 1L, 1L, null, solution, solution, 2L, visible, true);
        return NotificationRow.knowledgeGap(id, UUID.randomUUID(), 10, fact.eventKind(), Instant.EPOCH, null, fact);
    }

    static NotificationRow withGap(NotificationRow row, GapNotificationFact fact) {
        return NotificationRow.knowledgeGap(row.id(), row.notificationId(), row.recipientUserId(), row.type(), row.createdAt(), row.readAt(), fact);
    }
}
