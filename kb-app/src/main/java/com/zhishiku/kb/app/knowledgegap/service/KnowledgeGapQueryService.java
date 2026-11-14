package com.zhishiku.kb.app.knowledgegap.service;

import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.IdentityQueryService;
import com.zhishiku.kb.app.auth.service.KnowledgeGapAssigneeService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.knowledgegap.mapper.KnowledgeGapMapper;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapRows;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapState;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapViews;
import com.zhishiku.kb.app.query.QueryCursor;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.query.QueryPage;
import com.zhishiku.kb.app.query.QueryParameters;
import com.zhishiku.kb.common.api.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 当次同源 SQL 授权后分页；详情不内嵌无界历史，资料和人员只经窄服务投影。 */
@Service
@Transactional(readOnly = true, timeout = 5)
public class KnowledgeGapQueryService {
    private static final String LIST_CURSOR = "knowledge-gap-list";
    private static final String HISTORY_CURSOR = "knowledge-gap-history";
    private static final Set<String> EVENT_KINDS = Set.of("GAP_CREATED", "GAP_ASSIGNED", "NOTE_ADDED", "GAP_RETURNED",
            "GAP_SOLUTION_SUBMITTED", "GAP_SOLUTION_REJECTED", "GAP_RESOLVED", "GAP_WITHDRAWN", "GAP_CLOSED");
    private final KnowledgeGapAccess access;
    private final KnowledgeGapMapper mapper;
    private final IdentityQueryService identities;
    private final KnowledgeGapAssigneeService assignees;
    private final KnowledgeGapReferenceService references;

    public KnowledgeGapQueryService(KnowledgeGapAccess access, KnowledgeGapMapper mapper, IdentityQueryService identities,
                                    KnowledgeGapAssigneeService assignees, KnowledgeGapReferenceService references) {
        this.access = access;
        this.mapper = mapper;
        this.identities = identities;
        this.assignees = assignees;
        this.references = references;
    }

    public QueryPage<KnowledgeGapViews.Item> list(Map<String, List<String>> input) {
        return KnowledgeGapFailures.guarded(() -> {
            var actor = access.current();
            var parameters = new QueryParameters(input, "view", "status", "limit", "cursor");
            String view = parameters.exactText("view", 8);
            if (view == null) {
                view = "mine";
            }
            if (!view.equals("mine") && !view.equals("assigned")) {
                throw QueryException.invalid();
            }
            if (view.equals("assigned") && !has(actor, Permission.KNOWLEDGE_GAP_HANDLE)) {
                throw ApiException.forbidden("知识问题单操作被拒绝");
            }
            return list(actor, view, parameters);
        });
    }

    public QueryPage<KnowledgeGapViews.Item> queue(Map<String, List<String>> input) {
        return KnowledgeGapFailures.guarded(() -> list(access.requireCurrent(Permission.KNOWLEDGE_GAP_READ), "queue",
                new QueryParameters(input, "status", "limit", "cursor")));
    }

    public KnowledgeGapViews.Detail detail(String rawGapId, Map<String, List<String>> input) {
        return KnowledgeGapFailures.guarded(() -> {
            var actor = access.current();
            long gapId = QueryParameters.positiveValue(rawGapId);
            var gap = access.requireVisible(actor, gapId);
            new QueryParameters(input);
            var entry = gap.pendingSolutionId() == null ? null : entry(gapId, gap.pendingSolutionId(), actor.userId());
            if (entry != null && !"SOLUTION".equals(entry.kind())) {
                throw KnowledgeGapFailures.unavailable();
            }
            var users = new LinkedHashSet<Long>();
            add(users, gap.reporterUserId(), gap.assigneeUserId(), entry == null ? null : entry.authorUserId());
            var labels = labels(users);
            return new KnowledgeGapViews.Detail(gap.id(), gap.category(), gap.source(), gap.description(), gap.consentVersion(),
                    gap.state(), gap.version(), participant(gap.reporterUserId(), labels), participant(gap.assigneeUserId(), labels),
                    needsReassignment(actor, gap), gap.createdAt(), gap.updatedAt(), access.capabilities(actor, gap),
                    entry == null ? null : references.project(actor, gap, entry, labels.get(entry.authorUserId())));
        });
    }

    public QueryPage<KnowledgeGapViews.HistoryItem> history(String rawGapId, Map<String, List<String>> input) {
        return KnowledgeGapFailures.guarded(() -> {
            var actor = access.current();
            long gapId = QueryParameters.positiveValue(rawGapId);
            var gap = access.requireVisible(actor, gapId);
            var parameters = new QueryParameters(input, "limit", "cursor");
            var filters = QueryParameters.filters("actor", actor.userId(), "gapId", gapId);
            var cursor = cursor(parameters, HISTORY_CURSOR, filters);
            Long after = cursor == null ? null : cursor.id() - 1;
            int limit = parameters.limit();
            var rows = mapper.history(gapId, actor.userId(), after, limit + 1);
            if (rows == null || rows.size() > limit + 1) {
                throw KnowledgeGapFailures.unavailable();
            }
            long previous = after == null ? -1 : after;
            for (var row : rows) {
                if (row == null || row.gapId() != gapId || row.eventId() == null || row.resultVersion() <= previous
                        || row.actorUserId() <= 0 || !EVENT_KINDS.contains(row.kind())) {
                    throw KnowledgeGapFailures.unavailable();
                }
                previous = row.resultVersion();
            }
            var page = QueryPage.slice(rows, limit,
                    row -> QueryCursor.encode(HISTORY_CURSOR, filters, Math.incrementExact(row.resultVersion())));
            var entries = new HashMap<UUID, KnowledgeGapRows.Entry>();
            var users = new LinkedHashSet<Long>();
            for (var event : page.items()) {
                add(users, event.actorUserId(), event.assigneeUserId());
                if (event.entryId() != null) {
                    var entry = entry(gapId, event.entryId(), actor.userId());
                    if (entry.position() != event.resultVersion() || entry.authorUserId() != event.actorUserId()) {
                        throw KnowledgeGapFailures.unavailable();
                    }
                    entries.put(event.entryId(), entry);
                }
            }
            var labels = labels(users);
            var items = new ArrayList<KnowledgeGapViews.HistoryItem>();
            for (var event : page.items()) {
                var entry = entries.get(event.entryId());
                items.add(new KnowledgeGapViews.HistoryItem(event.eventId(), event.kind(), event.resultVersion(),
                        participant(event.actorUserId(), labels), participant(event.assigneeUserId(), labels), event.occurredAt(),
                        event.solutionId(), entry == null ? null : references.project(actor, gap, entry, labels.get(entry.authorUserId()))));
            }
            return new QueryPage<>(items, page.hasMore(), page.nextCursor());
        });
    }

    private QueryPage<KnowledgeGapViews.Item> list(PermissionService.PrincipalHandle actor, String view, QueryParameters parameters) {
        KnowledgeGapState status = null;
        String statusValue = parameters.exactText("status", 24);
        if (statusValue != null) {
            try {
                status = KnowledgeGapState.valueOf(statusValue);
            } catch (IllegalArgumentException invalid) {
                throw QueryException.invalid();
            }
        }
        var filters = QueryParameters.filters("actor", actor.userId(), "view", view, "status", status);
        var cursor = cursor(parameters, LIST_CURSOR, filters);
        Long before = cursor == null ? null : cursor.id();
        int limit = parameters.limit();
        var rows = mapper.list(actor.userId(), view, status, before, limit + 1);
        if (rows == null || rows.size() > limit + 1) {
            throw KnowledgeGapFailures.unavailable();
        }
        Long previous = before;
        for (var row : rows) {
            if (row == null || row.id() <= 0 || (previous != null && row.id() >= previous)
                    || (status != null && row.state() != status)
                    || (view.equals("mine") && row.reporterUserId() != actor.userId())
                    || (view.equals("assigned") && !Long.valueOf(actor.userId()).equals(row.assigneeUserId()))) {
                throw KnowledgeGapFailures.unavailable();
            }
            row.workflow();
            access.capabilities(actor, row);
            previous = row.id();
        }
        var page = QueryPage.slice(rows, limit, row -> QueryCursor.encode(LIST_CURSOR, filters, row.id()));
        var users = new LinkedHashSet<Long>();
        for (var gap : page.items()) {
            add(users, gap.reporterUserId(), gap.assigneeUserId());
        }
        var labels = labels(users);
        var items = page.items().stream().map(gap -> new KnowledgeGapViews.Item(gap.id(), gap.category(), gap.source(), gap.state(),
                gap.version(), participant(gap.reporterUserId(), labels), participant(gap.assigneeUserId(), labels),
                needsReassignment(actor, gap), gap.createdAt(), gap.updatedAt())).toList();
        return new QueryPage<>(items, page.hasMore(), page.nextCursor());
    }

    private KnowledgeGapRows.Entry entry(long gapId, UUID entryId, long actor) {
        var entry = mapper.entry(gapId, entryId, actor);
        if (entry == null || entry.gapId() != gapId || !entryId.equals(entry.entryId())) {
            throw KnowledgeGapFailures.unavailable();
        }
        return entry;
    }

    private boolean needsReassignment(PermissionService.PrincipalHandle actor, KnowledgeGapRows.Gap gap) {
        return !gap.state().terminal() && gap.assigneeUserId() != null && has(actor, Permission.KNOWLEDGE_GAP_READ)
                && !assignees.eligible(gap.assigneeUserId());
    }

    private Map<Long, String> labels(Set<Long> users) {
        if (users.isEmpty()) {
            return Map.of();
        }
        var result = identities.labels(users);
        if (result == null || !users.containsAll(result.keySet())) {
            throw KnowledgeGapFailures.unavailable();
        }
        return result;
    }

    private static boolean has(PermissionService.PrincipalHandle actor, Permission permission) {
        return actor.functionalPermissions().contains(permission.code());
    }

    private static KnowledgeGapViews.Participant participant(Long userId, Map<Long, String> labels) {
        return userId == null ? null : new KnowledgeGapViews.Participant(userId, labels.get(userId));
    }

    private static void add(Set<Long> users, Long... ids) {
        for (Long id : ids) {
            if (id != null) {
                users.add(id);
            }
        }
    }

    private static QueryCursor cursor(QueryParameters parameters, String kind, Map<String, Object> filters) {
        var cursor = QueryCursor.decode(parameters.cursor(), kind);
        if (cursor != null) {
            cursor.verify(kind, filters);
        }
        return cursor;
    }
}
