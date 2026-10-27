package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 内存版审计服务，按 {@code (operation_scope, request_id)} 索引。
 *
 * <p>与 {@code sys_identity_audit} 的 {@code UNIQUE (operation_scope, request_id)}
 * 同构，因此可以让不同实体的服务<b>共用同一个实例</b>，复现"同一 request_id
 * 打进同一命名空间"的真实判断。用它而不是 mock 的理由见
 * {@code SpaceDepartmentAuditIsolationTest} 的类注释：mock 不认识幂等键，
 * 撞车根本不会发生。
 *
 * <p>放在本包内是因为 {@link IdentityAuditService} 的测试构造器是包级可见的，
 * 而正是它让我们能绕开 mapper，只保留幂等判定的那部分行为。
 */
public class InMemoryAuditStore extends IdentityAuditService {

    private final Map<String, IdentityAuditRecord> byKey = new LinkedHashMap<>();

    public InMemoryAuditStore() {
        super(null);
    }

    @Override
    public IdentityAuditRecord existing(AuditScope scope, String requestId) {
        return byKey.get(key(scope, requestId));
    }

    @Override
    public IdentityAuditRecord success(AuditScope scope, String requestId, Long actorUserId,
                                       Long targetId, String operation,
                                       String beforeState, String afterState) {
        String key = key(scope, requestId);
        IdentityAuditRecord previous = byKey.get(key);
        if (previous != null) return previous;
        IdentityAuditRecord created = new IdentityAuditRecord(
                null, UUID.randomUUID(), scope.name(), requestId, actorUserId,
                scope.targetType(), targetId, operation, "SUCCESS",
                beforeState, afterState, null, Instant.now());
        byKey.put(key, created);
        return created;
    }

    @Override
    public IdentityAuditRecord failure(AuditScope scope, String requestId, Long actorUserId,
                                       Long targetId, String operation, String reason,
                                       String beforeState, String afterState) {
        String key = key(scope, requestId);
        IdentityAuditRecord previous = byKey.get(key);
        if (previous != null) return previous;
        IdentityAuditRecord created = new IdentityAuditRecord(
                null, UUID.randomUUID(), scope.name(), requestId, actorUserId,
                scope.targetType(), targetId, operation, "FAILED",
                beforeState, afterState, reason, Instant.now());
        byKey.put(key, created);
        return created;
    }

    /**
     * 已落库的事件，按写入顺序。
     */
    public List<IdentityAuditRecord> events() {
        return List.copyOf(byKey.values());
    }

    private static String key(AuditScope scope, String requestId) {
        return scope.name() + "\u0000" + requestId;
    }
}
