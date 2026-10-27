package com.zhishiku.kb.app.document.web;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.document.model.ReviewReceipt;
import com.zhishiku.kb.app.document.service.CandidateIndexRefreshService;
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
public class CandidateIndexRefreshController {
    private final CandidateIndexRefreshService service;

    public CandidateIndexRefreshController(CandidateIndexRefreshService service) {
        this.service = service;
    }

    @PostMapping(value = "/api/documents/{documentId}/versions/{versionId}/index-refresh", consumes = "application/json")
    @Operation(summary = "显式刷新未发布候选索引", description = "维护期间只排队；结束旧活动审核，完成后须重新送审")
    public R<ReviewReceipt> refresh(@PathVariable String documentId, @PathVariable String versionId,
                                     HttpServletRequest request) throws IOException {
        long actor = StpUtil.getLoginIdAsLong();
        CommandInput.noQuery(request);
        long document = QueryParameters.positiveValue(documentId), version = QueryParameters.positiveValue(versionId);
        String requestId = CommandInput.requestId(request);
        var body = CommandInput.object(request.getInputStream(), Set.of("expectedStateVersion"));
        return R.ok(service.refresh(actor, document, version, CommandInput.nonnegative(body.get("expectedStateVersion")), requestId));
    }
}
