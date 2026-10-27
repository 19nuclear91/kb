package com.zhishiku.kb.app.auth.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 启用状态变更请求。
 *
 * @param enabled        目标启用状态
 * @param version        客户端读取到的对象版本
 * @param requestId      幂等请求标识
 * @param clearanceLevel 只为能<b>显式拒绝</b>而接收，任何非空值都会被拒；
 *                       做法与 {@code DocumentRelocationRequest.securityLevel} 一致
 */
@Schema(name = "EnabledRequest", description = "启用状态变更请求")
public record EnabledRequest(
        @Schema(description = "是否启用") @NotNull Boolean enabled,
        @Schema(description = "客户端读取到的版本", example = "0")
        @NotNull @PositiveOrZero Long version,
        @Schema(description = "幂等请求标识", requiredMode = Schema.RequiredMode.REQUIRED)
        String requestId,
        @Schema(description = "不接受：启停不修改密级，密级调整是独立的受保护操作。"
                + "本字段只为把夹带密级的请求显式拒掉而存在")
        Short clearanceLevel) {
}
