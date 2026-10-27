# Tasks

> 本清单内的任务**全部可在无 PostgreSQL / Redis / MinIO / Docker 的环境下执行**，验证手段限于单元测试、Mockito 桩、ArchUnit
> 约束与迁移脚本文本断言（沿用仓库既有 `*SqlShapeTest` / `*MigrationTest` 的做法）。不编写或执行集成与端到端验证，不启动应用或外部服务。文档及 OpenAPI/Javadoc 仅交付并人工核对，不新增或运行文档内容、注解描述及示例测试；测试按明确的单元测试类/方法白名单运行，打包跳过测试。

## 1. 权限目录与判定基座

- [x] 1.1 新增 `Permission` 枚举，定义 design D4 表中的 16 个权限 code 与用途说明；验证：单元测试断言 code 全局唯一、无空白、且不含
  `*`（通配按目录外 code 处理）
- [x] 1.2 新增迁移 `V13__role_permission.sql`：建
  `sys_role_permission(role_id, permission, PRIMARY KEY(role_id, permission))`，`role_id` 外键引用 `sys_role` 并
  `ON DELETE CASCADE`；按 `code = 'ROLE_ADMIN'` 定位角色行（不硬编码 id）插入全部 16 条绑定；验证：迁移脚本文本断言测试（读
  `.sql` 源文件，不连库）确认其中的权限 code 与 `Permission` 枚举逐一对应，漏一条即失败
- [x] 1.3 新增 mapper 查询：按用户解析权限集（`sys_user_role ⋈ sys_role_permission`，过滤已停用用户）、按角色查权限集、全表扫描权限
  code；验证：SQL 文本断言测试（读 `@Select` 注解内容，不连库）确认停用过滤写在查询侧而非调用侧
- [x] 1.4 在 `IdentityManagementAuthorizer` 实现 `require(Permission)` / `has(Permission)`，移除 `ROLE_GLOBAL_MANAGER`
  常量与 `requireManager()` / `isGlobalManager()`；判定为精确匹配，不做冒号分段解析；验证：单元测试以桩 mapper 覆盖"持有 A
  不蕴含 B"、"多角色权限并集"、"无权限的已登录用户被拒"
- [x] 1.5 新增启动期校验组件：`sys_role_permission` 中出现枚举外 code 即启动失败并指出该 code；另对"枚举中的权限未全部出现在
  `ROLE_ADMIN` 绑定中"打 WARN 不 fail；验证：两个单元测试直接构造该组件并注入返回预设 code 列表的桩 mapper（不启动 Spring
  上下文），分别断言 fail 与 WARN，且 fail 的异常消息包含未知 code
- [x] 1.6 确认 `SaTokenPermissionApiGuardTest` 仍然通过（本变更不使用 Sa-Token permission API，`getPermissionList()`
  保持返回空列表）；验证：生产代码 permission API 禁用断言原样绿灯；角色 API 辅助断言使用测试夹具验证其不在禁用范围，不要求生产代码保留角色调用（已获用户确认）

## 2. 身份与解析运维调用点迁移

- [x] 2.1 按 design D4 表替换 `IdentityManagementController` 的 19 处判定为各自的具体权限；验证：每个端点一个"
  只持有该权限"的正向用例 + 一个"只持有相邻权限"的反向用例，权限解析与服务层均以 Mockito 桩提供，不复用旧的二值管理员夹具
- [x] 2.2 替换 `ParseDeadLetterController` 的 2 处判定为 `parse:deadletter:read` / `parse:deadletter:replay`
  ；验证：单元测试断言只持有 read 的操作者提交 replay 得到 403
- [x] 2.3 为 `identity:clearance:write` 补充独立性测试：持有 `identity:user:write` 但未持有 `identity:clearance:write`
  的操作者提交密级调整返回 403；验证：该用例通过，且既有「操作者不得调整自己的密级」的单元测试不受影响
- [x] 2.4 更新 README「角色与密级管理」一行与 `/api/admin/identity` 相关说明，描述逐操作权限判定；交付说明并人工核对权限 code 与 `Permission` 枚举一致，不添加文档内容测试

## 3. 空间侧短路拆解

- [x] 3.1 把 `SpaceAuthorizer:83` 的判定换为 `space:grant-manage`；验证：单元测试断言持有 `space:manage-all` 但未持有
  `space:grant-manage` 的操作者授出 manage 得到 403
- [x] 3.2 把 `SpaceAuthorizer:62` 与 `SpaceQueryService:64` 的判定换为 `space:manage-all`，只替换传入
  `SpaceMapper.listVisible` / `findById` 的布尔来源，不改 SQL 形状与参数签名；验证：`SpaceMapper.xml` 的 diff 为空，且
  `SpaceAuthorizerTest` 等不依赖外部服务的既有空间测试全绿
- [x] 3.3 替换 `SpaceController` 建空间处的判定为 `space:create`；验证：单元测试断言只持有 `space:create` 的操作者能建空间，并以反射或
  ArchUnit 断言建空间方法仍带 `@Transactional`，不执行授权实际落库验证
- [x] 3.4 新增防扩权回归测试：桩出"只持有 `parse:deadletter:read`"的操作者，断言 `SpaceQueryService` 传给 mapper 的
  `globalManager` 标志为 `false` 且按其 ACL 范围过滤；验证：该用例通过——这是 design D5 指出的最易出错点，不得用二值夹具构造
- [x] 3.5 新增 `space:manage-all` 的桩用例：持有该权限时 `SpaceAuthorizer` 不再查询空间 ACL 即放行，且 `SpaceQueryService`
  传给 mapper 的 `globalManager` 为 `true`；验证：以 Mockito 断言 ACL 解析未被调用，确认"空间无 ACL 也可管理"的短路目的未被破坏

## 4. 角色行 CRUD

- [x] 4.1 新增 `AuditScope` 条目 `ROLE_RENAME`、`ROLE_DELETE`、`ROLE_PERMISSION_GRANT`、`ROLE_PERMISSION_REVOKE`（
  `ROLE_CREATE` 已存在，复用），`targetType` 统一为 `role`；验证：`AuditScopeTest` 风格的测试断言新条目带实体前缀、与既有域不共用幂等命名空间
- [x] 4.2 实现创建角色（`POST /api/admin/identity/roles`）：受 `identity:role:write` 保护、`code` 全局唯一冲突返回 409、带
  `X-Request-Id` 幂等、留审计、初始权限集为空；验证：单元测试以桩 mapper 覆盖成功 / code 冲突 / 未授权 /
  重试幂等四条，并断言新角色不授予任何资格
- [x] 4.3 实现改名（`PATCH /api/admin/identity/roles/{roleId}`）：只接受显示名；请求体携带与现值不同的 `code` 返回 400
  而非静默忽略；验证：单元测试断言改 code 被拒，且 mapper 的更新方法未被调用
- [x] 4.4 实现删除（`DELETE /api/admin/identity/roles/{roleId}`）：双侧前置条件——`sys_user_role` 无绑定且 `kb_space_acl` /
  `kb_document_acl` 无该角色主体行，任一侧非空返回 409 并说明需先撤销哪一类；验证：单元测试三条用例（双侧为空成功 / 仍有绑定用户
  409 / 仍有授权记录 409），并断言两类前置查询都被执行、409 分支下删除方法未被调用
- [x] 4.5 实现角色权限集查询（`GET` 角色详情返回权限 code 列表）与权限目录查询，均受 `identity:role:read`
  保护；验证：单元测试断言无资格操作者得到 403，有资格者拿到的目录与 `Permission` 枚举同源
- [x] 4.6 为四个角色端点补 Knife4j 的 `@Operation` 与 `@ApiResponses` 注解并更新 README 的接口示例；交付注解与说明并人工核对参数和实现签名，不添加文档注解测试或执行示例

## 5. 角色权限集变更与不变量守卫

- [x] 5.1 实现 design D6 的不变量判定函数：输入"变更后"投影，输出是否仍存在同时持有 `identity:role:assign` 与
  `identity:role:write` 的启用用户；验证：单元测试以桩 mapper 覆盖"权限分散在两个角色"的场景，断言判定按用户权限并集而非被改角色局部状态得出
- [x] 5.2 在三条路径（解除用户角色、停用用户、移除角色权限）入口统一取固定键 `pg_advisory_xact_lock`，锁内判定、锁内写入；移除
  `requireAnotherEnabledAdminRemains` 中基于 `lockRoleByCode("ROLE_ADMIN")` 的旧机制；验证：单元测试以 `InOrder`
  断言三条路径都在判定查询之前调用取锁方法（"先锁后判"），并以 SQL 文本断言确认取锁语句使用固定键
- [x] 5.3 实现权限授予 / 移除（`PUT /api/admin/identity/roles/{roleId}/permissions`）：受 `identity:role:write` 保护、权限
  code 必须在枚举内否则 400、带 `X-Request-Id` 幂等、留审计；验证：单元测试覆盖授予 / 移除 / 未知 code / 未授权 / 重试五条
- [x] 5.4 把移除权限接入 5.1 的守卫：移除会使不变量不成立时返回 409 并保持权限集不变；验证：单元测试断言该 409
  路径下写方法未被调用，并断言"持有者经其他角色仍绑定同一权限时资格不受影响"

## 6. 引导调整

- [x] 6.1 把 `AdminBootstrapRunner:85` 的 `countEnabledHolders("ROLE_ADMIN") > 0` 换为 5.1 的不变量判定；验证：单元测试以桩
  mapper 断言"已存在满足不变量的启用用户时引导零动作"（不写任何 mapper 方法）
- [x] 6.2 在 `ensureRole()` 之后增加 `ensureRolePermissions(全集)`；若 `ROLE_ADMIN` 行已存在但权限集被改残，**不修复**只记
  WARN；验证：两个单元测试分别断言空库引导写入全集、以及既有残缺权限集不触发写入且打 WARN
- [x] 6.3 更新 README 第六节「上生产前的硬性检查项」与首个管理员引导段落，说明 `ROLE_ADMIN` 现在是一个权限集为全集的普通角色行；交付说明并人工核对角色语义，不添加文案测试

## 7. 构建与架构约束验证

- [x] 7.1 按明确的进程内单元测试类/方法白名单运行回归，保留 `SaTokenPermissionApiGuardTest`、`ModuleBoundaryTest`、`GatewayRouteConfigTest`、`NoDatabaseAccessTest`、`ReactiveStackTest` 等纯架构检查，排除 PostgreSQL 烟测、集成及文档内容测试；执行 `mvn -B clean package '-DskipTests'`。验证：构建与白名单检查通过，记录实际执行范围，不将排除项记为通过；角色 API 辅助测试按 1.6 调整。

---

## 未纳入本清单的验证

真实 PostgreSQL、Redis 会话和已启动应用上的迁移、并发、持久化及跨服务行为不在本任务清单内，不能据离线单元测试宣称已经验证。
