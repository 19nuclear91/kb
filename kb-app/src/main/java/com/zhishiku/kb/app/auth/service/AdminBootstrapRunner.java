package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.model.DepartmentRecord;
import com.zhishiku.kb.app.auth.model.IdentityRecord;
import com.zhishiku.kb.app.auth.model.LocalCredentialRecord;
import com.zhishiku.kb.app.auth.model.ManagementChange;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.model.RoleRecord;
import com.zhishiku.kb.app.config.AuthPasswordProperties;
import com.zhishiku.kb.app.config.BootstrapProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 首个管理员引导（设计 D10，任务 5.2）：空库部署时按配置建出第一名全局管理员。
 *
 * <p>实现 {@link ApplicationRunner} 而不是更早的钩子：Flyway 在上下文初始化期
 * 完成迁移，ApplicationRunner 在上下文就绪之后执行，sys_role / sys_dept /
 * sys_user 三张表必然已经存在；写在更早的钩子里等于赌迁移与建表的先后。
 *
 * <p>五步顺序（设计 D10，顺序本身是行为的一部分）：
 * <ol>
 *   <li>未配置用户名或密码：记 INFO 跳过，零动作——开发态默认。</li>
 *   <li>密码不合强度：抛异常让启动响亮地失败。静默启动出一个没有管理员的
 *       系统是最坏结局，宁可崩在启动日志里。</li>
 *   <li>已存在同时持有角色分配和角色写入权限的启用用户：零动作。这条让重复启动天然幂等。</li>
 *   <li>配置的管理员已存在但被停用：只记 WARN，不自动重新启用——停用是一次
 *       显式管理动作，引导不静默翻转它。</li>
 *   <li>否则在单事务内依次确保角色行、新角色权限全集、部门、用户与绑定，全程写审计：
 *       actor 为 null 表示系统动作，requestId 服务端生成。</li>
 * </ol>
 *
 * <p>不复用 {@link IdentityService}：它假设客户端语义（客户端 requestId、
 * 版本冲突、操作者必填、密码文案面向建号接口），引导是系统上下文，直接用
 * mapper 加审计更诚实，也避免为了适配而放宽服务层的校验。
 *
 * <p>多实例并发由唯一约束兜底：插入撞唯一键时重读，读不到才重新抛出。
 */
@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    /**
     * 角色行显示名与 V3 种子一致：同一 code 在开发与生产不应显示两个名字。
     */
    private static final String ADMIN_ROLE_NAME = "系统管理员";

    private final BootstrapProperties properties;
    private final IdentityDepartmentMapper mapper;
    private final IdentityAuditService audit;
    private final SecurityLevelDictionary securityLevels;
    private final BCryptPasswordEncoder passwordEncoder;
    private final AuthPasswordProperties passwordProperties;
    private final RolePermissionMapper rolePermissions;
    private final ManagementInvariant management;

    public AdminBootstrapRunner(BootstrapProperties properties, IdentityDepartmentMapper mapper,
                                IdentityAuditService audit, SecurityLevelDictionary securityLevels,
                                BCryptPasswordEncoder passwordEncoder,
                                AuthPasswordProperties passwordProperties,
                                RolePermissionMapper rolePermissions) {
        this.properties = properties;
        this.mapper = mapper;
        this.audit = audit;
        this.securityLevels = securityLevels;
        this.passwordEncoder = passwordEncoder;
        this.passwordProperties = passwordProperties;
        this.rolePermissions = rolePermissions;
        this.management = new ManagementInvariant(mapper);
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            log.info("未配置 kb.bootstrap.admin 的用户名或密码，跳过首个管理员引导");
            return;
        }
        BootstrapProperties.Admin admin = properties.admin();
        if (admin.password().length() < passwordProperties.minLength()) {
            throw new IllegalStateException("引导管理员密码长度不能少于 "
                    + passwordProperties.minLength() + " 位；请检查 KB_BOOTSTRAP_ADMIN_PASSWORD");
        }
        if (management.remainsAfter(ManagementChange.unchanged())) {
            log.info("已存在启用的全局管理员，引导零动作");
            return;
        }
        // 与管理资格收缩串行化；锁内重查，防止多个实例重复引导。
        management.lock();
        if (management.remainsAfter(ManagementChange.unchanged())) return;
        LocalCredentialRecord existing = mapper.findIdentityByUsername(admin.username());
        if (existing != null && !isEnabled(existing.getId())) {
            log.warn("配置的引导管理员 {} 已存在但被停用，只记 WARN，不自动重新启用", admin.username());
            return;
        }
        RoleSetup setup = ensureRole();
        RoleRecord role = setup.role();
        ensureRolePermissions(setup);
        DepartmentRecord department = ensureDepartment();
        long userId = existing != null ? existing.getId() : ensureUser(admin, department.getId());
        ensureBinding(userId, role.getId());
    }

    private boolean isEnabled(long userId) {
        IdentityRecord identity = mapper.findIdentity(userId);
        return identity != null && Boolean.TRUE.equals(identity.getEnabled());
    }

    /**
     * 确保 ROLE_ADMIN 角色行存在：空库（V3 种子已移除的生产）上它是引导的第一件产物。
     */
    private RoleSetup ensureRole() {
        RoleRecord existing = mapper.findRoleByCode("ROLE_ADMIN");
        if (existing != null) {
            return new RoleSetup(existing, false);
        }
        long roleId = mapper.nextRoleId();
        try {
            mapper.insertRole(roleId, "ROLE_ADMIN", ADMIN_ROLE_NAME);
        } catch (DataIntegrityViolationException concurrentInsert) {
            RoleRecord concurrent =
                    mapper.findRoleByCode("ROLE_ADMIN");
            if (concurrent != null) {
                return new RoleSetup(concurrent, false);
            }
            throw concurrentInsert;
        }
        audit.success(AuditScope.ROLE_CREATE, newRequestId(), null, roleId, "create_role", "{}",
                "{\"code\":\"" + json("ROLE_ADMIN")
                        + "\",\"name\":\"" + json(ADMIN_ROLE_NAME) + "\"}");
        return new RoleSetup(new RoleRecord(roleId, "ROLE_ADMIN", ADMIN_ROLE_NAME), true);
    }

    /**
     * 管理员角色的准备结果，用于区分本次新建角色与已存在角色。
     *
     * @param role    引导使用的管理员角色记录
     * @param created 是否由本次引导新建；只有新建角色才自动绑定功能权限全集
     */
    private record RoleSetup(RoleRecord role, boolean created) {
    }

    private void ensureRolePermissions(RoleSetup setup) {
        long roleId = setup.role().getId();
        if (!setup.created()) {
            var current = rolePermissions.findRolePermissions(roleId);
            var missing = Permission.codes().stream().filter(code -> !current.contains(code)).toList();
            if (!missing.isEmpty()) log.warn("既有 ROLE_ADMIN 权限不全，不自动修复，缺少：{}", missing);
            return;
        }
        for (String code : Permission.codes()) {
            rolePermissions.grantPermission(roleId, code);
            audit.success(AuditScope.ROLE_PERMISSION_GRANT, newRequestId(), null, roleId,
                    "grant_permission", "{}", "{\"permission\":\"" + code + "\"}");
        }
    }

    /**
     * 确保配置的部门存在：缺则建根部门，物化路径 {@code /<id>/}（设计 D10）。
     * 已存在则原样使用，不修改其名称或启停状态。
     */
    private DepartmentRecord ensureDepartment() {
        BootstrapProperties.Department configured = properties.department();
        DepartmentRecord existing = mapper.findDepartmentByCode(configured.code());
        if (existing != null) {
            return existing;
        }
        long departmentId = mapper.nextDepartmentId();
        DepartmentRecord created = new DepartmentRecord(departmentId, null, configured.code(),
                configured.name(), "/" + departmentId + "/", true, 0L, null, null);
        try {
            mapper.insertDepartment(created);
        } catch (DataIntegrityViolationException concurrentInsert) {
            DepartmentRecord concurrent = mapper.findDepartmentByCode(configured.code());
            if (concurrent != null) {
                return concurrent;
            }
            throw concurrentInsert;
        }
        audit.success(AuditScope.DEPARTMENT_CREATE, newRequestId(), null, departmentId,
                "create_department", "{}",
                "{\"code\":\"" + json(configured.code()) + "\",\"name\":\""
                        + json(configured.name()) + "\",\"path\":\"/" + departmentId + "/\"}");
        return created;
    }

    /**
     * 建引导管理员：启用、字典内密级、bcrypt 哈希、{@code must_change_password = true}。
     *
     * <p>密级不在字典内即启动失败——配置引用了服务端不存在的级别，与弱密码同族，
     * 都属于必须在启动时响亮暴露的配置错误。
     */
    private long ensureUser(BootstrapProperties.Admin admin, long departmentId) {
        if (!securityLevels.contains(admin.clearanceLevel())) {
            throw new IllegalStateException(securityLevels.rejectionMessage(
                    "kb.bootstrap.admin.clearance-level", admin.clearanceLevel()));
        }
        long userId = mapper.nextIdentityId();
        LocalCredentialRecord record = new LocalCredentialRecord();
        record.setId(userId);
        record.setUsername(admin.username());
        record.setDisplayName(admin.displayName());
        record.setDepartmentId(departmentId);
        record.setClearanceLevel(admin.clearanceLevel());
        record.setPasswordHash(passwordEncoder.encode(admin.password()));
        record.setMustChangePassword(true);
        try {
            if (mapper.insertIdentity(record) != 1) {
                throw new IllegalStateException("引导管理员创建失败");
            }
        } catch (DataIntegrityViolationException concurrentInsert) {
            LocalCredentialRecord concurrent = mapper.findIdentityByUsername(admin.username());
            if (concurrent != null) {
                return concurrent.getId();
            }
            throw concurrentInsert;
        }
        // 审计明细只记身份事实，不含任何密码或哈希值（与建号路径一致）
        audit.success(AuditScope.USER_CREATE, newRequestId(), null, userId, "create_user", "{}",
                "{\"username\":\"" + json(admin.username()) + "\",\"displayName\":\""
                        + json(admin.displayName()) + "\",\"departmentId\":" + departmentId
                        + ",\"clearanceLevel\":" + admin.clearanceLevel()
                        + ",\"mustChangePassword\":true}");
        return userId;
    }

    /**
     * 确保绑定（{@code ON CONFLICT DO NOTHING}）：重复绑定是幂等成功，
     * 审计明细的 {@code created} 字段记录本次是否真正新建。
     */
    private void ensureBinding(long userId, long roleId) {
        int created = mapper.insertUserRole(userId, roleId);
        audit.success(AuditScope.USER_ROLE_ASSIGN, newRequestId(), null, userId, "assign_role",
                "{}", "{\"roleId\":" + roleId + ",\"roleCode\":\""
                        + json("ROLE_ADMIN")
                        + "\",\"created\":" + (created == 1) + "}");
    }

    /**
     * 服务端生成请求标识：引导没有客户端调用方，审计的幂等键由服务端独占。
     */
    private static String newRequestId() {
        return UUID.randomUUID().toString();
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }
}
