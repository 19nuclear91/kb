package com.zhishiku.kb.common.security;

import com.zhishiku.kb.common.api.ApiException;

import java.util.Optional;

/**
 * 身份与部门操作领域的异常。
 *
 * <p>common 模块刻意只暴露原始 HTTP 状态数据，以保持对 Spring Web 的零依赖；
 * 由 app 模块负责把它映射成响应。
 */
public final class IdentityException extends ApiException {

    private final IdentityErrorCode errorCode;
    private final int httpStatus;
    private final IdentityConflictReason reason;

    public IdentityException(IdentityErrorCode errorCode, int httpStatus, String message) {
        this(errorCode, httpStatus, message, null);
    }

    private IdentityException(IdentityErrorCode errorCode, int httpStatus, String message,
                              IdentityConflictReason reason) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
        this.reason = reason;
    }

    public IdentityErrorCode errorCode() {
        return errorCode;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /**
     * 检查点显式给出的细分冲突原因；未提供时为空，调用方应回退到 errorCode。
     */
    public Optional<IdentityConflictReason> reason() {
        return Optional.ofNullable(reason);
    }

    public static IdentityException notFound(String message) {
        return new IdentityException(IdentityErrorCode.NOT_FOUND, 404, message);
    }

    public static IdentityException forbidden(String message) {
        return new IdentityException(IdentityErrorCode.FORBIDDEN, 403, message);
    }

    public static IdentityException disabled(String message) {
        return new IdentityException(IdentityErrorCode.DISABLED, 403, message);
    }

    public static IdentityException conflict(IdentityErrorCode code, String message) {
        if (code != IdentityErrorCode.CONFLICT
                && code != IdentityErrorCode.INVALID_HIERARCHY
                && code != IdentityErrorCode.INVALID_PARENT
                && code != IdentityErrorCode.INVALID_VERSION) {
            throw new IllegalArgumentException("冲突异常必须使用冲突类错误码: " + code);
        }
        return new IdentityException(code, 409, message);
    }

    /**
     * 通用 CONFLICT 的细分形式：errorCode 与 HTTP 状态保持 CONFLICT/409 不变。
     */
    public static IdentityException conflict(IdentityConflictReason reason, String message) {
        if (reason == null) throw new IllegalArgumentException("冲突原因不能为空");
        return new IdentityException(IdentityErrorCode.CONFLICT, 409, message, reason);
    }

    public static IdentityException badRequest(String message) {
        return new IdentityException(IdentityErrorCode.INVALID_REQUEST, 400, message);
    }

    /**
     * 未认证：无会话或凭据校验失败。
     *
     * <p>与 {@link #forbidden} 严格区分：403 表示"已认证但无资格"，
     * 401 表示"根本没有可用的身份"。把两者混同会同时误导调用方与用户——
     * 前者应引导重新登录，后者应引导申请权限。
     */
    public static IdentityException unauthorized(String message) {
        return new IdentityException(IdentityErrorCode.UNAUTHORIZED, 401, message);
    }

    /**
     * 已认证但处于强制改密状态。
     *
     * <p>既不是未认证（会话有效）也不是无资格（完成改密后即可），
     * 因此用独立错误码让前端能精确识别并跳转改密引导。
     */
    public static IdentityException passwordChangeRequired(String message) {
        return new IdentityException(IdentityErrorCode.PASSWORD_CHANGE_REQUIRED, 403, message);
    }
}
