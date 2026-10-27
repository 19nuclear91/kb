package com.zhishiku.kb.app.auth.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 自助改密请求。
 *
 * @param oldPassword 当前密码，用于校验操作者身份
 * @param newPassword 新密码，强度由服务端策略校验
 * @param requestId   请求标识；客户端可选，缺省由服务端生成，重试时携带首次的值可命中幂等
 */
@Schema(name = "PasswordChangeRequest", description = "自助改密请求")
public record PasswordChangeRequest(
        @Schema(description = "当前密码") @NotBlank String oldPassword,
        @Schema(description = "新密码") @NotBlank String newPassword,
        @Schema(description = "请求标识；客户端可选，缺省由服务端生成，重试时携带首次的值可命中幂等")
        String requestId) {
}
