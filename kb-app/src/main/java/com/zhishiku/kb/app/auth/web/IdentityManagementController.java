package com.zhishiku.kb.app.auth.web;

import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.DepartmentService;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.IdentityManagementAuthorizer;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.app.auth.web.dto.DepartmentCreateRequest;
import com.zhishiku.kb.app.auth.web.dto.DepartmentMoveRequest;
import com.zhishiku.kb.app.auth.web.dto.DepartmentRenameRequest;
import com.zhishiku.kb.app.auth.web.dto.EnabledRequest;
import com.zhishiku.kb.app.auth.web.dto.ExternalIdentityRemoveRequest;
import com.zhishiku.kb.app.auth.web.dto.ExternalIdentityRequest;
import com.zhishiku.kb.app.auth.web.dto.PasswordResetRequest;
import com.zhishiku.kb.app.auth.web.dto.RoleAssignmentRequest;
import com.zhishiku.kb.app.auth.web.dto.UserClearanceRequest;
import com.zhishiku.kb.app.auth.web.dto.UserCreateRequest;
import com.zhishiku.kb.app.auth.web.dto.UserDepartmentRequest;
import com.zhishiku.kb.common.api.R;
import com.zhishiku.kb.common.security.DepartmentSummary;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.IdentitySummary;
import com.zhishiku.kb.common.security.RoleSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 受身份管理权限保护的身份与部门事实管理接口。
 */
@RestController
@RequestMapping({"/api/admin/identity", "/api/identity"})
@Tag(name = "身份与部门管理", description = "受身份管理权限保护的生命周期和审计接口")
public class IdentityManagementController {

    private final IdentityService identities;
    private final DepartmentService departments;
    private final IdentityAuditService audits;
    private final IdentityManagementAuthorizer authorizer;

    @Autowired
    public IdentityManagementController(IdentityService identities, DepartmentService departments,
                                        IdentityAuditService audits,
                                        IdentityManagementAuthorizer authorizer) {
        this.identities = identities;
        this.departments = departments;
        this.audits = audits;
        this.authorizer = authorizer;
    }

    @GetMapping("/users/{userId}")
    @Operation(summary = "查询用户身份摘要", description = "只返回内部 ID、显示信息、主部门、密级、状态和版本")
    /**
     * 查询单个用户的服务端身份事实。
     *
     * @param userId 用户内部 ID
     * @return 用户身份摘要，不包含会话令牌或完整外部 subject
     */
    public R<IdentitySummary> user(@PathVariable @Positive long userId) {
        authorizer.require(Permission.IDENTITY_USER_READ);
        return R.ok(identities.get(userId));
    }

    @GetMapping("/users")
    @Operation(summary = "查询用户身份摘要列表")
    /**
     * 查询全部用户的身份摘要列表。
     *
     * @return 用户身份摘要列表
     */
    public R<List<IdentitySummary>> users() {
        authorizer.require(Permission.IDENTITY_USER_READ);
        return R.ok(identities.list());
    }

    /**
     * 创建本地账号。
     *
     * <p>操作者取自已验证会话；初始密码只在请求体中出现一次，响应、审计与日志都不回显。
     *
     * @param request 用户名、显示名、主部门、初始密级、初始密码与请求幂等标识
     * @param header  请求头 {@code X-Request-Id}；当请求体未提供标识时作为回退值
     * @return 创建后的用户身份摘要；命中同一请求标识的重放时返回首次创建的用户
     */
    @PostMapping("/users")
    @Operation(summary = "创建用户", description = """
            受对应操作的功能权限保护。用户名全局唯一；主部门必须存在且启用；\
            初始密级必须存在于服务端密级字典；初始密码须满足服务端强度约束。\
            创建后用户处于启用与强制首次改密状态。必须携带 X-Request-Id（或请求体 requestId）；\
            重复提交同一标识返回首次创建结果。""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "创建成功，或命中同一请求标识的重放"),
            @ApiResponse(responseCode = "400", description = "缺少请求标识、用户名超长、密码不合强度或密级不在字典中"),
            @ApiResponse(responseCode = "403", description = "缺少全局身份管理资格，或目标部门已停用"),
            @ApiResponse(responseCode = "404", description = "目标部门不存在"),
            @ApiResponse(responseCode = "409", description = "用户名已存在")
    })
    public R<IdentitySummary> createUser(@Valid @RequestBody UserCreateRequest request,
                                         @RequestHeader(value = "X-Request-Id", required = false) String header) {
        long actor = authorizer.require(Permission.IDENTITY_USER_WRITE);
        return R.ok(identities.createUser(request.username(), request.displayName(),
                request.departmentId(), request.clearanceLevel(), request.initialPassword(),
                actor, requestId(request.requestId(), header)));
    }

    /**
     * 查询角色目录。
     *
     * <p>角色取值来自服务端角色表，供管理操作选择与核对；不接受调用方声明。
     *
     * @return 角色摘要列表，含内部 ID、稳定 code 与名称
     */
    @GetMapping("/roles")
    @Operation(summary = "查询角色目录", description = "受对应操作的功能权限保护；返回服务端角色表中的全部角色（id、code、name）")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "角色摘要列表"),
            @ApiResponse(responseCode = "403", description = "缺少全局身份管理资格")
    })
    public R<List<RoleSummary>> roles() {
        authorizer.require(Permission.IDENTITY_ROLE_READ);
        return R.ok(identities.listRoles());
    }

    /**
     * 为用户分配角色。
     *
     * <p>重复分配是幂等成功（服务端走 {@code ON CONFLICT DO NOTHING}）；
     * 角色取值来自服务端角色表，不接受调用方声明。
     *
     * @param userId  目标用户内部 ID
     * @param request 角色内部 ID 与请求幂等标识
     * @param header  请求头 {@code X-Request-Id}；当请求体未提供标识时作为回退值
     * @return 空成功响应
     */
    @PostMapping("/users/{userId}/roles")
    @Operation(summary = "分配角色", description = """
            受对应操作的功能权限保护。角色取值必须来自服务端角色表；\
            重复分配同一角色是幂等成功，不视为错误。必须携带 X-Request-Id（或请求体 requestId）；\
            重复提交同一标识至多执行一次。分配在下一次请求即生效。""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "分配成功（含重复分配的幂等成功）"),
            @ApiResponse(responseCode = "400", description = "缺少请求标识，或角色不存在于服务端角色表"),
            @ApiResponse(responseCode = "403", description = "缺少全局身份管理资格"),
            @ApiResponse(responseCode = "404", description = "目标用户不存在")
    })
    public R<Void> assignRole(@PathVariable @Positive long userId,
                              @Valid @RequestBody RoleAssignmentRequest request,
                              @RequestHeader(value = "X-Request-Id", required = false) String header) {
        long actor = authorizer.require(Permission.IDENTITY_ROLE_ASSIGN);
        identities.assignRole(userId, request.roleId(), actor, requestId(request.requestId(), header));
        return R.ok();
    }

    /**
     * 解除用户的角色。
     *
     * <p>未绑定时返回 404：调用方对当前状态的认知与数据库不一致，不能静默成功。
     *
     * @param userId 目标用户内部 ID
     * @param roleId 角色内部 ID
     * @param header 请求头 {@code X-Request-Id}，必填（DELETE 没有请求体可承载标识）
     * @return 空成功响应
     */
    @DeleteMapping("/users/{userId}/roles/{roleId}")
    @Operation(summary = "解除角色", description = """
            受对应操作的功能权限保护。必须携带 X-Request-Id；重复提交同一标识至多执行一次。\
            未绑定该角色时返回 404，不视为成功。解除在下一次请求即生效。""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "解除成功"),
            @ApiResponse(responseCode = "400", description = "缺少请求标识"),
            @ApiResponse(responseCode = "403", description = "缺少全局身份管理资格"),
            @ApiResponse(responseCode = "404", description = "目标用户不存在，或该角色未绑定"),
            @ApiResponse(responseCode = "409", description = "解除会使系统失去最后一名启用的全局管理员")
    })
    public R<Void> revokeRole(@PathVariable @Positive long userId,
                              @PathVariable @Positive long roleId,
                              @RequestHeader(value = "X-Request-Id", required = false) String header) {
        long actor = authorizer.require(Permission.IDENTITY_ROLE_ASSIGN);
        identities.revokeRole(userId, roleId, actor, requestId(null, header));
        return R.ok();
    }

    /**
     * 管理员重置用户密码。
     *
     * <p>不校验原密码（管理员不知道也不应知道它）；重置后目标用户的下一次
     * 非白名单请求会被强制改密拦截器以 403 拒绝，直到其完成自助改密。
     *
     * @param userId  目标用户内部 ID
     * @param request 新密码与请求幂等标识
     * @param header  请求头 {@code X-Request-Id}；当请求体未提供标识时作为回退值
     * @return 重置后的用户身份摘要；命中同一请求标识的重放时返回首次结果
     */
    @PatchMapping("/users/{userId}/password")
    @Operation(summary = "重置用户密码", description = """
            受对应操作的功能权限保护。不校验原密码；新密码须满足服务端强度约束。\
            重置会把目标用户置为强制改密状态，其既有会话在下一次请求即被拦截。\
            必须携带 X-Request-Id（或请求体 requestId）；重复提交同一标识返回首次结果。""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "重置成功，或命中同一请求标识的重放"),
            @ApiResponse(responseCode = "400", description = "缺少请求标识，或新密码不合强度"),
            @ApiResponse(responseCode = "403", description = "缺少全局身份管理资格"),
            @ApiResponse(responseCode = "404", description = "目标用户不存在")
    })
    public R<IdentitySummary> resetPassword(@PathVariable @Positive long userId,
                                            @Valid @RequestBody PasswordResetRequest request,
                                            @RequestHeader(value = "X-Request-Id", required = false) String header) {
        long actor = authorizer.require(Permission.IDENTITY_USER_PASSWORD);
        return R.ok(identities.resetPassword(userId, request.newPassword(), actor,
                requestId(request.requestId(), header)));
    }

    @PatchMapping("/users/{userId}/department")
    @Operation(summary = "转移用户主部门")
    /**
     * 按客户端版本将用户转移到新的主部门。
     *
     * @param userId 待转移用户内部 ID
     * @param request 目标部门、客户端读取到的用户版本和请求幂等标识
     * @param header 请求头 {@code X-Request-Id}；当请求体未提供标识时作为回退值
     * @return 更新后的用户身份摘要
     */
    public R<IdentitySummary> moveUser(@PathVariable @Positive long userId,
                                       @Valid @RequestBody UserDepartmentRequest request,
                                       @RequestHeader(value = "X-Request-Id", required = false) String header) {
        long actor = authorizer.require(Permission.IDENTITY_USER_WRITE);
        requireNoClearanceChange(request.clearanceLevel(), "归属转移");
        return R.ok(identities.moveToDepartment(userId, request.departmentId(), request.version(),
                actor, requestId(request.requestId(), header)));
    }

    @PatchMapping("/users/{userId}/enabled")
    @Operation(summary = "启用或停用用户", description = "停用会使系统失去最后一名启用的全局管理员时返回 409")
    /**
     * 更新用户启用状态。
     *
     * @param userId 用户内部 ID
     * @param request 目标启用状态、客户端版本和请求幂等标识
     * @param header 请求头 {@code X-Request-Id}；当请求体未提供标识时作为回退值
     * @return 更新后的用户身份摘要
     */
    public R<IdentitySummary> setUserEnabled(@PathVariable @Positive long userId,
                                             @Valid @RequestBody EnabledRequest request,
                                             @RequestHeader(value = "X-Request-Id", required = false) String header) {
        long actor = authorizer.require(Permission.IDENTITY_USER_WRITE);
        requireNoClearanceChange(request.clearanceLevel(), "启停");
        return R.ok(identities.setEnabled(userId, request.enabled(), request.version(), actor,
                requestId(request.requestId(), header)));
    }

    /**
     * 按乐观锁版本调整用户密级。
     *
     * <p>操作者取自 {@code require(Permission)} 的返回值——也就是已验证会话——
     * <b>不接受请求体声明</b>。这不是风格问题：自我提权判定在服务端比较操作者与目标，
     * 若操作者可由请求体给出，那条判定就等于不存在。
     *
     * <p>文档注释放在 {@code @PatchMapping} / {@code @Operation} <b>之前</b>：
     * 放在注解之后会被编译器当成普通块注释，不进生成文档。本类其余方法沿用的是
     * 旧写法，新增的这个按 {@code DocumentRelocationControllerDocsTest} 钉下的
     * 约定写。
     *
     * @param userId  目标用户内部 ID
     * @param request 目标密级、客户端读取到的用户版本和请求幂等标识
     * @param header  请求头 {@code X-Request-Id}；当请求体未提供标识时作为回退值
     * @return 调整后的用户身份摘要，含新密级与新版本
     */
    @PatchMapping("/users/{userId}/clearance")
    @Operation(summary = "调整用户密级",
            description = """
                    独立的密级调整入口。密级不能通过归属转移、启停等其他身份写操作夹带修改，\
                    那类请求会返回 400。目标密级必须存在于服务端密级字典 sys_security_level；\
                    可用级别由字典决定，不由本接口写死。操作者不得以自身为目标。\
                    必须携带 version 与 X-Request-Id（或请求体 requestId）；\
                    重复提交同一标识返回首次结果。调整提交后权限判定立即按新密级执行。""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "调整成功，或命中同一请求标识的重放"),
            @ApiResponse(responseCode = "400", description = "缺少请求标识，或目标密级不在密级字典中"),
            @ApiResponse(responseCode = "403", description = "缺少全局身份管理资格，或以自身为目标"),
            @ApiResponse(responseCode = "404", description = "目标用户不存在"),
            @ApiResponse(responseCode = "409", description = "提交的 version 已过期，密级已被其他操作更新")
    })
    public R<IdentitySummary> setUserClearance(@PathVariable @Positive long userId,
                                               @Valid @RequestBody UserClearanceRequest request,
                                               @RequestHeader(value = "X-Request-Id", required = false) String header) {
        long actor = authorizer.require(Permission.IDENTITY_CLEARANCE_WRITE);
        return R.ok(identities.changeClearance(userId, request.clearanceLevel(), request.version(),
                actor, requestId(request.requestId(), header)));
    }

    @PostMapping("/users/{userId}/external-identities")
    @Operation(summary = "绑定外部身份", description = "subject 仅作为服务端查找键，响应和审计只返回 provider 摘要")
    /**
     * 为用户绑定认证提供方身份。
     *
     * @param userId 目标用户内部 ID
     * @param request provider、外部 subject 和请求幂等标识；subject 不会回显
     * @param header 请求头 {@code X-Request-Id}；当请求体未提供标识时作为回退值
     * @return 更新后的用户身份摘要
     */
    public R<IdentitySummary> bindExternal(@PathVariable @Positive long userId,
                                           @Valid @RequestBody ExternalIdentityRequest request,
                                           @RequestHeader(value = "X-Request-Id", required = false) String header) {
        long actor = authorizer.require(Permission.IDENTITY_EXTERNAL_WRITE);
        return R.ok(identities.bindExternal(userId, request.provider(), request.subject(), actor,
                requestId(request.requestId(), header)));
    }

    @DeleteMapping("/users/{userId}/external-identities")
    @Operation(summary = "解除外部身份")
    /**
     * 解除用户的一条外部身份绑定。
     *
     * @param userId 用户内部 ID
     * @param request 外部身份映射 ID和请求幂等标识
     * @param header 请求头 {@code X-Request-Id}；当请求体未提供标识时作为回退值
     * @return 空成功响应
     */
    public R<Void> unbindExternal(@PathVariable @Positive long userId,
                                  @Valid @RequestBody ExternalIdentityRemoveRequest request,
                                  @RequestHeader(value = "X-Request-Id", required = false) String header) {
        long actor = authorizer.require(Permission.IDENTITY_EXTERNAL_WRITE);
        identities.unbindExternal(userId, request.identityId(), actor, requestId(request.requestId(), header));
        return R.ok();
    }

    @GetMapping("/departments")
    @Operation(summary = "查询部门树")
    /**
     * 查询部门层级摘要。
     *
     * @return 部门列表及其父子关系、启用状态和版本
     */
    public R<List<DepartmentSummary>> departments() {
        authorizer.require(Permission.IDENTITY_DEPT_READ);
        return R.ok(departments.list());
    }

    @GetMapping("/departments/{departmentId}")
    @Operation(summary = "查询部门摘要")
    /**
     * 查询单个部门摘要。
     *
     * @param departmentId 部门内部 ID
     * @return 部门摘要
     */
    public R<DepartmentSummary> department(@PathVariable @Positive long departmentId) {
        authorizer.require(Permission.IDENTITY_DEPT_READ);
        return R.ok(departments.get(departmentId));
    }

    @PostMapping("/departments")
    @Operation(summary = "创建部门")
    /**
     * 创建部门，可选地挂到现有父部门下。
     *
     * @param request 部门编码、名称、父部门 ID 和请求幂等标识
     * @param header 请求头 {@code X-Request-Id}；当请求体未提供标识时作为回退值
     * @return 创建后的部门摘要
     */
    public R<DepartmentSummary> createDepartment(@Valid @RequestBody DepartmentCreateRequest request,
                                                 @RequestHeader(value = "X-Request-Id", required = false) String header) {
        long actor = authorizer.require(Permission.IDENTITY_DEPT_WRITE);
        return R.ok(departments.create(request.code(), request.name(), request.parentId(), actor,
                requestId(request.requestId(), header)));
    }

    @PatchMapping("/departments/{departmentId}/name")
    @Operation(summary = "重命名部门")
    /**
     * 按乐观锁版本修改部门名称。
     *
     * @param departmentId 部门内部 ID
     * @param request 新名称、客户端读取到的部门版本和请求幂等标识
     * @param header 请求头 {@code X-Request-Id}；当请求体未提供标识时作为回退值
     * @return 更新后的部门摘要
     */
    public R<DepartmentSummary> renameDepartment(@PathVariable @Positive long departmentId,
                                                 @Valid @RequestBody DepartmentRenameRequest request,
                                                 @RequestHeader(value = "X-Request-Id", required = false) String header) {
        long actor = authorizer.require(Permission.IDENTITY_DEPT_WRITE);
        return R.ok(departments.rename(departmentId, request.name(), request.version(), actor,
                requestId(request.requestId(), header)));
    }

    @PatchMapping("/departments/{departmentId}/parent")
    @Operation(summary = "移动部门")
    /**
     * 移动部门并一致更新其后代的祖先路径。
     *
     * @param departmentId 待移动部门内部 ID
     * @param request 目标父部门、客户端版本和请求幂等标识；父部门为空表示移动到根
     * @param header 请求头 {@code X-Request-Id}；当请求体未提供标识时作为回退值
     * @return 更新后的部门摘要
     */
    public R<DepartmentSummary> moveDepartment(@PathVariable @Positive long departmentId,
                                               @Valid @RequestBody DepartmentMoveRequest request,
                                               @RequestHeader(value = "X-Request-Id", required = false) String header) {
        long actor = authorizer.require(Permission.IDENTITY_DEPT_WRITE);
        return R.ok(departments.move(departmentId, request.parentId(), request.version(), actor,
                requestId(request.requestId(), header)));
    }

    @PatchMapping("/departments/{departmentId}/enabled")
    @Operation(summary = "启用或停用部门")
    /**
     * 更新部门启用状态。
     *
     * @param departmentId 部门内部 ID
     * @param request 目标启用状态、客户端版本和请求幂等标识
     * @param header 请求头 {@code X-Request-Id}；当请求体未提供标识时作为回退值
     * @return 更新后的部门摘要
     */
    public R<DepartmentSummary> setDepartmentEnabled(@PathVariable @Positive long departmentId,
                                                     @Valid @RequestBody EnabledRequest request,
                                                     @RequestHeader(value = "X-Request-Id", required = false) String header) {
        long actor = authorizer.require(Permission.IDENTITY_DEPT_WRITE);
        requireNoClearanceChange(request.clearanceLevel(), "部门启停");
        return R.ok(departments.setEnabled(departmentId, request.enabled(), request.version(), actor,
                requestId(request.requestId(), header)));
    }

    @GetMapping("/audits")
    @Operation(summary = "查询变更审计摘要")
    /**
     * 按目标和时间范围查询身份与部门变更审计。
     *
     * @param targetType 目标类型，例如 {@code USER} 或 {@code DEPARTMENT}
     * @param targetId 目标内部 ID
     * @param from 起始时间（包含），可选
     * @param to 截止时间（包含），可选
     * @return 脱敏的不可变审计摘要列表
     */
    public R<List<IdentityAuditRecord>> audits(@RequestParam String targetType,
                                               @RequestParam Long targetId,
                                               @RequestParam(required = false) Instant from,
                                               @RequestParam(required = false) Instant to) {
        authorizer.require(Permission.IDENTITY_AUDIT_READ);
        return R.ok(audits.list(targetType, targetId, from, to));
    }

    private static String requestId(String body, String header) {
        String value = body == null || body.isBlank() ? header : body;
        if (value == null || value.isBlank()) {
            throw IdentityException.badRequest("缺少请求标识 requestId");
        }
        return value;
    }

    /**
     * 其他身份写操作一律不改密级，夹带即拒。
     *
     * <p><b>为什么不是静默忽略</b>：Jackson 默认丢弃未知字段，所以一个带
     * {@code clearanceLevel} 的归属转移请求本来会返回 200——调用方以为密级也改了，
     * 实际只改了归属。那种"成功但没做"的响应比报错危险得多：它不会进任何错误日志，
     * 也不会触发重试，只会让一次预期中的收紧悄悄没有发生。
     *
     * <p>做法与 {@code DocumentRelocationService.requireNoSecurityLevelChange} 一致：
     * DTO 收下这个字段，只为了能明确拒掉它，并在报错里指出该走哪个接口。
     *
     * <p>判定放在 {@code require(Permission)} 之后：无资格的调用方应当先拿到 403，
     * 不该因为请求体形状不同而拿到 400——那等于用错误码回答"你的请求长什么样"
     * 而不是"你有没有资格"。
     */
    private static void requireNoClearanceChange(Short clearanceLevel, String operation) {
        if (clearanceLevel != null) {
            throw IdentityException.badRequest(
                    operation + "不修改密级；密级调整是独立的受保护操作，"
                            + "请使用 PATCH /api/admin/identity/users/{userId}/clearance");
        }
    }

}
