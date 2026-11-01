package com.zhishiku.kb.app.document.metadata.web;

import com.zhishiku.kb.app.document.metadata.model.MetadataViews;
import com.zhishiku.kb.app.document.metadata.service.TagService;
import com.zhishiku.kb.common.api.R;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TagController {
    private final TagService service;

    public TagController(TagService service) {
        this.service = service;
    }

    @PostMapping("/api/admin/document-tags/query")
    public R<MetadataViews.Page<MetadataViews.ManagedTag>> managed(HttpServletRequest request) {
        return R.ok(service.managed(MetadataInput.tagQuery(MetadataInput.body(request, MetadataInput.QUERY_LIMIT), true)));
    }

    @PostMapping("/api/documents/{id}/metadata-options/tags")
    public R<MetadataViews.Page<MetadataViews.Tag>> editable(@PathVariable String id, HttpServletRequest request) {
        return R.ok(service.editable(MetadataInput.id(id), MetadataInput.tagQuery(MetadataInput.body(request, MetadataInput.QUERY_LIMIT), false)));
    }

    @PostMapping("/api/documents/query/tags")
    public R<MetadataViews.Page<MetadataViews.Tag>> filter(HttpServletRequest request) {
        return R.ok(service.filter(MetadataInput.tagQuery(MetadataInput.body(request, MetadataInput.QUERY_LIMIT), false)));
    }

    @PostMapping("/api/admin/document-tags")
    public R<MetadataViews.TagReceipt> create(HttpServletRequest request, @RequestHeader("X-Request-Id") String requestId) {
        return R.ok(service.write(null, MetadataInput.tag(MetadataInput.body(request, MetadataInput.WRITE_LIMIT), true), requestId));
    }

    @PutMapping("/api/admin/document-tags/{id}")
    public R<MetadataViews.TagReceipt> update(@PathVariable String id, HttpServletRequest request,
                                             @RequestHeader("X-Request-Id") String requestId) {
        return R.ok(service.write(MetadataInput.id(id), MetadataInput.tag(MetadataInput.body(request, MetadataInput.WRITE_LIMIT), false), requestId));
    }
}
