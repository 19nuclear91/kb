package com.zhishiku.kb.app.document.imports.web;

import com.zhishiku.kb.app.document.imports.ImportProperties;
import com.zhishiku.kb.app.document.imports.model.ImportModels.BatchView;
import com.zhishiku.kb.app.document.imports.model.ImportModels.Created;
import com.zhishiku.kb.app.document.imports.model.ImportModels.ItemView;
import com.zhishiku.kb.app.document.imports.model.ImportModels.Page;
import com.zhishiku.kb.app.document.imports.model.ImportModels.Resolution;
import com.zhishiku.kb.app.document.imports.model.ImportModels.Receipt;
import com.zhishiku.kb.app.document.imports.service.ImportAccess;
import com.zhishiku.kb.app.document.imports.service.ImportCreateService;
import com.zhishiku.kb.app.document.imports.service.ImportReadService;
import com.zhishiku.kb.app.document.imports.service.ImportUploadService;
import com.zhishiku.kb.app.document.imports.support.ImportInput;
import com.zhishiku.kb.app.storage.StorageProperties;
import com.zhishiku.kb.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;

/** 本人导入账本入口；原件上传与公开查询分离。 */
@RestController
@RequestMapping("/api/document-imports")
@Tag(name = "批量文档导入")
public class ImportController {
    private final ImportProperties properties;
    private final StorageProperties storage;
    private final ImportAccess access;
    private final ImportCreateService creates;
    private final ImportReadService reads;
    private final ImportUploadService uploads;

    public ImportController(ImportProperties properties, StorageProperties storage, ImportAccess access,
                            ImportCreateService creates, ImportReadService reads, ImportUploadService uploads) {
        this.properties = properties;
        this.storage = storage;
        this.access = access;
        this.creates = creates;
        this.reads = reads;
        this.uploads = uploads;
    }

    @GetMapping("/capabilities")
    @Operation(summary = "读取批量上传限额")
    public ResponseEntity<R<ImportProperties.Capabilities>> capabilities(@RequestHeader(value = "satoken", required = false) String token,
                                                                        HttpServletRequest request) {
        noQuery(request);
        access.current(token);
        return ok(properties.capabilities(storage));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "创建本人导入批次")
    public ResponseEntity<R<Created>> create(@RequestHeader(value = "satoken", required = false) String token,
                                            @RequestHeader("X-Request-Id") String requestId,
                                            HttpServletRequest request) throws IOException {
        noQuery(request);
        access.current(token);
        var manifest = ImportInput.manifest(ImportInput.body(request.getInputStream(), ImportInput.MANIFEST_BYTES),
                properties.itemLimit(), properties.totalLimit(), storage.maxUploadBytes());
        return ok(creates.create(token, requestId, manifest));
    }

    @PostMapping(value = "/resolve", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "只读核对本人创建结果")
    public ResponseEntity<R<Resolution>> resolve(@RequestHeader(value = "satoken", required = false) String token,
                                                HttpServletRequest request) throws IOException {
        noQuery(request);
        access.current(token);
        return ok(creates.resolve(token, ImportInput.resolve(ImportInput.body(request.getInputStream(), 4096))));
    }

    @GetMapping
    @Operation(summary = "分页读取本人导入任务")
    public ResponseEntity<R<Page<BatchView>>> list(@RequestHeader(value = "satoken", required = false) String token,
                                                  HttpServletRequest request) {
        query(request);
        return ok(reads.list(token, limit(request), request.getParameter("cursor")));
    }

    @GetMapping("/{importId}")
    @Operation(summary = "读取本人导入任务摘要")
    public ResponseEntity<R<BatchView>> detail(@RequestHeader(value = "satoken", required = false) String token,
                                              @PathVariable String importId, HttpServletRequest request) {
        noQuery(request);
        return ok(reads.detail(token, ImportInput.id(importId)));
    }

    @GetMapping("/{importId}/items")
    @Operation(summary = "按原顺序读取导入项")
    public ResponseEntity<R<Page<ItemView>>> items(@RequestHeader(value = "satoken", required = false) String token,
                                                  @PathVariable String importId, HttpServletRequest request) {
        query(request);
        return ok(reads.items(token, ImportInput.id(importId), limit(request), request.getParameter("cursor")));
    }

    @GetMapping("/{importId}/items/{itemId}")
    @Operation(summary = "核对固定导入项")
    public ResponseEntity<R<ItemView>> item(@RequestHeader(value = "satoken", required = false) String token,
                                           @PathVariable String importId, @PathVariable String itemId,
                                           HttpServletRequest request) {
        noQuery(request);
        return ok(reads.item(token, ImportInput.id(importId), ImportInput.id(itemId)));
    }

    static <T> ResponseEntity<R<T>> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(R.ok(body));
    }

    @PostMapping(value = "/{importId}/items/{itemId}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "提交一个匹配的导入文件")
    public ResponseEntity<R<Receipt>> file(@RequestHeader(value = "satoken", required = false) String token,
                                           @PathVariable String importId, @PathVariable String itemId,
                                           MultipartHttpServletRequest request) {
        noQuery(request);
        access.current(token);
        var ids = Collections.list(request.getHeaders("X-Request-Id"));
        if (ids.size() != 1 || !request.getParameterMap().keySet().equals(Set.of("expectedRevision"))
                || request.getParameterValues("expectedRevision").length != 1
                || !request.getMultiFileMap().keySet().equals(Set.of("file"))
                || request.getFiles("file").size() != 1) {
            throw ImportInput.invalid();
        }
        String requestId = ImportInput.requestId(ids.getFirst());
        long revision = ImportInput.revision(request.getParameter("expectedRevision"));
        return ok(uploads.upload(token, ImportInput.id(importId), ImportInput.id(itemId), requestId, revision,
                request.getFiles("file").getFirst()));
    }

    static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) {
            throw ImportInput.invalid();
        }
    }

    private void query(HttpServletRequest request) {
        request.getParameterMap().forEach((key, values) -> {
            if (!Set.of("limit", "cursor").contains(key) || values.length != 1) {
                throw ImportInput.invalid();
            }
        });
    }

    private int limit(HttpServletRequest request) {
        String value = request.getParameter("limit");
        long limit = value == null ? 20 : ImportInput.id(value);
        if (limit > 100) {
            throw ImportInput.invalid();
        }
        return (int) limit;
    }
}
