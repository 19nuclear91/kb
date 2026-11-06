package com.zhishiku.kb.app.auth.lifecycle;

import com.zhishiku.kb.app.auth.model.IdentityRecord;
import com.zhishiku.kb.common.security.IdentityConflictReason;
import com.zhishiku.kb.common.security.IdentityException;

import java.util.Objects;

/** 人工状态和外部限制共同决定访问资格，任何撤销都不能复用旧认证代次。 */
public record IdentityAccessState(boolean manualEnabled, LifecycleAssignment.Restriction restriction,
                                  long authEpoch) {
    public IdentityAccessState {
        Objects.requireNonNull(restriction, "restriction");
        if (authEpoch < 1) {
            throw new IllegalStateException("认证代次无效");
        }
    }

    public static IdentityAccessState from(IdentityRecord user) {
        if (user.getManualEnabled() == null || user.getAuthEpoch() == null) {
            throw new IllegalStateException("身份访问状态不完整");
        }
        IdentityAccessState state = new IdentityAccessState(user.getManualEnabled(),
                LifecycleAssignment.Restriction.valueOf(user.getLifecycleRestriction()), user.getAuthEpoch());
        if (!Objects.equals(user.getEnabled(), state.enabled())) {
            throw new IllegalStateException("身份访问状态不一致");
        }
        return state;
    }

    public boolean enabled() {
        return manualEnabled && restriction == LifecycleAssignment.Restriction.CLEAR;
    }

    public IdentityAccessState manual(boolean enabled) {
        if (enabled && restriction != LifecycleAssignment.Restriction.CLEAR) {
            throw IdentityException.conflict(IdentityConflictReason.LIFECYCLE_RESTRICTED,
                    "账号存在生命周期限制，需先完成复职核对");
        }
        return enabled == manualEnabled ? this : new IdentityAccessState(enabled, restriction,
                Math.incrementExact(authEpoch));
    }

    public IdentityAccessState restrict(LifecycleAssignment.Restriction next) {
        if (next == LifecycleAssignment.Restriction.CLEAR) {
            throw new IllegalArgumentException("解除限制必须使用复职入口");
        }
        return restriction == next ? this : new IdentityAccessState(manualEnabled, next,
                Math.incrementExact(authEpoch));
    }

    public IdentityAccessState restore() {
        if (restriction == LifecycleAssignment.Restriction.CLEAR) {
            throw IdentityException.conflict(IdentityConflictReason.LIFECYCLE_CONFIRMATION_STALE,
                    "账号已不存在生命周期限制");
        }
        return new IdentityAccessState(manualEnabled, LifecycleAssignment.Restriction.CLEAR,
                Math.incrementExact(authEpoch));
    }

    public String auditJson() {
        return "{\"manualEnabled\":" + manualEnabled + ",\"enabled\":" + enabled()
                + ",\"restriction\":\"" + restriction + "\",\"authEpoch\":" + authEpoch + "}";
    }
}
