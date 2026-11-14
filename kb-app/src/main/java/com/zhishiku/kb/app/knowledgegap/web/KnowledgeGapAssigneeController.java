package com.zhishiku.kb.app.knowledgegap.web;

import com.zhishiku.kb.app.auth.model.KnowledgeGapAssigneeViews;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapAction;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapAccess;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapAssigneeQueryService;
import com.zhishiku.kb.app.query.QueryPage;
import com.zhishiku.kb.app.query.QueryParameters;
import com.zhishiku.kb.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/** 只读人员选择入口；名称经 POST 正文传输，不生成业务命令或通知。 */
@RestController
@Tag(name = "知识问题受理")
public class KnowledgeGapAssigneeController {
    private final KnowledgeGapAccess access;
    private final KnowledgeGapAssigneeQueryService queries;

    public KnowledgeGapAssigneeController(KnowledgeGapAccess access, KnowledgeGapAssigneeQueryService queries) {
        this.access = access;
        this.queries = queries;
    }

    @PostMapping(value = "/api/admin/knowledge-gaps/{gapId}/assignees/search", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "当前问题的最小处理人候选", description = "独立 assign 且当前可访问该问题；仅 nameQuery、limit、cursor，无身份目录回退")
    public R<QueryPage<KnowledgeGapAssigneeViews.Candidate>> search(@PathVariable String gapId,
                                                                    HttpServletRequest request) throws IOException {
        var actor = access.requireCurrent(Permission.KNOWLEDGE_GAP_ASSIGN);
        long target = QueryParameters.positiveValue(gapId);
        access.requireForAction(actor, target, KnowledgeGapAction.ASSIGN);
        KnowledgeGapInput.noQuery(request);
        var input = KnowledgeGapInput.search(request.getInputStream());
        return R.ok(queries.search(actor, target, input));
    }
}
