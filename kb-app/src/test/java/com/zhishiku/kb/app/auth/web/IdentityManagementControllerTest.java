package com.zhishiku.kb.app.auth.web;

import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.DepartmentService;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.IdentityManagementAuthorizer;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.app.config.GlobalExceptionHandler;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.RoleSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IdentityManagementControllerTest {

    private static final long ACTOR = 1L;

    @Test
    void unauthorizedReadDoesNotProbeResourceOrAudit() throws Exception {
        IdentityService identities = mock(IdentityService.class);
        DepartmentService departments = mock(DepartmentService.class);
        IdentityAuditService audits = mock(IdentityAuditService.class);
        IdentityManagementAuthorizer authorizer = mock(IdentityManagementAuthorizer.class);
        doThrow(IdentityException.forbidden("无权执行身份管理操作"))
                .when(authorizer).require(ArgumentMatchers.any(Permission.class));
        IdentityManagementController controller = new IdentityManagementController(
                identities, departments, audits, authorizer);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setValidator(validator)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvc.perform(get("/api/admin/identity/users/7"))
                .andExpect(status().isForbidden());
        verify(identities, never()).get(7L);
        verify(audits, never()).list(ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any());
    }

    @Test
    @DisplayName("角色目录：返回服务端角色表的 id、code、name")
    void roleCatalogReturnsServerSideRoles() throws Exception {
        IdentityService identities = mock(IdentityService.class);
        IdentityManagementAuthorizer authorizer = mock(IdentityManagementAuthorizer.class);
        when(authorizer.require(ArgumentMatchers.any(Permission.class))).thenReturn(ACTOR);
        when(identities.listRoles()).thenReturn(List.of(
                new RoleSummary(1L, "ROLE_ADMIN", "全局管理员"),
                new RoleSummary(2L, "ROLE_EDITOR", "编辑")));
        MockMvc mvc = mvc(identities, authorizer);

        mvc.perform(get("/api/admin/identity/roles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].roleId").value(1))
                .andExpect(jsonPath("$.data[0].code").value("ROLE_ADMIN"))
                .andExpect(jsonPath("$.data[0].name").value("全局管理员"))
                .andExpect(jsonPath("$.data[1].code").value("ROLE_EDITOR"));
    }

    @Test
    @DisplayName("角色目录：无资格操作者返回 403，且不读取目录")
    void roleCatalogIsForbiddenWithoutManagerQualification() throws Exception {
        IdentityService identities = mock(IdentityService.class);
        IdentityManagementAuthorizer authorizer = mock(IdentityManagementAuthorizer.class);
        doThrow(IdentityException.forbidden("无权执行身份管理操作"))
                .when(authorizer).require(ArgumentMatchers.any(Permission.class));
        MockMvc mvc = mvc(identities, authorizer);

        mvc.perform(get("/api/admin/identity/roles"))
                .andExpect(status().isForbidden());

        verify(identities, never()).listRoles();
    }

    private static MockMvc mvc(IdentityService identities, IdentityManagementAuthorizer authorizer) {
        IdentityManagementController controller = new IdentityManagementController(
                identities, mock(DepartmentService.class), mock(IdentityAuditService.class), authorizer);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(controller)
                .setValidator(validator)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
