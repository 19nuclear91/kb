package com.zhishiku.kb.app.parse.web;

import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.IdentityManagementAuthorizer;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.app.parse.model.ParseDeadLetterPage;
import com.zhishiku.kb.app.parse.model.ParseDeadLetterView;
import com.zhishiku.kb.app.parse.service.ParseDeadLetterService;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

/**
 * 解析死信管理接口。
 *
 * <p>端点分别要求死信读取或重放功能权限；响应只提供脱敏后的失败摘要，避免把对象存储路径、
 * 凭据或完整异常堆栈暴露给调用方。
 */
@RestController
@RequestMapping("/api/admin/parse")
@Tag(name = "解析死信管理", description = "管理员查询、筛选和重放解析死信")
@Validated
public class ParseDeadLetterController {
    private final ParseDeadLetterService service;
    private final IdentityManagementAuthorizer authorizer;
    private final IdentityService identities;

    public ParseDeadLetterController(ParseDeadLetterService service,
                                     IdentityManagementAuthorizer authorizer, IdentityService identities) {
        this.service = service;
        this.authorizer = authorizer;
        this.identities = identities;
    }

    @GetMapping("/dead-letters")
    @Operation(summary = "查询解析死信", description = "仅管理员可见，响应不包含对象路径或完整堆栈")
    /**
     * 分页查询解析死信，可按状态、失败分类和时间范围筛选。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数，范围 1-100
     * @param status 可选死信状态筛选
     * @param failureClass 可选失败分类筛选
     * @param from 可选起始时间（包含）
     * @param to 可选截止时间（不包含）
     * @param requestId 必填请求标识，用于审计关联
     * @return 脱敏的死信分页结果
     */
    public R<ParseDeadLetterPage> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String failureClass,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        long actor = authorizer.require(Permission.PARSE_DEADLETTER_READ);
        identities.resolvePrincipal(actor);
        return R.ok(service.list(page, size, status, failureClass, from, to, actor, requestId));
    }

    @PostMapping("/dead-letters/{deadLetterId}/replay")
    @Operation(summary = "重放解析死信", description = "重放创建新的执行周期，原死信历史保留")
    /**
     * 重放一条解析死信，创建新的执行周期并保留原失败历史。
     *
     * @param deadLetterId 死信内部 ID
     * @param request 重放原因；不得填写凭据或文档正文
     * @param requestId 必填请求标识，用于幂等控制和审计关联
     * @return 新执行周期对应的死信视图
     */
    public R<ParseDeadLetterView> replay(
            @PathVariable @Positive long deadLetterId,
            @Valid @RequestBody ParseDeadLetterReplayRequest request,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        long actor = authorizer.require(Permission.PARSE_DEADLETTER_REPLAY);
        identities.resolvePrincipal(actor);
        if (requestId == null || requestId.isBlank()) {
            throw ApiException.badRequest("缺少请求标识 X-Request-Id");
        }
        return R.ok(service.replay(actor, deadLetterId, requestId, request.reason()));
    }
}
