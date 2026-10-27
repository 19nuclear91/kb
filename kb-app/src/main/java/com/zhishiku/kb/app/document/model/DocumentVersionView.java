package com.zhishiku.kb.app.document.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 对外版本信息。刻意不返回 objectKey。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "DocumentVersionView", description = "文档版本和解析任务状态")
public class DocumentVersionView {

    @Schema(description = "版本 ID", example = "2001")
    private Long id;
    @Schema(description = "所属文档 ID", example = "1001")
    private Long docId;
    @Schema(description = "版本号", example = "2")
    private Integer versionNo;
    @Schema(description = "是否为当前已发布版本", example = "true")
    private Boolean current;
    @Schema(description = "版本创建时固定的发布策略")
    private String publicationPolicy;
    @Schema(description = "发布证据")
    private String publicationEvidence;
    @Schema(description = "当前审核轮次状态")
    private String reviewState;
    @Schema(description = "版本并发状态值")
    private Long stateVersion;
    @Schema(description = "原始文件名", example = "architecture.pdf")
    private String fileName;
    @Schema(description = "文件大小，单位为字节")
    private Long fileSize;
    @Schema(description = "文件 SHA-256 摘要")
    private String contentHash;
    @Schema(description = "文件 MIME 类型", example = "application/pdf")
    private String mimeType;
    @Schema(description = "索引状态", example = "indexed")
    private String indexStatus;
    @Schema(description = "索引失败原因")
    private String indexError;
    @Schema(description = "解析器标识", example = "external-parser")
    private String parserName;
    @Schema(description = "解析器版本", example = "2.9.2")
    private String parserVersion;
    @Schema(description = "解析结果是否被截断")
    private Boolean parseTruncated;
    @Schema(description = "解析问题数量")
    private Integer parseIssueCount;
    @Schema(description = "解析问题摘要")
    private String parseIssueSummary;
    @Schema(description = "解析任务 ID", example = "3001")
    private Long taskId;
    @Schema(description = "解析任务状态", example = "succeeded")
    private String taskStatus;
    @Schema(description = "已尝试次数", example = "1")
    private Integer attempts;
    @Schema(description = "最大尝试次数", example = "3")
    private Integer maxAttempts;
    @Schema(description = "最近一次任务错误")
    private String lastError;
    @Schema(description = "版本创建时间")
    private OffsetDateTime createdAt;
    @Schema(description = "版本更新时间")
    private OffsetDateTime updatedAt;
    @Schema(description = "下一次允许领取时间")
    private OffsetDateTime nextAttemptAt;
    @Schema(description = "最近失败分类")
    private String failureClass;
    @Schema(description = "稳定失败代码")
    private String failureCode;
    @Schema(description = "脱敏失败摘要")
    private String failureSummary;
    @Schema(description = "最近失败时间")
    private OffsetDateTime lastFailureAt;
    @Schema(description = "执行周期，从 0 开始递增")
    private Long executionGeneration;
    @Schema(description = "当前执行周期死信状态")
    private String deadLetterStatus;

    /**
     * 兼容解析元数据加入前的测试夹具和调用方。
     */
    public DocumentVersionView(Long id, Long docId, Integer versionNo, Boolean current,
                               String fileName, Long fileSize, String contentHash,
                               String mimeType, String indexStatus, String indexError,
                               Long taskId, String taskStatus, Integer attempts,
                               Integer maxAttempts, String lastError,
                               OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this(id, docId, versionNo, current,
                null, null, null, null, fileName, fileSize, contentHash, mimeType,
                indexStatus, indexError, null, null, null, null, null,
                taskId, taskStatus, attempts, maxAttempts, lastError, createdAt, updatedAt,
                null, null, null, null, null, null, null);
    }
}
