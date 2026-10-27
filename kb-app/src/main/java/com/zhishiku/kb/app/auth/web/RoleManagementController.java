package com.zhishiku.kb.app.auth.web;

import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.model.PermissionSummary;
import com.zhishiku.kb.app.auth.model.RoleDetail;
import com.zhishiku.kb.app.auth.service.IdentityManagementAuthorizer;
import com.zhishiku.kb.app.auth.service.RoleManagementService;
import com.zhishiku.kb.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色生命周期与权限目录，资格均从服务端实时解析。
 */
@RestController
@RequestMapping({"/api/admin/identity", "/api/identity"})
@Tag(name = "角色与功能权限")
public class RoleManagementController {
    private final RoleManagementService service;
    private final IdentityManagementAuthorizer authorizer;

    public RoleManagementController(RoleManagementService service, IdentityManagementAuthorizer authorizer) {
        this.service = service;
        this.authorizer = authorizer;
    }

    /**
     * 创建角色请求，初始权限集为空；幂等标识通过 {@code X-Request-Id} 请求头传递。
     *
     * @param code 全局唯一的角色编码，创建后不可修改，最长 64 个字符
     * @param name 角色显示名，最长 128 个字符
     */
    @Schema(name = "RoleCreateRequest", description = "创建空权限角色；X-Request-Id 在请求头传递")
    public record CreateRequest(
            @Schema(description = "全局唯一的角色编码，创建后不可修改", example = "ROLE_EDITOR")
            @NotBlank @Size(max = 64) String code,
            @Schema(description = "角色显示名", example = "知识编辑")
            @NotBlank @Size(max = 128) String name) {
    }

    /**
     * 修改角色显示名请求，角色编码仅用于核对，不支持改写。
     *
     * @param name 新的角色显示名，最长 128 个字符
     * @param code 可省略的既有角色编码；提供时必须与当前值完全相同
     */
    @Schema(name = "RoleRenameRequest", description = "只修改显示名；若提供 code，必须与原值相同")
    public record RenameRequest(
            @Schema(description = "新的角色显示名", example = "高级知识编辑")
            @NotBlank @Size(max = 128) String name,
            @Schema(description = "既有角色编码；可省略，提供时必须与原值完全相同", example = "ROLE_EDITOR")
            String code) {
    }

    /**
     * 单项角色权限变更请求，权限编码必须来自服务端目录。
     *
     * @param permission 要变更的功能权限编码，按完整编码精确匹配
     * @param granted    为 {@code true} 时授予，为 {@code false} 时移除
     */
    @Schema(name = "RolePermissionRequest", description = "授予或移除单个目录内权限；granted 为 true 授予，为 false 移除")
    public record PermissionRequest(
            @Schema(description = "服务端目录中的功能权限编码", example = "identity:role:read")
            @NotBlank String permission,
            @Schema(description = "是否授予权限；true 授予，false 移除", example = "true")
            @NotNull Boolean granted) {
    }

    @PutMapping("/roles/{roleId}/permissions")
    @Operation(summary = "授予或移除角色权限", description = "需要 identity:role:write；X-Request-Id 幂等，权限在下一次请求生效")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "变更后的角色权限集"),
            @ApiResponse(responseCode = "400", description = "权限不在目录或缺少请求标识"),
            @ApiResponse(responseCode = "403", description = "缺少角色写权限"),
            @ApiResponse(responseCode = "404", description = "角色不存在"),
            @ApiResponse(responseCode = "409", description = "变更会使管理资格不变量失效")})
    public R<RoleDetail> setPermission(@PathVariable long roleId, @Valid @RequestBody PermissionRequest request,
                                       @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        long actor = authorizer.require(Permission.IDENTITY_ROLE_WRITE);
        return R.ok(service.setPermission(roleId, request.permission(), request.granted(), actor, requestId));
    }

    @PostMapping("/roles")
    @Operation(summary = "创建角色", description = "需要 identity:role:write；初始权限为空，X-Request-Id 幂等")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "创建结果或重放结果"),
            @ApiResponse(responseCode = "400", description = "参数或请求标识无效"),
            @ApiResponse(responseCode = "403", description = "缺少角色写权限"),
            @ApiResponse(responseCode = "409", description = "code 已存在")})
    public R<RoleDetail> create(@Valid @RequestBody CreateRequest request,
                                @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        long actor = authorizer.require(Permission.IDENTITY_ROLE_WRITE);
        return R.ok(service.create(request.code(), request.name(), actor, requestId));
    }

    @PatchMapping("/roles/{roleId}")
    @Operation(summary = "修改角色显示名", description = "需要 identity:role:write；code 不可变，X-Request-Id 幂等")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "更新后的角色"),
            @ApiResponse(responseCode = "400", description = "code 不可变或参数无效"),
            @ApiResponse(responseCode = "403", description = "缺少角色写权限"),
            @ApiResponse(responseCode = "404", description = "角色不存在")})
    public R<RoleDetail> rename(@PathVariable long roleId, @Valid @RequestBody RenameRequest request,
                                @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        long actor = authorizer.require(Permission.IDENTITY_ROLE_WRITE);
        return R.ok(service.rename(roleId, request.name(), request.code(), actor, requestId));
    }

    @DeleteMapping("/roles/{roleId}")
    @Operation(summary = "删除无引用角色", description = "需要 identity:role:write；先解除所有用户绑定和空间、文档 ACL 引用")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "已删除或请求重放"),
            @ApiResponse(responseCode = "400", description = "缺少请求标识"),
            @ApiResponse(responseCode = "403", description = "缺少角色写权限"),
            @ApiResponse(responseCode = "404", description = "角色不存在"),
            @ApiResponse(responseCode = "409", description = "仍有用户绑定或授权记录")})
    public R<Void> delete(@PathVariable long roleId,
                          @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        long actor = authorizer.require(Permission.IDENTITY_ROLE_WRITE);
        service.delete(roleId, actor, requestId);
        return R.ok();
    }

    @GetMapping("/roles/{roleId}")
    @Operation(summary = "查询角色及当前权限集", description = "需要 identity:role:read")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "角色详情与权限 code 列表"),
            @ApiResponse(responseCode = "403", description = "缺少角色读权限"),
            @ApiResponse(responseCode = "404", description = "角色不存在")})
    public R<RoleDetail> get(@PathVariable long roleId) {
        authorizer.require(Permission.IDENTITY_ROLE_READ);
        return R.ok(service.get(roleId));
    }

    @GetMapping("/permissions")
    @Operation(summary = "查询功能权限目录", description = "需要 identity:role:read；返回代码定义的 code 与用途说明")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "完整权限目录"),
            @ApiResponse(responseCode = "403", description = "缺少角色读权限")})
    public R<List<PermissionSummary>> catalog() {
        authorizer.require(Permission.IDENTITY_ROLE_READ);
        return R.ok(service.catalog());
    }
}
