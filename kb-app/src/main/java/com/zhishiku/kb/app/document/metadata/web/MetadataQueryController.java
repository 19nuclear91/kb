package com.zhishiku.kb.app.document.metadata.web;

import com.zhishiku.kb.app.document.metadata.service.MetadataQueryService;
import com.zhishiku.kb.common.api.R;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MetadataQueryController {
    private final MetadataQueryService service;

    public MetadataQueryController(MetadataQueryService service) {
        this.service = service;
    }

    @PostMapping("/api/documents/query")
    public R<MetadataQueryService.Page> query(HttpServletRequest request) {
        return R.ok(service.query(MetadataInput.query(MetadataInput.body(request, MetadataInput.QUERY_LIMIT))));
    }
}
