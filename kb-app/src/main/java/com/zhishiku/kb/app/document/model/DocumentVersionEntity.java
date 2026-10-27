package com.zhishiku.kb.app.document.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * {@code kb_document_version} 持久化实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("kb_document_version")
@Schema(name = "DocumentVersionEntity", description = "文档版本持久化实体")
public class DocumentVersionEntity {

    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "版本 ID", example = "2001")
    private Long id;

    @Schema(description = "所属文档 ID", example = "1001")
    private Long docId;

    @Schema(description = "文档版本号", example = "1")
    private Integer versionNo;

    @Schema(description = "对象存储 key", example = "documents/uuid.pdf")
    private String objectKey;

    @Schema(description = "原始文件名", example = "architecture.pdf")
    private String fileName;

    @Schema(description = "文件大小，单位为字节", example = "102400")
    private Long fileSize;

    @Schema(description = "文件 SHA-256 摘要")
    private String contentHash;

    @Schema(description = "文件 MIME 类型", example = "application/pdf")
    private String mimeType;

    @Schema(description = "索引状态", example = "pending")
    private String indexStatus;

    @Schema(description = "服务端确认的真实上传者，与文档所有者独立")
    private Long uploaderUserId;

    @Schema(description = "版本插入时固定的发布策略")
    private String publicationPolicy;

    @Schema(description = "发布协议来源")
    private String publicationProtocol;

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

    @Schema(description = "创建时间")
    private OffsetDateTime createdAt;

    @Schema(description = "更新时间")
    private OffsetDateTime updatedAt;
}
