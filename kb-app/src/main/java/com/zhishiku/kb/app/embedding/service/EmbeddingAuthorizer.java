package com.zhishiku.kb.app.embedding.service;

import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.IdentityManagementAuthorizer;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.common.security.IdentityException;
import org.springframework.stereotype.Service;

@Service
public class EmbeddingAuthorizer {
    private final IdentityManagementAuthorizer permissions;
    private final IdentityService identities;

    public EmbeddingAuthorizer(IdentityManagementAuthorizer permissions, IdentityService identities) {
        this.permissions = permissions;
        this.identities = identities;
    }

    public long require(Permission permission) {
        long userId;
        try {
            if (!StpUtil.isLogin()) throw IdentityException.unauthorized("login required");
            userId = StpUtil.getLoginIdAsLong();
        } catch (SaTokenException | NumberFormatException e) {
            throw IdentityException.unauthorized("invalid session");
        }
        identities.resolvePrincipal(userId);
        return permissions.require(permission);
    }
}
