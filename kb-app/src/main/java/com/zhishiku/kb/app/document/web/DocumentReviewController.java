package com.zhishiku.kb.app.document.web;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.document.model.ReviewReceipt;
import com.zhishiku.kb.app.document.model.ReviewViews;
import com.zhishiku.kb.app.document.service.DocumentReviewQueryService;
import com.zhishiku.kb.app.document.service.DocumentReviewService;
import com.zhishiku.kb.app.query.CommandInput;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.query.QueryParameters;
import com.zhishiku.kb.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

@RestController
@Tag(name = "版本审核")
public class DocumentReviewController {
    private final DocumentReviewQueryService queries;
    private final DocumentReviewService commands;

    public DocumentReviewController(DocumentReviewQueryService queries, DocumentReviewService commands) {
        this.queries = queries;
        this.commands = commands;
    }

    @GetMapping("/api/reviews")
    @Operation(summary = "本人最小版本状态或当前合格审核待办")
    public R<ReviewViews.Page> list(@RequestParam MultiValueMap<String, String> params, HttpServletRequest request) throws IOException {
        long actor = StpUtil.getLoginIdAsLong();
        if (request.getInputStream().read() != -1) throw QueryException.invalid();
        return R.ok(queries.list(actor, params));
    }

    @GetMapping("/api/documents/{documentId}/versions/{versionId}/review")
    @Operation(summary = "指定版本审核状态与能力", description = "私有意见仅在 opinions=true 且当次授权通过时有界加载")
    public R<ReviewViews.Status> status(@PathVariable String documentId, @PathVariable String versionId,
                                        @RequestParam MultiValueMap<String, String> params, HttpServletRequest request) throws IOException {
        long actor = StpUtil.getLoginIdAsLong();
        if (request.getInputStream().read() != -1) throw QueryException.invalid();
        return R.ok(queries.status(actor, QueryParameters.positiveValue(documentId), QueryParameters.positiveValue(versionId), params));
    }

    @PostMapping(value = "/api/documents/{documentId}/versions/{versionId}/reviews", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "手动送审指定版本")
    public R<ReviewReceipt> submit(@PathVariable String documentId, @PathVariable String versionId,
                                   HttpServletRequest request) throws IOException {
        long actor = StpUtil.getLoginIdAsLong();
        CommandInput.noQuery(request);
        long document = QueryParameters.positiveValue(documentId), version = QueryParameters.positiveValue(versionId);
        String requestId = CommandInput.requestId(request);
        var body = CommandInput.object(request.getInputStream(), Set.of("expectedStateVersion"));
        return R.ok(commands.submit(actor, document, version, CommandInput.nonnegative(body.get("expectedStateVersion")), requestId));
    }

    @PostMapping(value = "/api/documents/{documentId}/versions/{versionId}/reviews/{reviewId}/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "驳回指定轮次", description = "reason 为 1–2000 个 Unicode code point 的私有纯文本")
    public R<ReviewReceipt> reject(@PathVariable String documentId, @PathVariable String versionId, @PathVariable String reviewId,
                                   HttpServletRequest request) throws IOException {
        long actor = StpUtil.getLoginIdAsLong();
        CommandInput.noQuery(request);
        long document = QueryParameters.positiveValue(documentId), version = QueryParameters.positiveValue(versionId);
        UUID round = roundId(reviewId);
        String requestId = CommandInput.requestId(request);
        var body = CommandInput.object(request.getInputStream(), Set.of("expectedStateVersion", "reason"));
        return R.ok(commands.reject(actor, document, version, round, CommandInput.nonnegative(body.get("expectedStateVersion")),
                requestId, CommandInput.text(body.get("reason"))));
    }

    @PostMapping(value = "/api/documents/{documentId}/versions/{versionId}/reviews/{reviewId}/withdraw", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "由本轮送审者撤回")
    public R<ReviewReceipt> withdraw(@PathVariable String documentId, @PathVariable String versionId, @PathVariable String reviewId,
                                     HttpServletRequest request) throws IOException {
        long actor = StpUtil.getLoginIdAsLong();
        CommandInput.noQuery(request);
        long document = QueryParameters.positiveValue(documentId), version = QueryParameters.positiveValue(versionId);
        UUID round = roundId(reviewId);
        String requestId = CommandInput.requestId(request);
        var body = CommandInput.object(request.getInputStream(), Set.of("expectedStateVersion"));
        return R.ok(commands.withdraw(actor, document, version, round,
                CommandInput.nonnegative(body.get("expectedStateVersion")), requestId));
    }

    public static UUID roundId(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw QueryException.invalid();
        return UUID.fromString(value);
    }
}
