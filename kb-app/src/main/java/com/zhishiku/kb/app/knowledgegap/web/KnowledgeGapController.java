package com.zhishiku.kb.app.knowledgegap.web;

import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapRows;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapAccess;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapCreateService;
import com.zhishiku.kb.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/** 当前会话主动发起的问题单；不接受客户端归属或模型、会话快照。 */
@RestController
@Tag(name = "知识问题单")
public class KnowledgeGapController {
    private final KnowledgeGapAccess access;
    private final KnowledgeGapCreateService creates;

    public KnowledgeGapController(KnowledgeGapAccess access, KnowledgeGapCreateService creates) {
        this.access = access;
        this.creates = creates;
    }

    @PostMapping(value = "/api/knowledge-gaps", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "主动提交知识问题", description = "需明确分享确认与单值 X-Request-Id；首次 201、匹配重放 200，回执无正文")
    public ResponseEntity<R<KnowledgeGapRows.Receipt>> create(HttpServletRequest request) throws IOException {
        access.current();
        KnowledgeGapInput.noQuery(request);
        String requestId = KnowledgeGapInput.requestId(request);
        var receipt = creates.create(KnowledgeGapInput.create(request.getInputStream()), requestId);
        return ResponseEntity.status(receipt.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Cache-Control", "no-store").body(R.ok(receipt));
    }
}
