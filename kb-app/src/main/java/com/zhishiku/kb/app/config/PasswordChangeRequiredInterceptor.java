package com.zhishiku.kb.app.config;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.service.EpochSessionService;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.SsoPublicEndpoints;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * 强制改密拦截（设计 D6）：处于强制改密状态的会话只放行完成改密所必需的白名单
 * 端点，其余 /api/** 请求一律以 403 {@code PASSWORD_CHANGE_REQUIRED} 拒绝。
 *
 * <p>为什么每请求读库而不是登录时把标记写进会话：管理员重置密码后，用户已经
 * 持有的旧会话里的标记会失真——重置必须让旧会话立刻进入强制改密状态，
 * 只有读库能做到这一点。代价是每请求一条主键 SELECT，可接受。
 *
 * <p>白名单按"方法 + 路径"精确匹配，而不是只排除路径：只排除路径会让同一路径
 * 上将来新增的其他方法静默获得豁免。白名单是完成改密所必需的最小集合：
 * 登录、登出、查询自身身份、自助改密与开发态登录。
 */
public class PasswordChangeRequiredInterceptor implements HandlerInterceptor {

    /**
     * 方法 + 路径的精确白名单。
     */
    private static final Set<String> WHITELIST = Set.of(
            "POST /api/auth/login",
            "POST /api/auth/logout",
            "GET /api/auth/me",
            "POST /api/auth/password",
            "POST /api/auth/dev-login");

    private final IdentityDepartmentMapper mapper;

    public PasswordChangeRequiredInterceptor(IdentityDepartmentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        if (WHITELIST.contains(request.getMethod() + " " + request.getRequestURI())
                || SsoPublicEndpoints.matches(request.getMethod(), request.getRequestURI())) {
            return true;
        }
        // 本拦截器注册在 SaInterceptor 之后：走到这里时非白名单路径已完成登录校验
        Object verified = request.getAttribute(EpochAuthenticationInterceptor.VERIFIED);
        Boolean mustChange;
        if (verified instanceof EpochSessionService.Session session) {
            mustChange = session.mustChangePassword();
        } else {
            try {
                mustChange = mapper.findMustChangePasswordById(StpUtil.getLoginIdAsLong());
            } catch (RuntimeException failed) {
                throw ApiException.unavailable("暂时无法确认身份，请稍后重试");
            }
        }
        if (mustChange == null) {
            throw ApiException.unavailable("暂时无法确认身份，请稍后重试");
        }
        if (mustChange) {
            throw IdentityException.passwordChangeRequired("必须先完成密码修改");
        }
        return true;
    }
}
