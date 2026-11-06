package com.zhishiku.kb.app.auth.lifecycle;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.LifecycleInboxMapper;
import com.zhishiku.kb.app.auth.mapper.LifecycleMapper;
import com.zhishiku.kb.app.auth.model.IdentityRecord;
import com.zhishiku.kb.app.auth.model.ManagementChange;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.ManagementInvariant;
import com.zhishiku.kb.common.security.IdentityConflictReason;
import com.zhishiku.kb.common.security.IdentityException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

@Service
public class LifecycleMappingService {
    private final IdentityDepartmentMapper users;
    private final LifecycleMapper mappings;
    private final LifecycleInboxMapper inbox;
    private final LifecycleAuthorizer auth;
    private final DingTalkLifecycleSettings settings;
    private final DingTalkMembership membership;
    private final LifecycleProofService proofs;
    private final LifecycleReceipts receipts;
    private final LifecycleReception reception;
    private final IdentityAuditService audit;
    private final TransactionTemplate transaction;
    private final LifecycleMappingPolicy policy;
    private final ManagementInvariant management;

    public record Verification(String source, String connectionId, String employeeId, long version, long mappingVersion) {
        @Override
        public String toString() {
            return "MappingVerification[redacted]";
        }
    }

    public record Confirmation(Verification verification, String proof, String reason, String requestId) {
        @Override
        public String toString() {
            return "MappingConfirmation[redacted]";
        }
    }

    public record Exit(long version, long mappingVersion, String reason, String requestId) {
    }

    public record Verified(String displayName, String connectionId, String proof, Instant expiresAt, long version, long mappingVersion) {
        @Override
        public String toString() {
            return "MappingVerified[redacted]";
        }
    }

    public record Mapped(long userId, boolean managed, long version, long mappingVersion, boolean manualEnabled,
                         boolean enabled, LifecycleAssignment.Restriction restriction) {
    }

    public LifecycleMappingService(IdentityDepartmentMapper users, LifecycleMapper mappings, LifecycleInboxMapper inbox,
                                   LifecycleAuthorizer auth, DingTalkLifecycleSettings settings, DingTalkMembership membership,
                                   LifecycleProofService proofs, LifecycleReceipts receipts, LifecycleReception reception,
                                   IdentityAuditService audit, PlatformTransactionManager manager) {
        this.users = users;
        this.mappings = mappings;
        this.inbox = inbox;
        this.auth = auth;
        this.settings = settings;
        this.membership = membership;
        this.proofs = proofs;
        this.receipts = receipts;
        this.reception = reception;
        this.audit = audit;
        this.transaction = new TransactionTemplate(manager);
        transaction.setTimeout(5);
        policy = new LifecycleMappingPolicy(mappings);
        management = new ManagementInvariant(users);
    }

    public Verified verify(String token, long userId, Verification input) {
        long actor = auth.require(token, Permission.IDENTITY_LIFECYCLE_MANAGE).userId();
        validate(userId, input);
        requireVersion(userId, input.version(), input.mappingVersion(), false);
        policy.requireAssignable(userId, "dingtalk", settings.enterpriseId(), input.employeeId());
        CurrentEmployee employee = membership.current(input.employeeId());
        return transaction.execute(status -> {
            management.lock();
            requireActor(token, actor);
            requireVersion(userId, input.version(), input.mappingVersion(), true);
            policy.requireAssignable(userId, "dingtalk", settings.enterpriseId(), input.employeeId());
            var issued = proofs.issue(actor, userId, "MAP", binding(userId, input), employee);
            return new Verified(employee.displayName(), settings.connectionId(), issued.proof(), issued.expiresAt(),
                    input.version(), input.mappingVersion());
        });
    }

    public Mapped map(String token, long userId, Confirmation input) {
        long actor = auth.require(token, Permission.IDENTITY_LIFECYCLE_MANAGE).userId();
        validate(userId, input.verification());
        var normalized = new Confirmation(input.verification(), input.proof(), LifecycleSupport.text(input.reason(), "reason", 500),
                LifecycleSupport.text(input.requestId(), "requestId", 128));
        return transaction.execute(status -> {
            management.lock();
            requireActor(token, actor);
            Mapped replay = receipts.replay(actor, normalized.requestId(), "MAP", Long.toString(userId), normalized, Mapped.class);
            if (replay != null) {
                return replay;
            }
            var requested = normalized.verification();
            requireVersion(userId, requested.version(), requested.mappingVersion(), true);
            requireMutable(mappings.findAssignment(userId));
            policy.requireAssignable(userId, "dingtalk", settings.enterpriseId(), requested.employeeId());
            if (mappings.restorationInFlight(userId)) {
                throw LifecycleSupport.stale();
            }
            if (!management.emergencyRemainsAfter(ManagementChange.unchanged())) {
                throw IdentityException.conflict(IdentityConflictReason.EMERGENCY_MANAGER_REQUIRED, "纳管前必须登记独立应急管理员");
            }
            var evidence = proofs.consume(normalized.proof(), actor, userId, "MAP", binding(userId, requested), CurrentEmployee.class);
            if (!Objects.equals(evidence.employeeId(), requested.employeeId()) || !Objects.equals(evidence.enterpriseId(), settings.enterpriseId())
                    || !Objects.equals(evidence.connectionId(), settings.connectionId()) || !"dingtalk".equals(evidence.source())) {
                throw LifecycleSupport.stale();
            }
            var pending = inbox.unresolvedEmployeeEvents(settings.enterpriseId(), requested.employeeId());
            for (long eventId : pending) {
                var event = inbox.findEventById(eventId);
                if (event == null || event.conflicted()) {
                    throw LifecycleSupport.stale();
                }
            }
            if (mappings.registerEmployee(userId, requested.mappingVersion(), settings.enterpriseId(), requested.employeeId(),
                    settings.connectionId(), actor) != 1) {
                throw LifecycleSupport.stale();
            }
            history(userId, actor, "map_employee", normalized.reason());
            for (long eventId : pending) {
                reception.reprocessEmployeeWithinTransaction(eventId, requested.employeeId());
            }
            var result = result(userId);
            save(actor, userId, "MAP", normalized.reason(), normalized.requestId(), normalized, result);
            return result;
        });
    }

    public Mapped exit(String token, long userId, Exit input) {
        long actor = auth.require(token, Permission.IDENTITY_LIFECYCLE_MANAGE).userId();
        validateVersions(userId, input.version(), input.mappingVersion());
        var normalized = new Exit(input.version(), input.mappingVersion(), LifecycleSupport.text(input.reason(), "reason", 500),
                LifecycleSupport.text(input.requestId(), "requestId", 128));
        return transaction.execute(status -> {
            management.lock();
            requireActor(token, actor);
            Mapped replay = receipts.replay(actor, normalized.requestId(), "UNMAP", Long.toString(userId), normalized, Mapped.class);
            if (replay != null) {
                return replay;
            }
            requireVersion(userId, input.version(), input.mappingVersion(), true);
            var assignment = mappings.findAssignment(userId);
            if (assignment == null || assignment.kind() != LifecycleAssignment.Kind.MANAGED) {
                throw LifecycleSupport.stale();
            }
            requireMutable(assignment);
            if (mappings.restorationInFlight(userId) || mappings.exitEmployee(userId, input.mappingVersion()) != 1) {
                throw LifecycleSupport.stale();
            }
            history(userId, actor, "unmap_employee", normalized.reason());
            var result = result(userId);
            save(actor, userId, "UNMAP", normalized.reason(), normalized.requestId(), normalized, result);
            return result;
        });
    }

    private void requireMutable(LifecycleAssignment current) {
        if (current != null && current.restriction() != LifecycleAssignment.Restriction.CLEAR) {
            throw IdentityException.conflict(IdentityConflictReason.LIFECYCLE_RESTRICTED, "请先处理生命周期限制");
        }
        if (current != null && current.kind() == LifecycleAssignment.Kind.MANAGED
                && !inbox.unresolvedEmployeeEvents(current.enterpriseId(), current.employeeId()).isEmpty()) {
            throw LifecycleSupport.stale();
        }
    }

    private void requireActor(String token, long actor) {
        if (auth.require(token, Permission.IDENTITY_LIFECYCLE_MANAGE).userId() != actor) {
            throw LifecycleSupport.stale();
        }
    }

    private IdentityRecord requireVersion(long userId, long version, long mappingVersion, boolean lock) {
        var assignment = lock ? mappings.lockAssignment(userId) : mappings.findAssignment(userId);
        var user = lock ? users.findIdentityForUpdate(userId) : users.findIdentity(userId);
        if (user == null) {
            throw IdentityException.notFound("用户不存在");
        }
        if (!Objects.equals(user.getVersion(), version) || (assignment == null ? -1 : assignment.version()) != mappingVersion) {
            throw LifecycleSupport.stale();
        }
        return user;
    }

    private Object binding(long user, Verification input) {
        return Map.of("userId", user, "verification", input, "sourceScope", settings.sourceScope());
    }

    private void validate(long user, Verification input) {
        if (input == null) {
            throw IdentityException.badRequest("缺少成员核验信息");
        }
        validateVersions(user, input.version(), input.mappingVersion());
        settings.requireConfigured();
        if (!"dingtalk".equals(input.source()) || !settings.connectionId().equals(input.connectionId())) {
            throw IdentityException.badRequest("生命周期来源不匹配");
        }
        String employee = LifecycleSupport.text(input.employeeId(), "employeeId", 128);
        if (!employee.equals(input.employeeId()) || employee.chars().anyMatch(Character::isISOControl)) {
            throw IdentityException.badRequest("员工标识格式无效");
        }
    }

    private static void validateVersions(long user, long version, long mappingVersion) {
        if (user <= 0 || version < 0 || mappingVersion < -1) {
            throw IdentityException.badRequest("目标或版本无效");
        }
    }

    private Mapped result(long userId) {
        var user = users.findIdentity(userId);
        var assignment = mappings.findAssignment(userId);
        var state = IdentityAccessState.from(user);
        return new Mapped(userId, assignment.kind() == LifecycleAssignment.Kind.MANAGED, user.getVersion(), assignment.version(),
                state.manualEnabled(), state.enabled(), state.restriction());
    }

    private void history(long user, long actor, String operation, String reason) {
        if (mappings.appendHistory(user, actor, operation, reason) != 1) {
            throw new IllegalStateException("映射历史写入失败");
        }
    }

    private void save(long actor, long user, String operation, String reason, String requestId, Object input, Mapped result) {
        audit.successWithReason(AuditScope.USER_LIFECYCLE_MAPPING, LifecycleSupport.digest(Map.of("actor", actor, "request", requestId)),
                actor, user, operation, "{}", LifecycleSupport.json(result), reason);
        receipts.save(actor, requestId, operation, Long.toString(user), input, result);
    }
}
