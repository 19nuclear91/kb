package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
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
}

