package com.zhishiku.kb.app.knowledgegap.web;

import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapAction;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapRows;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapAccess;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapClosureService;
import com.zhishiku.kb.app.query.QueryParameters;
import com.zhishiku.kb.common.api.R;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/** 无删除、编辑或重新打开端点，关闭不能替代提问者确认解决。 */
@RestController
public class KnowledgeGapClosureController {
    private final KnowledgeGapAccess access;
    private final KnowledgeGapClosureService closures;

    public KnowledgeGapClosureController(KnowledgeGapAccess access, KnowledgeGapClosureService closures) {
        this.access = access;
        this.closures = closures;
    }

    @PostMapping(value = "/api/knowledge-gaps/{gapId}/withdraw", consumes = MediaType.APPLICATION_JSON_VALUE)
    public R<KnowledgeGapRows.Receipt> withdraw(@PathVariable String gapId, HttpServletRequest request) throws IOException {
        long target = authorize(gapId, KnowledgeGapAction.WITHDRAW);
        KnowledgeGapInput.noQuery(request);
        String requestId = KnowledgeGapInput.requestId(request);
        return R.ok(closures.withdraw(target, KnowledgeGapInput.reason(request.getInputStream()), requestId));
    }

    @PostMapping(value = "/api/admin/knowledge-gaps/{gapId}/close", consumes = MediaType.APPLICATION_JSON_VALUE)
    public R<KnowledgeGapRows.Receipt> close(@PathVariable String gapId, HttpServletRequest request) throws IOException {
        long target = authorize(gapId, KnowledgeGapAction.CLOSE);
        KnowledgeGapInput.noQuery(request);
        String requestId = KnowledgeGapInput.requestId(request);
        return R.ok(closures.close(target, KnowledgeGapInput.reason(request.getInputStream()), requestId));
    }

    private long authorize(String rawGapId, KnowledgeGapAction action) {
        var actor = access.current();
        long target = QueryParameters.positiveValue(rawGapId);
        access.requireForAction(actor, target, action);
        return target;
    }
}
