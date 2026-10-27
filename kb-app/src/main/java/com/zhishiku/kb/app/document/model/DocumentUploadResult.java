package com.zhishiku.kb.app.document.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上传仅确认写入受理；元数据必须经过独立的读取鉴权。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "DocumentUploadResult", description = "文档上传结果")
public class DocumentUploadResult {

    @Schema(description = "文档 ID", example = "1001")
    private long documentId;

    @Schema(description = "文档版本 ID", example = "2001")
    private long versionId;

    @Schema(description = "解析任务 ID", example = "3001")
    private long taskId;

    @Schema(description = "索引状态", example = "pending")
    private String indexStatus;

    /**
     * 保留 record 风格访问器，便于服务层和已有调用方平滑迁移到 Lombok DTO。
     */
    public long documentId() {
        return documentId;
    }

    public long versionId() {
        return versionId;
    }

    public long taskId() {
        return taskId;
    }

    public String indexStatus() {
        return indexStatus;
    }
}
