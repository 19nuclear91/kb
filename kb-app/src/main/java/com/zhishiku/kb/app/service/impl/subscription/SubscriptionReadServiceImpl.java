package com.zhishiku.kb.app.service.impl.subscription;

import com.zhishiku.kb.app.domain.dto.subscription.SubscriptionQuery;
import com.zhishiku.kb.app.domain.dto.subscription.SubscriptionTarget;
import com.zhishiku.kb.app.domain.vo.subscription.SubscriptionViews;
import com.zhishiku.kb.app.enums.subscription.SubscriptionKind;
import com.zhishiku.kb.app.enums.subscription.SubscriptionMode;
import com.zhishiku.kb.app.enums.subscription.SubscriptionState;
import com.zhishiku.kb.app.mapper.subscription.SubscriptionMapper;
import com.zhishiku.kb.app.mapper.subscription.SubscriptionQueryMapper;
import com.zhishiku.kb.app.service.subscription.SubscriptionFailure;
import com.zhishiku.kb.app.service.subscription.SubscriptionReadService;
import com.zhishiku.kb.common.security.PermissionScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** 同次快照中读取本人关系和动态目标标签，所有结果只属于当前会话用户。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
public class SubscriptionReadServiceImpl implements SubscriptionReadService {
    private final SubscriptionAccess access;
    private final SubscriptionMapper mapper;
    private final SubscriptionQueryMapper queries;
    private final SubscriptionCommands commands;

    @Override
    public SubscriptionViews.Page<SubscriptionViews.Item> list(long actor, SubscriptionKind kind, SubscriptionState state,
                                                              int limit, String cursor) {
        SubscriptionRules.limit(limit);
        var scope = access.current(actor);
        String context = SubscriptionCursor.context(actor, "subscriptions", kind, state);
        String position = SubscriptionCursor.decode(cursor, context);
        UUID after = position == null ? null : SubscriptionRules.uuid(position);
        var rows = queries.subscriptions(query(scope, kind, null, after, null, state, limit + 1, true));
        checkPage(rows, limit);
        boolean more = rows.size() > limit;
        var items = new ArrayList<SubscriptionViews.Item>();
        for (var row : rows.subList(0, Math.min(limit, rows.size()))) {
            if (row.userId() != actor) {
                throw SubscriptionFailure.unavailable();
            }
            var target = access.target(scope, row.kind(), row.targetId(), true);
            items.add(new SubscriptionViews.Item(row.id(), row.generationId(), row.kind(), row.state(),
                    Long.toString(row.stateVersion()), target != null, target == null ? null : Long.toString(target.targetId()),
                    target == null ? null : target.label(), target == null ? null : target.enabled(), row.createdAt(), row.updatedAt()));
        }
        String next = more ? SubscriptionCursor.encode(context, items.getLast().subscriptionId().toString()) : null;
        return new SubscriptionViews.Page<>(items, more, next, Instant.now());
    }

    @Override
    public SubscriptionViews.Page<SubscriptionViews.Candidate> candidates(long actor, SubscriptionKind kind, String text,
                                                                         int limit, String cursor) {
        if (kind == null) {
            throw SubscriptionFailure.invalid();
        }
        SubscriptionRules.limit(limit);
        String pattern = SubscriptionRules.pattern(text);
        var scope = access.current(actor);
        String context = SubscriptionCursor.context(actor, "subscription-candidates", kind, pattern);
        String position = SubscriptionCursor.decode(cursor, context);
        Long after = position == null ? null : SubscriptionRules.number(position, 1);
        var rows = queries.candidates(query(scope, kind, pattern, null, after, null, limit + 1, false));
        checkPage(rows, limit);
        boolean more = rows.size() > limit;
        var items = rows.subList(0, Math.min(limit, rows.size())).stream().map(row -> candidate(kind, row)).toList();
        String next = more ? SubscriptionCursor.encode(context, items.getLast().targetId()) : null;
        return new SubscriptionViews.Page<>(items, more, next, Instant.now());
    }

    @Override
    public SubscriptionViews.Candidate target(long actor, SubscriptionKind kind, long targetId) {
        if (kind == null || targetId <= 0) {
            throw SubscriptionFailure.invalid();
        }
        var target = access.target(access.current(actor), kind, targetId, false);
        if (target == null) {
            throw SubscriptionFailure.hidden();
        }
        return candidate(kind, target);
    }

    @Override
    public SubscriptionViews.Preference preference(long actor) {
        access.current(actor);
        var preference = mapper.preference(actor);
        return new SubscriptionViews.Preference(preference == null ? SubscriptionMode.WEEKLY : preference.mode(),
                preference == null ? "0" : Long.toString(preference.version()), preference == null ? null : preference.effectiveAt(),
                "Asia/Shanghai", "每周一09:00汇总上一周周一00:00至本周一00:00的更新");
    }

    @Override
    public SubscriptionViews.ReceiptLookup receipt(long actor, String requestId) {
        access.current(actor);
        return commands.lookup(actor, requestId);
    }

    private SubscriptionViews.Candidate candidate(SubscriptionKind kind, SubscriptionTarget row) {
        return new SubscriptionViews.Candidate(kind, Long.toString(row.targetId()), row.label(), row.enabled(),
                row.subscriptionId(), row.generationId(), Long.toString(row.stateVersion()), row.state());
    }

    private SubscriptionQuery query(PermissionScope scope, SubscriptionKind kind, String pattern, UUID afterUuid,
                                     Long afterId, SubscriptionState state, int limit, boolean allowDisabled) {
        return new SubscriptionQuery(scope.userId(), scope.visibleSpaceIdList(), Arrays.asList(scope.principalKeys()),
                scope.clearanceLevel(), kind, null, pattern, afterUuid, afterId, state, limit, allowDisabled);
    }

    private void checkPage(List<?> rows, int limit) {
        if (rows == null || rows.size() > limit + 1) {
            throw SubscriptionFailure.unavailable();
        }
    }
}
