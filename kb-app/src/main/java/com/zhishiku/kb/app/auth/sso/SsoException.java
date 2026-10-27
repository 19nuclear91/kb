package com.zhishiku.kb.app.auth.sso;

/**
 * 仅携带固定错误类别，禁止包含平台响应或认证凭据。
 */
public final class SsoException extends RuntimeException {
    private final int status;
    private final String code;

    public SsoException(int status, String code) {
        super("企业认证请求未完成");
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static SsoException invalid() {
        return new SsoException(400, "INVALID_FLOW");
    }

    public static SsoException unavailable() {
        return new SsoException(503, "UPSTREAM_UNAVAILABLE");
    }

    public static SsoException storage() {
        return new SsoException(503, "STORAGE_UNAVAILABLE");
    }

    public static SsoException denied() {
        return new SsoException(403, "NOT_MEMBER");
    }
}
