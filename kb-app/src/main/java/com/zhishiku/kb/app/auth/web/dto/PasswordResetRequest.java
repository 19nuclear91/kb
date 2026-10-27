package com.zhishiku.kb.app.auth.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 管理员重置用户密码请求。
 *
 * @param newPassword 新密码；强度由服务端策略校验，重置后用户被强制首次改密
 * @param requestId   请求标识；重复提交同一标识返回首次结果
 */
@Schema(name = "PasswordResetRequest", description = "管理员重置用户密码请求")
public record PasswordResetRequest(
        @Schema(description = "新密码；强度由服务端策略校验，重置后用户被强制首次改密")
        @NotBlank String newPassword,
        @Schema(description = "请求标识；重复提交同一标识返回首次结果")
        String requestId) {
}
