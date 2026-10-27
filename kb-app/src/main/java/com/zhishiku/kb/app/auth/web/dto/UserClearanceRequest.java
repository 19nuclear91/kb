package com.zhishiku.kb.app.auth.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 调整用户密级请求。
 *
 * <p><b>为什么 {@code clearanceLevel} 上没有取值范围注解</b>：可用密级由
 * {@code sys_security_level} 决定，级数与取值至今未由业务确认。在这里写一个
 * {@code @Max(3)} 会把当前的字典内容固化成接口契约——字典增加第 5 级时，
 * 请求会在进入服务层之前就被拒绝，而拒绝理由与字典毫无关系，排查时根本看不出
 * 校验发生在哪一层。取值判定只在 {@code SecurityLevelDictionary} 一处。
 *
 * <p>{@code version} 的 {@code @PositiveOrZero} 不同：它是乐观锁计数器的结构属性
 * （{@code version} 从 0 起自增），与业务上有几级密级无关。
 *
 * @param clearanceLevel 目标密级，必须是服务端密级字典中已存在的级别
 * @param version        客户端读取到的用户版本
 * @param requestId      幂等请求标识
 */
@Schema(name = "UserClearanceRequest", description = "调整用户密级请求")
public record UserClearanceRequest(
        @Schema(description = "目标密级，取值必须存在于服务端密级字典", example = "2",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Short clearanceLevel,
        @Schema(description = "客户端读取到的用户版本", example = "4",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @PositiveOrZero Long version,
        @Schema(description = "幂等请求标识", requiredMode = Schema.RequiredMode.REQUIRED)
        String requestId) {
}
