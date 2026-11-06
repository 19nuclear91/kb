package com.zhishiku.kb.app.auth.model;

import java.time.Instant;
import org.apache.ibatis.annotations.AutomapConstructor;

/** 一次数据库读取获得的最小认证事实，不包含角色和资源权限。 */
public record AuthenticationIdentity(long userId, long authEpoch, boolean enabled,
                                     boolean departmentEnabled, boolean mustChangePassword, Instant checkedAt) {
    @AutomapConstructor
    public AuthenticationIdentity {
    }

    public AuthenticationIdentity(long userId, long authEpoch, boolean enabled, boolean departmentEnabled, boolean mustChangePassword) {
        this(userId, authEpoch, enabled, departmentEnabled, mustChangePassword, null);
    }
}
