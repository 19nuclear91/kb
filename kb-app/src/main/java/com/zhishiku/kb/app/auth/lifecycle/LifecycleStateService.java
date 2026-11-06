package com.zhishiku.kb.app.auth.lifecycle;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.LifecycleMapper;
import com.zhishiku.kb.app.auth.model.IdentityRecord;
import com.zhishiku.kb.app.auth.model.ManagementChange;
import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.ManagementInvariant;
import com.zhishiku.kb.common.security.IdentityConflictReason;
import com.zhishiku.kb.common.security.IdentityException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.zhishiku.kb.app.auth.mapper.OutputStopEvidenceMapper;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

/** 仅供已完成事件去重或复职凭据验证的事务调用，不能作为普通启用入口。 */
@Service
public class LifecycleStateService {
    private final IdentityDepartmentMapper users;
    private final LifecycleMapper lifecycle;
    private final IdentityAuditService audit;
    private final ManagementInvariant management;
    private OutputStopEvidenceMapper evidence;

    @Autowired
    public void setEvidence(OutputStopEvidenceMapper evidence) {
        this.evidence = evidence;
    }

    public LifecycleStateService(IdentityDepartmentMapper users, LifecycleMapper lifecycle,
                                 IdentityAuditService audit) {
        this.users = users;
        this.lifecycle = lifecycle;
        this.audit = audit;
        this.management = new ManagementInvariant(users);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public IdentityAccessState restrict(long userId, long mappingVersion, long eventId,
                                         LifecycleAssignment.Restriction restriction, String requestId) {
        return restrict(userId, mappingVersion, eventId, restriction, requestId, false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public IdentityAccessState restrict(long userId, long mappingVersion, long eventId,
                                         LifecycleAssignment.Restriction restriction, String requestId, boolean initialReception) {
        management.lock();
        LifecycleAssignment assignment = locked(userId, mappingVersion);
        IdentityRecord user = users.findIdentityForUpdate(userId);
        IdentityAccessState before = IdentityAccessState.from(Objects.requireNonNull(user));
        // 管理入口已保护保障；这里的缺失意味着外部损坏，普通离职账号仍必须被拒绝访问。
        // 将处置降为 REVIEW 以触发人工修复，不能以旧“最后管理员”异常回滚停权事实。
        boolean guaranteeBroken = !management.emergencyRemainsAfter(ManagementChange.disableUser(userId))
                || !management.remainsAfter(ManagementChange.disableUser(userId));
        if (guaranteeBroken) {
            restriction = LifecycleAssignment.Restriction.REVIEW;
        }
        IdentityAccessState after = before.restrict(restriction);
        if (Objects.equals(assignment.lastEventId(), eventId) && before.equals(after)) {
            return before;
        }
        persist(user, assignment, before, after, eventId, assignment.restoredAt(), null,
                "restrict", requestId, guaranteeBroken ? "应急保障异常，隔离受管账号等待核对" : "企业离职事件限制访问",
                AuditScope.USER_LIFECYCLE_RESTRICT);
        if (evidence != null && before.authEpoch() != after.authEpoch()
                && evidence.revoke(userId, before.authEpoch(), eventId, initialReception) != 1) {
            throw new IllegalStateException("撤销证据写入失败");
        }
        return after;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public IdentityAccessState restore(long userId, long mappingVersion, Instant restoredAt,
                                        long actor, String requestId, String reason) {
        management.lock();
        LifecycleAssignment assignment = locked(userId, mappingVersion);
        IdentityRecord user = users.findIdentityForUpdate(userId);
        IdentityAccessState before = IdentityAccessState.from(Objects.requireNonNull(user));
        IdentityAccessState after = before.restore();
        persist(user, assignment, before, after, assignment.lastEventId(), Objects.requireNonNull(restoredAt),
                actor, "restore", requestId, reason, AuditScope.USER_LIFECYCLE_RESTORE);
        return after;
    }

    private LifecycleAssignment locked(long userId, long version) {
        LifecycleAssignment assignment = lifecycle.lockAssignment(userId);
        if (assignment == null || assignment.kind() != LifecycleAssignment.Kind.MANAGED
                || assignment.version() != version) {
            throw IdentityException.conflict(IdentityConflictReason.LIFECYCLE_CONFIRMATION_STALE,
                    "员工映射已变化");
        }
        return assignment;
    }

    private void persist(IdentityRecord user, LifecycleAssignment assignment, IdentityAccessState before,
                         IdentityAccessState after, Long eventId, Instant restoredAt, Long actor,
                         String operation, String requestId, String reason, AuditScope scope) {
        if (lifecycle.updateRestriction(user.getId(), assignment.version(), after.restriction(), eventId,
                restoredAt) != 1) {
            throw IdentityException.conflict(IdentityConflictReason.LIFECYCLE_CONFIRMATION_STALE,
                    "员工映射已变化");
        }
        if (users.setUserAccessState(user.getId(), after.manualEnabled(), after.enabled(), after.authEpoch(),
                user.getVersion()) != 1) {
            throw IdentityException.conflict(IdentityConflictReason.LIFECYCLE_CONFIRMATION_STALE,
                    "用户状态已变化");
        }
        if (before.authEpoch() != after.authEpoch()) {
            users.enqueueSessionCleanup(user.getId(), before.authEpoch());
        }
        if (lifecycle.appendHistory(user.getId(), actor, operation, reason) != 1) {
            throw new IllegalStateException("生命周期历史写入失败");
        }
        audit.successWithReason(scope, requestId, actor, user.getId(), operation,
                before.auditJson(), after.auditJson(), reason);
    }
}
