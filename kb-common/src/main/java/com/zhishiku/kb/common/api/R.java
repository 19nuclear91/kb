package com.zhishiku.kb.common.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 统一响应封装。
 *
 * <p>网关与各服务共用同一结构，便于前端统一处理（见《架构决策记录.md》2.1）。
 *
 * <p><b>注意</b>：SSE 流式接口不得套用本结构。流式响应需要逐段下发，
 * 包进统一响应体会导致内容被缓冲成一坨后才返回（决策记录第 8 章）。
 *
 * @param <T>     业务数据类型；无数据成功响应时为 {@code Void}
 * @param code    业务状态码
 * @param message 面向调用方的状态消息
 * @param data    业务数据；失败响应通常为空
 */
@Schema(name = "ApiResponse", description = "统一接口响应。code=200 表示成功，其他情况统一为 500")
public record R<T>(
        @Schema(description = "业务状态码，200 表示成功，500 表示失败", example = "200") int code,
        @Schema(description = "响应消息", example = "ok") String message,
        @Schema(description = "业务数据") T data) {

    public static final int SUCCESS = 200;
    public static final int FAILURE = 500;

    public static <T> R<T> ok(T data) {
        return new R<>(SUCCESS, "ok", data);
    }

    public static R<Void> ok() {
        return new R<>(SUCCESS, "ok", null);
    }

    public static <T> R<T> fail(String message) {
        return new R<>(FAILURE, message, null);
    }

    /**
     * 兼容旧调用方；业务错误码不再细分，传入的 code 会被统一折叠为 500。
     */
    @Deprecated
    public static <T> R<T> fail(int ignoredCode, String message) {
        return fail(message);
    }

    public boolean isOk() {
        return code == SUCCESS;
    }
}
