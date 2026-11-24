package com.zhishiku.kb.app.search.assistance;

import com.zhishiku.kb.common.security.IdentityException;

/** 仅承载本次认证属性，消费或退出时释放原令牌，不读取线程隐式身份。 */
public final class AssistedCaller {
    private final long userId;
    private final long authEpoch;
    private String token;

    public AssistedCaller(long userId, long authEpoch, String verifiedToken) {
        if (userId <= 0 || authEpoch <= 0) {
            throw IdentityException.unauthorized("登录状态无效");
        }
        this.userId = userId;
        this.authEpoch = authEpoch;
        this.token = verifiedToken;
    }

    public long userId() {
        return userId;
    }

    public long authEpoch() {
        return authEpoch;
    }

    public synchronized String consumeToken() {
        String value = token;
        token = null;
        if (value == null || value.isEmpty()) {
            throw IdentityException.unauthorized("登录状态无效");
        }
        return value;
    }

    public synchronized void discardToken() {
        token = null;
    }

    @Override
    public String toString() {
        return "AssistedCaller[redacted]";
    }
}
