package com.zhishiku.kb.app.auth.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 转移用户主部门请求。
 *
 * @param departmentId   目标部门内部 ID
 * @param version        客户端读取到的用户版本
 * @param requestId      幂等请求标识
 * @param clearanceLevel 只为能<b>显式拒绝</b>而接收，任何非空值都会被拒；
 *                       做法与 {@code DocumentRelocationRequest.securityLevel} 一致
 */
@Schema(name = "UserDepartmentRequest", description = "转移用户主部门请求")
public record UserDepartmentRequest(
        @Schema(description = "目标部门内部 ID") @NotNull @Positive Long departmentId,
        @Schema(description = "客户端读取到的用户版本", example = "4")
        @NotNull @PositiveOrZero Long version,
        @Schema(description = "幂等请求标识", requiredMode = Schema.RequiredMode.REQUIRED)
        String requestId,
        @Schema(description = "不接受：归属转移不修改密级，密级调整是独立的受保护操作。"
                + "本字段只为把夹带密级的请求显式拒掉而存在")
        Short clearanceLevel) {
}
