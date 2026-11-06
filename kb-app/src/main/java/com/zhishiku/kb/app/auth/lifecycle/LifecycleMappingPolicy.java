package com.zhishiku.kb.app.auth.lifecycle;

import com.zhishiku.kb.common.security.IdentityConflictReason;
import com.zhishiku.kb.common.security.IdentityException;

import java.util.Objects;

/** 纳管与应急登记共用的归属检查，不按个人资料推断关联。 */
public final class LifecycleMappingPolicy {
    private final LifecycleAssignments assignments;

    public LifecycleMappingPolicy(LifecycleAssignments assignments) {
        this.assignments = Objects.requireNonNull(assignments);
    }

    public void requireAssignable(long userId, String source, String enterpriseId, String employeeId) {
        LifecycleAssignment current = assignments.findAssignment(userId);
        if (current != null && current.kind() == LifecycleAssignment.Kind.EMERGENCY) {
            throw IdentityException.conflict(IdentityConflictReason.EMERGENCY_MANAGER_REQUIRED,
                    "应急管理员不能纳入员工同步");
        }
        if (current != null && current.restriction() != LifecycleAssignment.Restriction.CLEAR) {
            throw IdentityException.conflict(IdentityConflictReason.LIFECYCLE_RESTRICTED,
                    "请先处理账号生命周期限制");
        }
        LifecycleAssignment owner = assignments.findEmployee(source, enterpriseId, employeeId);
        if (owner != null && owner.userId() != userId) {
            throw IdentityException.conflict(IdentityConflictReason.IDENTIFIER_CONFLICT,
                    "该员工已有内部账号关联");
        }
    }

    public void requireEmergencyCandidate(long userId) {
        LifecycleAssignment current = assignments.findAssignment(userId);
        if (current != null && (current.kind() == LifecycleAssignment.Kind.MANAGED
                || current.restriction() != LifecycleAssignment.Restriction.CLEAR)) {
            throw IdentityException.conflict(IdentityConflictReason.EMERGENCY_MANAGER_REQUIRED,
                    "受管或受限账号不能登记为应急管理员");
        }
    }
}
