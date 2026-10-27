package com.zhishiku.kb.app.space.web;

import com.zhishiku.kb.app.auth.model.IdentityQueryViews.PrincipalOption;
import com.zhishiku.kb.app.query.AdminQueryEndpoint;
import com.zhishiku.kb.app.query.QueryPage;
import com.zhishiku.kb.app.space.service.PrincipalQueryService;
import com.zhishiku.kb.common.api.R;
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
 * 管理资格只开放最小候选；授予 manage 仍由既有写接口独立校验。
 */
@AdminQueryEndpoint
@RestController
@Tag(name = "授权主体查询")
public class PrincipalQueryController {
    private final PrincipalQueryService service;

    public PrincipalQueryController(PrincipalQueryService service) {
        this.service = service;
    }

    @GetMapping("/api/admin/spaces/{spaceId}/acl/principals")
    @Operation(summary = "查询空间授权候选", description = "要求当前空间 manage 或 space:manage-all；无权与不存在统一 404，支持归档空间。")
    @Parameter(name = "principalType", in = ParameterIn.QUERY, required = true, description = "USER、ROLE 或 DEPT")
    @Parameter(name = "q", in = ParameterIn.QUERY, description = "候选 label 字面包含匹配，最多 128 个 Unicode 字符")
    @Parameter(name = "limit", in = ParameterIn.QUERY, description = "默认 20，范围 1–100")
    @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "绑定空间、类型与过滤条件的续页游标")
    public R<QueryPage<PrincipalOption>> space(@PathVariable String spaceId,
                                               @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> parameters) {
        return R.ok(service.space(spaceId, parameters));
    }

    @GetMapping("/api/admin/documents/{documentId}/acl/principals")
    @Operation(summary = "查询文档授权候选", description = "按文档当前所属空间重新检查 manage；已删除、无权或不存在统一 404。")
    @Parameter(name = "principalType", in = ParameterIn.QUERY, required = true, description = "USER、ROLE 或 DEPT")
    @Parameter(name = "q", in = ParameterIn.QUERY, description = "候选 label 字面包含匹配，最多 128 个 Unicode 字符")
    @Parameter(name = "limit", in = ParameterIn.QUERY, description = "默认 20，范围 1–100")
    @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "绑定文档、类型与过滤条件的续页游标")
    public R<QueryPage<PrincipalOption>> document(@PathVariable String documentId,
                                                  @Parameter(hidden = true) @RequestParam MultiValueMap<String,
                                                          String> parameters) {
        return R.ok(service.document(documentId, parameters));
    }
}
