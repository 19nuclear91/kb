package com.zhishiku.kb.app.knowledgegap.web;

import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapViews;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapQueryService;
import com.zhishiku.kb.app.query.QueryPage;
import com.zhishiku.kb.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 普通与受理列表严格分开；共用当前详情/历史，不提供管理员绕过资料权限的别名。 */
@RestController
public class KnowledgeGapQueryController {
    private final KnowledgeGapQueryService queries;

    public KnowledgeGapQueryController(KnowledgeGapQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/api/knowledge-gaps")
    @Operation(summary = "本人或当前被分派的知识问题", description = "固定创建位置倒序；先当次授权后分页，无隐藏总数")
    public R<QueryPage<KnowledgeGapViews.Item>> list(@RequestParam MultiValueMap<String, String> parameters) {
        return R.ok(queries.list(parameters));
    }

    @GetMapping("/api/admin/knowledge-gaps")
    @Operation(summary = "独立 read 的统一受理队列")
    public R<QueryPage<KnowledgeGapViews.Item>> queue(@RequestParam MultiValueMap<String, String> parameters) {
        return R.ok(queries.queue(parameters));
    }

    @GetMapping("/api/knowledge-gaps/{gapId}")
    @Operation(summary = "当前共享详情和动作能力", description = "不内嵌完整历史；当前方案资料独立鉴权")
    public R<KnowledgeGapViews.Detail> detail(@PathVariable String gapId, @RequestParam MultiValueMap<String, String> parameters) {
        return R.ok(queries.detail(gapId, parameters));
    }

    @GetMapping("/api/knowledge-gaps/{gapId}/history")
    @Operation(summary = "同单不可变历史", description = "按操作位置正序分页；每次重核当前访问，资料不可用不回显身份")
    public R<QueryPage<KnowledgeGapViews.HistoryItem>> history(@PathVariable String gapId,
                                                              @RequestParam MultiValueMap<String, String> parameters) {
        return R.ok(queries.history(gapId, parameters));
    }
}
