package com.zhishiku.kb.app.document.web;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.model.DocumentCitationView;
import com.zhishiku.kb.app.document.model.DocumentDetail;
import com.zhishiku.kb.app.document.model.DocumentFile;
import com.zhishiku.kb.app.document.model.DocumentPage;
import com.zhishiku.kb.app.document.model.DocumentProgress;
import com.zhishiku.kb.app.document.model.DocumentUploadResult;
import com.zhishiku.kb.app.document.model.DocumentVersionView;
import com.zhishiku.kb.app.document.service.DocumentCitationService;
import com.zhishiku.kb.app.document.service.DocumentManagementService;
import com.zhishiku.kb.app.document.service.DocumentUploadService;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.api.R;
import com.zhishiku.kb.common.file.DocumentFilePolicy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 文档管理 HTTP 入口。原件上传和版本解析均由 Worker 异步处理。
 */
@RestController
@RequestMapping("/api/documents")
@Tag(name = "文档管理", description = "文档元数据、版本、解析状态、文件下载和预览")
@Validated
public class DocumentController {

    private final DocumentUploadService uploadService;
    private final DocumentManagementService managementService;
    private final DocumentCitationService citationService;

    public DocumentController(DocumentUploadService uploadService,
                              DocumentManagementService managementService,
                              DocumentCitationService citationService) {
        this.uploadService = uploadService;
        this.managementService = managementService;
        this.citationService = citationService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传文档", description = "校验空间 edit 权限后上传原件，并创建待解析的文档版本")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "上传成功",
                    content = @Content(schema = @Schema(implementation = R.class))),
            @ApiResponse(responseCode = "500", description = "文件、参数、登录状态或权限无效")
    })
    /**
     * 上传文档原件并创建异步解析版本。
     *
     * @param spaceId 目标知识空间 ID，调用者需要该空间的 {@code edit} 权限
     * @param title 文档标题；为空时使用原始文件名
     * @param securityLevel 文档密级，必须是服务端密级字典 {@code sys_security_level}
     *                      中已存在的级别；可用级别由字典决定
     * @param file 待上传的原始文件
     * @return 文档 ID、版本 ID、解析任务 ID以及文件摘要
     */
    public R<DocumentUploadResult> upload(
            @Parameter(description = "目标知识空间 ID", required = true, example = "1",
                    in = ParameterIn.QUERY)
            @Positive @RequestParam long spaceId,
            @Parameter(description = "文档标题；为空时使用原始文件名", example = "架构说明")
            @RequestParam(required = false) String title,
            @Parameter(description = "文档密级，必须是服务端密级字典中已存在的级别；"
                    + "可用级别由 sys_security_level 决定，不在接口上写死", example = "1")
            @RequestParam(defaultValue = "0") short securityLevel,
            @Parameter(description = "待上传的原始文档", required = true)
            @RequestPart("file") MultipartFile file) {
        long userId = StpUtil.getLoginIdAsLong();
        return R.ok(uploadService.upload(userId, spaceId, title, securityLevel, file));
    }

    @GetMapping
    @Operation(summary = "文档列表", description = "只返回当前用户有 view 权限且未删除的文档")
    /**
     * 分页查询当前用户可查看的文档。
     *
     * @param spaceId 可选空间过滤条件
     * @param page 页码，从 1 开始
     * @param size 每页条数，范围 1-100
     * @return 文档分页结果及总条数
     */
    public R<DocumentPage> list(
            @Parameter(description = "按知识空间过滤", example = "1")
            @RequestParam(required = false) @Positive Long spaceId,
            @Parameter(description = "页码，从 1 开始", example = "1")
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @Parameter(description = "每页条数，范围 1-100", example = "20")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return R.ok(managementService.list(StpUtil.getLoginIdAsLong(), spaceId, page, size));
    }

    @GetMapping("/{documentId}")
    @Operation(summary = "文档详情", description = "返回文档元数据和版本历史")
    /**
     * 查询文档元数据及其版本历史。
     *
     * @param documentId 文档内部 ID
     * @return 文档详情和版本列表
     */
    public R<DocumentDetail> detail(@PathVariable @Positive long documentId) {
        return R.ok(managementService.detail(StpUtil.getLoginIdAsLong(), documentId));
    }

    @GetMapping("/{documentId}/progress")
    @Operation(summary = "解析进度", description = "查询当前版本或指定版本的解析状态、尝试次数和错误原因；"
            + "未初始化或维护等待时 waitingReason 为 EMBEDDING_MAINTENANCE，不代表解析失败或已发布")
    /**
     * 查询文档当前版本或指定版本的解析进度。
     *
     * @param documentId 文档内部 ID
     * @param versionId 可选版本 ID；为空时查询当前版本
     * @return 解析状态、重试次数、失败分类和脱敏错误摘要
     */
    public R<DocumentProgress> progress(
            @PathVariable @Positive long documentId,
            @RequestParam(required = false) @Positive Long versionId) {
        return R.ok(managementService.progress(StpUtil.getLoginIdAsLong(), documentId, versionId));
    }

    @PostMapping("/{documentId}/retry")
    @Operation(summary = "重试解析", description = "对失败或重试次数耗尽的版本重新创建待处理状态，需要 edit 权限")
    /**
     * 重新提交失败或达到重试上限的解析任务。
     *
     * @param documentId 文档内部 ID
     * @param versionId 可选版本 ID；为空时使用当前版本
     * @return 新执行周期的解析进度
     */
    public R<DocumentProgress> retry(
            @PathVariable @Positive long documentId,
            @RequestParam(required = false) @Positive Long versionId) {
        return R.ok(managementService.retry(StpUtil.getLoginIdAsLong(), documentId, versionId));
    }

    @DeleteMapping("/{documentId}")
    @Operation(summary = "删除文档", description = "软删除文档、取消未完成解析任务，并尽力清理对象存储原件")
    /**
     * 软删除文档并取消未完成的解析任务。
     *
     * @param documentId 文档内部 ID，调用者需要该文档所属空间的 {@code edit} 权限
     * @return 空成功响应
     */
    public R<Void> delete(@PathVariable @Positive long documentId) {
        managementService.delete(StpUtil.getLoginIdAsLong(), documentId);
        return R.ok();
    }

    @GetMapping("/{documentId}/versions")
    @Operation(summary = "版本列表", description = "按版本号倒序返回版本和解析任务状态")
    /**
     * 查询文档的全部版本和解析任务状态。
     *
     * @param documentId 文档内部 ID
     * @return 按版本号倒序排列的版本列表
     */
    public R<List<DocumentVersionView>> versions(@PathVariable @Positive long documentId) {
        return R.ok(managementService.versions(StpUtil.getLoginIdAsLong(), documentId));
    }

    @PostMapping(value = "/{documentId}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传新版本", description = "需要 edit 权限；解析完成前不会替换当前已发布版本")
    /**
     * 为已有文档上传新版本并异步解析。
     *
     * @param documentId 文档内部 ID
     * @param file 新版本原始文件
     * @return 新版本 ID、解析任务 ID和文件摘要
     */
    public R<DocumentUploadResult> uploadVersion(
            @PathVariable @Positive long documentId,
            @RequestPart("file") MultipartFile file) {
        return R.ok(managementService.uploadVersion(StpUtil.getLoginIdAsLong(), documentId, file));
    }

    /**
     * 核对固定版本的引用分块；无匹配分块与无权查看使用不同的安全结果。
     *
     * @param documentId 引用文档 ID
     * @param versionId  引用版本 ID
     * @param chunkId    引用分块 ID
     * @return 当前可查看原版本的片段状态及受保护文件入口
     */
    @GetMapping("/{documentId}/versions/{versionId}/citations/{chunkId}")
    @Operation(summary = "核对指定版本引用", description = "需要当前 view 权限；片段无匹配时返回 CHUNK_UNAVAILABLE，"
            + "不跳转最新版本；download 路径需要独立下载权限")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "原版本可查看，返回 AVAILABLE 或 CHUNK_UNAVAILABLE"),
            @ApiResponse(responseCode = "400", description = "文档、版本或分块 ID 非法"),
            @ApiResponse(responseCode = "401", description = "登录状态无效"),
            @ApiResponse(responseCode = "403", description = "必须先完成密码修改"),
            @ApiResponse(responseCode = "404", description = "文档或版本不存在，或当前用户无权访问")
    })
    public ResponseEntity<R<DocumentCitationView>> citation(
            @PathVariable @Positive long documentId,
            @PathVariable @Positive long versionId,
            @PathVariable @Positive long chunkId) {
        DocumentCitationView citation = citationService.find(StpUtil.getLoginIdAsLong(), documentId, versionId,
                chunkId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(R.ok(citation));
    }

    @GetMapping("/{documentId}/download")
    @Operation(summary = "下载当前版本", description = "需要 download 权限")
    /**
     * 下载文档当前已发布版本的原始文件。
     *
     * @param documentId 文档内部 ID
     * @return 带文件名、媒体类型和长度的流式附件响应
     */
    public ResponseEntity<StreamingResponseBody> download(@PathVariable @Positive long documentId) {
        return stream(managementService.file(StpUtil.getLoginIdAsLong(), documentId, null,
                PermissionService.PERM_DOWNLOAD), false);
    }

    @GetMapping("/{documentId}/versions/{versionId}/download")
    @Operation(summary = "下载指定版本", description = "需要 download 权限")
    /**
     * 下载文档指定版本的原始文件。
     *
     * @param documentId 文档内部 ID
     * @param versionId 版本内部 ID
     * @return 带文件名、媒体类型和长度的流式附件响应
     */
    public ResponseEntity<StreamingResponseBody> downloadVersion(
            @PathVariable @Positive long documentId,
            @PathVariable @Positive long versionId) {
        return stream(managementService.file(StpUtil.getLoginIdAsLong(), documentId, versionId,
                PermissionService.PERM_DOWNLOAD), false);
    }

    @GetMapping("/{documentId}/preview")
    @Operation(summary = "预览当前版本", description = "需要 view 权限；仅 PDF 和纯文本可内联预览，其他格式返回 415")
    /**
     * 以内联方式返回文档当前版本，供浏览器预览。
     *
     * @param documentId 文档内部 ID
     * @return 设置为 {@code inline} 的流式文件响应
     */
    public ResponseEntity<StreamingResponseBody> preview(@PathVariable @Positive long documentId) {
        return stream(managementService.file(StpUtil.getLoginIdAsLong(), documentId, null,
                PermissionService.PERM_VIEW), true);
    }

    @GetMapping("/{documentId}/versions/{versionId}/preview")
    @Operation(summary = "预览指定版本", description = "需要 view 权限；仅 PDF 和纯文本可内联预览，其他格式返回 415")
    /**
     * 以内联方式返回文档指定版本，供浏览器预览。
     *
     * @param documentId 文档内部 ID
     * @param versionId 版本内部 ID
     * @return 设置为 {@code inline} 的流式文件响应
     */
    public ResponseEntity<StreamingResponseBody> previewVersion(
            @PathVariable @Positive long documentId,
            @PathVariable @Positive long versionId) {
        return stream(managementService.file(StpUtil.getLoginIdAsLong(), documentId, versionId,
                PermissionService.PERM_VIEW), true);
    }

    private ResponseEntity<StreamingResponseBody> stream(DocumentFile file, boolean inline) {
        var input = new BufferedInputStream(managementServiceObject(file));
        MediaType contentType = MediaType.APPLICATION_OCTET_STREAM;
        if (inline) {
            try {
                input.mark(16);
                byte[] header = input.readNBytes(8);
                input.reset();
                contentType = MediaType.parseMediaType(
                        DocumentFilePolicy.previewMime(
                                file.getFileName(), file.getMimeType(), header));
            } catch (IOException | IllegalArgumentException e) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
                throw ApiException.unsupportedMediaType(
                        "该文件暂不支持安全在线预览，请通过下载接口获取原件");
            }
        }
        StreamingResponseBody body = output -> {
            try (input) {
                input.transferTo(output);
            }
        };
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType);
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "sandbox; default-src 'none'; base-uri 'none'; form-action 'none'");
        headers.set("Referrer-Policy", "no-referrer");
        if (file.getFileSize() != null && file.getFileSize() >= 0) {
            headers.setContentLength(file.getFileSize());
        }
        headers.setContentDisposition(ContentDisposition.builder(inline ? "inline" : "attachment")
                .filename(file.getFileName(), StandardCharsets.UTF_8)
                .build());
        headers.setCacheControl("no-store");
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private InputStream managementServiceObject(DocumentFile file) {
        // 文件定位信息只在完成业务权限判断后产生；对象存储接口本身不暴露给 Controller。
        return managementService.open(file);
    }

}
