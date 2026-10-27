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
    }
}
