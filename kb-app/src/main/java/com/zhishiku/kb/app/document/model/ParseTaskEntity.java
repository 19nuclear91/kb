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
 * {@code kb_parse_task} 持久化实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("kb_parse_task")
@Schema(name = "ParseTaskEntity", description = "文档解析任务持久化实体")
public class ParseTaskEntity {

    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "任务 ID", example = "3001")
    private Long id;

    @Schema(description = "待解析版本 ID", example = "2001")
    private Long versionId;

    @Schema(description = "任务状态", example = "pending")
    private String status;

    @Schema(description = "已尝试次数", example = "1")
    private Integer attempts;

    @Schema(description = "最大尝试次数", example = "3")
    private Integer maxAttempts;

    @Schema(description = "租约到期时间")
    private OffsetDateTime leaseUntil;

    @Schema(description = "租约持有者")
    private String leaseOwner;

    @Schema(description = "最近一次失败原因")
    private String lastError;

    @Schema(description = "创建时间")
    private OffsetDateTime createdAt;

    @Schema(description = "更新时间")
    private OffsetDateTime updatedAt;
}
