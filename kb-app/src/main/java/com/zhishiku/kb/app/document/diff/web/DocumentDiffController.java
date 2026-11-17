package com.zhishiku.kb.app.document.diff.web;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.document.diff.model.DiffViews.Response;
import com.zhishiku.kb.app.document.diff.service.DiffException;
import com.zhishiku.kb.app.document.diff.service.DocumentDiffService;
import com.zhishiku.kb.common.api.R;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DocumentDiffController {
    private final DocumentDiffService service;

    public DocumentDiffController(DocumentDiffService service) {
        this.service = service;
    }

    @GetMapping("/api/documents/{documentId}/versions/{versionId}/diff")
    public R<Response> get(@PathVariable String documentId, @PathVariable String versionId, HttpServletRequest request) {
        var parameters = request.getParameterMap();
        if (parameters.size() != 1 || !parameters.containsKey("baseVersionId")
                || parameters.get("baseVersionId").length != 1) {
            throw new DiffException(400, "INVALID_INPUT");
        }
        long document = id(documentId);
        long target = id(versionId);
        long base = id(parameters.get("baseVersionId")[0]);
        if (base == target) {
            throw new DiffException(400, "INVALID_INPUT");
        }
        return R.ok(service.compare(StpUtil.getLoginIdAsLong(), document, base, target));
    }

    private static long id(String raw) {
        try {
            if (raw == null || !raw.matches("[1-9][0-9]*")) {
                throw new NumberFormatException();
            }
            return Long.parseLong(raw);
        } catch (NumberFormatException invalid) {
            throw new DiffException(400, "INVALID_INPUT");
        }
    }
}
