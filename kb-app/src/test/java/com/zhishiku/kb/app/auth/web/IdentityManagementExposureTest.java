package com.zhishiku.kb.app.auth.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.model.ExternalIdentityRecord;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.DepartmentService;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.IdentityManagementAuthorizer;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.app.config.GlobalExceptionHandler;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.IdentitySummary;
import com.zhishiku.kb.common.security.RoleSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 身份管理端点的暴露面（任务 4.6）与载荷脱敏。
 *
 * <p>本类守两件事：
 * <ul>
 *   <li><b>载荷不泄露</b>：管理响应不得序列化外部 subject 或会话令牌。</li>
 *   <li><b>新端点暴露正确</b>：本变更新增的五条端点（建号、角色目录、角色分配、
 *       角色解除、重置密码）挂在 {@code /api/admin/identity}，兼容别名
 *       {@code /api/identity} 同享，且全部受 {@code requireManager} 保护。
 *       挂载与资格用 standalone MockMvc 实测。</li>
 * </ul>
 */
class IdentityManagementExposureTest {

    private static final long ACTOR = 1L;
    private static final long USER_ID = 7L;
    private static final long ROLE_ID = 2L;
    private static final String CREATE_USER_BODY =
            "{\"username\":\"alice\",\"displayName\":\"Alice\",\"departmentId\":2,"
                    + "\"clearanceLevel\":1,\"initialPassword\":\"initial-secret-1\"}";
    private static final String RESET_PASSWORD_BODY = "{\"newPassword\":\"brand-new-secret\"}";

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void managementPayloadsDoNotSerializeExternalSubjectOrSessionToken() throws Exception {
        String mapping = json.writeValueAsString(
                new ExternalIdentityRecord(1L, 7L, "oidc", "full-sensitive-subject", true));
        String summary = json.writeValueAsString(
                new IdentitySummary(7L, "alice", "Alice", 2L, (short) 1, true, 3L));

        assertThat(mapping).doesNotContain("full-sensitive-subject");
        assertThat(summary).doesNotContain("token", "subject");
    }

    @Test
    @DisplayName("新端点挂在 /api/admin/identity 与兼容别名 /api/identity 下，均可受理")
    void newEndpointsAreMountedUnderBothTheAdminPrefixAndTheAlias() throws Exception {
        IdentityService identities = mock(IdentityService.class);
        IdentityManagementAuthorizer authorizer = mock(IdentityManagementAuthorizer.class);
        when(authorizer.require(ArgumentMatchers.any(Permission.class))).thenReturn(ACTOR);
        when(identities.createUser(anyString(), anyString(), anyLong(), any(), anyString(),
                anyLong(), anyString())).thenReturn(summary());
        when(identities.listRoles()).thenReturn(List.of(
                new RoleSummary(ROLE_ID, "ROLE_EDITOR", "编辑")));
        when(identities.resetPassword(anyLong(), anyString(), anyLong(), anyString()))
                .thenReturn(summary());
        MockMvc mvc = mvc(identities, authorizer);

        for (String prefix : List.of("/api/admin/identity", "/api/identity")) {
            mvc.perform(post(prefix + "/users").contentType(MediaType.APPLICATION_JSON)
                            .header("X-Request-Id", "rid-create").content(CREATE_USER_BODY))
                    .andExpect(status().isOk());
            mvc.perform(get(prefix + "/roles")).andExpect(status().isOk());
            mvc.perform(post(prefix + "/users/" + USER_ID + "/roles")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Request-Id", "rid-assign").content("{\"roleId\":2}"))
                    .andExpect(status().isOk());
            mvc.perform(delete(prefix + "/users/" + USER_ID + "/roles/" + ROLE_ID)
                            .header("X-Request-Id", "rid-revoke"))
                    .andExpect(status().isOk());
            mvc.perform(patch(prefix + "/users/" + USER_ID + "/password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Request-Id", "rid-reset").content(RESET_PASSWORD_BODY))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("新端点在两个前缀下都受 requireManager 保护：无资格一律 403 且不进入服务层")
    void newEndpointsRequireManagerQualificationOnBothPrefixes() throws Exception {
        IdentityService identities = mock(IdentityService.class);
        IdentityManagementAuthorizer authorizer = mock(IdentityManagementAuthorizer.class);
        doThrow(IdentityException.forbidden("无权执行身份管理操作"))
                .when(authorizer).require(ArgumentMatchers.any(Permission.class));
        MockMvc mvc = mvc(identities, authorizer);

        for (String prefix : List.of("/api/admin/identity", "/api/identity")) {
            mvc.perform(post(prefix + "/users").contentType(MediaType.APPLICATION_JSON)
                            .header("X-Request-Id", "rid-create").content(CREATE_USER_BODY))
                    .andExpect(status().isForbidden());
            mvc.perform(get(prefix + "/roles")).andExpect(status().isForbidden());
            mvc.perform(post(prefix + "/users/" + USER_ID + "/roles")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Request-Id", "rid-assign").content("{\"roleId\":2}"))
                    .andExpect(status().isForbidden());
            mvc.perform(delete(prefix + "/users/" + USER_ID + "/roles/" + ROLE_ID)
                            .header("X-Request-Id", "rid-revoke"))
                    .andExpect(status().isForbidden());
            mvc.perform(patch(prefix + "/users/" + USER_ID + "/password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Request-Id", "rid-reset").content(RESET_PASSWORD_BODY))
                    .andExpect(status().isForbidden());
        }

        verifyNoInteractions(identities);
    }

    private static MockMvc mvc(IdentityService identities, IdentityManagementAuthorizer authorizer) {
        IdentityManagementController controller = new IdentityManagementController(
                identities, mock(DepartmentService.class), mock(IdentityAuditService.class),
                authorizer);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(controller)
                .setValidator(validator)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static IdentitySummary summary() {
        return new IdentitySummary(USER_ID, "alice", "Alice", 2L, (short) 1, true, 0L);
    }
}
