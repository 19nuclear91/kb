package com.zhishiku.kb.app.auth.web;

import com.zhishiku.kb.app.auth.service.AuthQueryService;
import com.zhishiku.kb.app.query.AdminQueryEndpoint;
import com.zhishiku.kb.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 普通启用用户的权限与字典只读查询，仍受强制改密限制。
 */
@AdminQueryEndpoint
@RestController
@Tag(name = "会话查询")
public class AuthQueryController {
    private final AuthQueryService service;

    public AuthQueryController(AuthQueryService service) {
        this.service = service;
    }

    @GetMapping("/api/auth/permissions")
    @Operation(summary = "查询本人当前功能权限", description = "无参数；实时角色并集，不代表正文权限或后续请求的授权凭据。")
    public R<List<String>> permissions(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> params) {
        return R.ok(service.permissions(params));
    }

    @GetMapping("/api/auth/security-levels")
    @Operation(summary = "查询实际密级字典", description = "无参数；返回服务端数值升序列表，不提供字典写入或密级调整资格。")
    public R<List<Short>> levels(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> params) {
        return R.ok(service.levels(params));
    }
}
