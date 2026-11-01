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
 * {@code kb_document} 持久化实体。
 *
 * <p>项目使用 MyBatis 写 SQL，不使用 JPA 的实体生命周期；这里的实体只负责
 * 明确数据库字段、生成键和文档模型，避免把插入参数对象误当成完整领域对象。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("kb_document")
@Schema(name = "DocumentEntity", description = "知识库文档持久化实体")
public class DocumentEntity {

    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "文档 ID", example = "1001")
    private Long id;

    @Schema(description = "知识空间 ID", example = "1")
    private Long spaceId;

    @Schema(description = "文档标题", example = "架构说明")
    private String title;

    @Schema(description = "文档级人工摘要")
    private String summary;

    @Schema(description = "本空间分类 ID，null 表示未分类")
    private Long categoryId;

    @Schema(description = "独立资料版本")
    private Long metadataVersion;

    @Schema(description = "文档密级：0 公开，1 内部，2 保密，3 机密", example = "1")
    private Short securityLevel;

    @Schema(description = "文档状态", example = "published")
    private String status;

    @Schema(description = "文档所有者用户 ID", example = "1")
    private Long ownerId;

    @Schema(description = "当前已发布版本 ID", example = "2001")
    private Long currentVersionId;

    @Schema(description = "创建时间")
    private OffsetDateTime createdAt;

    @Schema(description = "更新时间")
    private OffsetDateTime updatedAt;
}
