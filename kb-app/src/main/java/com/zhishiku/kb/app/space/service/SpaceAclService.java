package com.zhishiku.kb.app.space.service;

import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.space.mapper.SpaceMapper;
import com.zhishiku.kb.app.space.model.SpaceAclRecord;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.PermissionScope;
import com.zhishiku.kb.common.security.PrincipalType;
import com.zhishiku.kb.common.security.SpaceAclGrantResult;
import com.zhishiku.kb.common.security.SpaceAclSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 空间授权名单的授予、撤销与列出。
 *
 * <h2>可授予的权限位白名单</h2>
 * 允许 {@code view} / {@code download} / {@code edit} / {@code review} / {@code comment}。
 * review 仅参与空间审核人池判定，comment 仅为发表评论资格；两者都不授予正文、下载或足够密级。
 *
 * <p>{@code manage} 不在白名单里单独走一条路：它只允许持有 space:grant-manage 的操作者授出，
 * 因为 manage 的分量等同于该空间上的全部权限（持有者可以把 edit 授给自己
 * 所在的部门），允许空间管理员授出它等于允许管理资格自我扩散。
 */
@Service
public class SpaceAclService {

    private static final Logger log = LoggerFactory.getLogger(SpaceAclService.class);

    /**
     * 可经本接口授予的权限位。manage 单独判定，不在此列。
     */
    private static final Set<String> GRANTABLE =
            Set.of(PermissionService.PERM_VIEW, PermissionService.PERM_DOWNLOAD, PermissionService.PERM_EDIT,
                    PermissionService.PERM_REVIEW, PermissionService.PERM_COMMENT);

    private final SpaceMapper mapper;
    private final SpaceAuthorizer authorizer;
    private final IdentityAuditService audit;

    public SpaceAclService(SpaceMapper mapper, SpaceAuthorizer authorizer) {
        this(mapper, authorizer, null);
    }

    @Autowired
    public SpaceAclService(SpaceMapper mapper, SpaceAuthorizer authorizer, IdentityAuditService audit) {
        this.mapper = mapper;
        this.authorizer = authorizer;
        this.audit = audit;
    }

    @Transactional
    public SpaceAclGrantResult grant(long spaceId, PrincipalType principalType, long principalId,
                                     String permission, Long actorUserId, String requestId) {
        String normalizedPermission = normalizePermission(permission);
        boolean manage = PermissionService.PERM_MANAGE.equals(normalizedPermission);
        long actor = manage
                ? authorizer.requireGrantManage()
                : authorizer.requireSpaceManager(spaceId);
        Long effectiveActor = actorUserId == null ? actor : actorUserId;

        Set<String> keys = Set.of(PermissionScope.principalKey(principalType, principalId));

        var previous = existing(keys, spaceId, requestId, AuditScope.SPACE_ACL_GRANT);
        if (previous != null) {
            return previous;
        }

        if (principalType == PrincipalType.ROLE) {
            authorizer.requireRoleReference(principalId);
        }

        boolean already = mapper.countAcl(spaceId, principalType.code(), principalId,
                normalizedPermission) > 0;
        if (!already) {
            mapper.grantAcl(spaceId, principalType.code(), principalId, normalizedPermission);
        }
        long blocked = already ? 0 : countClearanceBlocked(spaceId, principalType, principalId);

        SpaceAclRecord record = new SpaceAclRecord();
        record.setSpaceId(spaceId);
        record.setPrincipalType(principalType.code());
        record.setPrincipalId(principalId);
        record.setPermission(normalizedPermission);
        record.setPrincipalKey(keys.iterator().next());
        SpaceAclSummary summary = summary(record);

        auditSuccess(AuditScope.SPACE_ACL_GRANT, requestId, effectiveActor, spaceId,
                "grant", "{}", state(summary));
        if (blocked > 0) {
            log.info("授权已写入但部分主体受密级限制: spaceId={} principal={} blocked={}",
                    spaceId, record.getPrincipalKey(), blocked);
        }
        return new SpaceAclGrantResult(summary, already, blocked);
    }

    @Transactional
    public void revoke(long spaceId, PrincipalType principalType, long principalId, String permission,
                       Long actorUserId, String requestId) {
        String normalizedPermission = normalizePermission(permission);
        boolean manage = PermissionService.PERM_MANAGE.equals(normalizedPermission);
        long actor = manage
                ? authorizer.requireGrantManage()
                : authorizer.requireSpaceManager(spaceId);
        Long effectiveActor = actorUserId == null ? actor : actorUserId;

        var previous = existing(Set.of(PermissionScope.principalKey(principalType, principalId)),
                spaceId, requestId, AuditScope.SPACE_ACL_REVOKE);
        if (previous != null) {
            return;
        }

        int removed = mapper.revokeAcl(spaceId, principalType.code(), principalId, normalizedPermission);
        // 撤销一条不存在的授权不改变状态，是幂等的，不报错；但仍留审计，
        // 因为"谁在什么时候试图撤销什么"本身就是需要可追溯的事实。
        String after = removed == 0 ? "{}"
                : "{\"spaceId\":" + spaceId + ",\"principalKey\":\""
                + PermissionScope.principalKey(principalType, principalId)
                + "\",\"permission\":\"" + normalizedPermission + "\"}";
        auditSuccess(AuditScope.SPACE_ACL_REVOKE, requestId, effectiveActor, spaceId,
                "revoke", "{}", after);
    }

    @Transactional(readOnly = true)
    public List<SpaceAclSummary> list(long spaceId) {
        authorizer.requireSpaceManager(spaceId);
        return mapper.listAcl(spaceId).stream().map(SpaceAclService::summary).toList();
    }

    /**
     * 统计这次授予中"因密级而当前不生效"的主体数（0 或 1——本接口一次只授一个主体）。
     *
     * <p>口径：主体在该空间内<b>没有任何一个有效成员</b>能看到任何一篇 published
     * 文档时，才算对该主体当前无效。对单个用户即"其 clearance 低于空间内最高文档密级"；
     * 对部门或角色，只要有成员具备足够密级就算生效——否则一个混合密级的部门
     * 会被整条判为无效，回执就失去了参考价值。
     */
    private long countClearanceBlocked(long spaceId, PrincipalType principalType, long principalId) {
        List<Long> userIds = mapper.userIdsForPrincipal(principalType.code(), principalId);
        if (userIds.isEmpty()) {
            return 0;
        }
        short best = 0;
        for (Long userId : userIds) {
            Short clearance = mapper.findUserClearance(userId);
            if (clearance != null && clearance > best) {
                best = clearance;
            }
        }
        return mapper.countDocumentsAboveClearance(spaceId, best) > 0 ? 1 : 0;
    }

    /**
     * 幂等重放：同一 requestId 已成功执行过则返回既有结果，不再写一次。
     */
    private SpaceAclGrantResult existing(Set<String> ignoredKeys, long spaceId, String requestId,
                                         AuditScope scope) {
        if (audit == null || requestId == null || requestId.isBlank()) {
            return null;
        }
        var previous = audit.existing(scope, requestId);
        if (previous == null) {
            return null;
        }
        log.debug("命中重复的授权请求: scope={} requestId={}", scope, requestId);
        List<SpaceAclSummary> acls = mapper.listAcl(spaceId).stream()
                .map(SpaceAclService::summary).toList();
        return new SpaceAclGrantResult(acls.isEmpty() ? null : acls.get(0), true, 0);
    }

    private static String normalizePermission(String permission) {
        if (permission == null || permission.isBlank()) {
            throw IdentityException.badRequest("permission 不能为空");
        }
        String normalized = permission.trim();
        if (GRANTABLE.contains(normalized) || PermissionService.PERM_MANAGE.equals(normalized)) {
            return normalized;
        }
        // 没有功能执行的权限位不能静默保存。
        throw IdentityException.badRequest(
                "权限位 " + normalized + " 不被支持，可授予的权限位仅为 view、download、edit、review、comment（manage 需 space:grant-manage）");
    }

    private void auditSuccess(AuditScope scope, String requestId, Long actorUserId, Long targetId,
                              String operation, String before, String after) {
        if (audit == null || requestId == null || requestId.isBlank()) {
            return;
        }
        audit.success(scope, requestId, actorUserId, targetId, operation, before, after);
    }

    private static SpaceAclSummary summary(SpaceAclRecord record) {
        return new SpaceAclSummary(
                record.getId() == null ? 0L : record.getId(),
                record.getSpaceId(),
                record.principalTypeEnum(),
                record.getPrincipalId(),
                record.getPrincipalKey(),
                record.getPrincipalName(),
                record.getPermission());
    }

    private static String state(SpaceAclSummary acl) {
        return "{\"spaceId\":" + acl.spaceId() + ",\"principalKey\":\"" + acl.principalKey()
                + "\",\"permission\":\"" + acl.permission() + "\"}";
    }
}
