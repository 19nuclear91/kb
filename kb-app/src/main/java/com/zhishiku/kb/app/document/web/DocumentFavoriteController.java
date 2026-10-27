package com.zhishiku.kb.app.document.web;

import com.zhishiku.kb.app.document.model.DocumentFavoriteViews;
import com.zhishiku.kb.app.document.service.DocumentFavoriteService;
import com.zhishiku.kb.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/** 本人收藏入口；身份由服务端当前会话取得。 */
@RestController
@Tag(name = "个人文档收藏")
public class DocumentFavoriteController {
    private final DocumentFavoriteService service;

    public DocumentFavoriteController(DocumentFavoriteService service) {
        this.service = service;
    }

    @PutMapping("/api/documents/{documentId}/favorite")
    @Operation(summary = "收藏文档", description = "当前 view 和密级检查通过后设为已收藏；重复提交保持原创建位置")
    public R<DocumentFavoriteViews.State> put(@PathVariable String documentId, HttpServletRequest request) throws IOException {
        DocumentFavoriteInput.noPayload(request);
        return R.ok(service.put(documentId));
    }

    @DeleteMapping("/api/documents/{documentId}/favorite")
    @Operation(summary = "取消本人收藏", description = "无需当前文档 view；关系不存在也返回未收藏，不返回文档内容")
    public R<DocumentFavoriteViews.State> delete(@PathVariable String documentId, HttpServletRequest request) throws IOException {
        DocumentFavoriteInput.noPayload(request);
        return R.ok(service.delete(documentId));
    }

    @GetMapping("/api/documents/favorites")
    @Operation(summary = "我的收藏", description = "按当前权限过滤后游标分页；limit 默认 20、上限 100，无总数")
    public R<DocumentFavoriteViews.Page> list(@RequestParam MultiValueMap<String, String> params) {
        return R.ok(service.list(params));
    }

    @PostMapping(value = "/api/documents/favorites/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "批量读取本人收藏状态", description = "不可见和不存在的文档均省略，不返回隐藏关系",
            requestBody = @RequestBody(required = true,
                    content = @Content(schema = @Schema(implementation = DocumentFavoriteInput.StatusRequest.class))))
    public R<DocumentFavoriteViews.States> states(HttpServletRequest request) throws IOException {
        DocumentFavoriteInput.noQuery(request);
        return R.ok(service.states(DocumentFavoriteInput.parse(request.getInputStream()).documentIds()));
    }
}
