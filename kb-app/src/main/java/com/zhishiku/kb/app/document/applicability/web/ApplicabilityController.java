package com.zhishiku.kb.app.document.applicability.web;

import com.zhishiku.kb.app.document.applicability.model.ApplicabilityViews;
import com.zhishiku.kb.app.document.applicability.service.ApplicabilityReadService;
import com.zhishiku.kb.app.document.applicability.service.ApplicabilityWriteService;
import com.zhishiku.kb.app.document.applicability.support.ValidityInput;
import com.zhishiku.kb.common.api.R;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
public class ApplicabilityController {
    private final ApplicabilityReadService reads;
    private final ApplicabilityWriteService writes;

    public ApplicabilityController(ApplicabilityReadService reads, ApplicabilityWriteService writes) {
        this.reads = reads;
        this.writes = writes;
    }

    @GetMapping("/api/documents/{documentId}/versions/{versionId}/applicability")
    public R<ApplicabilityViews.Detail> get(@PathVariable String documentId, @PathVariable String versionId, HttpServletRequest request) {
        ValidityInput.query(request.getParameterMap(), Set.of());
        return R.ok(reads.get(ValidityBody.actor(), ValidityInput.id(documentId), ValidityInput.id(versionId)));
    }

    @GetMapping("/api/documents/{documentId}/versions/{versionId}/applicability/history")
    public R<ApplicabilityViews.History> history(@PathVariable String documentId, @PathVariable String versionId, HttpServletRequest request) {
        var query = ValidityInput.query(request.getParameterMap(), Set.of("limit", "cursor"));
        return R.ok(reads.history(ValidityBody.actor(), ValidityInput.id(documentId), ValidityInput.id(versionId),
                ValidityInput.limit(query.get("limit")), ValidityInput.cursor(query.get("cursor"))));
    }

    @PutMapping("/api/documents/{documentId}/versions/{versionId}/applicability")
    public R<ApplicabilityViews.Receipt> update(@PathVariable String documentId, @PathVariable String versionId,
                                              @RequestHeader("X-Request-Id") String requestId, HttpServletRequest request) {
        var input = ValidityInput.applicability(ValidityBody.read(request));
        String normalizedRequest = ValidityInput.requestId(requestId);
        return R.ok(writes.update(ValidityBody.actor(), ValidityInput.id(documentId), ValidityInput.id(versionId), input, normalizedRequest));
    }

    @PostMapping("/api/documents/applicability/query")
    public R<ApplicabilityViews.Batch> batch(HttpServletRequest request) {
        return R.ok(reads.batch(ValidityBody.actor(), ValidityInput.sources(ValidityBody.read(request))));
    }
}
