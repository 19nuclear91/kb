package com.zhishiku.kb.app.query;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.audit.model.AuditQueryViews.Detail;
import com.zhishiku.kb.app.audit.model.AuditQueryViews.Metadata;
import com.zhishiku.kb.app.audit.model.AuditQueryViews.Page;
import com.zhishiku.kb.app.audit.service.AuditQueryService;
import com.zhishiku.kb.app.audit.web.AuditQueryController;
import com.zhishiku.kb.app.auth.model.IdentityQueryViews.ExternalBinding;
import com.zhishiku.kb.app.auth.model.IdentityQueryViews.PrincipalOption;
import com.zhishiku.kb.app.auth.model.SessionIdentity;
import com.zhishiku.kb.app.auth.service.AuthQueryService;
import com.zhishiku.kb.app.auth.service.DepartmentService;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.IdentityManagementAuthorizer;
import com.zhishiku.kb.app.auth.service.IdentityQueryService;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.app.auth.service.LocalAuthService;
import com.zhishiku.kb.app.auth.web.AuthController;
import com.zhishiku.kb.app.auth.web.AuthQueryController;
import com.zhishiku.kb.app.auth.web.IdentityManagementController;
import com.zhishiku.kb.app.auth.web.IdentityQueryController;
import com.zhishiku.kb.app.document.model.DocumentAdminView;
import com.zhishiku.kb.app.document.service.DocumentAdminQueryService;
import com.zhishiku.kb.app.document.web.DocumentAdminQueryController;
import com.zhishiku.kb.app.query.web.AdminQueryAdvice;
import com.zhishiku.kb.app.space.service.PrincipalQueryService;
import com.zhishiku.kb.app.space.web.PrincipalQueryController;
import com.zhishiku.kb.common.api.R;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.IdentitySummary;
import com.zhishiku.kb.common.security.PrincipalType;
import com.zhishiku.kb.common.security.RoleSummary;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 每个真实 controller 的业务依赖均为 Mock，不组装 controller-service-mapper 链路。
 */
class AdminQueryControllerTest {
    @Test
    void documentBindingPreservesRepeatedAndUnknownParametersForServiceValidation() throws Exception {
        var service = mock(DocumentAdminQueryService.class);
        when(service.list(any())).thenReturn(new QueryPage<>(List.of(new DocumentAdminView(9, 3, true)), false, null));
        var mvc =
                MockMvcBuilders.standaloneSetup(new DocumentAdminQueryController(service)).setControllerAdvice(new AdminQueryAdvice()).build();
        mvc.perform(get("/api/admin/documents").param("spaceId", "3").param("title", "private-title").param("limit",
                        "1", "2"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.items[0].length()").value(3))
                .andExpect(jsonPath("$.data.items[0].documentId").value(9));
        verify(service).list(eq(Map.of("spaceId", List.of("3"), "title", List.of("private-title"), "limit", List.of(
                "1", "2"))));
    }

    @Test
    void identitySearchRolesExternalAndLegacyContractsHaveDistinctMappings() throws Exception {
        var queries = mock(IdentityQueryService.class);
        var identities = mock(IdentityService.class);
        var local = mock(LocalAuthService.class);
        var user = new IdentitySummary(7, "u", "用户", 3L, (short) 0, true, 1);
        when(queries.users(any())).thenReturn(new QueryPage<>(List.of(user), false, null));
        when(queries.roles(eq("7"), any())).thenReturn(new QueryPage<>(List.of(new RoleSummary(2, "R", "角色")), false,
                null));
        when(queries.external(eq("7"), any())).thenReturn(new QueryPage<>(List.of(new ExternalBinding(9, 7, "oidc",
                true)), false, null));
        when(identities.list()).thenReturn(List.of(user));
        when(local.sessionIdentity(7)).thenReturn(new SessionIdentity(user, List.of(2L), false));
        var legacy = new IdentityManagementController(identities, mock(DepartmentService.class),
                mock(IdentityAuditService.class), mock(IdentityManagementAuthorizer.class));
        var mvc = MockMvcBuilders.standaloneSetup(new IdentityQueryController(queries), legacy,
                new AuthController(local)).setControllerAdvice(new AdminQueryAdvice()).build();
        mvc.perform(get("/api/admin/identity/users/search").param("q", "甲%_").param("enabled", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].userId").value(7)).andExpect(header().string("Cache-Control", "no-store"));
        verify(queries).users(Map.of("q", List.of("甲%_"), "enabled", List.of("false")));
        mvc.perform(get("/api/admin/identity/users/7/roles")).andExpect(status().isOk()).andExpect(jsonPath("$.data" +
                ".items[0].permissions").doesNotExist());
        mvc.perform(get("/api/admin/identity/users/7/external-identities").param("provider", "oidc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].length()").value(4)).andExpect(jsonPath("$.data.items[0].subject").doesNotExist());
        for (String path : List.of("/api/admin/identity/users", "/api/identity/users")) {
            mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray()).andExpect(header().doesNotExist("Cache-Control"));
        }
        mvc.perform(get("/api/identity/users/7/roles")).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/identity/users/7/external-identities")).andExpect(status().isMethodNotAllowed());
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::getLoginIdAsLong).thenReturn(7L);
            mvc.perform(get("/api/auth/me")).andExpect(status().isOk()).andExpect(jsonPath("$.data.user.userId").value(7))
                    .andExpect(jsonPath("$.data.roles[0]").value(2)).andExpect(jsonPath("$.data.mustChangePassword").value(false));
        }
    }

    @Test
    void selfQueriesIgnoreForgedIdentityHeadersAndUseNoStore() throws Exception {
        var service = mock(AuthQueryService.class);
        when(service.permissions(any())).thenReturn(List.of());
        when(service.levels(any())).thenReturn(List.of((short) 0, (short) 8));
        var mvc =
                MockMvcBuilders.standaloneSetup(new AuthQueryController(service)).setControllerAdvice(new AdminQueryAdvice()).build();
        mvc.perform(get("/api/auth/permissions").header("X-User-Id", "99").header("X-Roles", "ROLE_ADMIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty()).andExpect(header().string("Cache" +
                        "-Control", "no-store"));
        verify(service).permissions(Map.of());
        mvc.perform(get("/api/auth/security-levels")).andExpect(status().isOk()).andExpect(jsonPath("$.data[1]").value(8));
    }

    @Test
    void resourceAndAuditControllersForwardPathsWithoutExposingRawSummary() throws Exception {
        var principals = mock(PrincipalQueryService.class);
        var audits = mock(AuditQueryService.class);
        var option = new QueryPage<>(List.of(new PrincipalOption(PrincipalType.USER, 7, "候选")), false, null);
        when(principals.space(eq("3"), any())).thenReturn(option);
        when(principals.document(eq("9"), any())).thenReturn(option);
        var eventId = UUID.randomUUID();
        var metadata = new Metadata(2, eventId, "AUTH_LOGIN_FAILURE", "req", null, "auth", null, "login", "FAILED",
                Instant.EPOCH, "unknown");
        when(audits.list(any())).thenReturn(new Page(List.of(metadata), false, null, Instant.EPOCH,
                Instant.EPOCH.plusSeconds(60)));
        when(audits.detail(eq(eventId.toString()), any())).thenReturn(new Detail(metadata, List.of(), false));
        var mvc = MockMvcBuilders.standaloneSetup(new PrincipalQueryController(principals),
                        new AuditQueryController(audits))
                .setControllerAdvice(new AdminQueryAdvice()).build();
        for (String path : List.of("/api/admin/spaces/3/acl/principals", "/api/admin/documents/9/acl/principals")) {
            mvc.perform(get(path).param("principalType", "USER")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].length()").value(3)).andExpect(header().string("Cache" +
                            "-Control", "no-store"));
        }
        mvc.perform(get("/api/admin/audits").param("targetType", "auth")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].beforeState").doesNotExist()).andExpect(jsonPath("$.data.from").exists())
                .andExpect(jsonPath("$.data.to").exists()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/admin/audits/" + eventId)).andExpect(status().isOk()).andExpect(jsonPath("$.data" +
                        ".summaryAvailable").value(false))
                .andExpect(jsonPath("$.data.reason").doesNotExist()).andExpect(header().string("Cache-Control", "no" +
                        "-store"));
    }

    @Test
    void errorStatusMatrixDoesNotEchoOrLogSensitiveExceptions() throws Exception {
        var service = mock(DocumentAdminQueryService.class);
        var audits = mock(AuditQueryService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new DocumentAdminQueryController(service),
                        new AuditQueryController(audits))
                .setControllerAdvice(new AdminQueryAdvice()).build();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        root.addAppender(appender);
        try {
            var failures = Map.of(400, QueryException.invalid(), 401, IdentityException.unauthorized("private-token"),
                    403, QueryException.forbidden(), 404, QueryException.missing(),
                    503, new DataAccessResourceFailureException("private-jdbc"), 500, new IllegalStateException(
                            "private-error"));
            for (var entry : failures.entrySet()) {
                doThrow(entry.getValue()).when(service).list(any());
                doThrow(entry.getValue()).when(audits).list(any());
                doThrow(entry.getValue()).when(audits).detail(any(), any());
                for (String path : List.of("/api/admin/documents", "/api/admin/audits", "/api/admin/audits/private" +
                        "-event")) {
                    mvc.perform(get(path).param("cursor", "private-cursor").param("loginUsername", "private-search").param("requestId", "private-request"))
                            .andExpect(status().is(entry.getKey())).andExpect(header().string("Cache-Control", "no" +
                                    "-store"))
                            .andExpect(content().string(not(containsString("private-"))));
                }
            }
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("private-");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void standaloneBindingRetainsValuesForStrictPureParameterValidation() throws Exception {
        var mvc =
                MockMvcBuilders.standaloneSetup(new ParameterProbe()).setControllerAdvice(new AdminQueryAdvice()).build();
        for (String name : List.of("unknown", "title", "fileName"))
            mvc.perform(get("/parameter-probe").param(name, "private")).andExpect(status().isBadRequest());
        mvc.perform(get("/parameter-probe").param("limit", "1", "2")).andExpect(status().isBadRequest());
        for (String id : List.of("", "0", "-1", "01", "9223372036854775808"))
            mvc.perform(get("/parameter-probe").param("spaceId", id)).andExpect(status().isBadRequest());
        for (String value : List.of("0", "101", "-1"))
            mvc.perform(get("/parameter-probe").param("limit", value)).andExpect(status().isBadRequest());
        mvc.perform(get("/parameter-probe").param("enabled", "TRUE")).andExpect(status().isBadRequest());
        mvc.perform(get("/parameter-probe").param("q", "甲%_\\")).andExpect(status().isOk());
    }

    @Test
    void failuresBeforeControllerInvocationUseScopedAdviceWithoutCallingBusinessService() throws Exception {
        var service = mock(DocumentAdminQueryService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new DocumentAdminQueryController(service))
                .addInterceptors(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                        throw IdentityException.passwordChangeRequired("private-password-state");
                    }
                }).setControllerAdvice(new AdminQueryAdvice()).build();
        mvc.perform(get("/api/admin/documents")).andExpect(status().isForbidden()).andExpect(header().string("Cache" +
                        "-Control", "no-store"))
                .andExpect(content().string(not(containsString("private-"))));
        verifyNoInteractions(service);
    }

    @AdminQueryEndpoint
    @RestController
    static class ParameterProbe {
        @GetMapping("/parameter-probe")
        public R<String> parse(@RequestParam MultiValueMap<String, String> input) {
            var p = new QueryParameters(input, "spaceId", "limit", "enabled", "q");
            p.positive("spaceId");
            p.limit();
            p.bool("enabled");
            return R.ok(QueryParameters.like(p.text("q", 128)));
        }
    }
}
