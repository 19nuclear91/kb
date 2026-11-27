package com.zhishiku.kb.app.controller.subscription;

import com.zhishiku.kb.app.domain.vo.subscription.KnowledgeViews;
import com.zhishiku.kb.app.domain.vo.subscription.SubscriptionViews;
import com.zhishiku.kb.app.service.impl.subscription.KnowledgeCalendar;
import com.zhishiku.kb.app.service.impl.subscription.SubscriptionRules;
import com.zhishiku.kb.app.service.subscription.KnowledgeUpdateReadService;
import com.zhishiku.kb.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
@Tag(name = "我的知识更新", description = "按当前权限读取本人固定发布与每周汇总")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "参数、日期或游标无效"),
        @ApiResponse(responseCode = "401", description = "登录状态无效"),
        @ApiResponse(responseCode = "403", description = "主体不可用或需要强制改密"),
        @ApiResponse(responseCode = "404", description = "本人更新或周报不存在或当前不可读"),
        @ApiResponse(responseCode = "503", description = "来源、权限或完整结果暂不可读取")
})
public class KnowledgeUpdateController {
    private final KnowledgeUpdateReadService reads;

    @GetMapping("/knowledge-updates")
    @Operation(summary = "分页读取本人订阅更新")
    @Parameters({
            @Parameter(name = "weekStart", in = ParameterIn.QUERY, description = "可选北京时间周一日期YYYY-MM-DD"),
            @Parameter(name = "limit", in = ParameterIn.QUERY, description = "每页1至100，默认20"),
            @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "可选本人同条件下一页游标")
    })
    @ApiResponse(responseCode = "200", description = "R包装的当前可读发布页，包含两种提醒频率", useReturnTypeSchema = true)
    public ResponseEntity<R<SubscriptionViews.Page<KnowledgeViews.Update>>> updates(HttpServletRequest request) {
        long actor = SubscriptionInput.actor(request);
        var query = SubscriptionInput.query(request, "weekStart", "limit", "cursor");
        return ok(reads.updates(actor, KnowledgeCalendar.parse(query.exactText("weekStart", 10)), query.limit(), query.cursor()));
    }

    @GetMapping("/knowledge-updates/{id}")
    @Operation(summary = "读取本人固定发布及当前人工变更说明")
    @ApiResponse(responseCode = "200", description = "R包装的固定版本详情和可用对比基准，不自动切换最新版本", useReturnTypeSchema = true)
    public ResponseEntity<R<KnowledgeViews.Detail>> detail(
            @Parameter(name = "id", description = "本人更新小写UUID", required = true)
            @PathVariable
            String id,
            HttpServletRequest request
    ) {
        long actor = SubscriptionInput.actor(request);
        SubscriptionInput.query(request);
        return ok(reads.detail(actor, SubscriptionRules.uuid(id)));
    }

    @GetMapping("/knowledge-digests")
    @Operation(summary = "分页读取本人可见周报")
    @Parameters({
            @Parameter(name = "limit", in = ParameterIn.QUERY, description = "每页1至100，默认20"),
            @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "可选本人周报续页游标")
    })
    @ApiResponse(responseCode = "200", description = "R包装的本人已生成且存在当前可读成员的周报页", useReturnTypeSchema = true)
    public ResponseEntity<R<SubscriptionViews.Page<KnowledgeViews.Digest>>> digests(HttpServletRequest request) {
        long actor = SubscriptionInput.actor(request);
        var query = SubscriptionInput.query(request, "limit", "cursor");
        return ok(reads.digests(actor, query.limit(), query.cursor()));
    }

    @GetMapping("/knowledge-digests/{id}")
    @Operation(summary = "读取固定周报及当前可见文档组")
    @Parameters({
            @Parameter(name = "limit", in = ParameterIn.QUERY, description = "每页文档组1至100，默认20"),
            @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "可选绑定本人及本周报的组游标")
    })
    @ApiResponse(responseCode = "200", description = "R包装的周报周期及文档组，计数不包含隐藏发布", useReturnTypeSchema = true)
    public ResponseEntity<R<KnowledgeViews.DigestDetail>> digest(
            @Parameter(name = "id", description = "本人周报小写UUID", required = true)
            @PathVariable
            String id,
            HttpServletRequest request
    ) {
        long actor = SubscriptionInput.actor(request);
        var query = SubscriptionInput.query(request, "limit", "cursor");
        return ok(reads.digest(actor, SubscriptionRules.uuid(id), query.limit(), query.cursor()));
    }

    @GetMapping("/knowledge-digests/{id}/documents/{documentId}")
    @Operation(summary = "展开周报文档的各次固定发布")
    @Parameters({
            @Parameter(name = "limit", in = ParameterIn.QUERY, description = "每页发布数1至100，默认20"),
            @Parameter(name = "cursor", in = ParameterIn.QUERY, description = "可选绑定本人、周报及文档的明细游标")
    })
    @ApiResponse(responseCode = "200", description = "R包装的当前可读发布明细，保持原版本和时间", useReturnTypeSchema = true)
    public ResponseEntity<R<SubscriptionViews.Page<KnowledgeViews.Update>>> members(
            @Parameter(name = "id", description = "本人周报小写UUID", required = true)
            @PathVariable
            String id,
            @Parameter(name = "documentId", description = "周报内文档正整数ID的十进制字符串", required = true)
            @PathVariable
            String documentId,
            HttpServletRequest request
    ) {
        long actor = SubscriptionInput.actor(request);
        var query = SubscriptionInput.query(request, "limit", "cursor");
        return ok(reads.members(actor, SubscriptionRules.uuid(id), SubscriptionRules.number(documentId, 1), query.limit(), query.cursor()));
    }

    private static <T> ResponseEntity<R<T>> ok(T value) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(R.ok(value));
    }
}
