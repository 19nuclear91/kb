package com.zhishiku.kb.common.security;

import java.time.Instant;

/** 内部认证结果不授予资源权限，也不是可跨请求缓存的通行凭证。 */
public record AuthenticationCheck(long userId, long authEpoch, boolean mustChangePassword, Instant checkedAt) {
    public AuthenticationCheck(long userId, long authEpoch, boolean mustChangePassword) {
        this(userId, authEpoch, mustChangePassword, null);
    }
    public AuthenticationCheck {
        if (userId <= 0 || authEpoch <= 0) {
            throw new IllegalArgumentException("认证检查结果无效");
        }
    }
}
