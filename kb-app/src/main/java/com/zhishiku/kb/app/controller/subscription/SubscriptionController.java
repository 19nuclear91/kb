package com.zhishiku.kb.app.controller.subscription;

import com.zhishiku.kb.app.domain.req.subscription.SubscriptionRequests;
import com.zhishiku.kb.app.domain.vo.subscription.SubscriptionViews;
import com.zhishiku.kb.app.enums.subscription.SubscriptionKind;
import com.zhishiku.kb.app.enums.subscription.SubscriptionMode;
import com.zhishiku.kb.app.enums.subscription.SubscriptionState;
import com.zhishiku.kb.app.service.impl.subscription.SubscriptionRules;
import com.zhishiku.kb.app.service.subscription.SubscriptionReadService;
import com.zhishiku.kb.app.service.subscription.SubscriptionWriteService;
import com.zhishiku.kb.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/** 本人订阅入口，所有成功响应保持准确的R泛型和私有响应策略。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
@Tag(name = "知识订阅", description = "本人文档、空间、标签订阅及统一提醒偏好")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "字段、参数、标识或请求格式无效"),
        @ApiResponse(responseCode = "401", description = "登录或认证代次无效"),
        @ApiResponse(responseCode = "403", description = "主体不可用或需要强制改密"),
        @ApiResponse(responseCode = "404", description = "本人关系或当前可订阅目标不存在或不可访问"),
        @ApiResponse(responseCode = "409", description = "原意图、版本、代次冲突或达到订阅上限"),
        @ApiResponse(responseCode = "413", description = "JSON正文超过16KiB"),
        @ApiResponse(responseCode = "503", description = "依赖或完整结果暂不可确认")
})
public class SubscriptionController {
    private final SubscriptionReadService reads;
    private final SubscriptionWriteService writes;

    @GetMapping("/knowledge-subscriptions")
    @Operation(summary = "分页读取本人订阅管理列表")
    @Parameters({
            @Parameter(name = "kind", in = ParameterIn.QUERY, description = "可选DOCUMENT文档、SPACE空间、TAG标签"),
            @Parameter(name = "state", in = ParameterIn.QUERY, description = "可选ACTIVE有效、CANCELLED已取消"),
            @Parameter(name = "limit", in = ParameterIn.QUERY, description = "每页1至100，默认20"),
            @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "可选绑定本人与条件的下一页游标")
    })
    @ApiResponse(responseCode = "200", description = "R包装的本人订阅页，不可访问目标隐藏ID和名称", useReturnTypeSchema = true)
    public ResponseEntity<R<SubscriptionViews.Page<SubscriptionViews.Item>>> list(HttpServletRequest request) {
        long actor = SubscriptionInput.actor(request);
        var query = SubscriptionInput.query(request, "kind", "state", "limit", "cursor");
        String kind = query.exactText("kind", 20);
        String state = query.exactText("state", 20);
        return ok(reads.list(actor, kind == null ? null : SubscriptionInput.enumeration(SubscriptionKind.class, kind),
                state == null ? null : SubscriptionInput.enumeration(SubscriptionState.class, state), query.limit(), query.cursor()));
    }

    @PostMapping(value = "/knowledge-subscriptions/candidates", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "查找当前有资格订阅的目标")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = SubscriptionRequests.Candidates.class)))
    @ApiResponse(responseCode = "200", description = "R包装的受保护候选页及本人关系版本", useReturnTypeSchema = true)
    public ResponseEntity<R<SubscriptionViews.Page<SubscriptionViews.Candidate>>> candidates(HttpServletRequest request) {
        long actor = SubscriptionInput.actor(request);
        var body = SubscriptionInput.body(request, Set.of("kind", "query", "limit", "cursor"), Set.of("kind"));
        return ok(reads.candidates(actor, SubscriptionInput.enumeration(SubscriptionKind.class, SubscriptionInput.text(body.get("kind"))),
                SubscriptionInput.optional(body, "query"), SubscriptionInput.limit(body), SubscriptionInput.optional(body, "cursor")));
    }

    @PostMapping(value = "/knowledge-subscriptions/target-state", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "读取可订阅目标及本人当前关系")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = SubscriptionRequests.Target.class)))
    @ApiResponse(responseCode = "200", description = "R包装的当前授权目标与本人关系状态", useReturnTypeSchema = true)
    public ResponseEntity<R<SubscriptionViews.Candidate>> target(HttpServletRequest request) {
        long actor = SubscriptionInput.actor(request);
        var fields = Set.of("kind", "targetId");
        var body = SubscriptionInput.body(request, fields, fields);
        return ok(reads.target(actor, SubscriptionInput.enumeration(SubscriptionKind.class, SubscriptionInput.text(body.get("kind"))),
                SubscriptionInput.number(body.get("targetId"), 1)));
    }

    @PostMapping(value = "/knowledge-subscriptions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "建立或重新激活本人订阅")
    @Parameter(name = "X-Request-Id", in = ParameterIn.HEADER, required = true, description = "1至128个非控制字符，绑定原目标及预期版本")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = SubscriptionRequests.Subscribe.class)))
    @ApiResponse(responseCode = "200", description = "R包装的本次订阅代次及生效回执，不回补历史", useReturnTypeSchema = true)
    public ResponseEntity<R<SubscriptionViews.Receipt>> subscribe(HttpServletRequest request) {
        long actor = SubscriptionInput.actor(request);
        var fields = Set.of("kind", "targetId", "expectedVersion");
        var body = SubscriptionInput.body(request, fields, fields);
        return ok(writes.subscribe(actor, SubscriptionInput.requestId(request),
                SubscriptionInput.enumeration(SubscriptionKind.class, SubscriptionInput.text(body.get("kind"))),
                SubscriptionInput.number(body.get("targetId"), 1), SubscriptionInput.number(body.get("expectedVersion"), 0)));
    }

    @PutMapping(value = "/knowledge-subscriptions/{id}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "取消本人指定订阅代次")
    @Parameter(name = "X-Request-Id", in = ParameterIn.HEADER, required = true, description = "1至128个非控制字符，重试保持原请求")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = SubscriptionRequests.Cancel.class)))
    @ApiResponse(responseCode = "200", description = "R包装的原代次取消回执，已生成通知和历史保留", useReturnTypeSchema = true)
    public ResponseEntity<R<SubscriptionViews.Receipt>> cancel(
            @Parameter(name = "id", description = "本人稳定订阅关系的小写UUID", required = true)
            @PathVariable
            String id,
            HttpServletRequest request
    ) {
        long actor = SubscriptionInput.actor(request);
        var fields = Set.of("generationId", "expectedVersion");
        var body = SubscriptionInput.body(request, fields, fields);
        return ok(writes.cancel(actor, SubscriptionInput.requestId(request), SubscriptionRules.uuid(id),
                SubscriptionRules.uuid(SubscriptionInput.text(body.get("generationId"))),
                SubscriptionInput.number(body.get("expectedVersion"), 1)));
    }

    @GetMapping("/knowledge-subscription-preferences")
    @Operation(summary = "读取本人统一提醒频率")
    @ApiResponse(responseCode = "200", description = "R包装的频率及固定北京时间周报安排", useReturnTypeSchema = true)
    public ResponseEntity<R<SubscriptionViews.Preference>> preference(HttpServletRequest request) {
        long actor = SubscriptionInput.actor(request);
        SubscriptionInput.query(request);
        return ok(reads.preference(actor));
    }

    @PutMapping(value = "/knowledge-subscription-preferences", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "修改本人统一提醒频率", description = "仅影响之后发布，已有待提醒项继续使用原频率")
    @Parameter(name = "X-Request-Id", in = ParameterIn.HEADER, required = true, description = "1至128个非控制字符，绑定模式与预期版本")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = SubscriptionRequests.Preference.class)))
    @ApiResponse(responseCode = "200", description = "R包装的频率生效回执", useReturnTypeSchema = true)
    public ResponseEntity<R<SubscriptionViews.Receipt>> savePreference(HttpServletRequest request) {
        long actor = SubscriptionInput.actor(request);
        var fields = Set.of("mode", "expectedVersion");
        var body = SubscriptionInput.body(request, fields, fields);
        return ok(writes.preference(actor, SubscriptionInput.requestId(request),
                SubscriptionInput.enumeration(SubscriptionMode.class, SubscriptionInput.text(body.get("mode"))),
                SubscriptionInput.number(body.get("expectedVersion"), 0)));
    }

    @PostMapping(value = "/knowledge-subscriptions/receipts/query", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "只读核对本人原命令回执")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = SubscriptionRequests.ReceiptQuery.class)))
    @ApiResponse(responseCode = "200", description = "R包装的CONFIRMED或UNKNOWN状态，UNKNOWN不证明原命令未执行", useReturnTypeSchema = true)
    public ResponseEntity<R<SubscriptionViews.ReceiptLookup>> receipt(HttpServletRequest request) {
        long actor = SubscriptionInput.actor(request);
        var fields = Set.of("requestId");
        var body = SubscriptionInput.body(request, fields, fields);
        return ok(reads.receipt(actor, SubscriptionInput.text(body.get("requestId"))));
    }

    private static <T> ResponseEntity<R<T>> ok(T value) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(R.ok(value));
    }
}
