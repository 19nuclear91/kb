package com.zhishiku.kb.app.knowledgegap.web;

import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapAction;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapRows;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapAccess;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapAssignmentService;
import com.zhishiku.kb.app.query.QueryParameters;
import com.zhishiku.kb.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/** 每个动作使用独立固定端点，不由请求正文选择操作或覆盖操作者。 */
@RestController
public class KnowledgeGapAssignmentController {
    private final KnowledgeGapAccess access;
    private final KnowledgeGapAssignmentService assignments;

    public KnowledgeGapAssignmentController(KnowledgeGapAccess access, KnowledgeGapAssignmentService assignments) {
        this.access = access;
        this.assignments = assignments;
    }

    @PostMapping(value = "/api/admin/knowledge-gaps/{gapId}/assign", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "人工分派或改派", description = "需要独立 assign 和当前访问；改派使旧待确认方案失效")
    public R<KnowledgeGapRows.Receipt> assign(@PathVariable String gapId, HttpServletRequest request) throws IOException {
        long target = authorize(gapId, KnowledgeGapAction.ASSIGN);
        KnowledgeGapInput.noQuery(request);
        String requestId = KnowledgeGapInput.requestId(request);
        return R.ok(assignments.assign(target, KnowledgeGapInput.assignment(request.getInputStream()), requestId));
    }

    @PostMapping(value = "/api/knowledge-gaps/{gapId}/notes", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "追加同单共享说明", description = "不改变当前工作身份，不提供隐藏内部评论或正文编辑")
    public R<KnowledgeGapRows.Receipt> note(@PathVariable String gapId, HttpServletRequest request) throws IOException {
        long target = authorize(gapId, KnowledgeGapAction.NOTE);
        KnowledgeGapInput.noQuery(request);
        String requestId = KnowledgeGapInput.requestId(request);
        return R.ok(assignments.note(target, KnowledgeGapInput.note(request.getInputStream()), requestId));
    }

    @PostMapping(value = "/api/knowledge-gaps/{gapId}/return", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "负责人退回统一受理", description = "需要原因；清空当前分派和待确认方案，旧负责人不保留历史访问")
    public R<KnowledgeGapRows.Receipt> returnToReception(@PathVariable String gapId, HttpServletRequest request) throws IOException {
        long target = authorize(gapId, KnowledgeGapAction.RETURN);
        KnowledgeGapInput.noQuery(request);
        String requestId = KnowledgeGapInput.requestId(request);
        return R.ok(assignments.returnToReception(target, KnowledgeGapInput.reason(request.getInputStream()), requestId));
    }

    private long authorize(String rawGapId, KnowledgeGapAction action) {
        var actor = access.current();
        long target = QueryParameters.positiveValue(rawGapId);
        access.requireForAction(actor, target, action);
        return target;
    }
}
