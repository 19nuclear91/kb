package com.zhishiku.kb.app.audit.service;

import com.zhishiku.kb.app.audit.model.AuditQueryViews.Detail;
import com.zhishiku.kb.app.audit.model.AuditQueryViews.Page;
import com.zhishiku.kb.app.auth.model.AuditQueryFilter;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.AuditQuerySource;
import com.zhishiku.kb.app.query.QueryCursor;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.query.QueryPage;
import com.zhishiku.kb.app.query.QueryParameters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 新审计资格独立校验；游标窗口固定，身份资格每次重新取得。
 */
@Service
@Transactional(readOnly = true, timeout = 5)
public class AuditQueryService {
    private final AuditQuerySource source;
    private final AdminQueryAccess access;
    private final AuditSummaryProjector projector;
    private final Clock clock;

    @Autowired
    public AuditQueryService(AuditQuerySource source, AdminQueryAccess access, AuditSummaryProjector projector) {
        this(source, access, projector, Clock.systemUTC());
    }

    public AuditQueryService(AuditQuerySource source, AdminQueryAccess access, AuditSummaryProjector projector,
                             Clock clock) {
        this.source = source;
        this.access = access;
        this.projector = projector;
        this.clock = clock;
    }

    public Page list(Map<String, List<String>> input) {
        access.require(Permission.AUDIT_READ);
        var p = new QueryParameters(input, "actorUserId", "operationScope", "operation", "targetType", "targetId",
                "status", "requestId", "loginUsername", "from", "to", "limit", "cursor");
        Long actor = p.positive("actorUserId"), target = p.positive("targetId");
        String scope = p.exactText("operationScope", 128), operation = p.exactText("operation", 128);
        String type = p.exactText("targetType", 64), status = p.exactText("status", 16);
        String request = p.exactText("requestId", 256), username = p.exactText("loginUsername", 128);
        if (target != null && type == null || status != null && !Set.of("SUCCESS", "FAILED").contains(status)) {
            throw QueryException.invalid();
        }
        var cursor = QueryCursor.decode(p.cursor(), "audit");
        Instant to = p.instant("to"), from = p.instant("from");
        if (to == null) to = cursor == null ? clock.instant().truncatedTo(ChronoUnit.MICROS) : cursor.to();
        if (from == null) from = cursor == null ? to.minus(7, ChronoUnit.DAYS) : cursor.from();
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0)
            throw QueryException.invalid();
        var filters = QueryParameters.filters("actorUserId", actor, "operationScope", scope, "operation", operation,
                "targetType", type, "targetId", target, "status", status, "requestId", request,
                "loginUsername", username, "from", from, "to", to);
        if (cursor != null) cursor.verify("audit", filters);
        int limit = p.limit();
        var filter = new AuditQueryFilter(actor, scope, operation, type, target, status, request, username,
                from, to, cursor == null ? null : cursor.time(), cursor == null ? null : cursor.id(), limit + 1);
        var rows = source.list(filter).stream().map(projector::metadata).toList();
        Instant fixedFrom = from, fixedTo = to;
        var page = QueryPage.slice(rows, limit, row -> QueryCursor.encode("audit", filters, row.id(), row.createdAt()
                , fixedFrom, fixedTo));
        return new Page(page.items(), page.hasMore(), page.nextCursor(), from, to);
    }

    public Detail detail(String rawId, Map<String, List<String>> input) {
        access.require(Permission.AUDIT_READ);
        new QueryParameters(input);
        if (rawId == null || !rawId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F" +
                "]{12}")) {
            throw QueryException.invalid();
        }
        var event = source.detail(UUID.fromString(rawId));
        if (event == null) throw QueryException.missing();
        return projector.detail(event);
    }
}
