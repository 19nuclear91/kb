package com.zhishiku.kb.app.auth.service;

import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 登录事件的审计记录（设计 D8）。
 *
 * <p>事件 {@code request_id} 一律由服务端生成 UUID，不经过客户端：登录没有
 * 幂等需求（每次尝试都是一次新事件），而一旦客户端可控的值进入
 * {@code (operation_scope, request_id)} 命名空间，撞上既有行时这次尝试会被
 * 当成重试、静默返回旧事件——在最需要留痕的路径上留下审计空洞。
 *
 * <p>摘要记尝试的用户名；失败事件在来源已知时另记规范化后的 {@code sourceIp}，
 * 供运维按来源与用户名聚合。全局审计查询摘要不投影 {@code sourceIp}；旧的按目标审计接口
 * 原样返回 {@code after_state}，持有 {@code identity:audit:read} 者可见已知用户失败记录的来源。
 * 密码、令牌、哈希一律不进审计。
 */
@Service
public class LoginAuditService {

    private final IdentityAuditService audit;

    public LoginAuditService(IdentityAuditService audit) {
        this.audit = audit;
    }

    /**
     * 登录成功：actor 与 target 都是该用户。
     */
    public void success(long userId, String username) {
        audit.success(AuditScope.AUTH_LOGIN_SUCCESS, UUID.randomUUID().toString(),
                userId, userId, "login", "{}", summary(username));
    }

    /**
     * 登录失败。{@code resolvedUserId} 仅在用户名已解析到用户时给出；
     * 未知用户传 {@code null}，不因审计而多做一次用户查询。
     *
     * @param sourceIp 已规范化的登录来源；来源未知时为 null，摘要中不出现该字段
     */
    public void failure(String attemptedUsername, Long resolvedUserId, String reason, String sourceIp) {
        audit.failure(AuditScope.AUTH_LOGIN_FAILURE, UUID.randomUUID().toString(),
                null, resolvedUserId, "login", reason, "{}", failureSummary(attemptedUsername, sourceIp));
    }

    private static String summary(String username) {
        return "{\"username\":\"" + escape(username) + "\"}";
    }

    private static String failureSummary(String username, String sourceIp) {
        if (sourceIp == null) {
            return summary(username);
        }
        return "{\"username\":\"" + escape(username) + "\",\"sourceIp\":\"" + escape(sourceIp) + "\"}";
    }

    /**
     * 值进入 jsonb 列前必须转义：含引号或反斜杠的用户名会让
     * {@code CAST(? AS jsonb)} 直接失败，登录审计反而变成登录故障。
     */
    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
