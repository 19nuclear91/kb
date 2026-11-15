package com.zhishiku.kb.app.document.stewardship.web;

import com.zhishiku.kb.app.document.applicability.support.ValidityInput;
import com.zhishiku.kb.app.document.applicability.web.ValidityBody;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipViews;
import com.zhishiku.kb.app.document.stewardship.service.StewardshipCandidateService;
import com.zhishiku.kb.app.document.stewardship.support.StewardshipInput;
import com.zhishiku.kb.common.api.R;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StewardshipCandidateController {
    private final StewardshipCandidateService service;

    public StewardshipCandidateController(StewardshipCandidateService service) {
        this.service = service;
    }

    @PostMapping("/api/admin/documents/{documentId}/stewardship/candidates")
    public R<StewardshipViews.Candidates> candidates(@PathVariable String documentId, HttpServletRequest request) {
        return R.ok(service.query(ValidityBody.actor(), ValidityInput.id(documentId), StewardshipInput.candidates(ValidityBody.read(request))));
    }
}
