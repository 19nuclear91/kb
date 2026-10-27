package com.zhishiku.kb.app.document.web;

import com.zhishiku.kb.app.document.model.DocumentCapabilities;
import com.zhishiku.kb.app.document.service.DocumentCapabilitiesService;
import com.zhishiku.kb.app.query.AdminQueryEndpoint;
import com.zhishiku.kb.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前可查看文档的最小只读操作资格入口。
 */
@AdminQueryEndpoint
@RestController
@Tag(name = "用户资源能力")
public class DocumentCapabilitiesController {
    private final DocumentCapabilitiesService service;

    public DocumentCapabilitiesController(DocumentCapabilitiesService service) {
        this.service = service;
    }

    /**
     * 查询当前可查看文档的下载和编辑资格。
     *
     * @param documentId 原始路径标识
     * @param params 不允许查询参数
     * @return 文档最小能力投影
     */
    @GetMapping("/api/documents/{documentId}/capabilities")
    @Operation(summary = "查询文档操作资格", description = "须当前 view；分别核对 download/edit，结果不作为后续授权凭据")
    public R<DocumentCapabilities> get(@PathVariable String documentId,
                                       @RequestParam MultiValueMap<String, String> params) {
        return R.ok(service.get(documentId, params));
    }
}
