package com.zhishiku.kb.app.document.metadata.web;

import com.zhishiku.kb.app.document.metadata.model.MetadataViews;
import com.zhishiku.kb.app.document.metadata.service.CategoryService;
import com.zhishiku.kb.app.document.metadata.service.CategoryWriteService;
import com.zhishiku.kb.app.document.metadata.support.MetadataInput;
import com.zhishiku.kb.common.api.R;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CategoryController {
    private final CategoryService reads;
    private final CategoryWriteService writes;

    public CategoryController(CategoryService reads, CategoryWriteService writes) {
        this.reads = reads;
        this.writes = writes;
    }

    @GetMapping("/api/admin/spaces/{spaceId}/categories")
    public R<MetadataViews.CategoryPage> managed(@PathVariable String spaceId, HttpServletRequest request) {
        return R.ok(reads.managed(MetadataInput.id(spaceId), MetadataInput.categoryQuery(request.getParameterMap())));
    }

    @GetMapping("/api/documents/{documentId}/metadata-options/categories")
    public R<MetadataViews.CategoryPage> editable(@PathVariable String documentId, HttpServletRequest request) {
        return R.ok(reads.editable(MetadataInput.id(documentId), MetadataInput.categoryQuery(request.getParameterMap())));
    }

    @PostMapping("/api/documents/query/categories")
    public R<MetadataViews.CategoryPage> filter(HttpServletRequest request) {
        return R.ok(reads.filter(MetadataInput.categoryQuery(MetadataBody.read(request, MetadataInput.QUERY_LIMIT), true)));
    }

    @PostMapping("/api/admin/spaces/{spaceId}/categories")
    public R<MetadataViews.CategoryReceipt> create(@PathVariable String spaceId, HttpServletRequest request,
                                                  @RequestHeader("X-Request-Id") String requestId) {
        return R.ok(writes.write(MetadataInput.id(spaceId), null,
                MetadataInput.category(MetadataBody.read(request, MetadataInput.WRITE_LIMIT)), requestId));
    }

    @PutMapping("/api/admin/spaces/{spaceId}/categories/{categoryId}")
    public R<MetadataViews.CategoryReceipt> update(@PathVariable String spaceId, @PathVariable String categoryId,
                                                  HttpServletRequest request, @RequestHeader("X-Request-Id") String requestId) {
        return R.ok(writes.write(MetadataInput.id(spaceId), MetadataInput.id(categoryId),
                MetadataInput.category(MetadataBody.read(request, MetadataInput.WRITE_LIMIT)), requestId));
    }

    @DeleteMapping("/api/admin/spaces/{spaceId}/categories/{categoryId}")
    public R<MetadataViews.CategoryReceipt> delete(@PathVariable String spaceId, @PathVariable String categoryId,
                                                  HttpServletRequest request, @RequestHeader("X-Request-Id") String requestId) {
        return R.ok(writes.delete(MetadataInput.id(spaceId), MetadataInput.id(categoryId),
                MetadataInput.deleteCategory(MetadataBody.read(request, MetadataInput.WRITE_LIMIT)), requestId));
    }
}
