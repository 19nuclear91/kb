package com.zhishiku.kb.app.auth.controller.diagnostics;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhishiku.kb.app.auth.service.diagnostics.DiagnosticException;
import com.zhishiku.kb.app.auth.service.diagnostics.DiagnosticInput;
import com.zhishiku.kb.app.config.DiagnosticLimits;
import com.zhishiku.kb.app.auth.service.diagnostics.DiagnosticService;
import com.zhishiku.kb.app.auth.model.diagnostics.DiagnosticRequests;
import com.zhishiku.kb.app.auth.model.diagnostics.DiagnosticViews;
import com.zhishiku.kb.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@Tag(name = "权限诊断", description = "单用户、单资源的只读解释与可选授权预演")
@ApiResponses({
        @ApiResponse(responseCode = "400", description = "非法输入或诊断规模超过限额", content = @Content(schema = @Schema(implementation = DiagnosticAdvice.Failure.class))),
        @ApiResponse(responseCode = "401", description = "缺少有效会话", content = @Content(schema = @Schema(implementation = DiagnosticAdvice.Failure.class))),
        @ApiResponse(responseCode = "403", description = "操作者条件、诊断权限或 manage 授出资格不足", content = @Content(schema = @Schema(implementation = DiagnosticAdvice.Failure.class))),
        @ApiResponse(responseCode = "404", description = "资源不可管理、已删除或目标用户不存在", content = @Content(schema = @Schema(implementation = DiagnosticAdvice.Failure.class))),
        @ApiResponse(responseCode = "413", description = "实际正文超过 64 KiB 或配置的更小限额", content = @Content(schema = @Schema(implementation = DiagnosticAdvice.Failure.class))),
        @ApiResponse(responseCode = "503", description = "依赖故障、超时或事实无法可靠评估", content = @Content(schema = @Schema(implementation = DiagnosticAdvice.Failure.class)))
})
@RequestMapping(value = "/api/admin/{kind:spaces|documents}/{id}/authorization-diagnostics", produces = MediaType.APPLICATION_JSON_VALUE)
public class DiagnosticController {
    private final DiagnosticService service;
    private final DiagnosticLimits limits;

    public DiagnosticController(DiagnosticService service, DiagnosticLimits limits) {
        this.service = service;
        this.limits = limits;
    }

    @PostMapping(value = "/users/search", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "搜索诊断目标用户", description = "在路径资源管理范围内按显示名字面检索，仅返回启用用户的 ID 和显示名，不接受 URL 查询参数")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(schema = @Schema(implementation = DiagnosticRequests.Search.class)))
    @ApiResponse(responseCode = "200", description = "R 封装的最小候选页；成功和失败均 no-store", useReturnTypeSchema = true)
    public ResponseEntity<R<DiagnosticViews.Users>> users(
            @Parameter(name = "kind", description = "资源路径：spaces 空间或 documents 文档", required = true, schema = @Schema(allowableValues = {"spaces", "documents"}))
            @PathVariable
            String kind,
            @Parameter(name = "id", description = "路径资源 ID，1 至 9223372036854775807 的十进制整数", required = true)
            @PathVariable
            String id,
            HttpServletRequest request) {
        return success(service.search(kind(kind), DiagnosticInput.id(id), () -> body(request)));
    }

    @PostMapping(value = "/explain", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "解释指定用户的当前权限", description = "先核对操作者诊断与资源资格，再读取目标；空间可附选一份同空间文档，文档路径只诊断自身")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(schema = @Schema(implementation = DiagnosticRequests.Explain.class)))
    @ApiResponse(responseCode = "200", description = "R 封装的完整 EXPLAIN 结果，after 为 null；不包含正文或版本身份", useReturnTypeSchema = true)
    public ResponseEntity<R<DiagnosticViews.Result>> explain(
            @Parameter(name = "kind", description = "资源路径：spaces 空间或 documents 文档", required = true, schema = @Schema(allowableValues = {"spaces", "documents"}))
            @PathVariable
            String kind,
            @Parameter(name = "id", description = "路径资源 ID，1 至 9223372036854775807 的十进制整数", required = true)
            @PathVariable
            String id,
            HttpServletRequest request) {
        return success(service.diagnose(kind(kind), DiagnosticInput.id(id), false, () -> body(request)));
    }

    @PostMapping(value = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "只读预演一项授权意图", description = "同次快照计算 before／after；含 manage 的空间意图额外要求 space:grant-manage，不生成授权或审计回执")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(schema = @Schema(implementation = DiagnosticRequests.Preview.class)))
    @ApiResponse(responseCode = "200", description = "R 封装的完整 PREVIEW 结果，包含全部拟议变化；正式提交仍独立鉴权", useReturnTypeSchema = true)
    public ResponseEntity<R<DiagnosticViews.Result>> preview(
            @Parameter(name = "kind", description = "资源路径：spaces 空间或 documents 文档", required = true, schema = @Schema(allowableValues = {"spaces", "documents"}))
            @PathVariable
            String kind,
            @Parameter(name = "id", description = "路径资源 ID，1 至 9223372036854775807 的十进制整数", required = true)
            @PathVariable
            String id,
            HttpServletRequest request) {
        return success(service.diagnose(kind(kind), DiagnosticInput.id(id), true, () -> body(request)));
    }

    private JsonNode body(HttpServletRequest request) {
        if (request.getQueryString() != null) {
            throw new DiagnosticException(DiagnosticException.Reason.INVALID_INPUT);
        }
        if (request.getContentLengthLong() > limits.bodyBytes()) {
            throw new DiagnosticException(DiagnosticException.Reason.PAYLOAD_TOO_LARGE);
        }
        try {
            return DiagnosticInput.read(request.getInputStream(), limits.bodyBytes());
        } catch (IOException failure) {
            throw new DiagnosticException(DiagnosticException.Reason.INVALID_INPUT);
        }
    }

    private static String kind(String kind) {
        if (kind.equals("spaces")) {
            return "SPACE";
        }
        if (kind.equals("documents")) {
            return "DOCUMENT";
        }
        throw new DiagnosticException(DiagnosticException.Reason.INVALID_INPUT);
    }

    private static <T> ResponseEntity<R<T>> success(R<T> value) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").contentType(MediaType.APPLICATION_JSON).body(value);
    }
}
