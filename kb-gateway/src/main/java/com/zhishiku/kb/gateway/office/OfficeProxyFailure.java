package com.zhishiku.kb.gateway.office;

public final class OfficeProxyFailure extends RuntimeException {
    private final int status;

    public OfficeProxyFailure(int status) {
        super("Office 预览请求未完成");
        this.status = switch (status) {
            case 400, 401, 403, 404, 415, 429 -> status;
            default -> 503;
        };
    }

    public int status() {
        return status;
    }
}
