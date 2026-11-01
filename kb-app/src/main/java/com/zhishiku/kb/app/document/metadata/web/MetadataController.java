package com.zhishiku.kb.app.document.metadata.web;

import com.zhishiku.kb.app.document.metadata.model.MetadataViews;
import com.zhishiku.kb.app.document.metadata.service.MetadataException;
import com.zhishiku.kb.app.document.metadata.service.MetadataReadService;
import com.zhishiku.kb.app.document.metadata.service.MetadataWriteService;
import com.zhishiku.kb.common.api.R;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MetadataController {
    private final MetadataReadService reads;
    private final MetadataWriteService writes;

    public MetadataController(MetadataReadService reads, MetadataWriteService writes) {
        this.reads = reads;
        this.writes = writes;
    }

    @GetMapping("/api/documents/{id}/metadata")
    public R<MetadataViews.Metadata> get(@PathVariable String id, HttpServletRequest request) {
        if (request.getQueryString() != null) {
            throw new MetadataException(MetadataException.Reason.INVALID_INPUT);
        }
        return R.ok(reads.get(MetadataInput.id(id)));
    }

    @PutMapping("/api/documents/{id}/metadata")
    public R<MetadataViews.Receipt> update(@PathVariable String id, HttpServletRequest request,
                                          @RequestHeader("X-Request-Id") String requestId) {
        return R.ok(writes.update(MetadataInput.id(id), MetadataInput.update(MetadataInput.body(request, MetadataInput.WRITE_LIMIT)), requestId));
    }
}
