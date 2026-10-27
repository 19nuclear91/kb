package com.zhishiku.kb.app.auth.web.dto;

import com.zhishiku.kb.app.auth.service.IdentityService;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 创建用户请求。
 *
 * @param username        登录用户名，全局唯一；长度上限与 {@link IdentityService#USERNAME_MAX_LENGTH} 一致
 * @param displayName     显示名
 * @param departmentId    主部门内部 ID，必须存在且启用
 * @param clearanceLevel  初始密级，必须存在于服务端密级字典
 * @param initialPassword 初始密码，创建后强制首次修改；响应与审计均不回显
 * @param requestId       幂等请求标识
 */
@Schema(name = "UserCreateRequest", description = "创建用户请求")
public record UserCreateRequest(
        @Schema(description = "登录用户名，全局唯一", example = "alice")
        @NotBlank @Size(max = IdentityService.USERNAME_MAX_LENGTH) String username,
        @Schema(description = "显示名", example = "Alice")
        @NotBlank String displayName,
        @Schema(description = "主部门内部 ID")
        @NotNull @Positive Long departmentId,
        @Schema(description = "初始密级，必须存在于服务端密级字典", example = "1")
        @NotNull Short clearanceLevel,
        @Schema(description = "初始密码，创建后强制首次修改",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String initialPassword,
        @Schema(description = "同一操作域内的幂等请求标识")
        String requestId) {
}
