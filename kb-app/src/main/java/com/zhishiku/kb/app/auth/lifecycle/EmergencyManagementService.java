package com.zhishiku.kb.app.auth.lifecycle;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.LifecycleMapper;
import com.zhishiku.kb.app.auth.model.IdentityRecord;
import com.zhishiku.kb.app.auth.model.ManagementChange;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.EpochSessionService;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.LoginThrottle;
import com.zhishiku.kb.app.auth.service.ManagementInvariant;
import com.zhishiku.kb.app.config.PasswordEncoderConfig;
import com.zhishiku.kb.common.conversation.RequestSignature;
import com.zhishiku.kb.common.security.IdentityException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

@Service
public class EmergencyManagementService {
    private final IdentityDepartmentMapper users;
    private final LifecycleMapper lifecycle;
    private final LifecycleAuthorizer auth;
    private final LifecycleProofService proofs;
    private final LifecycleReceipts receipts;
    private final IdentityAuditService audit;
    private final BCryptPasswordEncoder encoder;
    private final LoginThrottle throttle;
    private final ManagementInvariant management;
    private final TransactionTemplate transaction;

    public record Verification(long userId, long version, Long replaceUserId, Long replaceMappingVersion, String password) {
        @Override
        public String toString() {
            return "EmergencyVerification[redacted]";
        }
    }

    public record Confirmation(long userId, long version, long mappingVersion, Long replaceUserId,
                                Long replaceMappingVersion, String proof, String reason, String requestId) {
        @Override
        public String toString() {
            return "EmergencyConfirmation[redacted]";
        }
    }

    public record Verified(String proof, Instant expiresAt, long version, long mappingVersion) {
        @Override
        public String toString() {
            return "EmergencyVerified[redacted]";
        }
    }

    public record Registered(long userId, long mappingVersion, boolean registered, Long replacedUserId) {
    }

    public record Evidence(boolean localPasswordVerified) {
    }

    public EmergencyManagementService(IdentityDepartmentMapper users, LifecycleMapper lifecycle,
                                      LifecycleAuthorizer auth, LifecycleProofService proofs, LifecycleReceipts receipts,
                                      IdentityAuditService audit, BCryptPasswordEncoder encoder, LoginThrottle throttle,
                                      PlatformTransactionManager manager) {
        this.users = users;
        this.lifecycle = lifecycle;
        this.auth = auth;
        this.proofs = proofs;
        this.receipts = receipts;
        this.audit = audit;
        this.encoder = encoder;
        this.throttle = throttle;
        this.management = new ManagementInvariant(users);
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setTimeout(5);
    }

    public Verified verify(String token, Verification input) {
        requireLocal(token, input.userId());
        validateTarget(input.userId(), input.version(), input.replaceUserId(), input.replaceMappingVersion());
        var user = users.findIdentity(input.userId());
        if (user == null) {
            throw IdentityException.unauthorized("登录状态无效");
        }
        String hash = users.findPasswordHashById(input.userId());
        boolean present = hash != null && !hash.isBlank();
        String password = input.password();
        if (password == null || password.length() > 256 || throttle.isThrottled(user.getUsername())) {
            throw IdentityException.unauthorized("本地凭据验证失败");
        }
        boolean matches = encoder.matches(password, present ? hash : PasswordEncoderConfig.DUMMY_PASSWORD_HASH);
        if (!present || !matches) {
            throttle.recordFailure(user.getUsername());
            throw IdentityException.unauthorized("本地凭据验证失败");
        }
        throttle.clear(user.getUsername());
        String credentialDigest = RequestSignature.digest(hash);
        return transaction.execute(status -> {
            management.lock();
            requireLocal(token, input.userId());
            lockTargets(input.userId(), input.replaceUserId());
            requireVersion(input.userId(), input.version());
            requireReplacement(input.replaceUserId(), input.replaceMappingVersion());
            management.requireEmergencyCandidate(input.userId());
            if (!credentialDigest.equals(RequestSignature.digest(users.findPasswordHashById(input.userId())))) {
                throw LifecycleSupport.stale();
            }
            var binding = binding(token, input.userId(), input.replaceUserId(), input.replaceMappingVersion());
            var issued = proofs.issue(input.userId(), input.userId(), "EMERGENCY", binding, new Evidence(true));
            return new Verified(issued.proof(), issued.expiresAt(), input.version(), mappingVersion(input.userId()));
        });
    }

    public Registered register(String token, Confirmation input) {
        requireLocal(token, input.userId());
        validateTarget(input.userId(), input.version(), input.replaceUserId(), input.replaceMappingVersion());
        String reason = LifecycleSupport.text(input.reason(), "reason", 500);
        String request = LifecycleSupport.text(input.requestId(), "requestId", 128);
        Confirmation normalized = new Confirmation(input.userId(), input.version(), input.mappingVersion(),
                input.replaceUserId(), input.replaceMappingVersion(), input.proof(), reason, request);
        return transaction.execute(status -> {
            management.lock();
            requireLocal(token, input.userId());
            Registered replay = receipts.replay(input.userId(), request, "EMERGENCY_REGISTER",
                    Long.toString(input.userId()), normalized, Registered.class);
            if (replay != null) {
                return replay;
            }
            lockTargets(input.userId(), input.replaceUserId());
            requireVersion(input.userId(), input.version());
            if (mappingVersion(input.userId()) != input.mappingVersion()) {
                throw LifecycleSupport.stale();
            }
            requireReplacement(input.replaceUserId(), input.replaceMappingVersion());
            management.requireEmergencyCandidate(input.userId());
            var evidence = proofs.consume(input.proof(), input.userId(), input.userId(), "EMERGENCY",
                    binding(token, input.userId(), input.replaceUserId(), input.replaceMappingVersion()), Evidence.class);
            if (!evidence.localPasswordVerified()) {
                throw LifecycleSupport.stale();
            }
            if (lifecycle.registerEmergency(input.userId()) != 1) {
                throw LifecycleSupport.stale();
            }
            history(input.userId(), input.userId(), "register_emergency", reason);
            if (input.replaceUserId() != null) {
                if (lifecycle.retireEmergency(input.replaceUserId(), input.replaceMappingVersion()) != 1) {
                    throw LifecycleSupport.stale();
                }
                history(input.replaceUserId(), input.userId(), "retire_emergency", reason);
            }
            management.requireEmergencyAfter(ManagementChange.unchanged());
            var result = new Registered(input.userId(), mappingVersion(input.userId()), true, input.replaceUserId());
            String auditRequest = LifecycleSupport.digest(Map.of("actor", input.userId(), "request", request));
            audit.successWithReason(AuditScope.USER_EMERGENCY_CHANGE, auditRequest, input.userId(), input.userId(),
                    "register_emergency", "{}", LifecycleSupport.json(result), reason);
            receipts.save(input.userId(), request, "EMERGENCY_REGISTER", Long.toString(input.userId()), normalized, result);
            return result;
        });
    }

    private void requireLocal(String token, long userId) {
        var session = auth.require(token, Permission.IDENTITY_EMERGENCY_MANAGE);
        if (session.userId() != userId || session.method() != EpochSessionService.Method.LOCAL) {
            throw IdentityException.forbidden("应急登记须由候选本人使用本地密码登录执行");
        }
    }

    private void lockTargets(long userId, Long replace) {
        var ids = new TreeSet<Long>();
        ids.add(userId);
        if (replace != null) {
            ids.add(replace);
        }
        for (long id : ids) {
            lifecycle.lockAssignment(id);
        }
        for (long id : ids) {
            if (users.findIdentityForUpdate(id) == null) {
                throw LifecycleSupport.stale();
            }
        }
    }

    private void requireVersion(long userId, long version) {
        var user = users.findIdentity(userId);
        if (user == null || !Objects.equals(user.getVersion(), version)) {
            throw LifecycleSupport.stale();
        }
    }

    private void requireReplacement(Long userId, Long version) {
        if (userId != null) {
            var assignment = lifecycle.findAssignment(userId);
            if (assignment == null || assignment.kind() != LifecycleAssignment.Kind.EMERGENCY || assignment.version() != version) {
                throw LifecycleSupport.stale();
            }
        }
    }

    private long mappingVersion(long userId) {
        var assignment = lifecycle.findAssignment(userId);
        return assignment == null ? -1 : assignment.version();
    }

    private Map<String, Object> binding(String token, long userId, Long replace, Long replaceVersion) {
        IdentityRecord user = users.findIdentity(userId);
        var department = users.findDepartment(user.getDepartmentId());
        if (department == null || !Boolean.TRUE.equals(department.getEnabled())) {
            throw LifecycleSupport.stale();
        }
        var facts = new TreeMap<String, Object>();
        facts.put("userId", userId);
        facts.put("userVersion", user.getVersion());
        facts.put("mappingVersion", mappingVersion(userId));
        facts.put("authEpoch", user.getAuthEpoch());
        facts.put("departmentId", user.getDepartmentId());
        facts.put("departmentVersion", department.getVersion());
        facts.put("credentialDigest", RequestSignature.digest(users.findPasswordHashById(userId)));
        facts.put("sessionDigest", RequestSignature.digest(token));
        facts.put("roles", users.findUserManagementPermissions(userId).stream()
                .map(role -> role.roleId() + ":" + role.permission()).sorted().toList());
        facts.put("replaceUserId", replace);
        facts.put("replaceMappingVersion", replaceVersion);
        return facts;
    }

    private void history(long user, long actor, String operation, String reason) {
        if (lifecycle.appendHistory(user, actor, operation, reason) != 1) {
            throw new IllegalStateException("应急登记历史写入失败");
        }
    }

    private static void validateTarget(long userId, long version, Long replacement, Long replacementVersion) {
        if (userId <= 0 || version < 0 || (replacement == null) != (replacementVersion == null)
                || (replacement != null && (replacement <= 0 || replacement == userId || replacementVersion < 0))) {
            throw IdentityException.badRequest("应急登记目标或版本无效");
        }
    }
}
