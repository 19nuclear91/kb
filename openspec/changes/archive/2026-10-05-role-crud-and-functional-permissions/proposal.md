# Proposal

## Why

系统当前的**功能资格**判定是一个硬编码的单点布尔：`IdentityManagementAuthorizer.ROLE_GLOBAL_MANAGER = "ROLE_ADMIN"`，全部
21 处管理端点与 4 处空间短路共用它。后果有三：

1. **无法做职责分离**。"审计员只能看审计、不能改身份"这类诉求无处落地——持有 `ROLE_ADMIN` 即拥有管理面全部能力，不持有则一项都没有。
   `sys_role` 里除 `ROLE_ADMIN` 外的角色行（如 V3 种子的 `ROLE_AUDITOR`）没有任何代码读取，是纯装饰。
2. **角色行本身不可管理**。`sys_role` 只能由空库引导（`AdminBootstrapRunner.ensureRole`）创建，运行期没有任何增删改路径。README
   第八节把它列为"仍缺"项。
3. **"最后一名管理员"保护存在未覆盖路径**。现有 spec 要求保护"包括解除角色、停用用户以及任何等效路径"，但删除角色行这条路径随角色
   CRUD 一并出现，必须同时堵上。

## What Changes

- **BREAKING**：功能资格的来源从「用户持有的角色 code」改为「用户经角色持有的权限集」。
  `IdentityManagementAuthorizer.requireManager()` 与 `isGlobalManager()` 被按权限细分的判定取代。
- 新增权限目录：16 个功能权限，每一个都对应一个真实存在的检查点。权限 code 由 Java 枚举定义，数据库只存角色与权限的绑定；启动时校验数据库中的
  code 全部在枚举内，发现未知 code 即启动失败。
- 新增 `sys_role_permission` 表与角色行 CRUD（创建 / 改名 / 删除）。`code` 创建后不可变。
- 角色删除为硬删除，前置条件为**双侧为空**：`sys_user_role` 中无绑定用户，且 `kb_space_acl` / `kb_document_acl`
  中无以该角色为主体的授权行。任一侧非空即拒绝，并在错误中说明需要先撤销什么。
- 空间侧的"全局管理员短路"被取消，拆成两个显式权限：`space:grant-manage`（授出 manage 权限位）与 `space:manage-all`（绕过空间
  ACL 判定，使新建且尚无 ACL 的空间仍可被管理）。
- "最后一名管理员"不变量重新定义为：**系统内必须始终存在至少一个启用用户，同时持有 `identity:role:assign`
  与 `identity:role:write`**。该不变量由三条写路径共享守卫：解除用户角色、停用用户、移除角色的权限。
- 权限判定**不支持通配与隐式继承**。`ROLE_ADMIN` 退化为一个恰好绑定了全部权限的普通角色行，其权限集由迁移与引导显式维护。

### 不做什么（明确排除）

- **不改动资源授权**。空间 ACL、文档例外 ACL 与密级过滤继续下推进 SQL（`PermissionService` / `SearchMapper.xml`
  ），不迁移到框架层字符串权限。把文档可见性搬到 `checkPermission` 式判定等于回到"先召回再过滤"，与《架构决策记录.md》3.4
  的选型前提冲突。
- **不引入 Sa-Token 的 permission API**。`SaTokenRoleProvider.getPermissionList()` 继续返回空列表，
  `SaTokenPermissionApiGuardTest` 的生产代码 permission API 禁用断言原样保留；角色 API 辅助断言改用测试夹具验证分类，不要求生产代码保留角色
  API 调用。权限判定在 kb-app 自己的授权组件内完成，与"角色不写进共享会话"同理——kb-ai 物理上没有数据库依赖，不应替 kb-app
  搬运权限事实。
- 不为角色引入 `enabled` 列（删除即硬删，无停用态）。
- 不改动 `kb_space_acl` / `kb_document_acl` 的表结构与 `principal_type` 语义。

## Capabilities

### New Capabilities

- `functional-authorization`: 功能权限目录、角色行生命周期（创建/改名/删除）、角色与权限的绑定，以及权限判定语义（无通配、无隐式继承、缺失即拒绝）。与
  `identity-department` 分开的理由：后者描述"用户与部门的身份事实"，本能力描述"持有何种资格才能操作管理面"，两者变更节奏与关注点不同。

### Modified Capabilities

- `identity-department`: 四条 requirement 的行为改变。
    - 「身份与部门管理操作必须受保护」：由单一"身份管理权限"改为每个操作声明并判定自己的权限，持有一类不蕴含另一类。
    - 「功能角色资格必须从服务端持久的角色归属解析」：资格来源由角色归属改为角色所绑定权限的并集，并新增"
      角色权限集变更后既有会话立即按新集判定"。
    - 「用户密级调整必须是独立且受保护的操作」：绑定到独立的密级调整权限，明确持有用户/部门/角色管理权限**不**蕴含它。
    - 「系统必须始终保留至少一个可管理的全局管理员」：不变量由 `COUNT(启用的 ROLE_ADMIN 持有者) > 0` 改为"存在同时持有
      `identity:role:assign` 与 `identity:role:write` 的启用用户"，新增"移除角色权限"这条等效路径，并明确判定基于变更后的全局状态而非被变更实体的局部状态。
- `space-authorization`: 两条 requirement 的行为改变。
    - 「空间管理资格必须按空间逐一判定」：兜底资格由 `ROLE_ADMIN` 改为独立的 `space:manage-all`
      ，并明确它不被任何其他管理权限蕴含（防止"死信处理员"之类的角色意外看到全部空间）。
    - 「manage 授权只能由全局管理员授出」：改为独立的 `space:grant-manage`，与 `space:manage-all` 分开——能管所有空间不等于能授出管理资格。

> 其余 identity-department requirement（用户账号创建、角色目录可查询、角色分配与解除）中"受全局身份管理资格保护"
> 的措辞，由上述「身份与部门管理操作必须受保护」统一重新定义，各自对应的具体权限见 design.md 的权限映射表，不单独出 delta。

## Impact

**数据库**

- 新增迁移 `V13`：建 `sys_role_permission(role_id, permission)`，并为既有 `ROLE_ADMIN` 行写入全部 16 条权限绑定。
- 后续每新增一个权限都需要一条显式 INSERT 迁移（无通配的代价）。配套启动期自检：枚举中的权限未全部出现在 `ROLE_ADMIN` 绑定中时打
  WARN，不 fail（部署方可能有意收窄）。

**kb-app 代码**

- `IdentityManagementAuthorizer`：`requireManager()` / `isGlobalManager()` 被 `require(Permission)` / `has(Permission)`
  取代，`ROLE_GLOBAL_MANAGER` 常量移除。
- `IdentityManagementController`（19 处）、`ParseDeadLetterController`（2 处）：逐个换成具体权限。
- `SpaceAuthorizer`（3 处）、`SpaceQueryService`（1 处）：换成 `space:grant-manage` / `space:manage-all`。传入
  `SpaceMapper.listVisible` / `findById` 的布尔标志只换来源，SQL 形状不变。
- `SaTokenRoleProvider`：新增按用户解析权限集的查询；`getPermissionList()` 保持返回空。
- `AdminBootstrapRunner`：`countEnabledHolders("ROLE_ADMIN")` 改为不变量判定；`ensureRole()` 后增加
  `ensureRolePermissions(全集)`。
- 新增角色行 CRUD 的 controller / service / mapper，以及 `AuditScope` 的 `ROLE_RENAME`、`ROLE_DELETE`、
  `ROLE_PERMISSION_GRANT`、`ROLE_PERMISSION_REVOKE`（`ROLE_CREATE` 已存在，复用）。

**API**

- 新增 `POST /api/admin/identity/roles`、`PATCH /api/admin/identity/roles/{roleId}`、
  `DELETE /api/admin/identity/roles/{roleId}`、`PUT /api/admin/identity/roles/{roleId}/permissions`。
- 既有管理端点的鉴权语义改变：此前只有 `ROLE_ADMIN` 持有者可用，此后按各自权限判定。升级后既有 `ROLE_ADMIN`
  持有者的可用范围不变（其权限集为全集）。
- 顺带修复一处可用性断裂：`identity:role:read` / `identity:dept:read` 可单独授予空间管理员，使其在授权时能查询主体目录，不必提升为全局管理员。

**测试**

- `SaTokenPermissionApiGuardTest` 的生产代码 permission API 禁用断言原样保留；角色 API 辅助断言改用测试夹具验证分类，不要求生产代码保留角色
  API 调用（本变更不使用 Sa-Token permission API）。
- 需要新增覆盖：权限细分后"持有 A 不蕴含 B"、不变量的三条路径（含并发）、角色删除的双侧前置条件、`space:manage-all` 在新建无
  ACL 空间上的行为。
- 既有测试中按 `ROLE_ADMIN` 构造的管理员夹具需改为按权限构造。
