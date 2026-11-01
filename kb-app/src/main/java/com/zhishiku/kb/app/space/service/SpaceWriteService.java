package com.zhishiku.kb.app.space.service;

import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.space.mapper.SpaceMapper;
import com.zhishiku.kb.app.space.model.SpaceRecord;
import com.zhishiku.kb.common.security.IdentityConflictReason;
import com.zhishiku.kb.common.security.IdentityErrorCode;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.PrincipalType;
import com.zhishiku.kb.common.security.SpaceSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/**
 * 空间生命周期写操作。
 *
 * <p><b>幂等</b>：每个方法通过 {@code X-Request-Id} 与 {@link AuditScope} 组合去重，
 * 因为幂等键 {@code (operation_scope, request_id)} 依赖操作域区分实体——
 * 空间创建与部门创建共用一个裸 {@code CREATE} 时会互相冒充重试。
 *
 * <p><b>乐观锁</b>：元数据更新与归档都带 {@code version}，过期版本一律 409，
 * 已提交状态保持不变。
 */
@Service
public class SpaceWriteService {

    private static final Logger log = LoggerFactory.getLogger(SpaceWriteService.class);

    private final SpaceMapper mapper;
    private final SpaceAuthorizer authorizer;
    private final IdentityAuditService audit;

    public SpaceWriteService(SpaceMapper mapper, SpaceAuthorizer authorizer) {
        this(mapper, authorizer, null);
    }

    @Autowired
    public SpaceWriteService(SpaceMapper mapper, SpaceAuthorizer authorizer, IdentityAuditService audit) {
        this.mapper = mapper;
        this.authorizer = authorizer;
        this.audit = audit;
    }

    /**
     * 创建空间，并在同一事务内为创建者授予该空间的 manage。
     *
     * <p>两步必须原子：否则创建失败会留下一个无人可管理的空间，或留下指向
     * 不存在空间的孤立授权。这里靠的是同一个 {@code @Transactional} 边界——
     * 授权插入抛错时整个事务回滚。
     *
     * <p>创建资格由独立的 space:create 功能权限授予。创建者的 manage 与空间在同一事务内产生。
     */
    @Transactional
    public SpaceSummary create(String code, String name, String description,
                               Long actorUserId, String requestId) {
        long actor = authorizer.requireCreate();
        Long effectiveActor = actorUserId == null ? actor : actorUserId;
        SpaceSummary previous = replayOrNull(AuditScope.SPACE_CREATE, requestId);
        if (previous != null) return previous;

        String normalizedCode = required(code, "code");
        String normalizedName = required(name, "name");
        if (mapper.findByCode(normalizedCode) != null) {
            throw IdentityException.conflict(IdentityConflictReason.IDENTIFIER_CONFLICT, "空间编码已存在");
        }

        long id = mapper.nextSpaceId();
        SpaceRecord space = new SpaceRecord();
        space.setId(id);
        space.setCode(normalizedCode);
        space.setName(normalizedName);
        space.setDescription(description);
        if (mapper.insertSpace(space) != 1) {
            throw IdentityException.conflict(IdentityErrorCode.CONFLICT, "空间创建失败");
        }
        mapper.createCategoryTree(id);
        // 与空间同生共死：创建者立即成为该空间的管理员，无需再调授权接口
        mapper.grantAcl(id, (short) PrincipalType.USER.code(), effectiveActor,
                PermissionService.PERM_MANAGE);

        SpaceSummary created = snapshot(id);
        auditSuccess(AuditScope.SPACE_CREATE, requestId, effectiveActor, id, "create", null, created);
        return created;
    }

    /**
     * 更新名称与描述。归档状态不在此处变更。
     */
    @Transactional
    public SpaceSummary updateMetadata(long spaceId, String name, String description, long version,
                                       Long actorUserId, String requestId) {
        long actor = authorizer.requireSpaceManager(spaceId);
        Long effectiveActor = actorUserId == null ? actor : actorUserId;
        SpaceSummary previous = replayOrNull(AuditScope.SPACE_UPDATE, requestId);
        if (previous != null) return previous;

        requireVersion(version);
        SpaceRecord before = requireSpaceForUpdate(spaceId);
        if (versionOf(before) != version) throw versionConflict("空间已被其他操作更新");
        String normalizedName = required(name, "name");

        if (mapper.updateMetadata(spaceId, normalizedName, description, version) != 1) {
            throw versionConflict("空间已被其他操作更新");
        }
        SpaceSummary after = snapshot(spaceId);
        auditSuccess(AuditScope.SPACE_UPDATE, requestId, effectiveActor, spaceId,
                "update", state(before), after);
        return after;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SpaceSummary archive(long spaceId, long version, Long actorUserId, String requestId) {
        return setArchived(spaceId, true, version, actorUserId, requestId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SpaceSummary unarchive(long spaceId, long version, Long actorUserId, String requestId) {
        return setArchived(spaceId, false, version, actorUserId, requestId);
    }

    private SpaceSummary setArchived(long spaceId, boolean archived, long version,
                                     Long actorUserId, String requestId) {
        AuditScope scope = archived ? AuditScope.SPACE_ARCHIVE : AuditScope.SPACE_UNARCHIVE;
        long actor = authorizer.requireSpaceManager(spaceId);
        Long effectiveActor = actorUserId == null ? actor : actorUserId;
        SpaceSummary previous = replayOrNull(scope, requestId);
        if (previous != null) return previous;

        requireVersion(version);
        mapper.lockCategoryTree(spaceId);
        authorizer.requireSpaceManager(spaceId);
        SpaceRecord before = requireSpaceForUpdate(spaceId);
        authorizer.requireSpaceManager(spaceId);
        if (versionOf(before) != version) throw versionConflict("空间已被其他操作更新");
        if (Boolean.valueOf(archived).equals(before.getArchived())) {
            // 已是目标状态：不写乐观锁、不重复计入版本，但仍留审计以便重试可查
            SpaceSummary current = snapshot(spaceId);
            auditSuccess(scope, requestId, effectiveActor, spaceId,
                    archived ? "archive" : "unarchive", state(before), current);
            return current;
        }
        if (mapper.setArchived(spaceId, archived, version) != 1) {
            throw versionConflict("空间已被其他操作更新");
        }
        SpaceSummary after = snapshot(spaceId);
        auditSuccess(scope, requestId, effectiveActor, spaceId,
                archived ? "archive" : "unarchive", state(before), after);
        return after;
    }

    /**
     * 删除空间。
     *
     * <p>有活文档时拒绝并提示改用归档：{@code kb_document.space_id} 没有
     * {@code ON DELETE} 子句，数据库层面本来就会拒绝，与其让调用方收到一个
     * 晦涩的外键错误，不如先给出明确的 409。
     *
     * <p>空空间允许真删除，其 ACL 行由 {@code kb_space_acl} 的
     * {@code ON DELETE CASCADE} 一并移除，不留悬挂授权。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(long spaceId, Long actorUserId, String requestId) {
        long actor = authorizer.requireSpaceManager(spaceId);
        Long effectiveActor = actorUserId == null ? actor : actorUserId;
        if (replayOrNull(AuditScope.SPACE_DELETE, requestId) != null) return;

        mapper.lockCategoryTree(spaceId);
        authorizer.requireSpaceManager(spaceId);
        SpaceRecord before = requireSpaceForUpdate(spaceId);
        authorizer.requireSpaceManager(spaceId);
        long documents = mapper.countReferencedDocuments(spaceId);
        if (documents > 0) {
            throw IdentityException.conflict(IdentityConflictReason.SPACE_NOT_EMPTY,
                    "空间仍有文档引用，不能删除，请改用归档");
        }
        if (mapper.deleteSpace(spaceId) != 1) {
            throw IdentityException.notFound("空间不存在或已被删除");
        }
        // 先写审计再提交：审计与删除在同一事务，回滚时两者一起消失
        auditSuccess(AuditScope.SPACE_DELETE, requestId, effectiveActor, spaceId,
                "delete", state(before), null);
    }

    /**
     * 幂等重放：若该 requestId 已在对应操作域成功执行过，直接返回其结果。
     *
     * <p>返回 null 表示这不是重放，调用方继续执行。缺少 requestId 时不启用
     * 幂等（控制器会强制要求它，这里只是防御数据库无记录时的空指针）。
     */
    private SpaceSummary replayOrNull(AuditScope scope, String requestId) {
        if (audit == null || !hasRequest(requestId)) return null;
        var previous = audit.existing(scope, requestId);
        if (previous == null) return null;
        if (scope == AuditScope.SPACE_DELETE) return null;
        if (previous.getTargetId() == null) return null;
        log.debug("命中重复请求，返回既有结果: scope={} requestId={}", scope, requestId);
        return snapshot(previous.getTargetId());
    }

    private SpaceRecord requireSpaceForUpdate(long spaceId) {
        SpaceRecord record = mapper.findByIdForUpdate(spaceId);
        if (record == null) throw IdentityException.notFound("空间不存在");
        return record;
    }

    /**
     * 重新读取快照，供审计记录变更后的状态。
     */
    private SpaceSummary snapshot(long spaceId) {
        SpaceRecord record = mapper.findRawById(spaceId);
        if (record == null) throw IdentityException.notFound("空间不存在");
        return new SpaceSummary(record.getId(), record.getCode(), record.getName(),
                record.getDescription(), Boolean.TRUE.equals(record.getArchived()),
                versionOf(record), false, false);
    }

    private void auditSuccess(AuditScope scope, String requestId, Long actorUserId, Long targetId,
                              String operation, Object before, Object after) {
        if (audit == null || !hasRequest(requestId)) return;
        audit.success(scope, requestId, actorUserId, targetId, operation, state(before), state(after));
    }

    private static boolean hasRequest(String requestId) {
        return requestId != null && !requestId.isBlank();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw IdentityException.badRequest(field + " 不能为空");
        }
        return value.trim();
    }

    private static void requireVersion(long version) {
        if (version < 0) throw IdentityException.badRequest("version 必须为非负数");
    }

    private static long versionOf(SpaceRecord record) {
        return record.getVersion() == null ? 0L : record.getVersion();
    }

    private static IdentityException versionConflict(String message) {
        return IdentityException.conflict(IdentityErrorCode.INVALID_VERSION, message);
    }

    private static String state(Object value) {
        if (value == null) return "{}";
        if (value instanceof SpaceSummary s) return state(s);
        if (value instanceof SpaceRecord r) {
            return "{\"id\":" + r.getId() + ",\"code\":\"" + json(r.getCode()) + "\",\"name\":\""
                    + json(r.getName()) + "\",\"archived\":" + Boolean.TRUE.equals(r.getArchived())
                    + ",\"version\":" + versionOf(r) + "}";
        }
        return "{}";
    }

    private static String state(SpaceSummary s) {
        return "{\"id\":" + s.spaceId() + ",\"code\":\"" + json(s.code()) + "\",\"name\":\""
                + json(s.name()) + "\",\"archived\":" + s.archived()
                + ",\"version\":" + s.version() + "}";
    }

    private static String json(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }
}
