package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.IdentityException;
import org.springframework.stereotype.Service;

/** 调用方已核对问题资格后，以持久提问者身份检查当次有效性，不开放身份目录。 */
@Service
public class KnowledgeGapParticipantAccess {
    private final PermissionService permissions;
    private final IdentityDepartmentMapper identities;

    public KnowledgeGapParticipantAccess(PermissionService permissions, IdentityDepartmentMapper identities) {
        this.permissions = permissions;
        this.identities = identities;
    }

    public PermissionService.PrincipalHandle active(long userId) {
        try {
            var principal = permissions.principalOf(userId);
            principal.requireEnabled();
            if (!Boolean.FALSE.equals(identities.findMustChangePasswordById(userId))) {
                throw ApiException.conflict("提问者当前不具备资料确认资格");
            }
            return principal;
        } catch (IdentityException rejected) {
            if (rejected.httpStatus() == 403 || rejected.httpStatus() == 404) {
                throw ApiException.conflict("提问者当前不具备资料确认资格");
            }
            throw ApiException.unavailable("暂时无法确认提问者资格");
        } catch (ApiException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw ApiException.unavailable("暂时无法确认提问者资格");
        }
    }
}
