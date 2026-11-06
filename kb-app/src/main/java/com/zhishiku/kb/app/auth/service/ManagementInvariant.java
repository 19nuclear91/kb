package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.model.ManagementChange;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.common.security.IdentityConflictReason;
import com.zhishiku.kb.common.security.IdentityException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * 管理面必须保留一名同时持有角色分配和角色写入权限的启用用户。
 */
@Component
public class ManagementInvariant {
    public static final Set<String> EMERGENCY_PERMISSIONS = Set.of(
            Permission.IDENTITY_ROLE_READ.code(), Permission.IDENTITY_ROLE_ASSIGN.code(), Permission.IDENTITY_ROLE_WRITE.code(),
            Permission.IDENTITY_USER_READ.code(), Permission.IDENTITY_USER_WRITE.code(), Permission.IDENTITY_USER_PASSWORD.code(),
            Permission.IDENTITY_DEPT_READ.code(), Permission.IDENTITY_DEPT_WRITE.code(), Permission.IDENTITY_LIFECYCLE_READ.code(),
            Permission.IDENTITY_LIFECYCLE_MANAGE.code(), Permission.IDENTITY_LIFECYCLE_RESTORE.code(), Permission.IDENTITY_EMERGENCY_MANAGE.code());
    private final IdentityDepartmentMapper mapper;

    public ManagementInvariant(IdentityDepartmentMapper mapper) {
        this.mapper = mapper;
    }

    public void lock() {
        mapper.lockManagement();
    }

    public boolean remainsAfter(ManagementChange change) {
        var byUser = new HashMap<Long, Set<String>>();
        for (var fact : mapper.findEnabledManagementPermissions()) {
            if (change.retains(fact)) {
                byUser.computeIfAbsent(fact.userId(), ignored -> new HashSet<>()).add(fact.permission());
            }
        }
        return byUser.values().stream().anyMatch(permissions ->
                permissions.contains(Permission.IDENTITY_ROLE_ASSIGN.code())
                        && permissions.contains(Permission.IDENTITY_ROLE_WRITE.code()));
    }

    /**
     * 调用者必须先 lock，且查询、业务写入都处于同一事务中。
     */
    public void requireAfter(ManagementChange change) {
        if (!remainsAfter(change)) {
            throw IdentityException.conflict(IdentityConflictReason.LAST_MANAGER_REQUIRED,
                    "该操作会使系统失去最后一名同时持有角色分配和角色写入权限的启用全局管理员");
        }
        requireEmergencyAfter(change);
    }

    public boolean emergencyRemainsAfter(ManagementChange change) {
        return emergencyRemainsAfter(change, null);
    }

    public boolean emergencyRemainsAfter(ManagementChange change, Long disabledDepartment) {
        var byUser = new HashMap<Long, Set<String>>();
        for (var fact : mapper.findEmergencyPermissions()) {
            if (change.retains(fact)) {
                byUser.computeIfAbsent(fact.userId(), ignored -> new HashSet<>()).add(fact.permission());
            }
        }
        return mapper.findEmergencyIdentities().stream().anyMatch(identity -> identity.eligible()
                && "EMERGENCY".equals(identity.kind())
                && (disabledDepartment == null || !disabledDepartment.equals(identity.departmentId()))
                && byUser.getOrDefault(identity.userId(), Set.of()).containsAll(EMERGENCY_PERMISSIONS));
    }

    public void requireEmergencyAfter(ManagementChange change) {
        requireEmergencyAfter(change, null);
    }

    public void requireEmergencyAfter(ManagementChange change, Long disabledDepartment) {
        if (mapper.lifecycleProtectionRequired() && !emergencyRemainsAfter(change, disabledDepartment)) {
            throw emergencyRequired();
        }
    }

    public void requireEmergencyCandidate(long userId) {
        var identity = mapper.findEmergencyCandidate(userId);
        if (identity == null || !identity.eligible()) {
            throw emergencyRequired();
        }
        var permissions = new HashSet<String>();
        for (var fact : mapper.findUserManagementPermissions(userId)) {
            if (fact.userId() == userId) {
                permissions.add(fact.permission());
            }
        }
        if (!permissions.containsAll(EMERGENCY_PERMISSIONS)) {
            throw emergencyRequired();
        }
    }

    private static IdentityException emergencyRequired() {
        return IdentityException.conflict(IdentityConflictReason.EMERGENCY_MANAGER_REQUIRED,
                "必须保留具备完整管理资格的独立本地应急管理员");
    }
}
