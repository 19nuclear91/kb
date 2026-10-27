package com.zhishiku.kb.app.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.model.ManagementChange;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.model.PermissionSummary;
import com.zhishiku.kb.app.auth.model.RoleDetail;
import com.zhishiku.kb.app.auth.model.RoleRecord;
import com.zhishiku.kb.common.security.IdentityConflictReason;
import com.zhishiku.kb.common.security.IdentityErrorCode;
import com.zhishiku.kb.common.security.IdentityException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 角色生命周期；写操作与审计在同一事务内，角色 code 创建后不可变。
 */
@Service
public class RoleManagementService {
    private final IdentityDepartmentMapper identities;
    private final RolePermissionMapper mapper;
    private final IdentityAuditService audit;
    private final ObjectMapper json;

    public RoleManagementService(IdentityDepartmentMapper identities, RolePermissionMapper mapper,
                                 IdentityAuditService audit, ObjectMapper json) {
        this.identities = identities;
        this.mapper = mapper;
        this.audit = audit;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public RoleDetail get(long roleId) {
        RoleRecord role = identities.findRoleById(roleId);
        if (role == null) throw IdentityException.notFound("角色不存在");
        return detail(role);
    }

    public List<PermissionSummary> catalog() {
        return Arrays.stream(Permission.values()).map(p -> new PermissionSummary(p.code(), p.description())).toList();
    }

    @Transactional
    public RoleDetail create(String code, String name, long actor, String requestId) {
        String request = IdentityWriteSupport.requestId(requestId);
        identities.lockManagement();
        var previous = audit.existing(AuditScope.ROLE_CREATE, request);
        if (previous != null) return get(previous.getTargetId());
        String normalizedCode = IdentityWriteSupport.maxLength(code, "code", 64);
        String normalizedName = IdentityWriteSupport.maxLength(name, "name", 128);
        if (identities.findRoleByCode(normalizedCode) != null) {
            throw IdentityException.conflict(IdentityConflictReason.IDENTIFIER_CONFLICT, "角色 code 已存在");
        }
        long id = identities.nextRoleId();
        try {
            if (identities.insertRole(id, normalizedCode, normalizedName) != 1) throw conflict("角色创建失败");
        } catch (DataIntegrityViolationException duplicate) {
            throw conflict("角色 code 已存在");
        }
        RoleDetail created = new RoleDetail(id, normalizedCode, normalizedName, List.of());
        audit.success(AuditScope.ROLE_CREATE, request, actor, id, "create_role", "{}", state(created));
        return created;
    }

    @Transactional
    public RoleDetail rename(long roleId, String name, String code, long actor, String requestId) {
        String request = IdentityWriteSupport.requestId(requestId);
        identities.lockManagement();
        var previous = audit.existing(AuditScope.ROLE_RENAME, request);
        if (previous != null) return get(previous.getTargetId());
        RoleRecord role = requireLockedRole(roleId);
        if (code != null && !code.equals(role.getCode())) throw IdentityException.badRequest("角色 code 创建后不可变");
        String normalizedName = IdentityWriteSupport.maxLength(name, "name", 128);
        RoleDetail before = detail(role);
        if (mapper.renameRole(roleId, normalizedName) != 1) throw conflict("角色更新失败");
        role.setName(normalizedName);
        RoleDetail after = detail(role);
        audit.success(AuditScope.ROLE_RENAME, request, actor, roleId, "rename_role", state(before), state(after));
        return after;
    }

    @Transactional
    public void delete(long roleId, long actor, String requestId) {
        String request = IdentityWriteSupport.requestId(requestId);
        identities.lockManagement();
        if (audit.existing(AuditScope.ROLE_DELETE, request) != null) return;
        RoleRecord role = requireLockedRole(roleId);
        long users = mapper.countBoundUsers(roleId);
        long acls = mapper.countAclReferences(roleId);
        if (users > 0 || acls > 0) {
            throw IdentityException.conflict(IdentityConflictReason.ROLE_IN_USE,
                    "删除角色前需先撤销：" + (users > 0 ? "用户绑定" : "")
                    + (users > 0 && acls > 0 ? "、" : "") + (acls > 0 ? "空间或文档授权记录" : ""));
        }
        RoleDetail before = detail(role);
        if (mapper.deleteRole(roleId) != 1) throw conflict("角色删除失败");
        audit.success(AuditScope.ROLE_DELETE, request, actor, roleId, "delete_role", state(before), "{}");
    }

    @Transactional
    public RoleDetail setPermission(long roleId, String permission, boolean granted, long actor, String requestId) {
        String request = IdentityWriteSupport.requestId(requestId);
        var management = new ManagementInvariant(identities);
        management.lock();
        AuditScope scope = granted ? AuditScope.ROLE_PERMISSION_GRANT : AuditScope.ROLE_PERMISSION_REVOKE;
        var previous = audit.existing(scope, request);
        if (previous != null) return get(previous.getTargetId());
        if (!Permission.contains(permission)) throw IdentityException.badRequest("未知功能权限：" + permission);
        RoleRecord role = requireLockedRole(roleId);
        RoleDetail before = detail(role);
        if (granted) {
            mapper.grantPermission(roleId, permission);
        } else {
            if (before.permissions().contains(permission)) {
                management.requireAfter(ManagementChange.revokePermission(roleId, permission));
            }
            mapper.revokePermission(roleId, permission);
        }
        RoleDetail after = detail(role);
        audit.success(scope, request, actor, roleId, granted ? "grant_permission" : "revoke_permission",
                state(Map.of("permission", permission, "permissions", before.permissions())),
                state(Map.of("permission", permission, "permissions", after.permissions())));
        return after;
    }

    private RoleRecord requireLockedRole(long roleId) {
        IdentityWriteSupport.id(roleId, "roleId");
        RoleRecord role = mapper.lockRole(roleId);
        if (role == null) throw IdentityException.notFound("角色不存在");
        return role;
    }

    private RoleDetail detail(RoleRecord role) {
        return new RoleDetail(role.getId(), role.getCode(), role.getName(), mapper.findRolePermissions(role.getId()));
    }

    private String state(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("角色审计序列化失败", e);
        }
    }

    private static IdentityException conflict(String message) {
        return IdentityException.conflict(IdentityErrorCode.CONFLICT, message);
    }
}
