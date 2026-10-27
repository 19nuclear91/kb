package com.zhishiku.kb.app.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 登录路由的拦截排除与强制改密拦截器的注册（任务 2.7、3.3）。
 *
 * <p>不启动容器：直接调用 {@link SaTokenConfig#addInterceptors} 拿到注册结果，
 * 用反射读 {@link InterceptorRegistration} 的私有 pattern 列表（Spring 未公开
 * 读取接口），再用 AntPathMatcher 做语义断言——"其余 /api/** 仍受保护"必须
 * 通过匹配行为验证，而不是只看列表内容。强制改密拦截器的白名单与判定行为
 * 由 {@link PasswordChangeRequiredInterceptorTest} 覆盖。
 */
class SaTokenConfigTest {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(target);
    }

    private static List<InterceptorRegistration> registrations() throws Exception {
        InterceptorRegistry registry = new InterceptorRegistry();
        new SaTokenConfig(mock(IdentityDepartmentMapper.class)).addInterceptors(registry);
        List<InterceptorRegistration> registrations = field(registry, "registrations");
        assertThat(registrations).as("响应保护、登录校验与强制改密拦截器都必须注册").hasSize(3);
        return registrations;
    }

    @Test
    void loginIsExcludedWhileTheRestOfApiStaysProtected() throws Exception {
        InterceptorRegistration registration = registrations().get(1);

        assertThat((Object) field(registration, "interceptor")).isInstanceOf(SaInterceptor.class);
        List<String> includes = field(registration, "includePatterns");
        List<String> excludes = field(registration, "excludePatterns");

        assertThat(includes).contains("/api/**");
        assertThat(excludes).containsExactlyInAnyOrder("/api/auth/dev-login", "/api/auth/login");

        assertThat(matchesAny(excludes, "/api/auth/login")).isTrue();
        assertThat(matchesAny(excludes, "/api/auth/dev-login")).isTrue();
        assertThat(matchesAny(excludes, "/api/space/documents")).isFalse();
        assertThat(matchesAny(includes, "/api/space/documents")).isTrue();
        assertThat(matchesAny(excludes, "/api/documents/10/versions/20/citations/30")).isFalse();
        assertThat(matchesAny(includes, "/api/documents/10/versions/20/citations/30")).isTrue();
    }

    @Test
    void passwordChangeInterceptorRunsAfterTheLoginCheck() throws Exception {
        List<InterceptorRegistration> registrations = registrations();

        assertThat((Object) field(registrations.get(0), "interceptor")).isInstanceOf(SsoSecurityHeadersInterceptor.class);
        assertThat((Object) field(registrations.get(1), "interceptor"))
                .isInstanceOf(SaInterceptor.class);
        assertThat((Object) field(registrations.get(2), "interceptor"))
                .as("注册顺序即执行顺序；强制改密判定必须发生在登录校验之后")
                .isInstanceOf(PasswordChangeRequiredInterceptor.class);

        List<String> includes = field(registrations.get(2), "includePatterns");
        assertThat(includes).contains("/api/**");
    }

    private static boolean matchesAny(List<String> patterns, String path) {
        return patterns.stream().anyMatch(p -> MATCHER.match(p, path));
    }
}
