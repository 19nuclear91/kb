package com.zhishiku.kb.app.document.web;

import com.zhishiku.kb.app.document.model.DocumentAdminView;
import com.zhishiku.kb.app.document.service.DocumentAdminQueryService;
import com.zhishiku.kb.app.query.AdminQueryEndpoint;
import com.zhishiku.kb.app.query.QueryPage;
import com.zhishiku.kb.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文档管理元数据入口，不授予内容阅读资格。
 */
@AdminQueryEndpoint
@RestController
@Tag(name = "管理查询", description = "当前管理资格下的最小只读投影")
public class DocumentAdminQueryController {
    private final DocumentAdminQueryService service;

    public DocumentAdminQueryController(DocumentAdminQueryService service) {
        this.service = service;
    }

    @GetMapping("/api/admin/documents")
    @Operation(summary = "分页查询可管理文档", description = "仅返回 documentId/spaceId/spaceArchived；包含归档空间，不授予 view/download。")
    @Parameter(name = "spaceId", description = "精确空间 ID", in = ParameterIn.QUERY)
    @Parameter(name = "documentId", description = "精确文档 ID", in = ParameterIn.QUERY)
    @Parameter(name = "spaceArchived", description = "空间是否归档", in = ParameterIn.QUERY)
    @Parameter(name = "limit", description = "默认 20，范围 1–100", in = ParameterIn.QUERY)
    @Parameter(name = "cursor", description = "同一查询下一页游标", in = ParameterIn.QUERY)
    public R<QueryPage<DocumentAdminView>> list(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> params) {
        return R.ok(service.list(params));
    }
}
