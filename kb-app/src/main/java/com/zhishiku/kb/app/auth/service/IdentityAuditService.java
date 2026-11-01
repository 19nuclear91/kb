package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.mapper.IdentityAuditMapper;
import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import com.zhishiku.kb.common.security.IdentityErrorCode;
import com.zhishiku.kb.common.security.IdentityException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * 仅追加的审计边界。调用方在自己的数据库事务内调用它，
 * 因此审计写入失败会连带回滚业务变更。
 *
 * <p><b>操作域命名规范</b>：操作域只接受 {@link AuditScope}，且每个枚举常量
 * 必须带实体前缀（{@code <实体>_<操作>}）。原因在于幂等键是
 * {@code UNIQUE (operation_scope, request_id)}，而 {@code request_id} 由客户端
 * 提供、服务端只校验非空：一旦两个实体共用裸操作域（例如都用 {@code CREATE}），
 * 两者就落在同一个幂等命名空间里。那时后到的请求会命中前者的审计行、
 * 被当作重试，并返回前者的 targetId——调用方拿到的是另一个实体，
 * 而自己请求创建的实体压根没有被创建，却收到了成功响应。
 *
 * <p>所以新增实体时不要图省事复用通用动词，照 {@code AuditScope} 的既有写法
 * 加前缀即可。枚举的作用正是让裸字符串在编译期就传不进来。
 */
@Service
public class IdentityAuditService {

    private final IdentityAuditMapper mapper;
    private final Clock clock;

    public IdentityAuditService(IdentityAuditMapper mapper) {
        this(mapper, Clock.systemUTC());
    }

    IdentityAuditService(IdentityAuditMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public IdentityAuditRecord existing(AuditScope scope, String requestId) {
        return mapper.findByRequest(requireScope(scope).name(),
                IdentityWriteSupport.requestId(requestId));
    }

    /** 调用方已串行化并核对请求绑定，任何写入失败直接传播。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public IdentityAuditRecord appendSuccess(AuditScope scope, String requestId, long actorUserId,
                                              long targetId, String operation, String beforeState, String afterState) {
        AuditScope normalizedScope = requireScope(scope);
        String request = IdentityWriteSupport.requestId(requestId);
        String action = IdentityWriteSupport.required(operation, "operation");
        String before = safeJson(beforeState);
        String after = safeJson(afterState);
        UUID eventId = UUID.randomUUID();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (mapper.insert(eventId, normalizedScope.name(), request, actorUserId, normalizedScope.targetType(),
                targetId, action, "SUCCESS", before, after, null, now) != 1) {
            throw new IllegalStateException("审计事件写入失败");
        }
        return new IdentityAuditRecord(null, eventId, normalizedScope.name(), request, actorUserId,
                normalizedScope.targetType(), targetId, action, "SUCCESS", before, after, null, now);
    }

    /**
     * 带原因的严格追加入口：调用者须在同一事务内串行化请求并完成精确重放判断。
     * 唯一键或其他写入错误直接向外传播，由业务事务整体回滚。
     */
    @Transactional(
            propagation = Propagation.MANDATORY)
    public IdentityAuditRecord successWithReason(AuditScope scope, String requestId, Long actorUserId,
                                                 Long targetId, String operation, String beforeState,
                                                 String afterState, String reason) {
        AuditScope normalizedScope = requireScope(scope);
        String request = IdentityWriteSupport.requestId(requestId);
        String action = IdentityWriteSupport.required(operation, "operation");
        String explanation = IdentityWriteSupport.required(reason, "reason");
        String before = safeJson(beforeState);
        String after = safeJson(afterState);
        UUID eventId = UUID.randomUUID();
        // PostgreSQL 时间精度为微秒，首次响应与落库后的重放保持一致。
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (mapper.insert(eventId, normalizedScope.name(), request, actorUserId, normalizedScope.targetType(),
                targetId, action, "SUCCESS", before, after, explanation, now) != 1) {
            throw new IllegalStateException("审计事件写入失败");
        }
        return new IdentityAuditRecord(null, eventId, normalizedScope.name(), request, actorUserId,
                normalizedScope.targetType(), targetId, action, "SUCCESS", before, after, explanation, now);
    }

    /**
     * 写入一条成功事件。{@code target_type} 取自 {@link AuditScope#targetType()}，
     * 不由调用方另传，避免操作域与目标类型不一致。
     */
    public IdentityAuditRecord success(AuditScope scope, String requestId, Long actorUserId,
                                       Long targetId, String operation,
                                       String beforeState, String afterState) {
        AuditScope normalizedScope = requireScope(scope);
        String scopeName = normalizedScope.name();
        String normalizedRequest = IdentityWriteSupport.requestId(requestId);
        IdentityAuditRecord previous = mapper.findByRequest(scopeName, normalizedRequest);
        if (previous != null) return previous;

        UUID eventId = UUID.randomUUID();
        Instant now = clock.instant();
        String normalizedTarget = normalizedScope.targetType();
        String normalizedOperation = IdentityWriteSupport.required(operation, "operation");
        String normalizedBefore = safeJson(beforeState);
        String normalizedAfter = safeJson(afterState);
        int inserted;
        try {
            inserted = mapper.insert(eventId, scopeName, normalizedRequest, actorUserId,
                    normalizedTarget, targetId, normalizedOperation, "SUCCESS",
                    normalizedBefore, normalizedAfter, null, now);
        } catch (DataIntegrityViolationException duplicate) {
            IdentityAuditRecord concurrent = mapper.findByRequest(scopeName, normalizedRequest);
            if (concurrent != null) return concurrent;
            throw duplicate;
        }
        if (inserted != 1) {
            throw IdentityException.conflict(
                    IdentityErrorCode.CONFLICT,
                    "审计事件写入失败");
        }
        return new IdentityAuditRecord(null, eventId, scopeName, normalizedRequest,
                actorUserId, normalizedTarget, targetId, normalizedOperation, "SUCCESS",
                normalizedBefore, normalizedAfter, null, now);
    }

    /**
     * 写入一条失败事件。字段规则与 {@link #success} 相同，{@code status} 为
     * {@code FAILED}；{@code reason} 记录失败原因，可为空。
     */
    public IdentityAuditRecord failure(AuditScope scope, String requestId, Long actorUserId,
                                       Long targetId, String operation, String reason,
                                       String beforeState, String afterState) {
        AuditScope normalizedScope = requireScope(scope);
        String scopeName = normalizedScope.name();
        String normalizedRequest = IdentityWriteSupport.requestId(requestId);
        IdentityAuditRecord previous = mapper.findByRequest(scopeName, normalizedRequest);
        if (previous != null) return previous;

        UUID eventId = UUID.randomUUID();
        Instant now = clock.instant();
        String normalizedTarget = normalizedScope.targetType();
        String normalizedOperation = IdentityWriteSupport.required(operation, "operation");
        String normalizedReason = reason == null || reason.isBlank() ? null : reason.trim();
        String normalizedBefore = safeJson(beforeState);
        String normalizedAfter = safeJson(afterState);
        int inserted;
        try {
            inserted = mapper.insert(eventId, scopeName, normalizedRequest, actorUserId,
                    normalizedTarget, targetId, normalizedOperation, "FAILED",
                    normalizedBefore, normalizedAfter, normalizedReason, now);
        } catch (DataIntegrityViolationException duplicate) {
            IdentityAuditRecord concurrent = mapper.findByRequest(scopeName, normalizedRequest);
            if (concurrent != null) return concurrent;
            throw duplicate;
        }
        if (inserted != 1) {
            throw IdentityException.conflict(
                    IdentityErrorCode.CONFLICT,
                    "审计事件写入失败");
        }
        return new IdentityAuditRecord(null, eventId, scopeName, normalizedRequest,
                actorUserId, normalizedTarget, targetId, normalizedOperation, "FAILED",
                normalizedBefore, normalizedAfter, normalizedReason, now);
    }

    public List<IdentityAuditRecord> list(String targetType, Long targetId,
                                          Instant from, Instant to) {
        Instant start = from == null ? Instant.EPOCH : from;
        Instant end = to == null ? clock.instant().plusSeconds(1) : to;
        if (!end.isAfter(start)) throw IdentityException.badRequest("时间范围无效");
        return mapper.list(IdentityWriteSupport.required(targetType, "targetType"),
                targetId, start, end);
    }

    private static AuditScope requireScope(AuditScope scope) {
        if (scope == null) throw IdentityException.badRequest("operationScope不能为空");
        return scope;
    }

    private static String safeJson(String state) {
        if (state == null || state.isBlank()) return "{}";
        String value = state.trim();
        if ((value.startsWith("{") && value.endsWith("}"))
                || (value.startsWith("[") && value.endsWith("]"))) return value;
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }
}
