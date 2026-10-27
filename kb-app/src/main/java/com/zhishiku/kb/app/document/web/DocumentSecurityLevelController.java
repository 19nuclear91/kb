package com.zhishiku.kb.app.document.web;

import com.zhishiku.kb.app.document.model.DocumentSecurityLevelChange;
import com.zhishiku.kb.app.document.model.DocumentSecurityLevelResult;
import com.zhishiku.kb.app.document.model.DocumentSecurityLevelState;
import com.zhishiku.kb.app.document.service.DocumentSecurityLevelService;
import com.zhishiku.kb.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/documents/{documentId}/security-level")
@Tag(name = "文档密级", description = "需要 document:security-level:write；调整前后密级均不超过操作者当前密级")
public class DocumentSecurityLevelController {
    private final DocumentSecurityLevelService service;

    public DocumentSecurityLevelController(DocumentSecurityLevelService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "查询文档密级和密级版本", description = "返回最小管理状态，不赋予正文阅读权限")
    public R<DocumentSecurityLevelState> get(@PathVariable long documentId) {
        return R.ok(service.get(documentId));
    }

    @PatchMapping
    @Operation(summary = "调整文档密级", description = "需要原因、密级版本和请求标识；原密级与目标密级均不得超过操作者当前密级。"
            + "请求体 requestId 与 X-Request-Id 同时提供时必须一致；合法重试返回首次回执。")
    public R<DocumentSecurityLevelResult> change(@PathVariable long documentId,
                                                 @RequestBody DocumentSecurityLevelChange request,
                                                 @RequestHeader(value = "X-Request-Id", required = false) String header) {
        return R.ok(service.change(documentId, request, header));
    }
}
