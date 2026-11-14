package com.zhishiku.kb.app.knowledgegap.web;

import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapAction;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapDocumentOption;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapRows;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapAccess;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapSolutionQueryService;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapSolutionService;
import com.zhishiku.kb.app.query.QueryPage;
import com.zhishiku.kb.app.query.QueryParameters;
import com.zhishiku.kb.common.api.R;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/** 提交方案、本人确认和仍未解决均是显式动作，不能由发布或通知自动驱动。 */
@RestController
public class KnowledgeGapSolutionController {
    private final KnowledgeGapAccess access;
    private final KnowledgeGapSolutionService solutions;
    private final KnowledgeGapSolutionQueryService queries;

    public KnowledgeGapSolutionController(KnowledgeGapAccess access, KnowledgeGapSolutionService solutions,
                                          KnowledgeGapSolutionQueryService queries) {
        this.access = access;
        this.solutions = solutions;
        this.queries = queries;
    }

    @PostMapping(value = "/api/knowledge-gaps/{gapId}/solutions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public R<KnowledgeGapRows.Receipt> submit(@PathVariable String gapId, HttpServletRequest request) throws IOException {
        long target = authorize(gapId, KnowledgeGapAction.SOLUTION);
        KnowledgeGapInput.noQuery(request);
        String requestId = KnowledgeGapInput.requestId(request);
        return R.ok(solutions.submit(target, KnowledgeGapInput.solution(request.getInputStream()), requestId));
    }

    @PostMapping(value = "/api/knowledge-gaps/{gapId}/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    public R<KnowledgeGapRows.Receipt> confirm(@PathVariable String gapId, HttpServletRequest request) throws IOException {
        long target = authorize(gapId, KnowledgeGapAction.CONFIRM);
        KnowledgeGapInput.noQuery(request);
        String requestId = KnowledgeGapInput.requestId(request);
        return R.ok(solutions.confirm(target, KnowledgeGapInput.confirmation(request.getInputStream()), requestId));
    }

    @PostMapping(value = "/api/knowledge-gaps/{gapId}/reject-solution", consumes = MediaType.APPLICATION_JSON_VALUE)
    public R<KnowledgeGapRows.Receipt> reject(@PathVariable String gapId, HttpServletRequest request) throws IOException {
        long target = authorize(gapId, KnowledgeGapAction.REJECT_SOLUTION);
        KnowledgeGapInput.noQuery(request);
        String requestId = KnowledgeGapInput.requestId(request);
        return R.ok(solutions.reject(target, KnowledgeGapInput.rejection(request.getInputStream()), requestId));
    }

    @PostMapping(value = "/api/knowledge-gaps/{gapId}/solution-documents", consumes = MediaType.APPLICATION_JSON_VALUE)
    public R<QueryPage<KnowledgeGapDocumentOption>> candidates(@PathVariable String gapId, HttpServletRequest request) throws IOException {
        var actor = access.current();
        long target = QueryParameters.positiveValue(gapId);
        access.requireForAction(actor, target, KnowledgeGapAction.SOLUTION);
        KnowledgeGapInput.noQuery(request);
        return R.ok(queries.candidates(actor, target, KnowledgeGapInput.search(request.getInputStream())));
    }

    private long authorize(String rawGapId, KnowledgeGapAction action) {
        var actor = access.current();
        long target = QueryParameters.positiveValue(rawGapId);
        access.requireForAction(actor, target, action);
        return target;
    }
}
