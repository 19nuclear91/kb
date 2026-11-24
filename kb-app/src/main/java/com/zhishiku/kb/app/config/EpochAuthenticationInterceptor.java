package com.zhishiku.kb.app.config;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.service.EpochSessionService;
import com.zhishiku.kb.common.security.AuthenticationEndpoints;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/** app 直接访问也必须核对当前代次；请求属性仅在本次请求内复用。 */
public final class EpochAuthenticationInterceptor implements HandlerInterceptor {
    public static final String VERIFIED = EpochAuthenticationInterceptor.class.getName() + ".verified";
    public static final String VERIFIED_TOKEN = EpochAuthenticationInterceptor.class.getName() + ".token";
    private final EpochSessionService sessions;

    public EpochAuthenticationInterceptor(EpochSessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (AuthenticationEndpoints.isPublic(request.getMethod(), request.getRequestURI())) {
            return true;
        }
        String token = StpUtil.getTokenValue();
        request.setAttribute(VERIFIED, sessions.check(token));
        if ("/api/search/assisted".equals(PrivateApiResponse.path(request))) {
            request.setAttribute(VERIFIED_TOKEN, token);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception error) {
        request.removeAttribute(VERIFIED_TOKEN);
    }
}
