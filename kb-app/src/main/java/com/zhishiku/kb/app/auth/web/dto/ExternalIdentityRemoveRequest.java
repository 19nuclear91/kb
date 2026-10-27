package com.zhishiku.kb.app.auth.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 解除外部身份绑定请求。
 *
 * @param identityId 外部身份映射内部 ID
 * @param requestId  幂等请求标识
 */
@Schema(name = "ExternalIdentityRemoveRequest", description = "解除外部身份请求")
public record ExternalIdentityRemoveRequest(
        @Schema(description = "外部身份映射内部 ID")
        @NotNull @Positive Long identityId,
        @Schema(description = "幂等请求标识", requiredMode = Schema.RequiredMode.REQUIRED)
        String requestId) {
}
