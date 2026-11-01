package com.zhishiku.kb.app.auth.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.DepartmentService;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.IdentityManagementAuthorizer;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.app.auth.service.RoleManagementService;
import com.zhishiku.kb.app.config.GlobalExceptionHandler;
import com.zhishiku.kb.app.space.service.SpaceQueryService;
import com.zhishiku.kb.app.space.service.SpaceWriteService;
import com.zhishiku.kb.app.space.service.SpaceAclService;
import com.zhishiku.kb.app.space.service.SpaceAuthorizer;
import com.zhishiku.kb.app.space.service.DocumentAclService;
import com.zhishiku.kb.app.space.service.DocumentRelocationService;
import com.zhishiku.kb.app.space.web.SpaceController;
import com.zhishiku.kb.app.space.web.DocumentAclController;
import com.zhishiku.kb.app.space.web.DocumentRelocationController;
import com.zhishiku.kb.app.document.service.DocumentSecurityLevelService;
import com.zhishiku.kb.app.document.web.DocumentSecurityLevelController;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;
import com.zhishiku.kb.common.api.R;
import com.zhishiku.kb.common.security.IdentityConflictReason;
import com.zhishiku.kb.common.security.IdentityErrorCode;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 管理写入口的 reasonCode 投影：精确白名单、固定文案、原状态和旧入口隔离。
 *
 * <p>全部为 standaloneSetup 与服务替身，不启动 Spring 容器。
 */
class IdentityManagementFeedbackTest {
    private static final String ADMIN = "/api/admin/identity";
    private static final String ALIAS = "/api/identity";
    private static final String SECRET = "Secret-Pa55-word";
    private static final String HOSTILE = "<script>alert(1)</script>";

    private final IdentityService identities = mock(IdentityService.class);
    private final DepartmentService departments = mock(DepartmentService.class);
    private final IdentityAuditService audits = mock(IdentityAuditService.class);
    private final RoleManagementService roles = mock(RoleManagementService.class);
    private final IdentityManagementAuthorizer authorizer = mock(IdentityManagementAuthorizer.class);
    private final ObjectMapper json = new ObjectMapper();
    private final MockMvc mvc = mvc(new IdentityManagementController(identities, departments, audits, authorizer),
            new RoleManagementController(roles, authorizer));

    /** 一个写入口：方法、相对路径和请求体。 */
    record Write(String method, String path, String body) {
        MockHttpServletRequestBuilder at(String prefix) {
            var request = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), prefix + path)
                    .header("X-Request-Id", "rid-1");
            return body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body);
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    static final Write CREATE_USER = new Write("POST", "/users", "{\"username\":\"alice\",\"displayName\":\"Alice\","
            + "\"departmentId\":2,\"clearanceLevel\":1,\"initialPassword\":\"" + SECRET + "\"}");
    static final Write MOVE_USER = new Write("PATCH", "/users/7/department", "{\"departmentId\":3,\"version\":4}");
    static final Write USER_ENABLED = new Write("PATCH", "/users/7/enabled", "{\"enabled\":false,\"version\":4}");
    static final Write RESET_PASSWORD = new Write("PATCH", "/users/7/password",
            "{\"newPassword\":\"" + SECRET + "\"}");
    static final Write CLEARANCE = new Write("PATCH", "/users/7/clearance", "{\"clearanceLevel\":2,\"version\":4}");
    static final Write ASSIGN_ROLE = new Write("POST", "/users/7/roles", "{\"roleId\":2}");
    static final Write REVOKE_ROLE = new Write("DELETE", "/users/7/roles/2", null);
    static final Write CREATE_DEPARTMENT = new Write("POST", "/departments",
            "{\"code\":\"platform\",\"name\":\"平台组\",\"parentId\":null}");
    static final Write RENAME_DEPARTMENT = new Write("PATCH", "/departments/3/name", "{\"name\":\"平台\",\"version\":1}");
    static final Write MOVE_DEPARTMENT = new Write("PATCH", "/departments/3/parent", "{\"parentId\":null,\"version\":1}");
    static final Write DEPARTMENT_ENABLED = new Write("PATCH", "/departments/3/enabled",
            "{\"enabled\":false,\"version\":1}");
    static final Write CREATE_ROLE = new Write("POST", "/roles", "{\"code\":\"READER\",\"name\":\"读取者\"}");
    static final Write RENAME_ROLE = new Write("PATCH", "/roles/2", "{\"name\":\"读者\"}");
    static final Write DELETE_ROLE = new Write("DELETE", "/roles/2", null);
    static final Write ROLE_PERMISSION = new Write("PUT", "/roles/2/permissions",
            "{\"permission\":\"identity:role:write\",\"granted\":false}");

    static Stream<Write> writes() {
        return Stream.of(CREATE_USER, MOVE_USER, USER_ENABLED, RESET_PASSWORD, CLEARANCE, ASSIGN_ROLE, REVOKE_ROLE,
                CREATE_DEPARTMENT, RENAME_DEPARTMENT, MOVE_DEPARTMENT, DEPARTMENT_ENABLED,
                CREATE_ROLE, RENAME_ROLE, DELETE_ROLE, ROLE_PERMISSION);
    }

    static Stream<Write> readsAndExternalWrites() {
        return Stream.of(new Write("GET", "/users/7", null), new Write("GET", "/users", null),
                new Write("GET", "/roles", null), new Write("GET", "/roles/2", null),
                new Write("GET", "/permissions", null), new Write("GET", "/departments", null),
                new Write("GET", "/departments/3", null), new Write("GET", "/audits?targetType=USER&targetId=7", null),
                new Write("POST", "/users/7/external-identities", "{\"provider\":\"oidc\",\"subject\":\"" + SECRET + "\"}"),
                new Write("DELETE", "/users/7/external-identities", "{\"identityId\":5}"));
    }

    static final Write BIND_EXTERNAL = new Write("POST", "/users/7/external-identities",
            "{\"provider\":\"oidc\",\"subject\":\"" + SECRET + "\"}");

    static final Map<String, String> MESSAGES = Map.ofEntries(
            Map.entry("INVALID_VERSION", "数据已被其他操作更新，请重新读取后确认。"),
            Map.entry("DEPARTMENT_NOT_EMPTY", "部门仍有启用的子部门或用户，请先处理。"),
            Map.entry("ROLE_IN_USE", "角色仍有引用，请先处理绑定或授权。"),
            Map.entry("LAST_MANAGER_REQUIRED", "该操作会使系统失去最后一名合格管理员。"),
            Map.entry("IDENTIFIER_CONFLICT", "用户名或编码已存在。"),
            Map.entry("INVALID_HIERARCHY", "部门层级不符合要求，请重新读取后确认。"),
            Map.entry("INVALID_PARENT", "部门层级不符合要求，请重新读取后确认。"),
            Map.entry("CONFLICT", "当前状态已变化，请重新读取后确认。"),
            Map.entry("INVALID_REQUEST", "请求内容不符合要求。"),
            Map.entry("FORBIDDEN", "当前操作被拒绝。"),
            Map.entry("DISABLED", "目标或关联对象已停用。"),
            Map.entry("PASSWORD_CHANGE_REQUIRED", "请先修改密码。"),
            Map.entry("NOT_FOUND", "目标不存在或当前不可访问。"),
            Map.entry("UNAUTHORIZED", "登录状态无效或已过期。"));

    static Stream<Arguments> reasons() {
        String last = "该操作会使系统失去最后一名同时持有角色分配和角色写入权限的启用全局管理员 user=7";
        return Stream.of(
                Arguments.of(MOVE_DEPARTMENT, IdentityException.conflict(IdentityErrorCode.INVALID_VERSION,
                        "部门已被其他操作更新 version=7"), 409, "INVALID_VERSION"),
                Arguments.of(DEPARTMENT_ENABLED, IdentityException.conflict(
                        IdentityConflictReason.DEPARTMENT_NOT_EMPTY, "部门仍有启用的子部门或用户 count=3"), 409,
                        "DEPARTMENT_NOT_EMPTY"),
                Arguments.of(DELETE_ROLE, IdentityException.conflict(IdentityConflictReason.ROLE_IN_USE,
                        "删除角色前需先撤销：用户绑定"), 409, "ROLE_IN_USE"),
                Arguments.of(REVOKE_ROLE, IdentityException.conflict(
                        IdentityConflictReason.LAST_MANAGER_REQUIRED, last), 409, "LAST_MANAGER_REQUIRED"),
                Arguments.of(USER_ENABLED, IdentityException.conflict(
                        IdentityConflictReason.LAST_MANAGER_REQUIRED, last), 409, "LAST_MANAGER_REQUIRED"),
                Arguments.of(ROLE_PERMISSION, IdentityException.conflict(
                        IdentityConflictReason.LAST_MANAGER_REQUIRED, last), 409, "LAST_MANAGER_REQUIRED"),
                Arguments.of(CREATE_USER, IdentityException.conflict(
                        IdentityConflictReason.IDENTIFIER_CONFLICT, "用户名已存在 alice"), 409, "IDENTIFIER_CONFLICT"),
                Arguments.of(CREATE_DEPARTMENT, IdentityException.conflict(
                        IdentityConflictReason.IDENTIFIER_CONFLICT, "部门编码已存在 platform"), 409,
                        "IDENTIFIER_CONFLICT"),
                Arguments.of(CREATE_ROLE, IdentityException.conflict(
                        IdentityConflictReason.IDENTIFIER_CONFLICT, "角色 code 已存在 READER"), 409,
                        "IDENTIFIER_CONFLICT"),
                Arguments.of(MOVE_DEPARTMENT, IdentityException.conflict(IdentityErrorCode.INVALID_HIERARCHY,
                        "部门移动会造成层级环 /1/3/"), 409, "INVALID_HIERARCHY"),
                Arguments.of(MOVE_DEPARTMENT, IdentityException.conflict(IdentityErrorCode.INVALID_PARENT,
                        "父部门 9 非法"), 409, "INVALID_PARENT"),
                Arguments.of(RENAME_ROLE, IdentityException.conflict(IdentityErrorCode.CONFLICT, "角色更新失败 row=0"),
                        409, "CONFLICT"),
                Arguments.of(CLEARANCE, IdentityException.badRequest("密级 9 不在字典 [0,1,2]"), 400, "INVALID_REQUEST"),
                Arguments.of(CREATE_DEPARTMENT, IdentityException.disabled("父部门 3 已停用"), 403, "DISABLED"),
                Arguments.of(MOVE_USER, IdentityException.forbidden("不能操作本人 7"), 403, "FORBIDDEN"),
                Arguments.of(ASSIGN_ROLE, IdentityException.passwordChangeRequired("需先改密 7"), 403,
                        "PASSWORD_CHANGE_REQUIRED"),
                Arguments.of(RESET_PASSWORD, IdentityException.notFound("用户 7 不存在"), 404, "NOT_FOUND"),
                Arguments.of(RENAME_DEPARTMENT, IdentityException.unauthorized("会话 token 无效"), 401,
                        "UNAUTHORIZED"));
    }

    @ParameterizedTest(name = "{0} -> {3}")
    @MethodSource("reasons")
    void recognizedFailuresProjectFixedReasonWithOriginalStatus(Write write, IdentityException error, int status,
                                                                String reason) throws Exception {
        allow();
        failWrites(error);
        long before = invocations(identities, departments, audits, roles);

        JsonNode body = body(write.at(ADMIN), status);

        assertProjection(body, reason, MESSAGES.get(reason));
        assertThat(body.toString()).doesNotContain(error.getMessage());
        assertThat(invocations(identities, departments, audits, roles))
                .as("补充原因不得追加实体、引用或审计查询").isEqualTo(before + 1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("writes")
    void unqualifiedWritesAreRejectedFirstWithoutTouchingServices(Write write) throws Exception {
        forbid();

        assertProjection(body(write.at(ADMIN), 403), "FORBIDDEN", MESSAGES.get("FORBIDDEN"));
        verifyNoInteractions(identities, departments, audits, roles);
    }

    @Test
    void whitelistIsExactlyTheDesignedAdminWriteRoutes() {
        assertThat(IdentityManagementFeedbackAdvice.WRITES).hasSize(26)
                .allSatisfy(route -> assertThat(route).matches("(POST|PATCH|PUT|DELETE) /api/admin/(identity/.+|spaces.*|documents/.+)"))
                .noneMatch(route -> route.contains("external-identities"));
        assertThat(writes().map(Write::toString)).hasSize(15).doesNotHaveDuplicates();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("writes")
    void aliasWritesKeepOriginalFailureContract(Write write) throws Exception {
        forbid();

        assertOriginal(body(write.at(ALIAS), 403), "无权执行身份管理操作");
        verifyNoInteractions(identities, departments, audits, roles);
    }

    @Test
    void aliasDomainFailureKeepsOriginalMessageWithoutReason() throws Exception {
        allow();
        failWrites(IdentityException.conflict(IdentityConflictReason.ROLE_IN_USE, "删除角色前需先撤销：用户绑定"));

        assertOriginal(body(DELETE_ROLE.at(ALIAS), 409), "删除角色前需先撤销：用户绑定");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("readsAndExternalWrites")
    void readsAndExternalWritesOnSameControllersKeepOriginalContract(Write write) throws Exception {
        forbid();

        assertOriginal(body(write.at(ADMIN), 403), "无权执行身份管理操作");
        verifyNoInteractions(identities, departments, audits, roles);
    }

    @Test
    void readNotFoundAndExternalConflictKeepOriginalMessages() throws Exception {
        allow();
        when(identities.get(7L)).thenThrow(IdentityException.notFound("用户不存在"));
        when(identities.bindExternal(anyLong(), anyString(), anyString(), any(), anyString()))
                .thenThrow(IdentityException.conflict(IdentityErrorCode.CONFLICT, "外部身份已绑定其他用户"));

        assertOriginal(body(new Write("GET", "/users/7", null).at(ADMIN), 404), "用户不存在");
        JsonNode external = body(BIND_EXTERNAL.at(ADMIN), 409);
        assertOriginal(external, "外部身份已绑定其他用户");
        assertThat(external.toString()).doesNotContain(SECRET);
    }

    @Test
    void otherBusinessControllerOnSamePrefixIsNotProjected() throws Exception {
        MockMvc other = mvc(new OtherController());
        var response = other.perform(RENAME_DEPARTMENT.at(ADMIN)).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(409);
        assertOriginal(json.readTree(response.getContentAsString(StandardCharsets.UTF_8)), "其他业务的原始失败");
    }

    @Test
    void classificationComesFromSourceReasonNotFromMessage() throws Exception {
        allow();
        failWrites(IdentityException.conflict(IdentityErrorCode.CONFLICT, "角色仍有引用 用户名已存在 最后一名管理员"));

        assertProjection(body(DELETE_ROLE.at(ADMIN), 409), "CONFLICT", MESSAGES.get("CONFLICT"));
    }

    @Test
    void inconsistentCodeAndStatusFallsBackToOriginalResponse() throws Exception {
        allow();
        failWrites(new IdentityException(IdentityErrorCode.CONFLICT, 503, "服务暂不可用"));

        assertOriginal(body(DELETE_ROLE.at(ADMIN), 503), "服务暂不可用");
    }

    static Stream<Arguments> unrecognized() {
        return Stream.of(
                Arguments.of(new IllegalStateException("select * from kb_identity where password='" + SECRET + "'"),
                        500, "服务内部错误"),
                Arguments.of(new DataIntegrityViolationException("duplicate key " + SECRET), 500, "服务内部错误"),
                Arguments.of(com.zhishiku.kb.common.api.ApiException.unavailable("服务暂不可用"), 503, "服务暂不可用"));
    }

    @ParameterizedTest
    @MethodSource("unrecognized")
    void unrecognizedExceptionsOnWhitelistedRoutesKeepExistingHandling(RuntimeException error, int status,
                                                                     String message) throws Exception {
        allow();
        failWrites(error);

        JsonNode body = body(DELETE_ROLE.at(ADMIN), status);
        assertOriginal(body, message);
        assertThat(body.toString()).doesNotContain(SECRET);
    }

    @Test
    void bodyValidationFailureKeepsGenericBadRequest() throws Exception {
        allow();
        var request = new Write("POST", "/users", "{\"username\":\" \",\"displayName\":\"Alice\",\"departmentId\":2,"
                + "\"clearanceLevel\":1,\"initialPassword\":\"" + SECRET + "\"}").at(ADMIN);

        JsonNode body = body(request, 400);
        assertOriginal(body, "请求参数或请求体无效");
        assertThat(body.toString()).doesNotContain(SECRET);
        verifyNoInteractions(identities);
    }

    @Test
    void missingRequestIdOnWhitelistedWriteIsProjectedAsInvalidRequest() throws Exception {
        allow();
        var request = MockMvcRequestBuilders.patch(ADMIN + "/users/7/password")
                .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"" + SECRET + "\"}");

        JsonNode body = body(request, 400);
        assertProjection(body, "INVALID_REQUEST", MESSAGES.get("INVALID_REQUEST"));
        assertThat(body.toString()).doesNotContain(SECRET);
        verifyNoInteractions(identities);
    }

    @Test
    void sensitiveInputAndExceptionTextAreNeitherEchoedNorLogged() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("com.zhishiku.kb.app");
        Level previous = logger.getLevel();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        List<JsonNode> bodies = new java.util.ArrayList<>();
        try {
            allow();
            failWrites(IdentityException.badRequest("非法输入 password=" + SECRET + " name=" + HOSTILE));
            Write hostile = new Write("POST", "/departments", "{\"code\":\"x\",\"name\":\"" + HOSTILE + "\"}");
            for (Write write : List.of(CREATE_USER, RESET_PASSWORD, hostile)) {
                bodies.add(body(write.at(ADMIN), 400));
            }
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
            appender.stop();
        }

        assertThat(bodies).allSatisfy(body -> {
            assertProjection(body, "INVALID_REQUEST", MESSAGES.get("INVALID_REQUEST"));
            assertThat(body.toString()).doesNotContain(SECRET).doesNotContain("script");
        });
        assertThat(appender.list).isNotEmpty().allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(SECRET).doesNotContain("script");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    /** 让所有写服务替身抛出同一异常；每个请求只会命中其中一个。 */
    private void failWrites(RuntimeException error) {
        when(identities.createUser(anyString(), anyString(), anyLong(), any(), anyString(), any(), anyString()))
                .thenThrow(error);
        when(identities.moveToDepartment(anyLong(), anyLong(), anyLong(), any(), anyString())).thenThrow(error);
        when(identities.setEnabled(anyLong(), anyBoolean(), anyLong(), any(), anyString())).thenThrow(error);
        when(identities.resetPassword(anyLong(), anyString(), any(), anyString())).thenThrow(error);
        when(identities.changeClearance(anyLong(), anyShort(), anyLong(), any(), anyString())).thenThrow(error);
        doThrow(error).when(identities).assignRole(anyLong(), anyLong(), any(), anyString());
        doThrow(error).when(identities).revokeRole(anyLong(), anyLong(), any(), anyString());
        when(departments.create(anyString(), anyString(), any(), any(), anyString())).thenThrow(error);
        when(departments.rename(anyLong(), anyString(), anyLong(), any(), anyString())).thenThrow(error);
        when(departments.move(anyLong(), any(), anyLong(), any(), anyString())).thenThrow(error);
        when(departments.setEnabled(anyLong(), anyBoolean(), anyLong(), any(), anyString())).thenThrow(error);
        when(roles.create(anyString(), anyString(), anyLong(), anyString())).thenThrow(error);
        when(roles.rename(anyLong(), anyString(), any(), anyLong(), anyString())).thenThrow(error);
        doThrow(error).when(roles).delete(anyLong(), anyLong(), anyString());
        when(roles.setPermission(anyLong(), anyString(), anyBoolean(), anyLong(), anyString())).thenThrow(error);
    }

    private void allow() {
        when(authorizer.require(any(Permission.class))).thenReturn(1L);
    }

    private void forbid() {
        when(authorizer.require(any(Permission.class))).thenThrow(IdentityException.forbidden("无权执行身份管理操作"));
    }

    private JsonNode body(MockHttpServletRequestBuilder request, int status) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(status);
        return json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    /** 新投影：恰好四个字段，失败 code 与 data=null 保持 R 语义。 */
    private static void assertProjection(JsonNode body, String reason, String message) {
        assertThat(body.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("code", "message", "data", "reasonCode");
        assertThat(body.get("code").asInt()).isEqualTo(R.FAILURE);
        assertThat(body.get("data").isNull()).isTrue();
        assertThat(body.get("reasonCode").asText()).isEqualTo(reason);
        assertThat(body.get("message").asText()).isEqualTo(message);
    }

    /** 原响应：没有 reasonCode，message 保持既有处理器的输出。 */
    private static void assertOriginal(JsonNode body, String message) {
        assertThat(body.has("reasonCode")).isFalse();
        assertThat(body.get("code").asInt()).isEqualTo(R.FAILURE);
        assertThat(body.get("data").isNull()).isTrue();
        assertThat(body.get("message").asText()).isEqualTo(message);
    }

    private static MockMvc mvc(Object... controllers) {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        GlobalExceptionHandler fallback = new GlobalExceptionHandler();
        return MockMvcBuilders.standaloneSetup(controllers).setValidator(validator)
                .setControllerAdvice(fallback, new IdentityManagementFeedbackAdvice(fallback)).build();
    }

    private static long invocations(Object... mocks) {
        return Stream.of(mocks).mapToLong(m -> Mockito.mockingDetails(m).getInvocations().size()).sum();
    }

    static Stream<Write> resourceWrites() {
        String acl = "{\"principalType\":\"USER\",\"principalId\":7,\"permission\":\"view\"}";
        return Stream.of(new Write("POST", "/api/admin/spaces", "{\"code\":\"space\",\"name\":\"空间\"}"),
                new Write("PATCH", "/api/admin/spaces/2", "{\"name\":\"空间\",\"version\":1}"),
                new Write("POST", "/api/admin/spaces/2/archive", "{\"version\":1}"),
                new Write("POST", "/api/admin/spaces/2/unarchive", "{\"version\":1}"),
                new Write("DELETE", "/api/admin/spaces/2", null),
                new Write("POST", "/api/admin/spaces/2/acl", acl),
                new Write("DELETE", "/api/admin/spaces/2/acl", acl),
                new Write("POST", "/api/admin/documents/7/acl", acl),
                new Write("DELETE", "/api/admin/documents/7/acl", acl),
                new Write("PATCH", "/api/admin/documents/7/space", "{\"fromSpaceId\":2,\"toSpaceId\":3,\"targetCategoryId\":null,\"expectedMetadataVersion\":0}"),
                new Write("PATCH", "/api/admin/documents/7/security-level", "{\"securityLevel\":2,\"securityVersion\":1,\"reason\":\"定级\"}"));
    }

    @ParameterizedTest
    @MethodSource("resourceWrites")
    void resourceWriteRoutesProjectSafeFailures(Write write) throws Exception {
        for (IdentityConflictReason reason : List.of(IdentityConflictReason.SPACE_NOT_EMPTY,
                IdentityConflictReason.SOURCE_MISMATCH, IdentityConflictReason.TARGET_ARCHIVED,
                IdentityConflictReason.ALREADY_IN_TARGET)) {
            var error = IdentityException.conflict(reason, "space=2 count=99 " + SECRET);
            var response = resourceMvc(error).perform(write.at("")).andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(409);
            var body = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
            assertProjection(body, reason.name(), IdentityManagementFeedbackAdvice.text(reason.name()));
            assertThat(body.toString()).doesNotContain(SECRET, "space=2", "count=99");
        }
    }

    @ParameterizedTest
    @MethodSource("resourceWrites")
    void resourceWritesRetainStatusAndFallback(Write write) throws Exception {
        for (var error : List.of(IdentityException.forbidden("拒绝"), IdentityException.notFound("不存在"),
                IdentityException.conflict(IdentityErrorCode.INVALID_VERSION, "版本"),
                new IdentityException(IdentityErrorCode.CONFLICT, 503, "上游故障"))) {
            var response = resourceMvc(error).perform(write.at("")).andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(error.httpStatus());
            var body = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
            if (error.httpStatus() == 503) assertOriginal(body, "上游故障");
            else assertThat(body.get("reasonCode").asText()).isEqualTo(error.errorCode().name());
        }
    }

    @Test
    void resourceReadsAndUnlistedPatternsKeepOriginalContract() throws Exception {
        var error = IdentityException.conflict(IdentityErrorCode.CONFLICT, "原读取失败");
        for (String path : List.of("/api/admin/spaces", "/api/admin/spaces/2", "/api/admin/spaces/2/acl",
                "/api/admin/documents/7/acl", "/api/admin/documents/7/security-level")) {
            var response = resourceMvc(error).perform(MockMvcRequestBuilders.get(path)).andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(409);
            assertOriginal(json.readTree(response.getContentAsString(StandardCharsets.UTF_8)), "原读取失败");
        }
        for (String path : List.of("/api/admin/spaces/{spaceId}/acl/principals", "/api/admin/documents",
                "/api/admin/parse/deadletters", "/api/admin/embedding/runs", "/api/identity/users")) {
            var request = new MockHttpServletRequest("POST", path);
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, path);
            var result = new IdentityManagementFeedbackAdvice(new GlobalExceptionHandler()).handle(error, request);
            assertOriginal(json.valueToTree(result.getBody()), "原读取失败");
        }
    }

    private static MockMvc resourceMvc(IdentityException error) {
        var query = mock(SpaceQueryService.class);
        var writes = mock(SpaceWriteService.class);
        var acls = mock(SpaceAclService.class);
        var authorizer = mock(SpaceAuthorizer.class);
        var documentAcls = mock(DocumentAclService.class);
        var relocate = mock(DocumentRelocationService.class);
        var security = mock(DocumentSecurityLevelService.class);
        when(authorizer.requireEnabledUser()).thenReturn(1L);
        when(query.list(anyLong())).thenThrow(error);
        when(query.get(anyLong(), anyLong())).thenThrow(error);
        when(writes.create(anyString(), anyString(), any(), any(), anyString())).thenThrow(error);
        when(writes.updateMetadata(anyLong(), anyString(), any(), anyLong(), any(), anyString())).thenThrow(error);
        when(writes.archive(anyLong(), anyLong(), any(), anyString())).thenThrow(error);
        when(writes.unarchive(anyLong(), anyLong(), any(), anyString())).thenThrow(error);
        doThrow(error).when(writes).delete(anyLong(), any(), anyString());
        when(acls.list(anyLong())).thenThrow(error);
        when(acls.grant(anyLong(), any(), anyLong(), anyString(), any(), anyString())).thenThrow(error);
        doThrow(error).when(acls).revoke(anyLong(), any(), anyLong(), anyString(), any(), anyString());
        when(documentAcls.list(anyLong())).thenThrow(error);
        when(documentAcls.grant(anyLong(), any(), anyLong(), anyString(), any(), anyString())).thenThrow(error);
        doThrow(error).when(documentAcls).revoke(anyLong(), any(), anyLong(), anyString(), any(), anyString());
        when(relocate.relocate(anyLong(), anyLong(), anyLong(), any(), any(), anyString(), any(), anyLong())).thenThrow(error);
        when(security.get(anyLong())).thenThrow(error);
        when(security.change(anyLong(), any(), any())).thenThrow(error);
        return mvc(new SpaceController(query, writes, acls, authorizer), new DocumentAclController(documentAcls),
                new DocumentRelocationController(relocate), new DocumentSecurityLevelController(security));
    }

    @RestController
    @RequestMapping(ADMIN)
    static class OtherController {
        @PatchMapping("/departments/{departmentId}/name")
        R<Void> rename() {
            throw IdentityException.conflict(IdentityErrorCode.INVALID_VERSION, "其他业务的原始失败");
        }
    }
}
