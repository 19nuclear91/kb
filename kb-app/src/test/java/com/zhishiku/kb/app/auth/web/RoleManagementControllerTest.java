package com.zhishiku.kb.app.auth.web;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.model.RoleRecord;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.IdentityManagementAuthorizer;
import com.zhishiku.kb.app.auth.service.RoleManagementService;
import com.zhishiku.kb.app.config.GlobalExceptionHandler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RoleManagementControllerTest {
    @Test
    void permissionsEndpointRequiresRoleWriteAndRejectsUnknownCode() throws Exception {
        var mapper = mock(RolePermissionMapper.class);
        var identities = mock(IdentityDepartmentMapper.class);
        var service = new RoleManagementService(identities, mapper, mock(IdentityAuditService.class),
                new ObjectMapper());
        var mvc = MockMvcBuilders.standaloneSetup(new RoleManagementController(service,
                        new IdentityManagementAuthorizer(mapper)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
            when(mapper.findUserPermissions(1L)).thenReturn(List.of(Permission.IDENTITY_ROLE_READ.code()));
            mvc.perform(put("/api/admin/identity/roles/3/permissions").header("X-Request-Id", "grant")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"permission\":\"identity:user:read\"," +
                                    "\"granted\":true}"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(identities);
            when(mapper.findUserPermissions(1L)).thenReturn(List.of(Permission.IDENTITY_ROLE_WRITE.code()));
            mvc.perform(put("/api/admin/identity/roles/3/permissions").header("X-Request-Id", "unknown")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"permission\":\"space:*\"," +
                                    "\"granted\":true}"))
                    .andExpect(status().isBadRequest());
            when(mapper.lockRole(3L)).thenReturn(new RoleRecord(3L, "READER", "读取者"));
            mvc.perform(put("/api/admin/identity/roles/3/permissions").header("X-Request-Id", "grant")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"permission\":\"identity:user:read\"," +
                                    "\"granted\":true}"))
                    .andExpect(status().isOk());
            verify(mapper).grantPermission(3L, "identity:user:read");
        }
    }

    @ParameterizedTest
    @CsvSource({"POST,/roles", "PATCH,/roles/3", "DELETE,/roles/3", "GET,/roles/3", "GET,/permissions"})
    void unrelatedPermissionCannotAccessRoleEndpoints(String method, String path) throws Exception {
        var mapper = mock(RolePermissionMapper.class);
        when(mapper.findUserPermissions(1L)).thenReturn(List.of(Permission.IDENTITY_USER_WRITE.code()));
        var service = mock(RoleManagementService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new RoleManagementController(service,
                        new IdentityManagementAuthorizer(mapper)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
            mvc.perform(request(HttpMethod.valueOf(method), "/api/admin/identity" + path)
                            .header("X-Request-Id", "role-test").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"READER\",\"name\":\"读取者\"}"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(service);
        }
    }

    @Test
    void roleReadReturnsLiveBindingsAndCatalogFromEnum() throws Exception {
        var mapper = mock(RolePermissionMapper.class);
        var identities = mock(IdentityDepartmentMapper.class);
        when(mapper.findUserPermissions(1L)).thenReturn(List.of(Permission.IDENTITY_ROLE_READ.code()));
        when(mapper.findRolePermissions(3L)).thenReturn(List.of("identity:user:read"));
        when(identities.findRoleById(3L)).thenReturn(new RoleRecord(3L, "READER", "读取者"));
        var service = new RoleManagementService(identities, mapper, mock(IdentityAuditService.class),
                new ObjectMapper());
        var mvc = MockMvcBuilders.standaloneSetup(new RoleManagementController(service,
                        new IdentityManagementAuthorizer(mapper)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
            mvc.perform(get("/api/admin/identity/roles/3"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.permissions[0]").value("identity:user:read"
                    ));
            var response = mvc.perform(get("/api/admin/identity/permissions"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            var catalog = new ObjectMapper().readTree(response).get("data");
            assertThat(catalog.size()).isEqualTo(Permission.values().length);
            for (var permission : Permission.values()) {
                assertThat(catalog.get(permission.ordinal()).get("code").asText()).isEqualTo(permission.code());
                assertThat(catalog.get(permission.ordinal()).get("description").asText()).isEqualTo(permission.description());
            }
        }
    }

    @Test
    void roleWriteCreatesEmptyRoleAndRejectsCodeChange() throws Exception {
        var mapper = mock(RolePermissionMapper.class);
        var identities = mock(IdentityDepartmentMapper.class);
        when(mapper.findUserPermissions(1L)).thenReturn(List.of(Permission.IDENTITY_ROLE_WRITE.code()));
        when(identities.nextRoleId()).thenReturn(3L);
        when(identities.insertRole(3L, "READER", "读取者")).thenReturn(1);
        when(mapper.lockRole(3L)).thenReturn(new RoleRecord(3L, "READER", "读取者"));
        var service = new RoleManagementService(identities, mapper, mock(IdentityAuditService.class),
                new ObjectMapper());
        var mvc = MockMvcBuilders.standaloneSetup(new RoleManagementController(service,
                        new IdentityManagementAuthorizer(mapper)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
            mvc.perform(post("/api/admin/identity/roles").header("X-Request-Id", "create")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"READER\",\"name\":\"读取者\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.permissions").isEmpty());
            mvc.perform(patch("/api/admin/identity/roles/3").header("X-Request-Id", "rename")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"ROLE_ADMIN\"," +
                                    "\"name\":\"读取者\"}"))
                    .andExpect(status().isBadRequest());
            verify(mapper, never()).renameRole(anyLong(), anyString());
        }
    }

    @Test
    void everyRoleEndpointHasOperationAndResponses() {
        var endpoints = Arrays.stream(RoleManagementController.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers())).toList();
        assertThat(endpoints).hasSizeGreaterThanOrEqualTo(5);
        for (var method : endpoints) {
            assertThat(method.isAnnotationPresent(Operation.class)).as(method.getName()).isTrue();
            assertThat(method.isAnnotationPresent(ApiResponses.class)).as(method.getName()).isTrue();
        }
    }
}
