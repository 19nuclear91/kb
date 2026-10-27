package com.zhishiku.kb.app.space.web.dto;

import com.zhishiku.kb.common.security.PrincipalType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 撤销空间访问权请求。
 *
 * @param principalType 被撤销主体类型
 * @param principalId   被撤销主体内部 ID
 * @param permission    权限位
 * @param requestId     请求幂等标识
 */
@Schema(description = "空间授权撤销请求")
public record SpaceAclRevokeRequest(
        @Schema(description = "主体类型：USER=用户、ROLE=角色、DEPT=部门",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull PrincipalType principalType,
        @Schema(description = "主体 ID", requiredMode = Schema.RequiredMode.REQUIRED)
        @Positive long principalId,
        @Schema(description = "权限位；撤销 manage 需要 space:grant-manage",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String permission,
        @Schema(description = "请求标识，用于幂等去重")
        String requestId) {
}
