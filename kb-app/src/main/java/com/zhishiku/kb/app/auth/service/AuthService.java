package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.model.LoginResult;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.AuthHeaders;
import com.zhishiku.kb.common.security.QuotaPrincipal;
import org.springframework.stereotype.Service;

/**
 * Sa-Token 登录会话服务。生产环境由 SSO/OIDC 回调复用会话建立逻辑。
 */
@Service
public class AuthService {

    private final PermissionMapper permissionMapper;
    private final IdentityService identityService;
    private final EpochSessionService sessions;

    public AuthService(PermissionMapper permissionMapper, IdentityService identityService, EpochSessionService sessions) {
        this.permissionMapper = permissionMapper;
        this.identityService = identityService;
        this.sessions = sessions;
    }

    /**
     * 使用开发种子用户 ID 建立会话；生产入口应替换为外部认证后的用户 ID。
     */
    public LoginResult devLogin(long userId) {
        if (identityService != null) {
            identityService.resolvePrincipal(userId);
        } else if (permissionMapper.findClearanceLevel(userId) == null) {
            throw ApiException.badRequest("用户不存在或已停用");
        }
        String token = sessions.issue(userId, EpochSessionService.Method.DEV);
        return LoginResult.builder()
                .tokenName(AuthHeaders.SA_TOKEN)
                .tokenValue(token)
                .loginId(userId)
                .build();
    }

    /**
     * 解析当前会话的配额主体。部门信息只能从业务库读取，不能从客户端身份头推断。
     */
    public QuotaPrincipal quotaPrincipal(long userId) {
        if (identityService != null) return identityService.quotaPrincipal(userId);
        Long departmentId = permissionMapper.findDepartmentId(userId);
        if (departmentId == null) {
            throw ApiException.forbidden("用户没有有效部门，无法使用 AI 配额");
        }
        return new QuotaPrincipal(userId, departmentId);
    }
}
