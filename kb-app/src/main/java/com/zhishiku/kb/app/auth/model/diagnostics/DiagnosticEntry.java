package com.zhishiku.kb.app.auth.model.diagnostics;

import com.zhishiku.kb.common.security.PrincipalType;

/** 规范化意图条目，仅供只读存在性核对和行集叠加。 */
public record DiagnosticEntry(
        String sourceKind,
        PrincipalType principalType,
        long principalId,
        String permission
) {
    public String principalKey() {
        return principalType.code() + ":" + principalId;
    }

    public String key() {
        return sourceKind + ":" + principalKey() + ":" + permission;
    }
}
