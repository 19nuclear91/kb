package com.zhishiku.kb.ai.auth;

/** 固定代码，不携带用户、令牌或上游异常文本。 */
public final class OutputRevokedException extends RuntimeException {
    private final String code;

    public OutputRevokedException(String code) {
        super("当前执行的访问许可已失效，请重新登录或稍后重试。");
        this.code = code;
    }

    public String code() {
        return code;
    }
}
