package com.zhishiku.kb.common.security;

import com.zhishiku.kb.common.api.ApiException;

import java.util.List;

/** 仅由已通过服务认证的身份检查返回，不以普通 HTTP 拒绝推断账号撤销。 */
public final class AccountRevokedException extends ApiException {
    public static final String HEADER = "X-Auth-Result";
    public static final String CODE = "ACCOUNT_REVOKED";
    private final int status;

    public AccountRevokedException(int status) {
        super("当前账号认证资格已失效");
        if (status != 401 && status != 403) {
            throw new IllegalArgumentException("账号撤销只能使用 401 或 403");
        }
        this.status = status;
    }

    @Override
    public int httpStatus() {
        return status;
    }

    public static boolean confirmed(int status, List<String> values) {
        return (status == 401 || status == 403) && values != null
                && values.size() == 1 && CODE.equals(values.getFirst());
    }
}
