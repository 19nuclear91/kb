package com.zhishiku.kb.app.auth.web;

import com.zhishiku.kb.app.auth.model.IdentityQueryViews.ExternalBinding;
import com.zhishiku.kb.app.auth.service.IdentityQueryService;
import com.zhishiku.kb.app.query.AdminQueryEndpoint;
import com.zhishiku.kb.app.query.QueryPage;
import com.zhishiku.kb.common.api.R;
import com.zhishiku.kb.common.security.IdentitySummary;
import com.zhishiku.kb.common.security.RoleSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 新增只读身份入口，不改变旧用户数组列表。
 */
@AdminQueryEndpoint
@RestController
@Tag(name = "身份查询", description = "有界身份与绑定关系查询")
public class IdentityQueryController {
    private final IdentityQueryService service;

    public IdentityQueryController(IdentityQueryService service) {
        this.service = service;
    }

    @GetMapping("/api/admin/identity/users/search")
    @Operation(summary = "分页检索用户", description = "需要 identity:user:read；q 为字面包含，departmentId 精确匹配主部门，另支持 " +
            "enabled/limit/cursor。")
    @Parameter(name = "q", in = ParameterIn.QUERY, description = "username/displayName 字面包含匹配，最多 128 个 Unicode 字符")
    @Parameter(name = "departmentId", in = ParameterIn.QUERY, description = "精确主部门 ID，不展开子树")
    @Parameter(name = "enabled", in = ParameterIn.QUERY, description = "严格 true 或 false")
    @Parameter(name = "limit", in = ParameterIn.QUERY, description = "默认 20，范围 1–100")
    @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "绑定过滤条件的 ID 升序游标")
    public R<QueryPage<IdentitySummary>> users(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> params) {
        return R.ok(service.users(params));
    }

    @GetMapping("/api/admin/identity/users/{userId}/roles")
    @Operation(summary = "查询用户角色绑定", description = "需要 identity:user:read；支持 limit/cursor，不返回角色权限，停用目标仍可查询。")
    @Parameter(name = "limit", in = ParameterIn.QUERY, description = "默认 20，范围 1–100")
    @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "绑定目标用户的角色 ID 升序游标")
    public R<QueryPage<RoleSummary>> roles(@PathVariable String userId,
                                           @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> params) {
        return R.ok(service.roles(userId, params));
    }

    @GetMapping("/api/admin/identity/users/{userId}/external-identities")
    @Operation(summary = "查询外部身份绑定", description = "需要 identity:external:read；支持 provider 精确过滤与 limit/cursor，绝不返回 " +
            "subject。")
    @Parameter(name = "provider", in = ParameterIn.QUERY, description = "认证提供方精确值，最多 128 个 Unicode 字符")
    @Parameter(name = "limit", in = ParameterIn.QUERY, description = "默认 20，范围 1–100")
    @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "绑定目标用户与 provider 的 ID 升序游标")
    public R<QueryPage<ExternalBinding>> external(@PathVariable String userId,
                                                  @Parameter(hidden = true) @RequestParam MultiValueMap<String,
                                                          String> params) {
        return R.ok(service.external(userId, params));
    }
}
