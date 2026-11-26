package com.zhishiku.kb.app.controller.answercard;

import com.zhishiku.kb.app.auth.service.EpochSessionService;
import com.zhishiku.kb.app.config.EpochAuthenticationInterceptor;
import com.zhishiku.kb.app.domain.req.answercard.AnswerCardRequests;
import com.zhishiku.kb.app.domain.vo.answercard.AnswerCardReceipt;
import com.zhishiku.kb.app.domain.vo.answercard.AnswerCardViews;
import com.zhishiku.kb.app.enums.answercard.AnswerCardOperation;
import com.zhishiku.kb.app.service.answercard.AnswerCardFailure;
import com.zhishiku.kb.app.service.answercard.AnswerCardReadService;
import com.zhishiku.kb.app.service.answercard.AnswerCardWriteService;
import com.zhishiku.kb.app.service.impl.answercard.AnswerCardSearchExecutor;
import com.zhishiku.kb.common.api.R;
import com.zhishiku.kb.common.security.IdentityException;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Set;

@RestController
@RequestMapping("/api/answer-cards")
@Tag(name = "标准答案卡", description = "独立人工审核的固定答案；内容访问始终检查所属空间与全部依据")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "字段、身份字符串或请求格式无效"),
        @ApiResponse(responseCode = "401", description = "登录或认证代次失效"),
        @ApiResponse(responseCode = "403", description = "缺少当前功能资格或需先改密"),
        @ApiResponse(responseCode = "404", description = "目标不存在或当前不可访问，不披露私有原因"),
        @ApiResponse(responseCode = "409", description = "版本、来源、人员或工作流已变化，请重新核对"),
        @ApiResponse(responseCode = "413", description = "正文超过上限，搜索16KiB，其他128KiB"),
        @ApiResponse(responseCode = "429", description = "答案卡搜索容量已满，Retry-After以秒计"),
        @ApiResponse(responseCode = "503", description = "依赖、预算或完整响应暂不可用")
})
public class AnswerCardController {
    private final AnswerCardReadService reads;
    private final AnswerCardWriteService writes;
    private final AnswerCardSearchExecutor search;

    public AnswerCardController(AnswerCardReadService reads, AnswerCardWriteService writes, AnswerCardSearchExecutor search) {
        this.reads = reads;
        this.writes = writes;
        this.search = search;
    }

    @GetMapping
    @Operation(summary = "浏览当前可展示标准答案")
    @Parameters({
            @Parameter(name = "spaceId", in = ParameterIn.QUERY, description = "可选所属空间正整数ID字符串，不可见空间返回空页"),
            @Parameter(name = "limit", in = ParameterIn.QUERY, description = "每页1至100，默认20"),
            @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "绑定当前用户、空间及用途的有界游标")
    })
    @ApiResponse(responseCode = "200", description = "R包装的有界授权摘要页，含协议头与no-store", useReturnTypeSchema = true)
    public ResponseEntity<R<AnswerCardViews.Page<AnswerCardViews.Item>>> browse(HttpServletRequest request) {
        long actor = actor(request);
        return ok(reads.browse(actor, AnswerCardInput.page(request, null, "spaceId", "limit", "cursor")));
    }

    @PostMapping(value = "/search", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "按问题独立搜索标准答案", description = "最多20项，不应用文档过滤、术语扩展或AI生成")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = AnswerCardRequests.Search.class)))
    @ApiResponse(responseCode = "200", description = "完整R包装搜索响应，包含已提交条件，X-Answer-Card-Version为1",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = SearchResponse.class)))
    public ResponseEntity<byte[]> search(HttpServletRequest request) {
        long actor = actor(request);
        var node = AnswerCardInput.body(request, 16384);
        AnswerCardInput.fields(node, Set.of("query", "spaceId", "limit"), Set.of("query"));
        String query = com.zhishiku.kb.app.domain.dto.answercard.AnswerCardContent.text(AnswerCardInput.text(node.get("query")), 1, 256);
        Long space = AnswerCardInput.nullableId(node.get("spaceId"), true);
        int limit = node.has("limit") ? AnswerCardInput.integer(node.get("limit"), 1, 20) : 10;
        byte[] bytes = search.execute(actor, () -> reads.search(actor, query, space, limit));
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("X-Answer-Card-Version", "1")
                .contentType(MediaType.APPLICATION_JSON).body(bytes);
    }

    @GetMapping("/{id}/revisions/{revisionId}")
    @Operation(summary = "读取选中的固定发布修订", description = "必须仍为当前可展示版，陈旧结果统一404")
    @ApiResponse(responseCode = "200", description = "R包装的审定原文、日期及全部可读固定依据", useReturnTypeSchema = true)
    public ResponseEntity<R<AnswerCardViews.Detail>> detail(
            @Parameter(description = "卡片正整数ID字符串", required = true)
            @PathVariable
            String id,
            @Parameter(description = "选中的固定修订正整数ID字符串", required = true)
            @PathVariable
            String revisionId,
            HttpServletRequest request) {
        long actor = actor(request);
        AnswerCardInput.page(request, null);
        return ok(reads.detail(actor, AnswerCardInput.number(id, false), AnswerCardInput.number(revisionId, false)));
    }

    @GetMapping("/workspace")
    @Operation(summary = "查询本人当前可操作卡片")
    @Parameters({
            @Parameter(name = "view", in = ParameterIn.QUERY, description = "editable可编辑、reviewable可审核、mine本人负责、attention本人需处理；默认editable"),
            @Parameter(name = "spaceId", in = ParameterIn.QUERY, description = "可选所属空间ID字符串"),
            @Parameter(name = "limit", in = ParameterIn.QUERY, description = "每页1至100，默认20"),
            @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "本用户本视图的续页游标")
    })
    @ApiResponse(responseCode = "200", description = "R包装的当前工作区页，不包含隐藏总数", useReturnTypeSchema = true)
    public ResponseEntity<R<AnswerCardViews.Page<AnswerCardViews.Workspace>>> workspaces(HttpServletRequest request) {
        long actor = actor(request);
        return ok(reads.workspaces(actor, AnswerCardInput.page(request, "editable", "view", "spaceId", "limit", "cursor")));
    }

    @GetMapping("/{id}/workspace")
    @Operation(summary = "读取卡片当前维护及审核工作区")
    @ApiResponse(responseCode = "200", description = "R包装的独立发布版、草稿及当前动作资格", useReturnTypeSchema = true)
    public ResponseEntity<R<AnswerCardViews.Workspace>> workspace(
            @Parameter(description = "卡片正整数ID字符串", required = true)
            @PathVariable
            String id,
            HttpServletRequest request) {
        long actor = actor(request);
        AnswerCardInput.page(request, null);
        return ok(reads.workspace(actor, AnswerCardInput.number(id, false)));
    }

    @GetMapping("/{id}/history")
    @Operation(summary = "按当前内容资格读取修订或审核历史")
    @Parameters({
            @Parameter(name = "view", in = ParameterIn.QUERY, description = "revisions内容修订、reviews审核轮次，默认revisions"),
            @Parameter(name = "revisionId", in = ParameterIn.QUERY, description = "审核轮次可选限定修订ID字符串"),
            @Parameter(name = "limit", in = ParameterIn.QUERY, description = "每页1至100，默认20"),
            @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "当前用户与历史用途的游标")
    })
    @ApiResponse(responseCode = "200", description = "R包装的逐修订授权历史页", useReturnTypeSchema = true)
    public ResponseEntity<R<AnswerCardViews.Page<AnswerCardViews.History>>> history(
            @Parameter(description = "卡片ID字符串", required = true)
            @PathVariable
            String id,
            HttpServletRequest request) {
        long actor = actor(request);
        return ok(reads.history(actor, AnswerCardInput.number(id, false),
                AnswerCardInput.page(request, "revisions", "view", "revisionId", "limit", "cursor")));
    }

    @GetMapping("/management")
    @Operation(summary = "读取空间内最小卡片管理清单")
    @Parameters({
            @Parameter(name = "spaceId", in = ParameterIn.QUERY, required = true, description = "当前可管理空间正整数ID字符串"),
            @Parameter(name = "limit", in = ParameterIn.QUERY, description = "每页1至100，默认20"),
            @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "管理用途游标")
    })
    @ApiResponse(responseCode = "200", description = "R包装的身份及复审状态页，不含正文或来源", useReturnTypeSchema = true)
    public ResponseEntity<R<AnswerCardViews.Page<AnswerCardViews.Management>>> management(HttpServletRequest request) {
        long actor = actor(request);
        return ok(reads.management(actor, AnswerCardInput.page(request, null, "spaceId", "limit", "cursor")));
    }

    @GetMapping("/{id}/stewardship")
    @Operation(summary = "读取最小责任人管理状态")
    @ApiResponse(responseCode = "200", description = "R包装的管理投影，不授予正文访问", useReturnTypeSchema = true)
    public ResponseEntity<R<AnswerCardViews.Management>> stewardship(
            @Parameter(description = "卡片ID字符串", required = true)
            @PathVariable
            String id,
            HttpServletRequest request) {
        long actor = actor(request);
        AnswerCardInput.page(request, null);
        return ok(reads.management(actor, AnswerCardInput.number(id, false)));
    }

    @PostMapping(value = "/source-options", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "按名称选择本人可读的当前发布依据")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = AnswerCardRequests.SourceOptions.class)))
    @ApiResponse(responseCode = "200", description = "R包装的固定来源候选页", useReturnTypeSchema = true)
    public ResponseEntity<R<AnswerCardViews.Page<AnswerCardViews.SourceOption>>> sources(HttpServletRequest request) {
        long actor = actor(request);
        return ok(reads.sources(actor, AnswerCardInput.candidate(AnswerCardInput.body(request, 131072), true)));
    }

    @PostMapping(value = "/{id}/stewardship/candidates", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "按显示名查询当前合格责任人")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = AnswerCardRequests.Candidates.class)))
    @ApiResponse(responseCode = "200", description = "R包装的ID和显示名候选页", useReturnTypeSchema = true)
    public ResponseEntity<R<AnswerCardViews.Page<AnswerCardViews.Person>>> candidates(
            @Parameter(description = "卡片ID字符串", required = true)
            @PathVariable
            String id,
            HttpServletRequest request) {
        long actor = actor(request);
        return ok(reads.candidates(actor, AnswerCardInput.number(id, false), AnswerCardInput.candidate(AnswerCardInput.body(request, 131072), false)));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "创建卡片与第一份草稿")
    @Parameter(name = "X-Request-Id", in = ParameterIn.HEADER, required = true, description = "原意图标识，1至128个非控制Unicode码点")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = AnswerCardRequests.Create.class)))
    @ApiResponse(responseCode = "200", description = "R包装的无正文持久回执，创建不发布", useReturnTypeSchema = true)
    public ResponseEntity<R<AnswerCardReceipt>> create(HttpServletRequest request) {
        return command(request, AnswerCardOperation.ANSWER_CARD_CREATE, null, null);
    }

    @PutMapping(value = "/{id}/draft", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "保存不可变草稿修订")
    @Parameter(name = "X-Request-Id", in = ParameterIn.HEADER, required = true, description = "原意图标识，1至128个非控制Unicode码点")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = AnswerCardRequests.Save.class)))
    @ApiResponse(responseCode = "200", description = "R包装的新草稿或原意图回执", useReturnTypeSchema = true)
    public ResponseEntity<R<AnswerCardReceipt>> save(
            @Parameter(description = "卡片ID字符串", required = true)
            @PathVariable
            String id,
            HttpServletRequest request) {
        return command(request, AnswerCardOperation.ANSWER_CARD_SAVE, id, null);
    }

    @PostMapping(value = "/{id}/reviews", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "手动送审并冻结精确草稿")
    @Parameter(name = "X-Request-Id", in = ParameterIn.HEADER, required = true, description = "原意图标识，1至128个非控制Unicode码点")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = AnswerCardRequests.Submit.class)))
    @ApiResponse(responseCode = "200", description = "R包装的固定审核轮次回执", useReturnTypeSchema = true)
    public ResponseEntity<R<AnswerCardReceipt>> submit(
            @Parameter(description = "卡片ID字符串", required = true)
            @PathVariable
            String id,
            HttpServletRequest request) {
        return command(request, AnswerCardOperation.ANSWER_CARD_SUBMIT, id, null);
    }

    @PostMapping(value = "/{id}/reviews/{reviewId}/approve", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "独立批准并原子发布固定修订")
    @Parameter(name = "X-Request-Id", in = ParameterIn.HEADER, required = true, description = "原意图标识，1至128个非控制Unicode码点")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = AnswerCardRequests.Decision.class)))
    @ApiResponse(responseCode = "200", description = "R包装的批准回执，重放不重复发布", useReturnTypeSchema = true)
    public ResponseEntity<R<AnswerCardReceipt>> approve(
            @Parameter(description = "卡片ID字符串", required = true)
            @PathVariable
            String id,
            @Parameter(description = "原审核轮次ID字符串", required = true)
            @PathVariable
            String reviewId,
            HttpServletRequest request) {
        return command(request, AnswerCardOperation.ANSWER_CARD_APPROVE, id, reviewId);
    }

    @PostMapping(value = "/{id}/reviews/{reviewId}/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "附原因驳回原审核轮次")
    @Parameter(name = "X-Request-Id", in = ParameterIn.HEADER, required = true, description = "原意图标识，1至128个非控制Unicode码点")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = AnswerCardRequests.Rejection.class)))
    @ApiResponse(responseCode = "200", description = "R包装的驳回回执，旧发布版保持原事实", useReturnTypeSchema = true)
    public ResponseEntity<R<AnswerCardReceipt>> reject(
            @Parameter(description = "卡片ID字符串", required = true)
            @PathVariable
            String id,
            @Parameter(description = "原审核轮次ID字符串", required = true)
            @PathVariable
            String reviewId,
            HttpServletRequest request) {
        return command(request, AnswerCardOperation.ANSWER_CARD_REJECT, id, reviewId);
    }

    @PostMapping(value = "/{id}/reviews/{reviewId}/withdraw", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "由当前合格送审者撤回原轮次")
    @Parameter(name = "X-Request-Id", in = ParameterIn.HEADER, required = true, description = "原意图标识，1至128个非控制Unicode码点")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = AnswerCardRequests.Decision.class)))
    @ApiResponse(responseCode = "200", description = "R包装的撤回回执，分支可继续编辑", useReturnTypeSchema = true)
    public ResponseEntity<R<AnswerCardReceipt>> withdraw(
            @Parameter(description = "卡片ID字符串", required = true)
            @PathVariable
            String id,
            @Parameter(description = "原审核轮次ID字符串", required = true)
            @PathVariable
            String reviewId,
            HttpServletRequest request) {
        return command(request, AnswerCardOperation.ANSWER_CARD_WITHDRAW, id, reviewId);
    }

    @PutMapping(value = "/{id}/stewardship", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "指定责任人或调整复审周期")
    @Parameter(name = "X-Request-Id", in = ParameterIn.HEADER, required = true, description = "原意图标识，1至128个非控制Unicode码点")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = AnswerCardRequests.Stewardship.class)))
    @ApiResponse(responseCode = "200", description = "R包装的最小配置回执，改派不重置日期", useReturnTypeSchema = true)
    public ResponseEntity<R<AnswerCardReceipt>> assign(
            @Parameter(description = "卡片ID字符串", required = true)
            @PathVariable
            String id,
            HttpServletRequest request) {
        return command(request, AnswerCardOperation.ANSWER_CARD_STEWARDSHIP_UPDATE, id, null);
    }

    @PostMapping(value = "/{id}/stewardship/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "本人确认当前发布答案仍准确", description = "不延长有效期，不解除来源变化阻断")
    @Parameter(name = "X-Request-Id", in = ParameterIn.HEADER, required = true, description = "原意图标识，1至128个非控制Unicode码点")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = AnswerCardRequests.Confirmation.class)))
    @ApiResponse(responseCode = "200", description = "R包装的准确性确认回执", useReturnTypeSchema = true)
    public ResponseEntity<R<AnswerCardReceipt>> confirm(
            @Parameter(description = "卡片ID字符串", required = true)
            @PathVariable
            String id,
            HttpServletRequest request) {
        return command(request, AnswerCardOperation.ANSWER_CARD_CONFIRM, id, null);
    }

    @PostMapping(value = "/commands/query", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "主动核对本人原写入意图", description = "NOT_FOUND仅表示本次未查到，不证明在途请求不会提交")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = AnswerCardRequests.CommandQuery.class)))
    @ApiResponse(responseCode = "200", description = "R包装的COMMITTED回执或NOT_FOUND，不包含原始正文", useReturnTypeSchema = true)
    public ResponseEntity<R<AnswerCardViews.CommandResult>> query(HttpServletRequest request) {
        long actor = actor(request);
        var node = AnswerCardInput.body(request, 131072);
        var fields = Set.of("operation", "requestId", "cardId", "reviewId", "payload");
        AnswerCardInput.fields(node, fields, fields);
        AnswerCardOperation operation;
        try {
            operation = AnswerCardOperation.valueOf(AnswerCardInput.text(node.get("operation")));
        } catch (IllegalArgumentException ignored) {
            throw AnswerCardFailure.invalid();
        }
        var intent = AnswerCardInput.intent(operation, AnswerCardInput.nullableId(node.get("cardId"), true),
                AnswerCardInput.nullableId(node.get("reviewId"), true), node.get("payload"));
        var receipt = writes.query(actor, AnswerCardInput.text(node.get("requestId")), intent);
        return ok(new AnswerCardViews.CommandResult(receipt == null ? "NOT_FOUND" : "COMMITTED", receipt));
    }

    private ResponseEntity<R<AnswerCardReceipt>> command(HttpServletRequest request, AnswerCardOperation operation, String card, String review) {
        long actor = actor(request);
        var ids = Collections.list(request.getHeaders("X-Request-Id"));
        if (ids.size() != 1) {
            throw AnswerCardFailure.invalid();
        }
        var input = AnswerCardInput.intent(operation, card == null ? null : AnswerCardInput.number(card, false),
                review == null ? null : AnswerCardInput.number(review, false), AnswerCardInput.body(request, 131072));
        return ok(writes.execute(actor, ids.getFirst(), input));
    }

    private static long actor(HttpServletRequest request) {
        if (!(request.getAttribute(EpochAuthenticationInterceptor.VERIFIED) instanceof EpochSessionService.Session session)) {
            throw IdentityException.unauthorized("登录状态无效");
        }
        if (session.mustChangePassword()) {
            throw IdentityException.passwordChangeRequired("必须先完成密码修改");
        }
        return session.userId();
    }

    private static <T> ResponseEntity<R<T>> ok(T value) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("X-Answer-Card-Version", "1").body(R.ok(value));
    }

    @Schema(description = "独立搜索的R包装响应，实际按统一R序列化后有界交付")
    public record SearchResponse(
            @Schema(description = "业务成功码200")
            int code,
            @Schema(description = "固定成功消息ok")
            String message,
            @Schema(description = "成功标志true")
            boolean ok,
            @Schema(description = "完整标准答案搜索结果")
            AnswerCardViews.Search data
    ) {
    }
}
