package com.zhishiku.kb.app.document.web;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.document.model.ReviewReceipt;
import com.zhishiku.kb.app.document.service.ReviewApprovalService;
import com.zhishiku.kb.app.query.CommandInput;
import com.zhishiku.kb.app.query.QueryParameters;
import com.zhishiku.kb.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Set;

@RestController
public class DocumentReviewApprovalController {
    private final ReviewApprovalService service;

    public DocumentReviewApprovalController(ReviewApprovalService service) {
        this.service = service;
    }

    @PostMapping(value = "/api/documents/{documentId}/versions/{versionId}/reviews/{reviewId}/approve", consumes = "application/json")
    @Operation(summary = "通过并原子发布指定轮次")
    public R<ReviewReceipt> approve(@PathVariable String documentId, @PathVariable String versionId,
                                     @PathVariable String reviewId, HttpServletRequest request) throws IOException {
        long actor = StpUtil.getLoginIdAsLong();
        CommandInput.noQuery(request);
        long document = QueryParameters.positiveValue(documentId), version = QueryParameters.positiveValue(versionId);
        var round = DocumentReviewController.roundId(reviewId);
        String requestId = CommandInput.requestId(request);
        var body = CommandInput.object(request.getInputStream(), Set.of("expectedStateVersion"));
        return R.ok(service.approve(actor, document, version, round,
                CommandInput.nonnegative(body.get("expectedStateVersion")), requestId));
    }
}
