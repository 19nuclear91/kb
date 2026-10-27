package com.zhishiku.kb.app.config;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.common.security.SsoPublicEndpoints;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 主应用统一登录校验；具体空间/文档权限仍由 PermissionService 负责。
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    private final IdentityDepartmentMapper identityDepartmentMapper;

    public SaTokenConfig(IdentityDepartmentMapper identityDepartmentMapper) {
        this.identityDepartmentMapper = identityDepartmentMapper;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SsoSecurityHeadersInterceptor()).addPathPatterns("/api/auth/sso/**");
        registry.addInterceptor(new SaInterceptor(handle -> {
                    var request = SaHolder.getRequest();
                    if (!SsoPublicEndpoints.matches(request.getMethod(), request.getRequestPath())) StpUtil.checkLogin();
                }))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/dev-login", "/api/auth/login");
        // 注册顺序即执行顺序：强制改密判定在登录校验之后，每请求读库（设计 D6）
        registry.addInterceptor(new PasswordChangeRequiredInterceptor(identityDepartmentMapper))
                .addPathPatterns("/api/**");
    }
}
