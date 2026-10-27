package com.zhishiku.kb.app.auth.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 绑定外部身份请求。
 *
 * @param provider  认证提供方
 * @param subject   外部 subject，仅作为服务端查找键，不在响应中回显
 * @param requestId 幂等请求标识
 */
@Schema(name = "ExternalIdentityRequest", description = "绑定外部身份请求")
public record ExternalIdentityRequest(
        @Schema(description = "认证提供方", example = "oidc") @NotBlank String provider,
        @Schema(description = "外部 subject；仅服务端查找，不在响应中回显")
        @NotBlank String subject,
        @Schema(description = "幂等请求标识", requiredMode = Schema.RequiredMode.REQUIRED)
        String requestId) {
}
