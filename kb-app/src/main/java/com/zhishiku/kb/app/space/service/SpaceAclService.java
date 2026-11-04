package com.zhishiku.kb.app.space.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.space.mapper.SpaceMapper;
import com.zhishiku.kb.app.space.model.SpaceAclBatchGrantResult;
import com.zhishiku.kb.app.space.model.SpaceAclPrincipal;
import com.zhishiku.kb.app.space.model.SpaceAclRecord;
import com.zhishiku.kb.common.security.IdentityErrorCode;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private static final ObjectMapper JSON = new ObjectMapper();

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
    public SpaceAclBatchGrantResult grantBatch(long spaceId, List<SpaceAclPrincipal> principals,
                                               List<String> permissions, String requestId) {
        List<String> normalized = validateBatch(principals, permissions);
        if (spaceId <= 0) {
            throw IdentityException.badRequest("spaceId 必须为正整数");
        }
        if (requestId == null || requestId.isBlank()) {
            throw IdentityException.badRequest("缺少请求标识 X-Request-Id");
        }
        long actor = normalized.contains(PermissionService.PERM_MANAGE)
                ? authorizer.requireGrantManage() : authorizer.requireSpaceManager(spaceId);
        var previous = audit.existing(AuditScope.SPACE_ACL_BATCH_GRANT, requestId);
        if (previous != null) {
            if (!Objects.equals(previous.getActorUserId(), actor)
                    || !Objects.equals(previous.getTargetId(), spaceId)) {
                throw IdentityException.conflict(IdentityErrorCode.CONFLICT, "请求标识已用于不同操作");
            }
            try {
                var saved = JSON.readValue(previous.getAfterState(), SpaceAclBatchGrantResult.class);
                if (saved.spaceId() != spaceId) {
                    throw IdentityException.conflict(IdentityErrorCode.CONFLICT, "请求标识已用于不同操作");
                }
                return new SpaceAclBatchGrantResult(saved.spaceId(), saved.items(), saved.grantedCount(),
                        saved.alreadyGrantedCount(), saved.clearanceBlockedPrincipalCount(), true);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("批量授权审计快照无法读取", e);
            }
        }
        // 整批角色引用先校验完毕，避免后续无效主体导致先前条目提前写入。
        for (SpaceAclPrincipal principal : principals) {
            if (principal.principalType() == PrincipalType.ROLE) {
                authorizer.requireRoleReference(principal.principalId());
            }
        }
        List<SpaceAclBatchGrantResult.Item> items = new ArrayList<>();
        long granted = 0;
        long blocked = 0;
        for (SpaceAclPrincipal principal : principals) {
            boolean newlyGranted = false;
            for (String permission : normalized) {
                boolean already = mapper.countAcl(spaceId, principal.principalType().code(),
                        principal.principalId(), permission) > 0;
                if (!already) {
                    already = mapper.grantAcl(spaceId, principal.principalType().code(),
                            principal.principalId(), permission) == 0;
                }
                if (!already) {
                    granted++;
                    newlyGranted = true;
                }
                items.add(new SpaceAclBatchGrantResult.Item(principal.principalType(), principal.principalId(),
                        permission, already));
            }
            if (newlyGranted) {
                blocked += countClearanceBlocked(spaceId, principal.principalType(), principal.principalId());
            }
        }
        var result = new SpaceAclBatchGrantResult(spaceId, List.copyOf(items), granted, items.size() - granted,
                blocked, false);
        try {
            String after = JSON.writeValueAsString(Map.of("spaceId", spaceId, "items", result.items(),
                    "grantedCount", result.grantedCount(), "alreadyGrantedCount", result.alreadyGrantedCount(),
                    "clearanceBlockedPrincipalCount", blocked));
            audit.success(AuditScope.SPACE_ACL_BATCH_GRANT, requestId, actor, spaceId, "batch-grant", "{}", after);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("批量授权审计快照无法序列化", e);
        }
        return result;
    }

    private static List<String> validateBatch(List<SpaceAclPrincipal> principals, List<String> permissions) {
        if (principals == null || principals.isEmpty() || principals.size() > 50) {
            throw IdentityException.badRequest("主体列表不能为空，须包含 1 至 50 个主体，上限为 50");
        }
        if (permissions == null || permissions.isEmpty() || permissions.size() > 6) {
            throw IdentityException.badRequest("权限位列表不能为空，须包含 1 至 6 个权限位，上限为 6");
        }
        Set<SpaceAclPrincipal> uniquePrincipals = new HashSet<>();
        for (SpaceAclPrincipal principal : principals) {
            if (principal == null || principal.principalType() == null || principal.principalId() <= 0) {
                throw IdentityException.badRequest("主体类型必须为 USER、ROLE、DEPT，主体 ID 必须为正整数；主体上限为 50");
            }
            if (!uniquePrincipals.add(principal)) {
                throw IdentityException.badRequest("主体重复：" + principal.principalType() + ":"
                        + principal.principalId() + "；主体上限为 50");
            }
        }
        List<String> normalized = new ArrayList<>();
        for (String permission : permissions) {
            String value;
            try {
                value = normalizePermission(permission);
            } catch (IdentityException error) {
                throw IdentityException.badRequest(error.getMessage() + "；权限位上限为 6");
            }
            if (normalized.contains(value)) {
                throw IdentityException.badRequest("权限位重复：" + value + "；权限位上限为 6");
            }
            normalized.add(value);
        }
        return normalized;
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
     * <p>口径：主体有有效成员，且空间内存在密级高于这些成员最高 clearance 的 published
     * 文档时，计为一个受阻主体。这表示至少有一篇文档因密级无法访问，
     * 不表示所有文档都不可访问；无有效成员时不计入。部门或角色取成员的最高密级。
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
