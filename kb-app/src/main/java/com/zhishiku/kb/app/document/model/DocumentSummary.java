package com.zhishiku.kb.app.document.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 文档列表和详情中的元数据，不包含对象存储 key。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "DocumentSummary", description = "文档元数据摘要")
public class DocumentSummary {

    @Schema(description = "文档 ID", example = "1001")
    private Long id;
    @Schema(description = "知识空间 ID", example = "1")
    private Long spaceId;
    @Schema(description = "文档标题", example = "架构说明")
    private String title;
    @Schema(description = "密级：0 公开，1 内部，2 保密，3 机密", example = "1")
    private Short securityLevel;
    @Schema(description = "文档状态", example = "published")
    private String status;
    @Schema(description = "所有者用户 ID", example = "1")
    private Long ownerId;
    @Schema(description = "当前已发布版本 ID", example = "2001")
    private Long currentVersionId;
    @Schema(description = "当前版本号", example = "2")
    private Integer currentVersionNo;
    @Schema(description = "当前版本文件名", example = "architecture.pdf")
    private String currentFileName;
    @Schema(description = "当前版本文件大小")
    private Long currentFileSize;
    @Schema(description = "当前版本 MIME 类型", example = "application/pdf")
    private String currentMimeType;
    @Schema(description = "当前版本索引状态", example = "indexed")
    private String currentIndexStatus;
    @Schema(description = "当前版本索引失败原因")
    private String currentIndexError;
    @Schema(description = "创建时间")
    private OffsetDateTime createdAt;
    @Schema(description = "更新时间")
    private OffsetDateTime updatedAt;
}
