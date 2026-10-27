package com.zhishiku.kb.common.security;

/**
 * 跨网关、业务服务和 AI 服务统一使用的认证请求头。
 */
public final class AuthHeaders {

    /**
     * Sa-Token 默认请求头名；必须与各服务的 sa-token.token-name 保持一致。
     */
    public static final String SA_TOKEN = "satoken";

    private AuthHeaders() {
    }
}
