package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.model.DepartmentRecord;
import com.zhishiku.kb.common.security.DepartmentSummary;
import com.zhishiku.kb.common.security.IdentityConflictReason;
import com.zhishiku.kb.common.security.IdentityErrorCode;
import com.zhishiku.kb.common.security.IdentityException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 部门生命周期的写操作，统一运行在事务内。
 */
@Service
public class DepartmentService {

    private final IdentityDepartmentMapper mapper;
    private final IdentityAuditService audit;

    public DepartmentService(IdentityDepartmentMapper mapper) {
        this(mapper, null);
    }

    @Autowired
    public DepartmentService(IdentityDepartmentMapper mapper, IdentityAuditService audit) {
        this.mapper = mapper;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<DepartmentSummary> list() {
        return mapper.listDepartments().stream().map(DepartmentService::summary).toList();
    }

    @Transactional(readOnly = true)
    public DepartmentSummary get(long id) {
        return summary(requireDepartment(id));
    }

    @Transactional
    public DepartmentSummary create(String code, String name, Long parentId) {
        return create(code, name, parentId, null, null);
    }

    @Transactional
    public DepartmentSummary create(String code, String name, Long parentId,
                                    Long actorUserId, String requestId) {
        if (audit != null && hasRequest(requestId)) {
            var previous = audit.existing(AuditScope.DEPARTMENT_CREATE, requestId);
            if (previous != null && previous.getTargetId() != null) return get(previous.getTargetId());
        }
        String normalizedCode = IdentityWriteSupport.required(code, "code");
        String normalizedName = IdentityWriteSupport.required(name, "name");
        DepartmentRecord existing = mapper.findDepartmentByCode(normalizedCode);
        if (existing != null) {
            throw IdentityException.conflict(IdentityConflictReason.IDENTIFIER_CONFLICT,
                    "部门编码已存在");
        }

        DepartmentRecord parent = parentId == null ? null : requireDepartment(parentId);
        if (parent != null && !Boolean.TRUE.equals(parent.getEnabled())) {
            throw IdentityException.disabled("父部门已停用");
        }
        long id = mapper.nextDepartmentId();
        DepartmentRecord department = new DepartmentRecord(
                id, parentId, normalizedCode, normalizedName,
                parent == null ? DepartmentPath.forRoot(id) : DepartmentPath.forChild(parent.getPath(), id),
                true, 0L, null, null);
        if (mapper.insertDepartment(department) != 1) {
            throw IdentityException.conflict(IdentityErrorCode.CONFLICT,
                    "部门创建失败");
        }
        DepartmentSummary result = summary(department);
        auditSuccess(requestId, actorUserId, null, result, "create", AuditScope.DEPARTMENT_CREATE);
        return result;
    }

    @Transactional
    public DepartmentSummary rename(long id, String name, long version) {
        return rename(id, name, version, null, null);
    }

    @Transactional
    public DepartmentSummary rename(long id, String name, long version,
                                    Long actorUserId, String requestId) {
        if (audit != null && hasRequest(requestId)
                && audit.existing(AuditScope.DEPARTMENT_RENAME, requestId) != null) {
            return get(id);
        }
        IdentityWriteSupport.required(name, "name");
        requireVersion(version);
        DepartmentRecord before = requireDepartment(id);
        if (mapper.renameDepartment(id, name.trim(), version) != 1) {
            throw versionConflict("部门已被其他操作更新");
        }
        DepartmentSummary result = get(id);
        auditSuccess(requestId, actorUserId, before, result, "rename", AuditScope.DEPARTMENT_RENAME);
        return result;
    }

    @Transactional
    public DepartmentSummary move(long id, Long parentId, long version) {
        return move(id, parentId, version, null, null);
    }

    @Transactional
    public DepartmentSummary move(long id, Long parentId, long version,
                                  Long actorUserId, String requestId) {
        if (audit != null && hasRequest(requestId)
                && audit.existing(AuditScope.DEPARTMENT_MOVE, requestId) != null) {
            return get(id);
        }
        requireVersion(version);
        DepartmentRecord department = requireDepartmentForUpdate(id);
        if (versionOf(department) != version) {
            throw versionConflict("部门已被其他操作更新");
        }

        DepartmentRecord parent = parentId == null ? null : requireDepartment(parentId);
        if (parent != null && !Boolean.TRUE.equals(parent.getEnabled())) {
            throw IdentityException.disabled("目标父部门已停用");
        }
        if (parent != null && (parent.getId().equals(id)
                || DepartmentPath.isSameOrDescendant(parent.getPath(), department.getPath()))) {
            throw IdentityException.conflict(IdentityErrorCode.INVALID_HIERARCHY,
                    "部门移动会造成层级环");
        }

        String oldPrefix = DepartmentPath.requirePath(department.getPath());
        String newPath = parent == null
                ? DepartmentPath.forRoot(id)
                : DepartmentPath.forChild(parent.getPath(), id);
        if (oldPrefix.equals(newPath) && (parentId == null
                ? department.getParentId() == null
                : parentId.equals(department.getParentId()))) {
            return summary(department);
        }

        mapper.rewriteDescendantPaths(id, oldPrefix, newPath, oldPrefix + "%");
        if (mapper.moveDepartment(id, parentId, newPath, version) != 1) {
            throw versionConflict("部门已被其他操作更新");
        }
        DepartmentSummary result = get(id);
        auditSuccess(requestId, actorUserId, department, result, "move", AuditScope.DEPARTMENT_MOVE);
        return result;
    }

    @Transactional
    public DepartmentSummary setEnabled(long id, boolean enabled, long version) {
        return setEnabled(id, enabled, version, null, null);
    }

    @Transactional
    public DepartmentSummary setEnabled(long id, boolean enabled, long version,
                                        Long actorUserId, String requestId) {
        AuditScope auditScope = enabled ? AuditScope.DEPARTMENT_ENABLE : AuditScope.DEPARTMENT_DISABLE;
        if (audit != null && hasRequest(requestId) && audit.existing(auditScope, requestId) != null) {
            return get(id);
        }
        requireVersion(version);
        DepartmentRecord department = requireDepartmentForUpdate(id);
        if (enabled && department.getParentId() != null) {
            DepartmentRecord parent = requireDepartment(department.getParentId());
            if (!Boolean.TRUE.equals(parent.getEnabled())) {
                throw IdentityException.disabled("父部门已停用");
            }
        }
        if (!enabled && (mapper.countEnabledChildren(id) > 0 || mapper.countEnabledUsers(id) > 0)) {
            throw IdentityException.conflict(IdentityConflictReason.DEPARTMENT_NOT_EMPTY,
                    "部门仍有启用的子部门或用户，不能停用");
        }
        if (Boolean.valueOf(enabled).equals(department.getEnabled())) {
            auditSuccess(requestId, actorUserId, department, summary(department),
                    enabled ? "enable" : "disable", auditScope);
            return summary(department);
        }
        if (mapper.setDepartmentEnabled(id, enabled, version) != 1) {
            throw versionConflict("部门已被其他操作更新");
        }
        DepartmentSummary result = get(id);
        auditSuccess(requestId, actorUserId, department, result,
                enabled ? "enable" : "disable", auditScope);
        return result;
    }

    private void auditSuccess(String requestId, Long actorUserId,
                              Object before, Object after, String operation, AuditScope scope) {
        if (audit == null || requestId == null || requestId.isBlank()) return;
        audit.success(scope, requestId, actorUserId,
                after instanceof DepartmentSummary summary ? summary.departmentId() : null,
                operation, state(before), state(after));
    }

    private static boolean hasRequest(String requestId) {
        return requestId != null && !requestId.isBlank();
    }

    private static String state(Object value) {
        if (value == null) return "{}";
        if (value instanceof DepartmentSummary d) {
            return "{\"id\":" + d.departmentId() + ",\"code\":\""
                    + json(d.code()) + "\",\"name\":\"" + json(d.name())
                    + "\",\"parentId\":" + (d.parentId() == null ? "null" : d.parentId())
                    + ",\"enabled\":" + d.enabled() + ",\"version\":" + d.version() + "}";
        }
        if (value instanceof DepartmentRecord d) {
            return "{\"id\":" + d.getId() + ",\"code\":\""
                    + json(d.getCode()) + "\",\"name\":\"" + json(d.getName())
                    + "\",\"parentId\":" + (d.getParentId() == null ? "null" : d.getParentId())
                    + ",\"enabled\":" + Boolean.TRUE.equals(d.getEnabled())
                    + ",\"version\":" + (d.getVersion() == null ? 0 : d.getVersion()) + "}";
        }
        return "{}";
    }

    private static String json(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private DepartmentRecord requireDepartment(long id) {
        DepartmentRecord record = mapper.findDepartment(id);
        if (record == null) throw IdentityException.notFound("部门不存在");
        return record;
    }

    private DepartmentRecord requireDepartmentForUpdate(long id) {
        DepartmentRecord record = mapper.findDepartmentForUpdate(id);
        if (record == null) throw IdentityException.notFound("部门不存在");
        return record;
    }

    private static void requireVersion(long version) {
        if (version < 0) throw IdentityException.badRequest("version 必须为非负数");
    }

    private static IdentityException versionConflict(String message) {
        return IdentityException.conflict(
                IdentityErrorCode.INVALID_VERSION, message);
    }

    private static long versionOf(DepartmentRecord record) {
        return record.getVersion() == null ? 0L : record.getVersion();
    }

    private static DepartmentSummary summary(DepartmentRecord record) {
        return new DepartmentSummary(record.getId(), record.getCode(), record.getName(),
                record.getParentId(), record.getPath(), Boolean.TRUE.equals(record.getEnabled()),
                versionOf(record));
    }
}
