package com.zhishiku.kb.app.auth.sso;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 企业浏览器认证的最小 HTTP 投影。
 */
public final class SsoViews {
    private SsoViews() {
    }

    /**
     * 可发起登录的连接。
     *
     * @param connectionId 固定连接键
     * @param platform     固定平台枚举
     */
    @Schema(description = "启用的企业登录连接")
    public record Provider(@Schema(description = "固定连接键") String connectionId,
                           @Schema(description = "固定平台枚举") String platform) {
    }

    /**
     * 浏览器顶层导航目标。
     *
     * @param authorizationUrl 服务端生成的官方授权地址
     */
    @Schema(description = "授权发起结果")
    public record Authorization(@Schema(description = "服务端生成的官方授权地址") String authorizationUrl) {
    }

    /**
     * 本人绑定状态，不含外部身份或管理员记录 ID。
     *
     * @param connectionId      固定连接键
     * @param platform          固定平台枚举
     * @param connectionEnabled 是否允许发起新授权
     * @param bound             当前作用域是否存在启用绑定
     */
    @Schema(description = "本人企业身份绑定状态")
    public record BindingStatus(@Schema(description = "固定连接键") String connectionId,
                                @Schema(description = "固定平台枚举") String platform,
                                @Schema(description = "是否允许发起新授权") boolean connectionEnabled,
                                @Schema(description = "当前作用域是否存在启用绑定") boolean bound) {
    }

    /**
     * 绑定完成结果。
     *
     * @param connectionId 固定连接键
     * @param platform     固定平台枚举
     * @param bound        已完成启用绑定
     */
    @Schema(description = "本人绑定完成")
    public record Bound(@Schema(description = "固定连接键") String connectionId,
                        @Schema(description = "固定平台枚举") String platform,
                        @Schema(description = "已完成启用绑定") boolean bound) {
    }

    /**
     * 固定错误类别与服务端关联标识。
     *
     * @param errorCode     固定错误类别
     * @param correlationId 不含凭据的服务端随机关联 ID
     */
    @Schema(description = "企业认证错误数据")
    public record Error(@Schema(description = "固定错误类别") String errorCode,
                        @Schema(description = "服务端随机关联 ID") String correlationId) {
    }
}
