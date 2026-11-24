package com.zhishiku.kb.app.search.term;

import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.common.security.IdentityException;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.stereotype.Component;

/** 词典管理资格独立于资源 ACL；每次检查均清理请求内 Mapper 一级缓存。 */
@Component
public class TermAccess {
    private final AdminQueryAccess access;
    private final SqlSessionTemplate sessions;

    public TermAccess(AdminQueryAccess access, SqlSessionTemplate sessions) {
        this.access = access;
        this.sessions = sessions;
    }

    public long requireRead() {
        return require(Permission.SEARCH_TERM_READ);
    }

    public long requireWrite() {
        return require(Permission.SEARCH_TERM_WRITE);
    }

    public void recheckWriter(long expectedActorId) {
        if (requireWrite() != expectedActorId) {
            throw new TermException(TermException.Reason.FORBIDDEN);
        }
    }

    private long require(Permission permission) {
        sessions.clearCache();
        try {
            var actor = access.current();
            actor.requireEnabled();
            if (actor.userId() <= 0 || !actor.functionalPermissions().contains(permission.code())) {
                throw new TermException(TermException.Reason.FORBIDDEN);
            }
            return actor.userId();
        } catch (QueryException denied) {
            throw new TermException(TermException.Reason.FORBIDDEN);
        } catch (IdentityException denied) {
            if (denied.httpStatus() == 401) {
                throw denied;
            }
            throw new TermException(TermException.Reason.FORBIDDEN);
        }
    }
}
