package com.zhishiku.kb.app.auth.web;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.DepartmentService;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.IdentityManagementAuthorizer;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.app.config.GlobalExceptionHandler;
import com.zhishiku.kb.app.parse.service.ParseDeadLetterService;
import com.zhishiku.kb.app.parse.web.ParseDeadLetterController;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.stream.Stream;

import static com.zhishiku.kb.app.auth.model.Permission.IDENTITY_AUDIT_READ;
import static com.zhishiku.kb.app.auth.model.Permission.IDENTITY_CLEARANCE_WRITE;
import static com.zhishiku.kb.app.auth.model.Permission.IDENTITY_DEPT_READ;
import static com.zhishiku.kb.app.auth.model.Permission.IDENTITY_DEPT_WRITE;
import static com.zhishiku.kb.app.auth.model.Permission.IDENTITY_EXTERNAL_WRITE;
import static com.zhishiku.kb.app.auth.model.Permission.IDENTITY_ROLE_ASSIGN;
import static com.zhishiku.kb.app.auth.model.Permission.IDENTITY_ROLE_READ;
import static com.zhishiku.kb.app.auth.model.Permission.IDENTITY_USER_PASSWORD;
import static com.zhishiku.kb.app.auth.model.Permission.IDENTITY_USER_READ;
import static com.zhishiku.kb.app.auth.model.Permission.IDENTITY_USER_WRITE;
import static com.zhishiku.kb.app.auth.model.Permission.PARSE_DEADLETTER_READ;
import static com.zhishiku.kb.app.auth.model.Permission.PARSE_DEADLETTER_REPLAY;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 每条路径使用真实鉴权器和单权限 mapper 桩，防止二值管理员夹具掩盖扩权。
 */
class FunctionalEndpointPermissionTest {
    /**
     * 单个管理接口的权限验证用例。
     *
     * @param method     HTTP 请求方法
     * @param path       被测接口路径
     * @param body       JSON 请求体；无请求体时为空字符串
     * @param permission 该接口要求的单项功能权限
     */
    record Endpoint(String method, String path, String body, Permission permission) {
    }

    static Stream<Endpoint> endpoints() {
        String base = "/api/admin/identity";
        return Stream.of(
                new Endpoint("GET", base + "/users/7", "", IDENTITY_USER_READ),
                new Endpoint("GET", base + "/users", "", IDENTITY_USER_READ),
                new Endpoint("POST", base + "/users", "{\"username\":\"alice\",\"displayName\":\"Alice\"," +
                        "\"departmentId\":2,\"clearanceLevel\":1,\"initialPassword\":\"StrongPassword123!\"}",
                        IDENTITY_USER_WRITE),
                new Endpoint("GET", base + "/roles", "", IDENTITY_ROLE_READ),
                new Endpoint("POST", base + "/users/7/roles", "{\"roleId\":2}", IDENTITY_ROLE_ASSIGN),
                new Endpoint("DELETE", base + "/users/7/roles/2", "", IDENTITY_ROLE_ASSIGN),
                new Endpoint("PATCH", base + "/users/7/password", "{\"newPassword\":\"StrongPassword123!\"}",
                        IDENTITY_USER_PASSWORD),
                new Endpoint("PATCH", base + "/users/7/department", "{\"departmentId\":2,\"version\":0}",
                        IDENTITY_USER_WRITE),
                new Endpoint("PATCH", base + "/users/7/enabled", "{\"enabled\":false,\"version\":0}",
                        IDENTITY_USER_WRITE),
                new Endpoint("PATCH", base + "/users/7/clearance", "{\"clearanceLevel\":2,\"version\":0}",
                        IDENTITY_CLEARANCE_WRITE),
                new Endpoint("POST", base + "/users/7/external-identities", "{\"provider\":\"oidc\"," +
                        "\"subject\":\"subject\"}", IDENTITY_EXTERNAL_WRITE),
                new Endpoint("DELETE", base + "/users/7/external-identities", "{\"identityId\":2}",
                        IDENTITY_EXTERNAL_WRITE),
                new Endpoint("GET", base + "/departments", "", IDENTITY_DEPT_READ),
                new Endpoint("GET", base + "/departments/2", "", IDENTITY_DEPT_READ),
                new Endpoint("POST", base + "/departments", "{\"code\":\"ops\",\"name\":\"运维\"}", IDENTITY_DEPT_WRITE),
                new Endpoint("PATCH", base + "/departments/2/name", "{\"name\":\"运维\",\"version\":0}",
                        IDENTITY_DEPT_WRITE),
                new Endpoint("PATCH", base + "/departments/2/parent", "{\"parentId\":3,\"version\":0}",
                        IDENTITY_DEPT_WRITE),
                new Endpoint("PATCH", base + "/departments/2/enabled", "{\"enabled\":false,\"version\":0}",
                        IDENTITY_DEPT_WRITE),
                new Endpoint("GET", base + "/audits?targetType=user&targetId=7", "", IDENTITY_AUDIT_READ),
                new Endpoint("GET", "/api/admin/parse/dead-letters", "", PARSE_DEADLETTER_READ),
                new Endpoint("POST", "/api/admin/parse/dead-letters/2/replay", "{\"reason\":\"重试\"}",
                        PARSE_DEADLETTER_REPLAY));
    }

    @ParameterizedTest
    @MethodSource("endpoints")
    void onlyRequiredPermissionIsEnough(Endpoint endpoint) throws Exception {
        check(endpoint, endpoint.permission(), true);
    }

    @ParameterizedTest
    @MethodSource("endpoints")
    void adjacentPermissionIsRejectedWithoutServiceCalls(Endpoint endpoint) throws Exception {
        Permission adjacent = switch (endpoint.permission()) {
            case IDENTITY_CLEARANCE_WRITE -> IDENTITY_USER_WRITE;
            case PARSE_DEADLETTER_REPLAY -> PARSE_DEADLETTER_READ;
            default -> Permission.values()[(endpoint.permission().ordinal() + 1) % Permission.values().length];
        };
        check(endpoint, adjacent, false);
    }

    private void check(Endpoint endpoint, Permission held, boolean allowed) throws Exception {
        var permissions = mock(RolePermissionMapper.class);
        when(permissions.findUserPermissions(1L)).thenReturn(List.of(held.code()));
        var identities = mock(IdentityService.class);
        var departments = mock(DepartmentService.class);
        var audit = mock(IdentityAuditService.class);
        var parse = mock(ParseDeadLetterService.class);
        var authorizer = new IdentityManagementAuthorizer(permissions);
        var mvc = MockMvcBuilders.standaloneSetup(
                        new IdentityManagementController(identities, departments, audit, authorizer),
                        new ParseDeadLetterController(parse, authorizer, identities))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
            mvc.perform(request(HttpMethod.valueOf(endpoint.method()), endpoint.path())
                            .header("X-Request-Id", "permission-test")
                            .contentType(MediaType.APPLICATION_JSON).content(endpoint.body()))
                    .andExpect(allowed ? status().isOk() : status().isForbidden());
            if (!allowed) verifyNoInteractions(identities, departments, audit, parse);
        }
    }
}
