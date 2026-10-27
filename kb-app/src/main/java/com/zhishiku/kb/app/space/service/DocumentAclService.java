package com.zhishiku.kb.app.space.service;

import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.space.mapper.DocumentAclMapper;
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
 * 文档级例外授权。
 *
 * <p><b>语义边界：例外只能扩权。</b>它把个别文档额外授予空间外的主体；
 * 无法收紧——"空间可见但某文档更严"需要 deny 语义，那是二期的事
 * （见 V1 迁移里 {@code kb_document_acl} 的注释）。因此在写路径上，
 * 一切表现为"试图缩小既有可见范围"的请求都必须被明确拒绝，
 * 而不是接受后什么都不发生。
 *
 * <p><b>鉴权依据是文档当前所属空间</b>，不是请求里声称的空间：调用方只能
 * 为自己可管理的空间内的文档开设例外。
 *
 * <p><b>跨变更约束（已兑付）</b>：文档迁移所属空间时<b>必须清理其遗留的例外 ACL</b>，
 * 这件事由 {@link DocumentRelocationService#relocate} 在搬迁的同一事务内完成——它按
 * {@code doc_id} 整体清空 {@code kb_document_acl}，并在回执与审计里给出被清除的条数与摘要。
 * 原因是例外授权的鉴权依据是"文档当前所属空间的管理资格"：搬迁后原空间的管理员不再
 * 有资格管理该文档，但其当初授出的例外记录仍然留在表里继续生效，那等于让一个已经
 * 失去资格的人保留了对文档的授权遗泽。
 *
 * <p>因此本服务的写路径<b>不需要</b>为搬迁做任何额外处理：搬迁之后 {@link #grant} 看到的
 * 已经是空的例外集合，而 {@link #requireOwningSpace} 查出的归属已经是目标空间，于是新的
 * 例外自然按目标空间的管理资格判定。<b>若将来新增任何修改 {@code kb_document.space_id}
 * 的入口</b>，它同样必须清理例外，否则这条约束就从"已兑付"退回"有漏洞"。
 */
@Service
public class DocumentAclService {

    private static final Logger log = LoggerFactory.getLogger(DocumentAclService.class);

    /**
     * 文档例外只接受独立阅读、下载、编辑和发表评论；review 只能在空间授予。
     */
    private static final Set<String> GRANTABLE = Set.of(PermissionService.PERM_VIEW, PermissionService.PERM_DOWNLOAD,
            PermissionService.PERM_EDIT, PermissionService.PERM_COMMENT);

    private final DocumentAclMapper mapper;
    private final SpaceAuthorizer authorizer;
    private final IdentityAuditService audit;

    public DocumentAclService(DocumentAclMapper mapper, SpaceAuthorizer authorizer) {
        this(mapper, authorizer, null);
    }

    @Autowired
    public DocumentAclService(DocumentAclMapper mapper, SpaceAuthorizer authorizer,
                              IdentityAuditService audit) {
        this.mapper = mapper;
        this.authorizer = authorizer;
        this.audit = audit;
    }

    @Transactional
    public SpaceAclGrantResult grant(long documentId, PrincipalType principalType, long principalId,
                                     String permission, Long actorUserId, String requestId) {
        long actor = authorizer.requireSpaceManager(requireOwningSpace(documentId));
        Long effectiveActor = actorUserId == null ? actor : actorUserId;

        String normalizedPermission = requireWideningPermission(permission);
        var previous = replay(AuditScope.DOC_ACL_GRANT, documentId, requestId);
        if (previous != null) {
            return previous;
        }

        if (principalType == PrincipalType.ROLE) {
            authorizer.requireRoleReference(principalId);
        }

        boolean already = mapper.countAcl(documentId, principalType.code(), principalId,
                normalizedPermission) > 0;
        if (!already) {
            mapper.grantAcl(documentId, principalType.code(), principalId, normalizedPermission);
        }
        long blocked = already ? 0 : countClearanceBlocked(documentId, principalType, principalId);

        SpaceAclRecord record = new SpaceAclRecord();
        record.setSpaceId(documentId);
        record.setPrincipalType(principalType.code());
        record.setPrincipalId(principalId);
        record.setPermission(normalizedPermission);
        record.setPrincipalKey(PermissionScope.principalKey(principalType, principalId));
        SpaceAclSummary summary = summary(record);

        auditSuccess(AuditScope.DOC_ACL_GRANT, requestId, effectiveActor, documentId,
                "grant", "{}", state(summary));
        if (blocked > 0) {
            log.info("文档例外已写入但主体受密级限制: documentId={} principal={}",
                    documentId, record.getPrincipalKey());
        }
        return new SpaceAclGrantResult(summary, already, blocked);
    }

    @Transactional
    public void revoke(long documentId, PrincipalType principalType, long principalId, String permission,
                       Long actorUserId, String requestId) {
        long actor = authorizer.requireSpaceManager(requireOwningSpace(documentId));
        Long effectiveActor = actorUserId == null ? actor : actorUserId;

        String normalizedPermission = requireWideningPermission(permission);
        if (replay(AuditScope.DOC_ACL_REVOKE, documentId, requestId) != null) {
            return;
        }

        int removed = mapper.revokeAcl(documentId, principalType.code(), principalId, normalizedPermission);
        String after = removed == 0 ? "{}"
                : "{\"documentId\":" + documentId + ",\"principalKey\":\""
                + PermissionScope.principalKey(principalType, principalId)
                + "\",\"permission\":\"" + normalizedPermission + "\"}";
        auditSuccess(AuditScope.DOC_ACL_REVOKE, requestId, effectiveActor, documentId,
                "revoke", "{}", after);
    }

    @Transactional(readOnly = true)
    public List<SpaceAclSummary> list(long documentId) {
        authorizer.requireSpaceManager(requireOwningSpace(documentId));
        return mapper.listAcl(documentId).stream().map(DocumentAclService::summary).toList();
    }

    /**
     * 例外授权的鉴权依据：文档<b>当前</b>所属空间的管理资格。
     *
     * <p>不接受调用方传入的空间 ID——否则管理员可以声称某文档属于自己管理的
     * 空间，从而对别人的文档开设例外。
     */
    private long requireOwningSpace(long documentId) {
        Long spaceId = mapper.findOwningSpaceId(documentId);
        if (spaceId == null) {
            throw IdentityException.notFound("文档不存在或已删除");
        }
        return spaceId;
    }

    /**
     * 拒绝任何非扩权语义的权限位。
     *
     * <p>两层拒绝：{@code manage} 在文档级别没有意义（管理文档的授权名单不等于
     * 管理空间），{@code review} 仅在空间执行。若被静默接受，都会制造"授了权"的假象。
     * {@code comment} 例外只为该文档增加发表资格，不隐含 view 或足够密级。真正的"收紧"请求在
     * 本表结构里无法表达——本表只存授予，不存拒绝——所以这里的拒绝是语义层面的第一道闸。
     */
    private static String requireWideningPermission(String permission) {
        if (permission == null || permission.isBlank()) {
            throw IdentityException.badRequest("permission 不能为空");
        }
        String normalized = permission.trim();
        if (PermissionService.PERM_MANAGE.equals(normalized)) {
            throw IdentityException.badRequest(
                    "文档例外只能扩权，manage 不能用在文档级例外上；请改用 view、download、edit 或 comment");
        }
        if (!GRANTABLE.contains(normalized)) {
            throw IdentityException.badRequest(
                    "权限位 " + normalized + " 不被支持，文档例外可授予的权限位仅为 view、download、edit、comment");
        }
        return normalized;
    }

    private long countClearanceBlocked(long documentId, PrincipalType principalType, long principalId) {
        List<Long> userIds = userIdsForPrincipal(principalType, principalId);
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
        return mapper.countDocumentAboveClearance(documentId, best) > 0 ? 1 : 0;
    }

    /**
     * 主体展开：只为回执统计密级，因此按主体类型直接查用户。
     */
    private List<Long> userIdsForPrincipal(PrincipalType type, long principalId) {
        return switch (type) {
            case USER -> mapper.findUserClearance(principalId) == null ? List.of() : List.of(principalId);
            default -> mapper.listAclUserIds(type.code(), principalId);
        };
    }

    private SpaceAclGrantResult replay(AuditScope scope, long documentId, String requestId) {
        if (audit == null || requestId == null || requestId.isBlank()) {
            return null;
        }
        if (audit.existing(scope, requestId) == null) {
            return null;
        }
        List<SpaceAclSummary> acls = mapper.listAcl(documentId).stream()
                .map(DocumentAclService::summary).toList();
        return new SpaceAclGrantResult(acls.isEmpty() ? null : acls.get(0), true, 0);
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
        return "{\"documentId\":" + acl.spaceId() + ",\"principalKey\":\"" + acl.principalKey()
                + "\",\"permission\":\"" + acl.permission() + "\"}";
    }
}
