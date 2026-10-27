package com.zhishiku.kb.common.security;

/**
 * 身份与部门管理接口共用的稳定错误分类。
 */
public enum IdentityErrorCode {
    NOT_FOUND,
    FORBIDDEN,
    DISABLED,
    CONFLICT,
    INVALID_HIERARCHY,
    INVALID_PARENT,
    INVALID_VERSION,
    INVALID_REQUEST,
    UNAUTHORIZED,
    PASSWORD_CHANGE_REQUIRED
}
