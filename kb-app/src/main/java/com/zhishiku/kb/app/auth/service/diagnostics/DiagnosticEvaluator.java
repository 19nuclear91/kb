package com.zhishiku.kb.app.auth.service.diagnostics;

import com.zhishiku.kb.app.auth.mapper.diagnostics.DiagnosticMapper;
import com.zhishiku.kb.app.auth.model.diagnostics.DiagnosticFacts;
import com.zhishiku.kb.app.auth.model.diagnostics.DiagnosticViews;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** SQL 负责授权求值；此处只生成有固定原因的最小披露投影。 */
@Component
public class DiagnosticEvaluator {
    public static final List<String> SPACE_PERMISSIONS = List.of("view", "download", "edit", "review", "comment", "manage");
    public static final List<String> DOCUMENT_PERMISSIONS = List.of("view", "download", "edit", "review", "comment");
    private final DiagnosticMapper mapper;

    public DiagnosticEvaluator(DiagnosticMapper mapper) {
        this.mapper = mapper;
    }

    DiagnosticViews.Layer evaluate(DiagnosticAccess.Resource resource, DiagnosticTargetLoader.Target target,
                                    List<DiagnosticFacts.Acl> rows) {
        if (!target.passed()) {
            return new DiagnosticViews.Layer("BLOCKED", blocked(SPACE_PERMISSIONS),
                    resource.documentId() == null ? null : blocked(DOCUMENT_PERMISSIONS),
                    unassessed(resource.documentId() == null ? "NOT_APPLICABLE" : "NOT_EVALUATED", resource.documentId() != null), List.of());
        }
        Map<String, DiagnosticFacts.Grant> grants = new HashMap<>();
        for (var grant : mapper.grants(resource.spaceId(), resource.documentId(), target.keys(), rows, target.globalManage())) {
            if (!SPACE_PERMISSIONS.contains(grant.permission()) || grants.put(grant.permission(), grant) != null) {
                throw unavailable();
            }
        }
        if (grants.size() != SPACE_PERMISSIONS.size()) {
            throw unavailable();
        }
        DiagnosticFacts.Current current = null;
        if (resource.documentId() != null) {
            current = mapper.current(resource.spaceId(), resource.documentId(), target.clearance(), target.keys(), rows);
            if (current == null || (current.hasCurrent() && !current.validCurrent())) {
                throw unavailable();
            }
        }
        List<DiagnosticViews.Source> sources = new ArrayList<>();
        for (var row : rows) {
            String type = switch (row.principalType()) {
                case 1 -> "USER";
                case 2 -> "ROLE";
                case 3 -> "DEPT";
                default -> throw unavailable();
            };
            sources.add(new DiagnosticViews.Source(row.key(), row.sourceKind(), type, Long.toString(row.principalId()),
                    row.label(), row.permission(), row.proposed() ? "PROPOSED" : "EXISTING"));
        }
        if (target.globalManage()) {
            sources.add(new DiagnosticViews.Source("GLOBAL_MANAGEMENT", "GLOBAL_MANAGEMENT", null, null,
                    "全局空间管理资格", "manage", "EXISTING"));
        }
        List<DiagnosticViews.Permission> space = new ArrayList<>();
        for (String permission : SPACE_PERMISSIONS) {
            List<String> reasons = resource.archived() && !permission.equals("manage") ? List.of("SPACE_ARCHIVED") : List.of();
            space.add(permission(permission, grants.get(permission).spaceGranted(), reasons, sources, false));
        }
        List<DiagnosticViews.Permission> document = null;
        if (current != null) {
            document = new ArrayList<>();
            List<String> conditions = conditions(resource, current);
            for (String permission : DOCUMENT_PERMISSIONS) {
                document.add(permission(permission, grants.get(permission).documentGranted(), conditions, sources, true));
            }
        }
        DiagnosticViews.Current access = unassessed("NOT_APPLICABLE", false);
        if (current != null) {
            if (!current.hasCurrent()) {
                access = unassessed("NO_CURRENT_PUBLISHED_VERSION", true);
            } else {
                List<String> viewReasons = new ArrayList<>(conditions(resource, current));
                if (!grants.get("view").documentGranted()) {
                    viewReasons.add("VIEW_NOT_GRANTED");
                }
                List<String> downloadReasons = new ArrayList<>(viewReasons);
                if (!grants.get("download").documentGranted()) {
                    downloadReasons.add("DOWNLOAD_NOT_GRANTED");
                }
                if (current.viewAllowed() != viewReasons.isEmpty() || current.downloadAllowed() != downloadReasons.isEmpty()) {
                    throw unavailable();
                }
                access = new DiagnosticViews.Current("ASSESSED", "CURRENT_PUBLISHED", current.viewAllowed() ? "ALLOWED" : "DENIED",
                        current.downloadAllowed() ? "ALLOWED" : "DENIED", List.copyOf(viewReasons), List.copyOf(downloadReasons));
            }
        }
        return new DiagnosticViews.Layer("PASSED", List.copyOf(space), document == null ? null : List.copyOf(document), access, List.copyOf(sources));
    }

    private static DiagnosticViews.Permission permission(String permission, boolean matched, List<String> conditions,
                                                          List<DiagnosticViews.Source> sources, boolean document) {
        List<String> keys = sources.stream().filter(source -> source.permission().equals(permission)
                && (document || !source.sourceKind().equals("DOCUMENT"))).map(DiagnosticViews.Source::key).toList();
        if (matched != !keys.isEmpty()) {
            throw unavailable();
        }
        List<String> reasons = new ArrayList<>(conditions);
        if (!matched) {
            reasons.add("AUTHORIZATION_NOT_GRANTED");
        }
        return new DiagnosticViews.Permission(permission, matched ? "MATCHED" : "NOT_MATCHED",
                conditions.isEmpty() ? "PASSED" : "BLOCKED", List.copyOf(reasons), keys);
    }

    private static List<String> conditions(DiagnosticAccess.Resource resource, DiagnosticFacts.Current current) {
        List<String> reasons = new ArrayList<>();
        if (resource.archived()) {
            reasons.add("SPACE_ARCHIVED");
        }
        if (!current.clearancePassed()) {
            reasons.add("CLEARANCE_BLOCKED");
        }
        return List.copyOf(reasons);
    }

    private static List<DiagnosticViews.Permission> blocked(List<String> permissions) {
        return permissions.stream().map(permission -> new DiagnosticViews.Permission(permission, "NOT_EVALUATED", "NOT_EVALUATED",
                List.of("SUBJECT_BLOCKED"), List.of())).toList();
    }

    private static DiagnosticViews.Current unassessed(String status, boolean document) {
        return new DiagnosticViews.Current(status, document ? "CURRENT_PUBLISHED" : null, null, null, List.of(), List.of());
    }

    private static DiagnosticException unavailable() {
        return new DiagnosticException(DiagnosticException.Reason.DIAGNOSTIC_UNAVAILABLE);
    }
}
