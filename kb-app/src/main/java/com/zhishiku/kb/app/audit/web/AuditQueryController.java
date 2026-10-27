package com.zhishiku.kb.app.audit.web;

import com.zhishiku.kb.app.audit.model.AuditQueryViews.Detail;
import com.zhishiku.kb.app.audit.model.AuditQueryViews.Page;
import com.zhishiku.kb.app.audit.service.AuditQueryService;
import com.zhishiku.kb.app.query.AdminQueryEndpoint;
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
 * 仅新增全局查询路径，不更改旧的按目标审计接口。
 */
@AdminQueryEndpoint
@RestController
@Tag(name = "全局管理与登录审计查询")
public class AuditQueryController {
    private final AuditQueryService service;

    public AuditQueryController(AuditQueryService service) {
        this.service = service;
    }

    @GetMapping("/api/admin/audits")
    @Operation(summary = "分页查询管理与登录审计", description = "需要 audit:read；精确条件按 AND 组合。首期仅 sys_identity_audit，不含 AI Redis " +
            "审计或独立 Embedding 操作表。")
    @Parameter(name = "actorUserId", in = ParameterIn.QUERY, description = "操作者正整数 ID")
    @Parameter(name = "operationScope", in = ParameterIn.QUERY, description = "操作域精确值，最多 128 字符")
    @Parameter(name = "operation", in = ParameterIn.QUERY, description = "操作精确值，最多 128 字符")
    @Parameter(name = "targetType", in = ParameterIn.QUERY, description = "目标类型精确值，保留大小写，最多 64 字符")
    @Parameter(name = "targetId", in = ParameterIn.QUERY, description = "目标正整数 ID，必须同时提供 targetType")
    @Parameter(name = "status", in = ParameterIn.QUERY, description = "SUCCESS 或 FAILED")
    @Parameter(name = "requestId", in = ParameterIn.QUERY, description = "请求标识精确值，最多 256 字符")
    @Parameter(name = "loginUsername", in = ParameterIn.QUERY, description = "登录尝试用户名精确值，最多 128 字符；仅匹配登录事件")
    @Parameter(name = "from", in = ParameterIn.QUERY, description = "带时区含起点；默认 to 前 7 天")
    @Parameter(name = "to", in = ParameterIn.QUERY, description = "带时区不含终点；默认首次请求时间，最大窗口 31 天")
    @Parameter(name = "limit", in = ParameterIn.QUERY, description = "默认 20，范围 1–100")
    @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "固定窗口与过滤条件的倒序续页游标")
    public R<Page> list(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> parameters) {
        return R.ok(service.list(parameters));
    }

    @GetMapping("/api/admin/audits/{eventId}")
    @Operation(summary = "查询安全审计详情", description = "需要 audit:read，先鉴权再判断存在性；未知或异常摘要仅提供元数据并标记 summaryAvailable=false。")
    public R<Detail> detail(@PathVariable String eventId,
                            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> parameters) {
        return R.ok(service.detail(eventId, parameters));
    }
}
