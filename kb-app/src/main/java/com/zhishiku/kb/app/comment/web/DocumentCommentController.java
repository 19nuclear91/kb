package com.zhishiku.kb.app.comment.web;

import com.zhishiku.kb.app.comment.model.CommentViews;
import com.zhishiku.kb.app.comment.service.DocumentCommentQueryService;
import com.zhishiku.kb.app.comment.service.DocumentCommentService;
import com.zhishiku.kb.app.query.CommandInput;
import com.zhishiku.kb.app.query.QueryParameters;
import com.zhishiku.kb.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/** 固定版本评论入口；身份与时间只取服务端会话和时钟，客户端不能覆盖。 */
@RestController
@Tag(name = "版本评论")
public class DocumentCommentController {
    private static final String BASE = "/api/documents/{documentId}/versions/{versionId}/comments";
    private final DocumentCommentQueryService queries;
    private final DocumentCommentService commands;

    public DocumentCommentController(DocumentCommentQueryService queries, DocumentCommentService commands) {
        this.queries = queries;
        this.commands = commands;
    }

    @GetMapping(BASE)
    @Operation(summary = "固定版本根评论", description = "仅 limit/cursor；按创建位置倒序，limit 默认 20、上限 100，无总数")
    public R<CommentViews.Page> roots(@PathVariable String documentId, @PathVariable String versionId,
                                      @RequestParam MultiValueMap<String, String> params, HttpServletRequest request) throws IOException {
        CommentInput.noPayload(request);
        return R.ok(queries.roots(QueryParameters.positiveValue(documentId), QueryParameters.positiveValue(versionId), params));
    }

    @GetMapping(BASE + "/{rootId}/replies")
    @Operation(summary = "根评论下的回复", description = "仅 limit/cursor；按创建位置正序，独立游标")
    public R<CommentViews.Replies> replies(@PathVariable String documentId, @PathVariable String versionId,
                                           @PathVariable String rootId, @RequestParam MultiValueMap<String, String> params,
                                           HttpServletRequest request) throws IOException {
        CommentInput.noPayload(request);
        return R.ok(queries.replies(QueryParameters.positiveValue(documentId), QueryParameters.positiveValue(versionId),
                CommentInput.commentId(rootId), params));
    }

    @GetMapping(BASE + "/{commentId}")
    @Operation(summary = "固定评论目标", description = "无参数；用于回复通知定位，仍按版本当前权限读取")
    public R<CommentViews.Detail> detail(@PathVariable String documentId, @PathVariable String versionId,
                                         @PathVariable String commentId, @RequestParam MultiValueMap<String, String> params,
                                         HttpServletRequest request) throws IOException {
        CommentInput.noPayload(request);
        return R.ok(queries.detail(QueryParameters.positiveValue(documentId), QueryParameters.positiveValue(versionId),
                CommentInput.commentId(commentId), params));
    }

    @PostMapping(value = BASE, consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "发表评论或单层回复", description = "需要 X-Request-Id；新建 201，匹配重放 200，回执不含正文",
            requestBody = @RequestBody(required = true,
                    content = @Content(schema = @Schema(implementation = CommentInput.CreateRequest.class))))
    public ResponseEntity<R<CommentViews.Receipt>> create(@PathVariable String documentId, @PathVariable String versionId,
                                                          HttpServletRequest request) throws IOException {
        CommentInput.noQuery(request);
        long document = QueryParameters.positiveValue(documentId), version = QueryParameters.positiveValue(versionId);
        String requestId = CommandInput.requestId(request);
        var input = CommentInput.create(request.getInputStream());
        var receipt = commands.create(document, version, input.parentId(), input.body(), requestId);
        return ResponseEntity.status(receipt.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(R.ok(receipt));
    }

    @DeleteMapping(BASE + "/{commentId}")
    @Operation(summary = "删除本人评论", description = "无请求体或查询参数；清除正文并保留墓碑，本人重复删除仍为 204")
    public ResponseEntity<Void> delete(@PathVariable String documentId, @PathVariable String versionId,
                                       @PathVariable String commentId, HttpServletRequest request) throws IOException {
        CommentInput.noQuery(request);
        CommentInput.noPayload(request);
        commands.delete(QueryParameters.positiveValue(documentId), QueryParameters.positiveValue(versionId),
                CommentInput.commentId(commentId));
        return ResponseEntity.noContent().build();
    }
}
