package com.zhishiku.kb.app.config;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.service.EpochSessionService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 主应用统一登录校验；具体空间/文档权限仍由 PermissionService 负责。
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    private final IdentityDepartmentMapper identityDepartmentMapper;
    private final EpochSessionService sessions;

    public SaTokenConfig(IdentityDepartmentMapper identityDepartmentMapper, EpochSessionService sessions) {
        this.identityDepartmentMapper = identityDepartmentMapper;
        this.sessions = sessions;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SsoSecurityHeadersInterceptor()).addPathPatterns("/api/auth/sso/**");
        registry.addInterceptor(new EpochAuthenticationInterceptor(sessions)).addPathPatterns("/api/**");
        // 注册顺序即执行顺序：强制改密判定在登录校验之后，每请求读库（设计 D6）
        registry.addInterceptor(new PasswordChangeRequiredInterceptor(identityDepartmentMapper))
                .addPathPatterns("/api/**");
    }
}
