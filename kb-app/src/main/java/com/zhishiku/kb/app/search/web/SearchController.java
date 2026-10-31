package com.zhishiku.kb.app.search.web;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.search.config.SearchLimitsProperties;
import com.zhishiku.kb.app.search.model.SearchInput;
import com.zhishiku.kb.app.search.service.SearchService;
import com.zhishiku.kb.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前端搜索与 AI 召回共用的授权检索入口。
 */
@RestController
@RequestMapping("/api/search")
@Tag(name = "文档检索", description = "带权限过滤的全文和向量混合检索")
public class SearchController {
    private final SearchService searchService;
    private final SearchLimitsProperties limits;

    public SearchController(SearchService searchService) {
        this(searchService, SearchLimitsProperties.defaults());
    }

    @Autowired
    public SearchController(SearchService searchService, SearchLimitsProperties limits) {
        this.searchService = searchService;
        this.limits = limits;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "混合检索", description = "POST JSON 请求体承载检索词；按当前登录用户权限检索可见文档分块")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "检索成功",
                    content = @Content(schema = @Schema(implementation = R.class))),
            @ApiResponse(responseCode = "400", description = "请求无效或 URL 包含查询参数"),
            @ApiResponse(responseCode = "405", description = "GET 检索已停用"),
            @ApiResponse(responseCode = "413", description = "搜索请求体超过上限"),
            @ApiResponse(responseCode = "429", description = "本人搜索并发达到上限"),
            @ApiResponse(responseCode = "503", description = "检索预算、容量或依赖暂不可用")
    })
    public ResponseEntity<?> search(@Valid @RequestBody SearchRequest body,
                                                    HttpServletRequest request) {
        if (request.getQueryString() != null) {
            return ResponseEntity.badRequest().body(R.fail("URL 查询参数不受支持"));
        }
        int candidates = body.candidates() == null ? 50 : body.candidates();
        int topK = body.topK() == null ? candidates : body.topK();
        SearchInput.validate(body.query(), candidates, topK, limits.maxQueryCodepoints());
        long userId = StpUtil.getLoginIdAsLong();
        return ResponseEntity.ok().header("Cache-Control", "no-store").contentType(MediaType.APPLICATION_JSON)
                .body(searchService.searchResponse(userId, body.query(), null, candidates, topK));
    }
}
