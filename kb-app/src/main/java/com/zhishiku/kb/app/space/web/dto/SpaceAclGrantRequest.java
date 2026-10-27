package com.zhishiku.kb.app.space.web.dto;

import com.zhishiku.kb.common.security.PrincipalType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 授予空间访问权请求。
 *
 * @param principalType 授权主体类型
 * @param principalId   授权主体内部 ID
 * @param permission    权限位
 * @param requestId     请求幂等标识
 */
@Schema(description = "空间授权请求")
public record SpaceAclGrantRequest(
        @Schema(description = "主体类型：USER=用户、ROLE=角色、DEPT=部门（部门向下生效）",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull PrincipalType principalType,
        @Schema(description = "主体 ID", requiredMode = Schema.RequiredMode.REQUIRED)
        @Positive long principalId,
        @Schema(description = "权限位：空间接受 view、download、edit、review、comment，文档例外接受 view、download、edit、comment；manage 需 space:grant-manage",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String permission,
        @Schema(description = "请求标识，用于幂等去重")
        String requestId) {
}
