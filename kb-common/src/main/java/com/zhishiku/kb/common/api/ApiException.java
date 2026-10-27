package com.zhishiku.kb.common.api;

/**
 * 业务异常。由全局异常处理器转换为统一响应。
 */
public class ApiException extends RuntimeException {
    private final int httpStatus;

    public ApiException(String message) {
        this(message, 500);
    }

    private ApiException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /**
     * 兼容旧构造方式；错误码不再参与对外响应。
     */
    @Deprecated
    public ApiException(int ignoredCode, String message) {
        this(message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(message, 403);
    }

    public static ApiException notFound(String message) {
        return new ApiException(message, 404);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(message, 400);
    }

    public static ApiException conflict(String message) {
        return new ApiException(message, 409);
    }

    public static ApiException unavailable(String message) {
        return new ApiException(message, 503);
    }

    public static ApiException unsupportedMediaType(String message) {
        return new ApiException(message, 415);
    }
}
