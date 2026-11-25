package com.zhishiku.kb.app.auth.service.diagnostics;

import com.zhishiku.kb.app.auth.mapper.diagnostics.DiagnosticMapper;
import com.zhishiku.kb.app.auth.model.diagnostics.DiagnosticFacts;
import com.zhishiku.kb.app.auth.model.diagnostics.DiagnosticEntry;
import com.zhishiku.kb.app.auth.model.diagnostics.DiagnosticViews;
import com.zhishiku.kb.app.config.DiagnosticLimits;
import com.zhishiku.kb.common.security.PrincipalType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 只叠加精确条目，不调用写服务、不展开拟授权角色或部门成员。 */
@Component
public class DiagnosticOverlay {
    record Overlay(
            List<DiagnosticFacts.Acl> after,
            List<DiagnosticViews.Change> changes
    ) {
    }

    private final DiagnosticMapper mapper;
    private final DiagnosticLimits limits;

    public DiagnosticOverlay(DiagnosticMapper mapper, DiagnosticLimits limits) {
        this.mapper = mapper;
        this.limits = limits;
    }

    Overlay apply(DiagnosticAccess.Resource resource, DiagnosticTargetLoader.Target target,
                  List<DiagnosticFacts.Acl> before, DiagnosticInput.Intent intent) {
        Set<Long> roles = intent.entries().stream().filter(entry -> entry.principalType() == PrincipalType.ROLE)
                .map(DiagnosticEntry::principalId).collect(Collectors.toSet());
        if (intent.action().equals("GRANT") && !roles.isEmpty()
                && !Set.copyOf(mapper.existingRoles(roles)).equals(roles)) {
            throw new DiagnosticException(DiagnosticException.Reason.INVALID_INPUT);
        }
        Set<String> existing = Set.copyOf(mapper.existingEntries(resource.spaceId(), resource.documentId(), intent.entries()));
        var rows = new LinkedHashMap<String, DiagnosticFacts.Acl>();
        before.forEach(row -> rows.put(row.key(), row));
        Set<String> union = before.stream().map(DiagnosticFacts.Acl::key).collect(Collectors.toSet());
        List<DiagnosticViews.Change> changes = new ArrayList<>();
        for (var entry : intent.entries()) {
            boolean present = existing.contains(entry.key());
            boolean granting = intent.action().equals("GRANT");
            String outcome = granting ? (present ? "UNCHANGED" : "ADDED") : (present ? "REMOVED" : "UNCHANGED");
            changes.add(new DiagnosticViews.Change(entry.sourceKind(), entry.principalType().name(),
                    Long.toString(entry.principalId()), entry.permission(), intent.action(), outcome));
            if (target.passed() && target.keys().contains(entry.principalKey())) {
                if (granting && !present) {
                    rows.put(entry.key(), new DiagnosticFacts.Acl(entry.sourceKind(), entry.principalType().code(),
                            entry.principalId(), entry.principalKey(), Long.toString(entry.principalId()), entry.permission(), true));
                    union.add(entry.key());
                } else if (!granting) {
                    rows.remove(entry.key());
                }
            }
        }
        if (union.size() + (target.globalManage() ? 1 : 0) > limits.sources()) {
            throw new DiagnosticException(DiagnosticException.Reason.DIAGNOSTIC_LIMIT_EXCEEDED);
        }
        return new Overlay(List.copyOf(rows.values()), List.copyOf(changes));
    }
}
