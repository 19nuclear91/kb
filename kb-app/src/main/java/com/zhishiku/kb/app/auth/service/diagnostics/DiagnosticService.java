package com.zhishiku.kb.app.auth.service.diagnostics;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.IdentityQueryMapper;
import com.zhishiku.kb.app.auth.mapper.diagnostics.DiagnosticMapper;
import com.zhishiku.kb.app.auth.model.diagnostics.DiagnosticFacts;
import com.zhishiku.kb.app.auth.model.diagnostics.DiagnosticViews;
import com.zhishiku.kb.app.config.DiagnosticLimits;
import com.zhishiku.kb.app.query.QueryCursor;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.query.QueryParameters;
import com.zhishiku.kb.common.security.PrincipalType;
import com.zhishiku.kb.common.api.R;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Supplier;

@Service
public class DiagnosticService {
    private final DiagnosticAccess access;
    private final DiagnosticDatabase database;
    private final DiagnosticTargetLoader targets;
    private final DiagnosticMapper mapper;
    private final DiagnosticEvaluator evaluator;
    private final DiagnosticOverlay overlay;
    private final DiagnosticLimits limits;
    private final IdentityQueryMapper users;
    private final IdentityDepartmentMapper identities;

    public DiagnosticService(DiagnosticAccess access, DiagnosticDatabase database, DiagnosticTargetLoader targets,
                             DiagnosticMapper mapper, DiagnosticEvaluator evaluator, DiagnosticOverlay overlay,
                             DiagnosticLimits limits, IdentityQueryMapper users, IdentityDepartmentMapper identities) {
        this.access = access;
        this.database = database;
        this.targets = targets;
        this.mapper = mapper;
        this.evaluator = evaluator;
        this.overlay = overlay;
        this.limits = limits;
        this.users = users;
        this.identities = identities;
    }

    public R<DiagnosticViews.Result> diagnose(String kind, long id, boolean preview, Supplier<JsonNode> body) {
        return database.response(() -> {
            // 读取正文不查询目标；路径准入先行，附选文档和 manage 在解析后另行核对。
            access.require(kind, id, null, false);
            var request = DiagnosticInput.request(body.get(), kind, preview);
            var resource = access.require(kind, id, request.documentId(), request.intent() != null && request.intent().changesManage());
            String evaluatedAt = mapper.evaluatedAt().toString();
            var target = targets.load(request.userId());
            List<DiagnosticFacts.Acl> rows = target.passed()
                    ? mapper.sources(resource.spaceId(), resource.documentId(), target.keys(), limits.sources() + 1) : List.of();
            if (rows.size() + (target.globalManage() ? 1 : 0) > limits.sources()) {
                throw new DiagnosticException(DiagnosticException.Reason.DIAGNOSTIC_LIMIT_EXCEEDED);
            }
            if (rows.stream().map(DiagnosticFacts.Acl::key).distinct().count() != rows.size()
                    || rows.stream().anyMatch(row -> !target.keys().contains(row.principalKey())
                    || !row.principalKey().equals(row.principalType() + ":" + row.principalId())
                    || row.proposed() || !DiagnosticEvaluator.SPACE_PERMISSIONS.contains(row.permission())
                    || !(row.sourceKind().equals("SPACE") || row.sourceKind().equals("DOCUMENT") && resource.documentId() != null
                    && List.of("view", "download", "edit", "comment").contains(row.permission())))) {
                throw new DiagnosticException(DiagnosticException.Reason.DIAGNOSTIC_UNAVAILABLE);
            }
            var before = evaluator.evaluate(resource, target, rows);
            DiagnosticViews.Layer after = null;
            List<DiagnosticViews.Change> changes = List.of();
            if (request.intent() != null) {
                var hypothetical = overlay.apply(resource, target, rows, request.intent());
                after = evaluator.evaluate(resource, target, hypothetical.after());
                changes = hypothetical.changes();
            }
            return new DiagnosticViews.Result(DiagnosticViews.CONTRACT, preview ? "PREVIEW" : "EXPLAIN",
                    new DiagnosticViews.User(Long.toString(target.userId()), target.label()), resource(resource), evaluatedAt,
                    DiagnosticViews.ASSUMPTIONS, before, after, changes);
        });
    }

    public R<DiagnosticViews.Users> search(String kind, long id, Supplier<JsonNode> body) {
        return database.response(() -> {
            var resource = access.require(kind, id, null, false);
            var request = DiagnosticInput.search(body.get());
            var actor = identities.findIdentity(resource.actorId());
            if (actor == null || actor.getAuthEpoch() == null) {
                throw new DiagnosticException(DiagnosticException.Reason.DIAGNOSTIC_UNAVAILABLE);
            }
            var filters = QueryParameters.filters("actor", resource.actorId(), "epoch", actor.getAuthEpoch(),
                    "kind", kind, "resource", id, "q", request.q(), "limit", request.limit());
            try {
                var cursor = QueryCursor.decode(request.cursor(), "diagnostic-users");
                if (cursor != null) {
                    cursor.verify("diagnostic-users", filters);
                }
                var rows = users.principals(PrincipalType.USER, QueryParameters.like(request.q()),
                        cursor == null ? null : cursor.id(), request.limit() + 1);
                boolean more = rows.size() > request.limit();
                var items = rows.stream().limit(request.limit()).map(row ->
                        new DiagnosticViews.User(Long.toString(row.principalId()), row.label())).toList();
                String next = more ? QueryCursor.encode("diagnostic-users", filters, Long.parseLong(items.getLast().userId())) : null;
                return new DiagnosticViews.Users("AUTHORIZATION_DIAGNOSTIC_USERS_V1", resource(resource), items, more, next);
            } catch (QueryException failure) {
                throw new DiagnosticException(DiagnosticException.Reason.INVALID_INPUT);
            }
        });
    }

    private static DiagnosticViews.Resource resource(DiagnosticAccess.Resource resource) {
        return new DiagnosticViews.Resource(resource.kind(), Long.toString(resource.spaceId()),
                resource.documentId() == null ? null : Long.toString(resource.documentId()));
    }
}
