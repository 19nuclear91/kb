package com.zhishiku.kb.app.document.stewardship.web;

import com.zhishiku.kb.app.document.applicability.support.ValidityInput;
import com.zhishiku.kb.app.document.applicability.web.ValidityBody;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipViews;
import com.zhishiku.kb.app.document.stewardship.service.StewardshipConfirmService;
import com.zhishiku.kb.app.document.stewardship.service.StewardshipQueryService;
import com.zhishiku.kb.app.document.stewardship.service.StewardshipWriteService;
import com.zhishiku.kb.app.document.stewardship.support.StewardshipInput;
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
public class StewardshipController {
    private final StewardshipQueryService reads;
    private final StewardshipWriteService writes;
    private final StewardshipConfirmService confirms;

    public StewardshipController(StewardshipQueryService reads, StewardshipWriteService writes, StewardshipConfirmService confirms) {
        this.reads = reads;
        this.writes = writes;
        this.confirms = confirms;
    }

    @GetMapping("/api/admin/documents/{documentId}/stewardship")
    public R<StewardshipViews.Detail> get(@PathVariable String documentId, HttpServletRequest request) {
        ValidityInput.query(request.getParameterMap(), Set.of());
        return R.ok(reads.get(ValidityBody.actor(), ValidityInput.id(documentId)));
    }

    @PutMapping("/api/admin/documents/{documentId}/stewardship")
    public R<StewardshipViews.Receipt> update(@PathVariable String documentId, @RequestHeader("X-Request-Id") String requestId,
                                             HttpServletRequest request) {
        var input = StewardshipInput.update(ValidityBody.read(request));
        return R.ok(writes.update(ValidityBody.actor(), ValidityInput.id(documentId), input, ValidityInput.requestId(requestId)));
    }

    @PostMapping("/api/documents/{documentId}/stewardship/confirm")
    public R<StewardshipViews.Receipt> confirm(@PathVariable String documentId, @RequestHeader("X-Request-Id") String requestId,
                                              HttpServletRequest request) {
        var input = StewardshipInput.confirm(ValidityBody.read(request));
        return R.ok(confirms.confirm(ValidityBody.actor(), ValidityInput.id(documentId), input, ValidityInput.requestId(requestId)));
    }

    @GetMapping("/api/documents/stewardship")
    public R<StewardshipViews.Mine> mine(HttpServletRequest request) {
        var query = ValidityInput.query(request.getParameterMap(), Set.of("view", "limit", "cursor"));
        return R.ok(reads.mine(ValidityBody.actor(), query.has("view") ? ValidityInput.rawText(query.get("view")) : "attention",
                ValidityInput.limit(query.get("limit")), ValidityInput.cursor(query.get("cursor"))));
    }

    @GetMapping("/api/documents/stewardship/count")
    public R<StewardshipViews.Count> count(HttpServletRequest request) {
        ValidityInput.query(request.getParameterMap(), Set.of());
        return R.ok(reads.count(ValidityBody.actor()));
    }

    @GetMapping("/api/admin/spaces/{spaceId}/stewardship")
    public R<StewardshipViews.Space> space(@PathVariable String spaceId, HttpServletRequest request) {
        var query = ValidityInput.query(request.getParameterMap(), Set.of("state", "limit", "cursor"));
        return R.ok(reads.space(ValidityBody.actor(), ValidityInput.id(spaceId), ValidityInput.rawText(query.get("state")),
                ValidityInput.limit(query.get("limit")), ValidityInput.cursor(query.get("cursor"))));
    }
}
