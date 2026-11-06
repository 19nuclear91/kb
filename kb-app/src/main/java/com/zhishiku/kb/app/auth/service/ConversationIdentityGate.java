package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.lifecycle.IdentityAccessState;
import com.zhishiku.kb.common.security.AccountRevokedException;
import com.zhishiku.kb.common.security.IdentityException;
import org.springframework.stereotype.Service;

/**
 * 内部历史入口复用当前身份与强制改密事实。
 */
@Service
public class ConversationIdentityGate {
    private final PermissionService permissions;
    private final IdentityDepartmentMapper identities;

    public ConversationIdentityGate(PermissionService permissions, IdentityDepartmentMapper identities) {
        this.permissions = permissions;
        this.identities = identities;
    }

    public PermissionService.PrincipalHandle require(long user) {
        var handle = permissions.principalOf(user);
        handle.requireEnabled();
        if (Boolean.TRUE.equals(identities.findMustChangePasswordById(user))) {
            throw IdentityException.passwordChangeRequired("请先修改密码");
        }
        return handle;
    }

    /** 生成写入先锁身份，再锁会话/轮次；不取得管理或映射锁，避免反向锁序。 */
    public void requireEpoch(long user, long expected) {
        var row = identities.findIdentityForUpdate(user);
        if (expected < 1) {
            throw IdentityException.unauthorized("执行身份已失效");
        }
        if (row == null) {
            throw new AccountRevokedException(401);
        }
        var current = IdentityAccessState.from(row);
        if (current.authEpoch() != expected) {
            throw new AccountRevokedException(401);
        }
        if (!current.enabled()) {
            throw new AccountRevokedException(403);
        }
        try {
            require(user);
        } catch (IdentityException denied) {
            if (denied.httpStatus() == 401 || denied.httpStatus() == 403) {
                throw new AccountRevokedException(denied.httpStatus());
            }
            throw denied;
        }
    }

    /** 恢复任务只判定持久撤销事实，不借此授予读取或生成资格。 */
    public boolean revoked(long user, Long expected) {
        if (expected == null) {
            return false;
        }
        var current = identities.findAuthenticationIdentity(user);
        if (current != null && current.authEpoch() < 1) {
            throw com.zhishiku.kb.common.api.ApiException.unavailable("无法确认执行身份");
        }
        return current == null || current.authEpoch() != expected || !current.enabled()
                || !current.departmentEnabled() || current.mustChangePassword();
    }
}

