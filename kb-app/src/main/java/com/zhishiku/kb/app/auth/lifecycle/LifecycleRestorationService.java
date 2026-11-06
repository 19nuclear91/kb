package com.zhishiku.kb.app.auth.lifecycle;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.LifecycleInboxMapper;
import com.zhishiku.kb.app.auth.mapper.LifecycleMapper;
import com.zhishiku.kb.app.auth.model.ManagementChange;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.ManagementInvariant;
import com.zhishiku.kb.app.auth.service.SecurityLevelDictionary;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.RoleSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class LifecycleRestorationService {
    private final IdentityDepartmentMapper users;
    private final LifecycleMapper mappings;
    private final LifecycleInboxMapper inbox;
    private final LifecycleAuthorizer auth;
    private final DingTalkLifecycleSettings settings;
    private final DingTalkMembership membership;
    private final LifecycleProofService proofs;
    private final LifecycleReceipts receipts;
    private final LifecycleStateService states;
    private final SecurityLevelDictionary levels;
    private final ManagementInvariant management;
    private final TransactionTemplate transaction;

    public record Check(long version, long mappingVersion) {
    }

    public record Confirmation(long version, long mappingVersion, String proof, String reason, String requestId) {
        @Override
        public String toString() {
            return "RestorationConfirmation[redacted]";
        }
    }

    public record Verified(String proof, Instant expiresAt, long version, long mappingVersion, String employeeName,
                           long departmentId, String departmentName, short clearanceLevel, List<RoleSummary> roles,
                           boolean manualEnabled, LifecycleAssignment.Restriction restriction, long eventBoundary) {
        @Override
        public String toString() {
            return "RestorationVerified[redacted]";
        }
    }

    public record Restored(long userId, long version, long mappingVersion, boolean manualEnabled, boolean enabled,
                           boolean passwordChangeRequired, String outcome) {
    }

    public record Evidence(Instant checkedAt) {
    }

    private record Snapshot(long userId, long version, long mappingVersion, String sourceScope, String enterpriseId,
                            String employeeId, long epoch, boolean manualEnabled, LifecycleAssignment.Restriction restriction,
                            long departmentId, long departmentVersion, String departmentName, short clearanceLevel,
                            List<RoleSummary> roles, List<String> permissions, long roleFactRevision,
                            boolean passwordChangeRequired, Long lastEventId, LifecycleEventBoundary boundary) {
        @Override
        public String toString() {
            return "RestorationSnapshot[redacted]";
        }
    }

    public LifecycleRestorationService(IdentityDepartmentMapper users, LifecycleMapper mappings, LifecycleInboxMapper inbox,
                                       LifecycleAuthorizer auth, DingTalkLifecycleSettings settings, DingTalkMembership membership,
                                       LifecycleProofService proofs, LifecycleReceipts receipts, LifecycleStateService states,
                                       SecurityLevelDictionary levels, PlatformTransactionManager manager) {
        this.users = users;
        this.mappings = mappings;
        this.inbox = inbox;
        this.auth = auth;
        this.settings = settings;
        this.membership = membership;
        this.proofs = proofs;
        this.receipts = receipts;
        this.states = states;
        this.levels = levels;
        management = new ManagementInvariant(users);
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(5);
    }

    public Verified check(String token, long userId, Check input) {
        long actor = auth.require(token, Permission.IDENTITY_LIFECYCLE_RESTORE).userId();
        validate(userId, input.version(), input.mappingVersion());
        Snapshot before = snapshot(userId, input.version(), input.mappingVersion(), false);
        CurrentEmployee member = currentMember(before);
        return transaction.execute(status -> {
            management.lock();
            requireActor(token, actor);
            var current = snapshot(userId, input.version(), input.mappingVersion(), true);
            if (!LifecycleSupport.digest(before).equals(LifecycleSupport.digest(current))) {
                throw LifecycleSupport.stale();
            }
            var issued = proofs.issue(actor, userId, "RESTORE", current, new Evidence(member.checkedAt()));
            return new Verified(issued.proof(), issued.expiresAt(), current.version(), current.mappingVersion(), member.displayName(),
                    current.departmentId(), current.departmentName(), current.clearanceLevel(), current.roles(),
                    current.manualEnabled(), current.restriction(), current.boundary().lastEventId());
        });
    }

    public Restored restore(String token, long userId, Confirmation input) {
        long actor = auth.require(token, Permission.IDENTITY_LIFECYCLE_RESTORE).userId();
        validate(userId, input.version(), input.mappingVersion());
        var normalized = new Confirmation(input.version(), input.mappingVersion(), input.proof(),
                LifecycleSupport.text(input.reason(), "reason", 500), LifecycleSupport.text(input.requestId(), "requestId", 128));
        // 匹配重放不要求平台仍可达；但必须重新鉴权，且回执绑定完全相同的请求。
        Object prepared = transaction.execute(status -> {
            management.lock();
            requireActor(token, actor);
            Restored replay = replay(actor, userId, normalized);
            if (replay != null) {
                return replay;
            }
            return snapshot(userId, input.version(), input.mappingVersion(), true);
        });
        if (prepared instanceof Restored replay) {
            return replay;
        }
        var before = (Snapshot) Objects.requireNonNull(prepared);
        // 外部查询在事务之外；不能因为旧核对成功或本次失败而跳过再次核验。
        CurrentEmployee member = currentMember(before);
        return transaction.execute(status -> {
            management.lock();
            requireActor(token, actor);
            Restored replay = replay(actor, userId, normalized);
            if (replay != null) {
                return replay;
            }
            Snapshot current = snapshot(userId, input.version(), input.mappingVersion(), true);
            if (!LifecycleSupport.digest(before).equals(LifecycleSupport.digest(current))) {
                throw LifecycleSupport.stale();
            }
            Evidence evidence = proofs.consume(normalized.proof(), actor, userId, "RESTORE", current, Evidence.class);
            if (evidence.checkedAt() == null || member.checkedAt().isBefore(evidence.checkedAt())) {
                throw LifecycleSupport.stale();
            }
            management.requireEmergencyAfter(ManagementChange.unchanged());
            String request = LifecycleSupport.digest(Map.of("actor", actor, "request", normalized.requestId()));
            var restored = states.restore(userId, current.mappingVersion(), member.checkedAt(), actor, request, normalized.reason());
            inbox.resolveReview(userId, current.boundary().lastEventId(), member.checkedAt());
            mappings.invalidateRestorationProofs(userId);
            String outcome = restored.manualEnabled() ? "RESTORED_RELOGIN_REQUIRED" : "RESTORED_MANUALLY_DISABLED";
            var result = new Restored(userId, current.version() + 1, current.mappingVersion() + 1,
                    restored.manualEnabled(), restored.enabled(), current.passwordChangeRequired(), outcome);
            receipts.save(actor, normalized.requestId(), "RESTORE", Long.toString(userId), normalized, result);
            return result;
        });
    }

    private Restored replay(long actor, long userId, Confirmation normalized) {
        return receipts.replay(actor, normalized.requestId(), "RESTORE", Long.toString(userId), normalized, Restored.class);
    }

    private CurrentEmployee currentMember(Snapshot expected) {
        var member = membership.current(expected.employeeId());
        if (member == null || member.checkedAt() == null || !"dingtalk".equals(member.source())
                || !expected.enterpriseId().equals(member.enterpriseId()) || !expected.employeeId().equals(member.employeeId())
                || !settings.connectionId().equals(member.connectionId())) {
            throw LifecycleFailures.unavailable();
        }
        return member;
    }

    private void requireActor(String token, long actor) {
        if (auth.require(token, Permission.IDENTITY_LIFECYCLE_RESTORE).userId() != actor) {
            throw LifecycleSupport.stale();
        }
    }

    private Snapshot snapshot(long userId, long version, long mappingVersion, boolean lock) {
        var mapping = lock ? mappings.lockAssignment(userId) : mappings.findAssignment(userId);
        var user = lock ? users.findIdentityForUpdate(userId) : users.findIdentity(userId);
        if (user == null) {
            throw IdentityException.notFound("用户不存在");
        }
        if (mapping == null || mapping.kind() != LifecycleAssignment.Kind.MANAGED || mapping.version() != mappingVersion
                || !Objects.equals(user.getVersion(), version) || !"dingtalk".equals(mapping.source())
                || !Objects.equals(settings.enterpriseId(), mapping.enterpriseId())
                || !Objects.equals(settings.connectionId(), mapping.connectionId())) {
            throw LifecycleSupport.stale();
        }
        var state = IdentityAccessState.from(user);
        if (state.restriction() == LifecycleAssignment.Restriction.CLEAR || state.restriction() != mapping.restriction()) {
            throw LifecycleSupport.stale();
        }
        var department = user.getDepartmentId() == null ? null : users.findDepartment(user.getDepartmentId());
        if (department == null || !Boolean.TRUE.equals(department.getEnabled()) || department.getVersion() == null
                || !levels.contains(user.getClearanceLevel())) {
            throw LifecycleSupport.stale();
        }
        var boundary = inbox.boundary(mapping.enterpriseId(), mapping.employeeId());
        if (boundary == null || boundary.lastEventId() <= 0 || boundary.pending() > 0 || mapping.lastEventId() == null) {
            throw LifecycleSupport.stale();
        }
        var roles = List.copyOf(mappings.userRoles(userId));
        var permissions = users.findUserManagementPermissions(userId).stream()
                .map(fact -> fact.roleId() + ":" + fact.permission()).sorted().toList();
        Boolean mustChange = users.findMustChangePasswordById(userId);
        if (mustChange == null) {
            throw LifecycleFailures.unavailable();
        }
        return new Snapshot(userId, version, mappingVersion, settings.sourceScope(), mapping.enterpriseId(), mapping.employeeId(),
                state.authEpoch(), state.manualEnabled(), state.restriction(), department.getId(), department.getVersion(),
                department.getName(), user.getClearanceLevel(), roles, permissions, mappings.roleFactRevision(userId),
                mustChange, mapping.lastEventId(), boundary);
    }

    private static void validate(long user, long version, long mappingVersion) {
        if (user <= 0 || version < 0 || mappingVersion < 0) {
            throw IdentityException.badRequest("目标或版本无效");
        }
    }
}
