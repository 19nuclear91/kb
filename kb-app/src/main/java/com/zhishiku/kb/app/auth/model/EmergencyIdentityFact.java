package com.zhishiku.kb.app.auth.model;

/** 资格判断只需要本地凭据是否存在，不读取或传播密码哈希。 */
public record EmergencyIdentityFact(long userId, Long departmentId, String kind, boolean enabled,
                                     boolean manualEnabled, boolean departmentEnabled, boolean localPassword,
                                     boolean mustChangePassword, String restriction) {
    public boolean eligible() {
        return ("NONE".equals(kind) || "EMERGENCY".equals(kind)) && enabled && manualEnabled
                && departmentId != null && departmentEnabled && localPassword && !mustChangePassword
                && "CLEAR".equals(restriction);
    }
}
