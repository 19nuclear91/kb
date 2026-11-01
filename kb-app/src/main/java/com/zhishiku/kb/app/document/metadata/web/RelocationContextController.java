package com.zhishiku.kb.app.document.metadata.web;

import com.zhishiku.kb.app.document.metadata.service.MetadataException;
import com.zhishiku.kb.app.document.metadata.service.RelocationContextService;
import com.zhishiku.kb.common.api.R;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RelocationContextController {
    private final RelocationContextService service;

    public RelocationContextController(RelocationContextService service) {
        this.service = service;
    }

    @GetMapping("/api/admin/documents/{id}/relocation-context")
    public R<RelocationContextService.Context> get(@PathVariable String id, HttpServletRequest request) {
        if (request.getQueryString() != null) {
            throw new MetadataException(MetadataException.Reason.INVALID_INPUT);
        }
        return R.ok(service.get(MetadataInput.id(id)));
    }
}
