package com.zhishiku.kb.app.auth.web;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.model.LoginResult;
import com.zhishiku.kb.app.auth.model.SessionIdentity;
import com.zhishiku.kb.app.auth.service.LocalAuthService;
import com.zhishiku.kb.app.auth.web.dto.LoginRequest;
import com.zhishiku.kb.app.auth.web.dto.PasswordChangeRequest;
import com.zhishiku.kb.app.config.GlobalExceptionHandler;
import com.zhishiku.kb.common.security.AuthHeaders;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.IdentitySummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话控制器的路由、状态码与序列化形状（任务 2.6）。
 *
 * <p>"dev-auth 关闭时 dev-login 不可用"在本地用注解断言表达：@ConditionalOnProperty
 * 的求值发生在容器启动期，而仓库测试不启动容器（不依赖 DB/Redis）。新控制器
 * 不带该注解，加上 DevAuthController 只声明 dev-login 一条路由，即"关闭时
 * login/logout/me 可用、dev-login 不可用"。
 */
class AuthControllerTest {

    private LocalAuthService localAuthService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        localAuthService = mock(LocalAuthService.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(localAuthService))
                .setValidator(validator)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void loginReturnsExtendedLoginResult() throws Exception {
        when(localAuthService.login("alice", "secret")).thenReturn(LoginResult.builder()
                .tokenName(AuthHeaders.SA_TOKEN)
                .tokenValue("token-abc")
                .loginId(7L)
                .mustChangePassword(true)
                .user(new IdentitySummary(7L, "alice", "Alice", 42L, (short) 1, true, 3L))
                .build());

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"secret"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.tokenName").value(AuthHeaders.SA_TOKEN))
                .andExpect(jsonPath("$.data.tokenValue").value("token-abc"))
                .andExpect(jsonPath("$.data.mustChangePassword").value(true))
                .andExpect(jsonPath("$.data.user.username").value("alice"));
    }

    @Test
    void credentialFailureMapsTo401WithUniformMessage() throws Exception {
        when(localAuthService.login("alice", "bad"))
                .thenThrow(IdentityException.unauthorized("用户名或密码错误"));

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"bad"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    void meReturnsServerResolvedIdentity() throws Exception {
        when(localAuthService.sessionIdentity(7L)).thenReturn(new SessionIdentity(
                new IdentitySummary(7L, "alice", "Alice", 42L, (short) 1, true, 3L),
                List.of(1L, 2L), false));

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);

            mvc.perform(get("/api/auth/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.user.userId").value(7))
                    .andExpect(jsonPath("$.data.user.username").value("alice"))
                    .andExpect(jsonPath("$.data.roles[0]").value(1))
                    .andExpect(jsonPath("$.data.roles[1]").value(2))
                    .andExpect(jsonPath("$.data.mustChangePassword").value(false));
        }
    }

    @Test
    void logoutEndsTheCurrentSession() throws Exception {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            mvc.perform(post("/api/auth/logout"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            stp.verify(() -> StpUtil.logout());
        }
    }

    @Test
    void passwordChangeUsesSessionUserAndFallsBackToHeaderRequestId() throws Exception {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);

            mvc.perform(post("/api/auth/password")
                            .header("X-Request-Id", "rid-header")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"oldPassword":"old-secret","newPassword":"new-secret-1"}
                                    """))
                    .andExpect(status().isOk());

            verify(localAuthService).changePassword(7L, "old-secret", "new-secret-1", "rid-header");

            mvc.perform(post("/api/auth/password")
                            .header("X-Request-Id", "rid-header")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"oldPassword":"old-secret","newPassword":"new-secret-1","requestId":"rid-body"}
                                    """))
                    .andExpect(status().isOk());

            verify(localAuthService).changePassword(7L, "old-secret", "new-secret-1", "rid-body");
        }
    }

    @Test
    void sessionRoutesAreUnconditionalAndDevLoginStaysBehindTheSwitch() throws Exception {
        assertThat(AuthController.class.getAnnotation(ConditionalOnProperty.class)).isNull();

        ConditionalOnProperty dev = DevAuthController.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(dev).isNotNull();
        assertThat(dev.name()).containsExactly("kb.dev-auth.enabled");
        assertThat(dev.havingValue()).isEqualTo("true");

        assertThat(DevAuthController.class.getDeclaredMethods())
                .filteredOn(m -> m.isAnnotationPresent(PostMapping.class))
                .singleElement()
                .satisfies(m -> assertThat(m.getName()).isEqualTo("devLogin"));

        assertThat(AuthController.class.getDeclaredMethod("login", LoginRequest.class)
                .getAnnotation(PostMapping.class).value()).containsExactly("/login");
        assertThat(AuthController.class.getDeclaredMethod("logout")
                .getAnnotation(PostMapping.class).value()).containsExactly("/logout");
        assertThat(AuthController.class.getDeclaredMethod("me")
                .getAnnotation(GetMapping.class).value()).containsExactly("/me");
        assertThat(AuthController.class.getDeclaredMethod("changePassword",
                        PasswordChangeRequest.class, String.class)
                .getAnnotation(PostMapping.class).value()).containsExactly("/password");
    }
}
