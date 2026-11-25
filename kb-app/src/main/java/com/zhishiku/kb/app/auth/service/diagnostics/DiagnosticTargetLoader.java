package com.zhishiku.kb.app.auth.service.diagnostics;

import com.zhishiku.kb.app.config.DiagnosticLimits;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.PermissionScope;
import com.zhishiku.kb.common.security.PrincipalType;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 目标事实仅供解释，不能转为普通业务入口的 PrincipalHandle。 */
@Component
public class DiagnosticTargetLoader {
    record Target(
            long userId,
            String label,
            boolean passed,
            short clearance,
            Set<String> keys,
            boolean globalManage
    ) {
        Target {
            keys = Set.copyOf(keys);
        }
    }

    private final IdentityDepartmentMapper identities;
    private final IdentityService identityService;
    private final PermissionMapper permissions;
    private final RolePermissionMapper roles;
    private final DiagnosticLimits limits;

    public DiagnosticTargetLoader(IdentityDepartmentMapper identities, IdentityService identityService,
                                  PermissionMapper permissions, RolePermissionMapper roles, DiagnosticLimits limits) {
        this.identities = identities;
        this.identityService = identityService;
        this.permissions = permissions;
        this.roles = roles;
        this.limits = limits;
    }

    Target load(long userId) {
        try {
            var user = identities.findIdentity(userId);
            if (user == null) {
                throw new DiagnosticException(DiagnosticException.Reason.NOT_FOUND);
            }
            try {
                identityService.requirePrimaryDepartment(user);
                if (!Boolean.FALSE.equals(identities.findMustChangePasswordById(userId))) {
                    return blocked(userId, user.getDisplayName());
                }
            } catch (IdentityException failure) {
                if (failure.httpStatus() == 403) {
                    return blocked(userId, user.getDisplayName());
                }
                throw new DiagnosticException(DiagnosticException.Reason.DIAGNOSTIC_UNAVAILABLE);
            }
            Set<String> keys = new LinkedHashSet<>();
            keys.add(PermissionScope.principalKey(PrincipalType.USER, userId));
            add(keys, PrincipalType.ROLE, permissions.findRoleIdsBounded(userId, limits.principalKeys()));
            add(keys, PrincipalType.DEPT, permissions.findDeptChainIdsBounded(userId, limits.principalKeys() - keys.size() + 1));
            return new Target(userId, user.getDisplayName(), true,
                    user.getClearanceLevel() == null ? 0 : user.getClearanceLevel(), keys,
                    roles.findUserPermissions(userId).contains(Permission.SPACE_MANAGE_ALL.code()));
        } catch (DataAccessException failure) {
            throw new DiagnosticException(DiagnosticException.Reason.DIAGNOSTIC_UNAVAILABLE);
        }
    }

    private void add(Set<String> keys, PrincipalType type, List<Long> ids) {
        keys.addAll(PermissionScope.principalKeys(type, ids));
        if (keys.size() > limits.principalKeys()) {
            throw new DiagnosticException(DiagnosticException.Reason.DIAGNOSTIC_LIMIT_EXCEEDED);
        }
    }

    private static Target blocked(long id, String label) {
        return new Target(id, label, false, (short) 0, Set.of(), false);
    }
}
