package com.zhishiku.kb.app.document.imports.service;

import com.zhishiku.kb.app.auth.service.EpochSessionService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.common.api.ApiException;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.stereotype.Component;

/** 每次重新读取身份及空间资格，不跨网络阶段缓存授权结果。 */
@Component
public class ImportAccess {
    private final EpochSessionService sessions;
    private final PermissionService permissions;
    private final SqlSessionTemplate sql;

    public ImportAccess(EpochSessionService sessions, PermissionService permissions, SqlSessionTemplate sql) {
        this.sessions = sessions;
        this.permissions = permissions;
        this.sql = sql;
    }

    public EpochSessionService.Session current(String token) {
        sql.clearCache();
        var session = sessions.check(token);
        if (session.mustChangePassword()) {
            throw new ImportException(ImportException.Reason.FORBIDDEN);
        }
        return session;
    }

    public boolean canUpload(long userId, long spaceId) {
        sql.clearCache();
        try {
            var principal = permissions.principalOf(userId);
            principal.requireEnabled();
            return permissions.resolve(principal, PermissionService.PERM_EDIT).visibleSpaceIdList().contains(spaceId);
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException ignored) {
            throw new ImportException(ImportException.Reason.UNAVAILABLE);
        }
    }

    public EpochSessionService.Session requireUpload(String token, long spaceId) {
        var session = current(token);
        if (!canUpload(session.userId(), spaceId)) {
            throw new ImportException(ImportException.Reason.FORBIDDEN);
        }
        return session;
    }
}
