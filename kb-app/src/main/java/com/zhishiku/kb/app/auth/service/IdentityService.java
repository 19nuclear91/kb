package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.lifecycle.IdentityAccessState;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.model.DepartmentRecord;
import com.zhishiku.kb.app.auth.model.ExternalIdentityRecord;
import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import com.zhishiku.kb.app.auth.model.IdentityRecord;
import com.zhishiku.kb.app.auth.model.LocalCredentialRecord;
import com.zhishiku.kb.app.auth.model.ManagementChange;
import com.zhishiku.kb.app.auth.model.RoleRecord;
import com.zhishiku.kb.app.config.AuthPasswordProperties;
import com.zhishiku.kb.common.security.IdentityConflictReason;
import com.zhishiku.kb.common.security.IdentityErrorCode;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.IdentitySummary;
import com.zhishiku.kb.common.security.QuotaPrincipal;
import com.zhishiku.kb.common.security.ResolvedPrincipal;
import com.zhishiku.kb.common.security.RoleSummary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 用户生命周期、外部身份绑定与服务端主体解析。
 */
@Service
public class IdentityService {

    /**
     * 用户名长度上限。列类型是 TEXT，这个上限是输入面约束而非存储约束；
     * 取 64 与 {@code SpaceCreateRequest.code} 的既有上限一致。
     */
    public static final int USERNAME_MAX_LENGTH = 64;

    private final IdentityDepartmentMapper mapper;
    private final PermissionMapper permissionMapper;
    private final IdentityAuditService audit;
    private final SecurityLevelDictionary securityLevels;
    private final BCryptPasswordEncoder passwordEncoder;
    private final AuthPasswordProperties passwordProperties;
    private final ManagementInvariant management;

    public IdentityService(IdentityDepartmentMapper mapper) {
        this(mapper, null, null, null, null, null);
    }

    public IdentityService(IdentityDepartmentMapper mapper, PermissionMapper permissionMapper,
                           IdentityAuditService audit) {
        this(mapper, permissionMapper, audit, null, null, null);
    }

    public IdentityService(IdentityDepartmentMapper mapper, PermissionMapper permissionMapper,
                           IdentityAuditService audit, SecurityLevelDictionary securityLevels) {
        this(mapper, permissionMapper, audit, securityLevels, null, null);
    }

    @Autowired
    public IdentityService(IdentityDepartmentMapper mapper, PermissionMapper permissionMapper,
                           IdentityAuditService audit, SecurityLevelDictionary securityLevels,
                           BCryptPasswordEncoder passwordEncoder,
                           AuthPasswordProperties passwordProperties) {
        this.mapper = mapper;
        this.permissionMapper = permissionMapper;
        this.audit = audit;
        this.securityLevels = securityLevels;
        this.passwordEncoder = passwordEncoder;
        this.passwordProperties = passwordProperties;
        this.management = new ManagementInvariant(mapper);
    }

    @Transactional(readOnly = true)
    public IdentitySummary get(long userId) {
        return summary(requireIdentity(userId));
    }

    @Transactional(readOnly = true)
    public List<IdentitySummary> list() {
        return mapper.listIdentities().stream().map(IdentityService::summary).toList();
    }

    /**
     * 角色目录（任务 4.2）：返回服务端角色表的全部角色。
     *
     * <p>只读投影：内部 ID、稳定 code 与名称。角色取值永远来自服务端，
     * 分配与解除（任务 4.3）只能引用这里返回的标识。
     */
    @Transactional(readOnly = true)
    public List<RoleSummary> listRoles() {
        return mapper.findAllRoles().stream()
                .map(role -> new RoleSummary(role.getId(), role.getCode(), role.getName()))
                .toList();
    }

    /**
     * 在授权事务中确认角色仍存在，并持锁到引用写入提交，避免删除后留下悬空 ACL。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireRoleReference(long roleId) {
        if (mapper.findRoleForReference(roleId) == null) {
            throw IdentityException.badRequest("授权主体角色不存在");
        }
    }

    /**
     * 分配角色（任务 4.3）。
     *
     * <p>插入走 {@code ON CONFLICT DO NOTHING}：重复分配是幂等成功而不是错误。
     * 审计明细的 {@code created} 字段区分本次是否真正新建了绑定，历史仍然可读。
     *
     * <p>角色取值必须来自服务端角色表：先查后插，查不到即取值错误（400）。
     *
     * @param actorUserId 操作者，取自已验证会话；不接受请求体声明
     */
    @Transactional
    public void assignRole(long userId, long roleId, Long actorUserId, String requestId) {
        requireRoleCollaborators();
        String normalizedRequest = IdentityWriteSupport.requestId(requestId);
        if (audit.existing(AuditScope.USER_ROLE_ASSIGN, normalizedRequest) != null) {
            return;
        }
        IdentityWriteSupport.id(roleId, "roleId");
        management.lock();
        mapper.lockLifecycleForUser(userId);
        requireIdentity(userId);
        RoleRecord role = mapper.findRoleById(roleId);
        if (role == null) {
            throw IdentityException.badRequest("roleId " + roleId + " 不存在于角色表");
        }
        int created = mapper.insertUserRole(userId, roleId);
        audit.success(AuditScope.USER_ROLE_ASSIGN, normalizedRequest, actorUserId, userId,
                "assign_role", "{}",
                "{\"roleId\":" + roleId + ",\"roleCode\":\"" + json(role.getCode())
                        + "\",\"created\":" + (created == 1) + "}");
    }

    /**
     * 解除角色（任务 4.3）。
     *
     * <p>与分配不同，解除未绑定的角色返回 404 而不是幂等成功：删除 0 行意味着
     * 调用方对当前状态的认知与数据库不一致，静默返回成功会掩盖它。
     *
     * <p>按变更后用户权限并集保护管理资格，所有角色解除均在固定事务锁内判定。
     *
     * @param actorUserId 操作者，取自已验证会话；不接受请求体声明
     */
    @Transactional
    public void revokeRole(long userId, long roleId, Long actorUserId, String requestId) {
        requireRoleCollaborators();
        String normalizedRequest = IdentityWriteSupport.requestId(requestId);
        management.lock();
        if (audit.existing(AuditScope.USER_ROLE_REVOKE, normalizedRequest) != null) {
            return;
        }
        IdentityWriteSupport.id(roleId, "roleId");
        mapper.lockLifecycleForUser(userId);
        requireIdentity(userId);
        management.requireAfter(ManagementChange.unbindRole(userId, roleId));
        if (mapper.deleteUserRole(userId, roleId) != 1) {
            throw IdentityException.notFound("用户未绑定该角色");
        }
        audit.success(AuditScope.USER_ROLE_REVOKE, normalizedRequest, actorUserId, userId,
                "revoke_role",
                "{\"roleId\":" + roleId + ",\"bound\":true}",
                "{\"roleId\":" + roleId + ",\"bound\":false}");
    }

    /**
     * 管理员重置密码（任务 4.5）。
     *
     * <p>与自助改密（{@code USER_PASSWORD_CHANGE}）是两条独立路径：这里不校验
     * 原密码（管理员不知道也不应该知道它），代价是重置后必须强制目标用户改密——
     * 置新哈希的同时把 {@code must_change_password} 置真。否则一次重置会变成
     * 一次长期有效的凭据接管：目标用户的旧密码失效了，却没有任何机制让它知道
     * 或更换，新密码只有管理员见过。
     *
     * <p>顺序固定：重放 → 请求形状（强度）→ 数据库事实（用户存在）→ 写入 → 审计。
     * 强度校验放在查库之前：弱密码的拒绝不需要读任何数据，与建号（任务 4.1）
     * 的顺序一致。
     *
     * <p>重放命中时返回该用户的当前摘要：密码本身不可回显，摘要里也没有它，
     * 所以"首次结果"就是首次重置后的用户事实；与其余用户写操作的重放语义一致。
     *
     * @param actorUserId 操作者，取自已验证会话；不接受请求体声明
     */
    @Transactional
    public IdentitySummary resetPassword(long userId, String newPassword, Long actorUserId,
                                         String requestId) {
        requirePasswordResetCollaborators();
        String normalizedRequest = IdentityWriteSupport.requestId(requestId);
        if (audit.existing(AuditScope.USER_PASSWORD_RESET, normalizedRequest) != null) {
            return get(userId);
        }
        if (newPassword == null || newPassword.isBlank()) {
            throw IdentityException.badRequest("newPassword不能为空");
        }
        if (newPassword.length() < passwordProperties.minLength()) {
            throw IdentityException.badRequest(
                    "新密码长度不能少于 " + passwordProperties.minLength() + " 位");
        }
        management.lock();
        requireIdentityForUpdate(userId);
        management.requireEmergencyAfter(ManagementChange.disableUser(userId));
        Boolean mustChange = mapper.findMustChangePasswordById(userId);
        mapper.updatePassword(userId, passwordEncoder.encode(newPassword), true);
        // 审计明细只记标记状态，不含任何密码或哈希值
        audit.success(AuditScope.USER_PASSWORD_RESET, normalizedRequest, actorUserId, userId,
                "reset_password",
                "{\"mustChangePassword\":" + Boolean.TRUE.equals(mustChange) + "}",
                "{\"mustChangePassword\":true}");
        return get(userId);
    }

    @Transactional
    public IdentitySummary moveToDepartment(long userId, long departmentId, long version) {
        return moveToDepartment(userId, departmentId, version, null, null);
    }

    @Transactional
    public IdentitySummary moveToDepartment(long userId, long departmentId, long version,
                                            Long actorUserId, String requestId) {
        management.lock();
        if (audit != null && hasRequest(requestId) && audit.existing(AuditScope.USER_MOVE_DEPARTMENT, requestId) != null) {
            return get(userId);
        }
        IdentityWriteSupport.id(userId, "userId");
        IdentityWriteSupport.id(departmentId, "departmentId");
        IdentityWriteSupport.version(version);
        IdentityRecord before = requireIdentityForUpdate(userId);
        if (versionOf(before, "用户") != version) {
            throw versionConflict("用户已被其他操作更新");
        }
        DepartmentRecord target = mapper.findDepartment(departmentId);
        if (target == null) throw IdentityException.notFound("目标部门不存在");
        if (!Boolean.TRUE.equals(target.getEnabled())) {
            management.requireEmergencyAfter(ManagementChange.disableUser(userId));
            throw IdentityException.disabled("目标部门已停用");
        }
        if (mapper.moveUserToDepartment(userId, departmentId, version) != 1) {
            throw versionConflict("用户已被其他操作更新");
        }
        management.requireEmergencyAfter(ManagementChange.unchanged());
        IdentitySummary result = get(userId);
        auditSuccess(AuditScope.USER_MOVE_DEPARTMENT, requestId, actorUserId, userId, "move_department",
                state(before), state(result));
        return result;
    }

    @Transactional
    public IdentitySummary setEnabled(long userId, boolean enabled, long version) {
        return setEnabled(userId, enabled, version, null, null);
    }

    @Transactional
    public IdentitySummary setEnabled(long userId, boolean enabled, long version,
                                      Long actorUserId, String requestId) {
        management.lock();
        AuditScope auditScope = enabled ? AuditScope.USER_ENABLE : AuditScope.USER_DISABLE;
        IdentityRecord before = requireIdentityForUpdate(userId);
        IdentityAccessState previous = IdentityAccessState.from(before);
        IdentityAccessState next = previous.manual(enabled);
        if (audit != null && hasRequest(requestId) && audit.existing(auditScope, requestId) != null) {
            return get(userId);
        }
        IdentityWriteSupport.version(version);
        if (versionOf(before, "用户") != version) {
            throw versionConflict("用户已被其他操作更新");
        }
        if (previous.equals(next)) {
            auditSuccess(auditScope, requestId, actorUserId, userId, enabled ? "enable" : "disable",
                    previous.auditJson(), next.auditJson());
            return summary(before);
        }
        if (enabled) {
            Long departmentId = before.getDepartmentId();
            if (departmentId == null || !isDepartmentEnabled(departmentId)) {
                throw IdentityException.disabled("用户主部门已停用");
            }
        } else if (previous.enabled()) {
            management.requireAfter(ManagementChange.disableUser(userId));
        }
        if (mapper.setUserAccessState(userId, next.manualEnabled(), next.enabled(), next.authEpoch(), version) != 1) {
            throw versionConflict("用户已被其他操作更新");
        }
        mapper.enqueueSessionCleanup(userId, previous.authEpoch());
        IdentitySummary result = get(userId);
        auditSuccess(auditScope, requestId, actorUserId, userId,
                enabled ? "enable" : "disable", previous.auditJson(), next.auditJson());
        return result;
    }

    /**
     * 调整用户密级。
     *
     * <h2>为什么没有"不带 requestId"的便捷重载</h2>
     *
     * <p>同类写操作都有一个省略 actor 与 requestId 的重载，密级调整<b>刻意没有</b>。
     * 密级是检索侧那条"与"关系里唯一能凭一次写操作放宽可见范围的因子，
     * 一次没有审计的调整就是一次查不出操作者的扩权。requestId 由
     * {@link IdentityWriteSupport#requestId} 强制非空，缺失即 400、不进入持久化。
     *
     * <h2>检查顺序不是随意的</h2>
     *
     * <p>重放判定在最前（与同类写操作一致，让重试拿到首次结果而不是第二次写入）；
     * 自我提权与字典校验在取行锁之前——两者都是纯判定，不该为一个必然被拒的请求
     * 去锁住一行；version 比较与更新行数双重判定在最后，前者给出清晰的冲突，
     * 后者兜住"比较与更新之间被人抢先"的窗口。
     *
     * @param actorUserId 操作者，取自已验证会话；为 {@code null} 时跳过自我提权判定，
     *                    因此控制器必须传入真实会话用户，不能接受请求体声明
     */
    @Transactional
    public IdentitySummary changeClearance(long userId, short clearanceLevel, long version,
                                           Long actorUserId, String requestId) {
        requireClearanceCollaborators();
        String normalizedRequest = IdentityWriteSupport.requestId(requestId);
        if (audit.existing(AuditScope.USER_CLEARANCE_CHANGE, normalizedRequest) != null) {
            return get(userId);
        }
        IdentityWriteSupport.id(userId, "userId");
        IdentityWriteSupport.version(version);
        if (actorUserId != null && actorUserId == userId) {
            throw IdentityException.forbidden("不能调整自己的密级");
        }
        if (!securityLevels.contains(clearanceLevel)) {
            throw IdentityException.badRequest(
                    securityLevels.rejectionMessage("clearanceLevel", clearanceLevel));
        }
        management.lock();
        IdentityRecord before = requireIdentityForUpdate(userId);
        if (versionOf(before, "用户") != version) {
            throw versionConflict("用户已被其他操作更新");
        }
        if (mapper.updateUserClearance(userId, clearanceLevel, version) != 1) {
            throw versionConflict("用户已被其他操作更新");
        }
        IdentitySummary result = get(userId);
        audit.success(AuditScope.USER_CLEARANCE_CHANGE, normalizedRequest, actorUserId, userId,
                "change_clearance",
                clearanceState(before.getId(), clearance(before), versionOf(before, "用户")),
                clearanceState(result.userId(), result.clearanceLevel(), result.version()));
        return result;
    }

    /**
     * 创建本地账号（设计 D9）。
     *
     * <p>重放判定在最前：请求标识由客户端提供，重试必须拿回首次创建的用户，
     * 而不是撞上唯一约束的 409——那是把一次成功的创建报成失败。
     *
     * <p>校验顺序：请求自身形状（用户名、显示名、部门 ID、密级、密码强度）全部
     * 在触碰数据库之前完成；部门与用户名的数据库事实随后判定；唯一约束在写入时
     * 兜底"预检查与插入之间"的并发窗口。
     *
     * <p>新用户 enabled 恒为 true、version 交给数据库默认值 0；初始密码经 bcrypt
     * 哈希后落库并置 {@code must_change_password = true}（设计 D5）。密码只在参数中
     * 出现一次，响应、审计与日志都不回显。
     *
     * @param actorUserId 操作者，取自已验证会话；不接受请求体声明
     * @return 创建后的用户身份摘要；命中同一请求标识的重放时返回首次创建的用户
     */
    @Transactional
    public IdentitySummary createUser(String username, String displayName, long departmentId,
                                      Short clearanceLevel, String initialPassword,
                                      Long actorUserId, String requestId) {
        requireUserCreationCollaborators();
        String normalizedRequest = IdentityWriteSupport.requestId(requestId);
        IdentityAuditRecord replay = audit.existing(AuditScope.USER_CREATE, normalizedRequest);
        if (replay != null) {
            if (replay.getTargetId() == null) {
                throw new IllegalStateException("USER_CREATE 审计事件缺少 targetId，无法重放");
            }
            return get(replay.getTargetId());
        }
        String normalizedUsername =
                IdentityWriteSupport.maxLength(username, "username", USERNAME_MAX_LENGTH);
        String normalizedDisplayName = IdentityWriteSupport.required(displayName, "displayName");
        IdentityWriteSupport.id(departmentId, "departmentId");
        if (!securityLevels.contains(clearanceLevel)) {
            throw IdentityException.badRequest(
                    securityLevels.rejectionMessage("clearanceLevel", clearanceLevel));
        }
        requirePasswordStrength(initialPassword);
        DepartmentRecord department = mapper.findDepartment(departmentId);
        if (department == null) throw IdentityException.notFound("目标部门不存在");
        if (!Boolean.TRUE.equals(department.getEnabled())) {
            throw IdentityException.disabled("目标部门已停用");
        }
        if (mapper.findIdentityByUsername(normalizedUsername) != null) {
            throw IdentityException.conflict(IdentityConflictReason.IDENTIFIER_CONFLICT, "用户名已存在");
        }
        long id = mapper.nextIdentityId();
        LocalCredentialRecord record = new LocalCredentialRecord();
        record.setId(id);
        record.setUsername(normalizedUsername);
        record.setDisplayName(normalizedDisplayName);
        record.setDepartmentId(departmentId);
        record.setClearanceLevel(clearanceLevel);
        record.setPasswordHash(passwordEncoder.encode(initialPassword));
        record.setMustChangePassword(true);
        try {
            if (mapper.insertIdentity(record) != 1) {
                throw IdentityException.conflict(IdentityErrorCode.CONFLICT, "用户创建失败");
            }
        } catch (DataIntegrityViolationException duplicate) {
            // 预检查与插入之间的并发窗口：唯一约束是最后一道判定
            throw IdentityException.conflict(IdentityErrorCode.CONFLICT, "用户名已存在");
        }
        IdentitySummary result = get(id);
        audit.success(AuditScope.USER_CREATE, normalizedRequest, actorUserId, id, "create_user",
                "{}", createdState(result));
        return result;
    }

    @Transactional
    public IdentitySummary bindExternal(long userId, String provider, String subject) {
        return bindExternal(userId, provider, subject, null, null);
    }

    @Transactional
    public IdentitySummary bindExternal(long userId, String provider, String subject,
                                        Long actorUserId, String requestId) {
        String checkedProvider = IdentityWriteSupport.provider(provider);
        if (checkedProvider.startsWith("sso-v1:"))
            throw IdentityException.badRequest("企业 SSO 身份须由本人验证后绑定");
        if (audit != null && hasRequest(requestId) && audit.existing(AuditScope.EXTERNAL_BIND, requestId) != null) {
            return get(userId);
        }
        IdentityWriteSupport.id(userId, "userId");
        String normalizedProvider = IdentityWriteSupport.provider(provider);
        String normalizedSubject = IdentityWriteSupport.subject(subject);
        IdentityRecord user = requireIdentity(userId);
        ExternalIdentityRecord existing = mapper.findExternalIdentity(normalizedProvider, normalizedSubject);
        if (existing != null) {
            if (userId == existing.getUserId() && Boolean.TRUE.equals(existing.getEnabled())) {
                auditSuccess(AuditScope.EXTERNAL_BIND, requestId, actorUserId, userId,
                        "bind", "{}", "{\"provider\":\"" + json(normalizedProvider) + "\"}");
                return summary(user);
            }
            throw IdentityException.conflict(IdentityErrorCode.CONFLICT, "外部身份已绑定其他用户");
        }
        ExternalIdentityRecord mapping = new ExternalIdentityRecord(
                null, userId, normalizedProvider, normalizedSubject, true);
        try {
            if (mapper.insertExternalIdentity(mapping) != 1) {
                throw IdentityException.conflict(IdentityErrorCode.CONFLICT, "外部身份绑定失败");
            }
        } catch (DataIntegrityViolationException e) {
            throw IdentityException.conflict(IdentityErrorCode.CONFLICT, "外部身份已被绑定");
        }
        auditSuccess(AuditScope.EXTERNAL_BIND, requestId, actorUserId, userId,
                "bind", "{}", "{\"provider\":\"" + json(normalizedProvider) + "\"}");
        return summary(user);
    }

    @Transactional
    public void unbindExternal(long userId, long identityId) {
        unbindExternal(userId, identityId, null, null);
    }

    @Transactional
    public void unbindExternal(long userId, long identityId, Long actorUserId, String requestId) {
        if (audit != null && hasRequest(requestId) && audit.existing(AuditScope.EXTERNAL_UNBIND, requestId) != null) {
            return;
        }
        IdentityWriteSupport.id(userId, "userId");
        IdentityWriteSupport.id(identityId, "identityId");
        ExternalIdentityRecord owned = mapper.findExternalIdentityById(identityId);
        if (owned == null || !Long.valueOf(userId).equals(owned.getUserId())) {
            throw IdentityException.notFound("外部身份不存在");
        }
        if (mapper.deleteExternalIdentity(identityId) != 1) {
            throw IdentityException.notFound("外部身份不存在");
        }
        auditSuccess(AuditScope.EXTERNAL_UNBIND, requestId, actorUserId, userId,
                "unbind", "{\"provider\":\"" + json(owned.getProvider()) + "\"}", "{}");
    }

    @Transactional(readOnly = true)
    public ResolvedPrincipal resolvePrincipal(long userId) {
        IdentityRecord user = requireIdentity(userId);
        if (!Boolean.TRUE.equals(user.getEnabled())) throw IdentityException.disabled("用户已停用");
        long departmentId = user.getDepartmentId() == null ? 0 : user.getDepartmentId();
        DepartmentRecord department = departmentId <= 0 ? null : mapper.findDepartment(departmentId);
        if (department == null) throw IdentityException.disabled("用户没有有效主部门");
        if (!Boolean.TRUE.equals(department.getEnabled())) throw IdentityException.disabled("用户主部门已停用");
        List<Long> roles = permissionMapper == null ? List.of() : permissionMapper.findRoleIds(userId);
        List<Long> chain = permissionMapper == null ? List.of() : permissionMapper.findDeptChainIds(userId);
        return new ResolvedPrincipal(userId, departmentId, clearance(user), roles, chain);
    }

    @Transactional(readOnly = true)
    public QuotaPrincipal quotaPrincipal(long userId) {
        ResolvedPrincipal principal = resolvePrincipal(userId);
        return new QuotaPrincipal(principal.userId(), principal.departmentId());
    }

    private IdentityRecord requireIdentity(long userId) {
        IdentityRecord record = mapper.findIdentity(userId);
        if (record == null) throw IdentityException.notFound("用户不存在");
        return record;
    }

    private IdentityRecord requireIdentityForUpdate(long userId) {
        mapper.lockLifecycleForUser(userId);
        IdentityRecord record = mapper.findIdentityForUpdate(userId);
        if (record == null) {
            throw IdentityException.notFound("用户不存在");
        }
        return record;
    }

    private boolean isDepartmentEnabled(long id) {
        DepartmentRecord department = mapper.findDepartment(id);
        return department != null && Boolean.TRUE.equals(department.getEnabled());
    }

    /**
     * 密级调整要么带齐字典与审计，要么根本不执行。
     *
     * <p>同类写操作对缺失的 {@code audit} 是宽容的——{@link #auditSuccess} 悄悄跳过，
     * 便于测试只关心主逻辑。密级调整不能照抄这份宽容：缺字典意味着取值校验被跳过，
     * 缺审计意味着一次扩权没有留下操作者，两者都是"静默放行"，与本变更修复的
     * 角色接线缺陷同形。
     *
     * <p>抛 {@link IllegalStateException} 而不是业务异常，因为这只可能是装配错误：
     * Spring 上下文里两者都是必然存在的 Bean，只有测试用的简化构造器会缺。
     */
    private void requireClearanceCollaborators() {
        if (securityLevels == null || audit == null) {
            throw new IllegalStateException(
                    "密级调整需要密级字典与审计服务，缺一不可；请使用四参数构造器");
        }
    }

    /**
     * 建号要么带齐字典、审计、密码编码器与密码策略，要么根本不执行。
     *
     * <p>与 {@link #requireClearanceCollaborators} 同理：缺任何一项都意味着
     * 取值校验或凭据处理被静默跳过，那是"建出一个无法安全登录的账号"的形态。
     */
    private void requireUserCreationCollaborators() {
        if (securityLevels == null || audit == null || passwordEncoder == null
                || passwordProperties == null) {
            throw new IllegalStateException(
                    "创建用户需要密级字典、审计、密码编码器与密码策略，缺一不可；请使用完整构造器");
        }
    }

    /**
     * 管理员重置密码要么带齐审计、密码编码器与密码策略，要么根本不执行。
     *
     * <p>与建号同理：缺任何一项都意味着强度校验或凭据处理被静默跳过，
     * 而重置的后果比建号更重——它直接替换一个既有账户的登录凭据。
     */
    private void requirePasswordResetCollaborators() {
        if (audit == null || passwordEncoder == null || passwordProperties == null) {
            throw new IllegalStateException(
                    "管理员重置密码需要审计、密码编码器与密码策略，缺一不可；请使用完整构造器");
        }
    }

    /**
     * 角色分配与解除要么带审计，要么根本不执行。
     *
     * <p>审计不是旁路记录：重放判定读的就是审计表，缺了它请求标识幂等
     * 就只剩一句空话，重试会变成重复写入。
     */
    private void requireRoleCollaborators() {
        if (audit == null) {
            throw new IllegalStateException(
                    "角色分配与解除需要审计服务；请使用三参数或完整构造器");
        }
    }


    /**
     * 初始密码强度：非空且长度不低于配置下限；密码不 trim，空白也是合法字符。
     */
    private void requirePasswordStrength(String password) {
        if (password == null || password.isBlank()) {
            throw IdentityException.badRequest("initialPassword不能为空");
        }
        if (password.length() < passwordProperties.minLength()) {
            throw IdentityException.badRequest(
                    "初始密码长度不能少于 " + passwordProperties.minLength() + " 位");
        }
    }

    private void auditSuccess(AuditScope scope, String requestId, Long actorUserId, Long targetId,
                              String operation, String before, String after) {
        if (audit != null && requestId != null && !requestId.isBlank()) {
            audit.success(scope, requestId, actorUserId, targetId, operation, before, after);
        }
    }

    private static boolean hasRequest(String requestId) {
        return requestId != null && !requestId.isBlank();
    }

    private static IdentitySummary summary(IdentityRecord record) {
        return new IdentitySummary(record.getId(), record.getUsername(), record.getDisplayName(),
                record.getDepartmentId(), clearance(record), Boolean.TRUE.equals(record.getEnabled()),
                versionOf(record, "用户"));
    }

    private static short clearance(IdentityRecord record) {
        return record.getClearanceLevel() == null ? 0 : record.getClearanceLevel();
    }

    private static long versionOf(IdentityRecord record, String ignored) {
        return record.getVersion() == null ? 0 : record.getVersion();
    }

    private static IdentityException versionConflict(String message) {
        return IdentityException.conflict(IdentityErrorCode.INVALID_VERSION, message);
    }

    private static String state(IdentityRecord record) {
        return "{\"id\":" + record.getId() + ",\"departmentId\":"
                + (record.getDepartmentId() == null ? "null" : record.getDepartmentId())
                + ",\"enabled\":" + Boolean.TRUE.equals(record.getEnabled())
                + ",\"version\":" + versionOf(record, "用户") + "}";
    }

    private static String state(IdentitySummary summary) {
        return "{\"id\":" + summary.userId() + ",\"departmentId\":"
                + (summary.departmentId() == null ? "null" : summary.departmentId())
                + ",\"enabled\":" + summary.enabled()
                + ",\"version\":" + summary.version() + "}";
    }

    /**
     * 密级调整的审计摘要。
     *
     * <p>不复用 {@link #state} 的原因是它不含密级：归属变更的摘要里没有
     * {@code clearanceLevel}，拿它来记密级调整，审计就只剩"某人改了某人"
     * 而读不出改成了几级——而前后密级恰好是这条事件唯一需要回答的事。
     */
    private static String clearanceState(long userId, short clearanceLevel, long version) {
        return "{\"id\":" + userId + ",\"clearanceLevel\":" + clearanceLevel
                + ",\"version\":" + version + "}";
    }

    /**
     * 建号的审计摘要：读得出用户名、初始密级与强制改密状态，不含密码或哈希。
     */
    private static String createdState(IdentitySummary summary) {
        return "{\"id\":" + summary.userId() + ",\"username\":\"" + json(summary.username())
                + "\",\"departmentId\":" + summary.departmentId()
                + ",\"clearanceLevel\":" + summary.clearanceLevel()
                + ",\"enabled\":" + summary.enabled()
                + ",\"version\":" + summary.version()
                + ",\"mustChangePassword\":true}";
    }

    private static String json(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
