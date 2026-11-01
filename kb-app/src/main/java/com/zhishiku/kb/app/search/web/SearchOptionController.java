package com.zhishiku.kb.app.search.web;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.search.model.SearchOptions;
import com.zhishiku.kb.app.search.service.SearchOptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前可检索已发布内容的稳定分类与标签候选。 */
@RestController
@Tag(name = "文档检索")
public class SearchOptionController {
    private final SearchOptionService service;

    public SearchOptionController(SearchOptionService service) {
        this.service = service;
    }

    @PostMapping(value = "/api/search/categories", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "搜索分类候选", requestBody = @RequestBody(required = true,
            content = @Content(schema = @Schema(implementation = SearchOptions.CategoryQuery.class))))
    public ResponseEntity<byte[]> categories(HttpServletRequest request) {
        var query = SearchOptionInput.category(SearchOptionInput.body(request));
        return response(service.categories(StpUtil.getLoginIdAsLong(), query));
    }

    @PostMapping(value = "/api/search/tags", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "搜索标签候选", requestBody = @RequestBody(required = true,
            content = @Content(schema = @Schema(implementation = SearchOptions.TagQuery.class))))
    public ResponseEntity<byte[]> tags(HttpServletRequest request) {
        var query = SearchOptionInput.tag(SearchOptionInput.body(request));
        return response(service.tags(StpUtil.getLoginIdAsLong(), query));
    }

    private static ResponseEntity<byte[]> response(byte[] body) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
