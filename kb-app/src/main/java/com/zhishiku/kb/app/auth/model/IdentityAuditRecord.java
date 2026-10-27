package com.zhishiku.kb.app.auth.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * 仅追加的身份与部门审计事件的持久化投影。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "IdentityAuditRecord", description = "身份与部门变更审计摘要")
public class IdentityAuditRecord {
    @Schema(description = "审计记录内部 ID", example = "100")
    private Long id;
    @Schema(description = "审计事件唯一 ID")
    private UUID eventId;
    @Schema(description = "操作域，例如 IDENTITY 或 DEPARTMENT", example = "IDENTITY")
    private String operationScope;
    @Schema(description = "请求幂等标识", example = "req-123")
    private String requestId;
    @Schema(description = "操作者用户内部 ID", example = "1")
    private Long actorUserId;
    @Schema(description = "目标类型", example = "USER")
    private String targetType;
    @Schema(description = "目标内部 ID", example = "2")
    private Long targetId;
    @Schema(description = "操作类型", example = "MOVE_DEPARTMENT")
    private String operation;
    @Schema(description = "操作结果", example = "SUCCESS")
    private String status;
    @Schema(description = "变更前脱敏摘要")
    private String beforeState;
    @Schema(description = "变更后脱敏摘要")
    private String afterState;
    @Schema(description = "失败或变更原因")
    private String reason;
    @Schema(description = "审计创建时间")
    private Instant createdAt;
}
