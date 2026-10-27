# Design

## Context

动机见 [proposal.md](proposal.md) 的 Why。此处只记录实现时必须先定下来的技术选择。

当前相关事实：

- 功能资格的唯一判定入口是 `IdentityManagementAuthorizer`，内部为 `StpUtil.checkRole("ROLE_ADMIN")`。全系统 25 个调用点：
  `IdentityManagementController` 19 处、`ParseDeadLetterController` 2 处、`SpaceAuthorizer` 3 处、`SpaceQueryService` 1 处。
- `SaTokenRoleProvider` 实现 `StpInterface`，每次判定实时查 `findRoleCodes(userId)`（连 `sys_role` 取 code，并过滤已停用用户）。角色
  **不写进会话**——会话经 Redis 与 kb-gateway、kb-ai 共享，而 kb-ai 刻意无数据库依赖，不应替 kb-app 搬运权限事实。
- `SaTokenRoleProvider.getPermissionList()` 固定返回空列表，`SaTokenPermissionApiGuardTest` 用 ArchUnit 断言生产代码不得调用
  Sa-Token 的 permission API（理由：调用会静默恒假）。
- 「最后一名管理员」现有保护在 `IdentityService.requireAnotherEnabledAdminRemains`：**先 `lockRoleByCode("ROLE_ADMIN")`
  取行锁，再判持有者数量**。注释明确记录了"先锁后判，不是先判后锁"的理由。
- `sys_role(id, code UNIQUE, name)`；`sys_user_role(user_id, role_id)` 带 `ON DELETE CASCADE`；`kb_space_acl` /
  `kb_document_acl` 的 `principal_id` 是多态列，**无外键**。

## Goals / Non-Goals

**Goals**

- 功能资格可按操作细分，且细分后的默认行为是"未显式授予即拒绝"。
- 不变量（管理面不锁死）在新模型下仍然成立，且在并发下成立。
- 改造对资源授权链路（`PermissionService` → `SearchMapper.xml`）零影响。

**Non-Goals**（设计层面的额外边界，proposal 的范围之外）

- 不引入权限组、权限继承树或角色继承。
- 不为功能权限引入资源维度（如"只能管理某几个部门的用户"）。本次权限全部是全局的；资源维度的委派只存在于空间 ACL 中，维持现状。
- 不改变 `SaTokenRoleProvider` 实时查库的策略，也不新增权限缓存。

## Decisions

### D1：权限 code 用 Java 枚举定义，数据库只存绑定

**选择**：`Permission` 枚举持有全部 code 与用途说明；`sys_role_permission` 只存 `(role_id, permission_code)`
。启动时扫描全表，发现枚举外的 code 即启动失败。

**替代方案**：`sys_permission` 字典表（像 `sys_security_level` 那样）。否决理由——密级是**业务数据**
（分几级由业务方决定，见《架构决策记录.md》10.5），而权限是**代码能力**：没有对应检查点的权限 code
是死数据，但运维会误以为配置生效了。枚举让"传一个裸字符串进去"在编译期不可能发生，与 `AuditScope` 用枚举的理由同源。

**启动校验为什么是 fail 而不是 WARN**：未知 code
意味着数据库与代码版本不匹配（回滚了代码但没回滚数据，或手工改库）。此时权限视图是不完整的，继续运行会以"某人少了一项能力"
的形式表现，极难归因。宁可崩在启动日志里——与 `AdminBootstrapRunner` 对弱密码的处理同一取向。

### D2：不使用 Sa-Token 的 permission API，判定自建

**选择**：`getPermissionList()` 继续返回空列表，`SaTokenPermissionApiGuardTest` 的生产代码 permission API 禁用断言**原样保留
**；角色 API 辅助断言使用测试夹具验证分类，不要求生产代码保留角色 API 调用。判定改为
`IdentityManagementAuthorizer.require(Permission)` / `has(Permission)`，内部查 `sys_user_role ⋈ sys_role_permission`。

**替代方案**：实现 `getPermissionList()`，调用点写 `StpUtil.checkPermission("identity:user:write")`。否决理由有二——

1. 那样必须放宽 guard test 的断言范围，拆掉一道已经证明有用的防线（它守的缺陷"`checkRole` 静默恒假"真实发生过）。
2. 框架层 permission 语义会诱使权限事实进入共享会话，而会话跨 kb-app / kb-gateway / kb-ai 共享。保持判定在 kb-app
   自己手里，与"角色不写进会话"是同一条边界的延伸。

代价是多写一个判定组件，收益是防线不动、边界更显式。

### D3：精确匹配，无通配、无继承

**选择**：权限 code 形如 `identity:user:write`，冒号只是命名约定，**判定时不做任何分段解析**。`space:*` 这类 code 按"枚举外
code"处理，走 D1 的启动失败。

**理由**：决定默认行为。有通配时，新增一个权限 `space:purge` 会让所有持 `space:*` 的角色在升级后自动获得它——静默扩权。无通配时，新增权限必须写一条显式
INSERT 迁移，变更可见且可审计。这与本项目"空主体集合 = 看不到任何东西，绝不能退化成不过滤"的一贯取向一致。

**代价与缓解**：每新增权限都要记得写迁移，忘了就是"升级后管理员静默少一项能力"。缓解是启动期自检——枚举中的权限未全部出现在
`ROLE_ADMIN` 绑定中时打 **WARN**（不是 fail：部署方可能有意收窄 `ROLE_ADMIN`）。

### D4：权限目录（16 项）与调用点映射

| 权限 code                    | 替换的调用点                                      |
|----------------------------|---------------------------------------------|
| `identity:user:read`       | `IdentityManagementController` :79 :91      |
| `identity:user:write`      | :119 建号、:245 转部门、:264 启停                    |
| `identity:user:password`   | :227 管理员重置密码                                |
| `identity:clearance:write` | :305 用户密级调整                                 |
| `identity:role:read`       | :139 角色目录                                   |
| `identity:role:assign`     | :168 分配、:197 解除                             |
| `identity:role:write`      | 新增：角色行 CRUD + 角色权限集变更                       |
| `identity:dept:read`       | :354 :367                                   |
| `identity:dept:write`      | :382 建、:400 改名、:418 移动、:436 启停              |
| `identity:external:write`  | :323 绑定、:341 解绑                             |
| `identity:audit:read`      | :457 审计检索                                   |
| `parse:deadletter:read`    | `ParseDeadLetterController:69`              |
| `parse:deadletter:replay`  | `ParseDeadLetterController:87`              |
| `space:create`             | `SpaceController` 建空间                       |
| `space:manage-all`         | `SpaceAuthorizer:62`、`SpaceQueryService:64` |
| `space:grant-manage`       | `SpaceAuthorizer:83`                        |

**`identity:clearance:write` 为什么单列**：密级是整个保密体系的根，且 spec 已有「操作者不得调整自己的密级」这条特殊保护。把它混进
`identity:user:write` 会让"能转部门"顺带"能提密级"。

**`space:manage-all` 为什么不是"拥有全部空间的 manage"**：`SpaceQueryService:58` 的注释记录了这个短路的真实目的——"
避免新空间尚无任何 ACL 时无人能管理"。新建空间的 ACL 表里一行都没有，任何基于 ACL 解析的资格都算不出来。所以语义必须是**绕过
ACL 判定**，而不是"在所有空间上持有 manage"。两者在"空间无 ACL"场景下结果不同。

**`space:manage-all` 与 `space:grant-manage` 为什么分开**：前者是"能管所有空间"，后者是"能授出管理资格"
。合并等于让任何跨空间管理员都能让管理资格扩散，与现有 spec「manage 授权只能由全局管理员授出」要堵的风险同源。

### D5：取消"通用短路"这个概念

`isGlobalManager()` 这个布尔值被删除，三处调用各自换成它真正需要的权限。

**这是本设计最容易出错的地方**。最省事的实现是把 `isGlobalManager()` 原地改成 `hasAnyAdminPermission()`，后果：

```
角色「死信处理员」只绑定 parse:deadletter:read / replay
      |
      v
hasAnyAdminPermission() == true
      |
      v
SpaceQueryService:64 短路 --> 看到系统内全部空间的列表
```

静默扩权，且现有测试（只覆盖 ROLE_ADMIN / 非管理员两种）不会失败。spec 的「持有其他管理权限但未持有跨空间管理权限」场景就是为这条钉的。

**实现面比看起来小**：这个布尔值最终是传进 SQL 的（`SpaceMapper.listVisible(viewIds, manageIds, globalManager)` /
`findById(..., globalManager)`），`SpaceMapper.xml` 按它决定是否加空间过滤谓词。改造只换布尔的**来源**，SQL 形状与参数签名都不动。

### D6：不变量的定义与并发机制

**不变量**：系统内必须存在至少一个启用用户，同时持有 `identity:role:assign` 与 `identity:role:write`。

两项缺一不可：只有 assign 没有 write，只能在既有角色间打转，改不了任何角色的能力；只有 write 没有 assign，改了角色也没法给人。

**判定必须基于变更后的全局状态**，不能看被改实体的局部状态。权限来自多角色并集：

```
alice: 角色X(role:assign) + 角色Y(role:write)
bob:   角色X(role:assign)

      从角色X移除 role:assign
                  |
                  v
alice 失去 assign（还有 write）；bob 什么都不剩
                  v
无人同时持有两者 --> 必须拒绝
```

判定形状（与变更在同一事务内）：

```sql
WITH after AS (
    SELECT ur.user_id, rp.permission
      FROM sys_user_role ur
      JOIN sys_role_permission rp ON rp.role_id = ur.role_id
     WHERE NOT (rp.role_id = :targetRole AND rp.permission = :removedPermission)
)
SELECT EXISTS (
    SELECT 1 FROM sys_user u
     WHERE u.enabled
       AND EXISTS (SELECT 1 FROM after a
                    WHERE a.user_id = u.id AND a.permission = 'identity:role:assign')
       AND EXISTS (SELECT 1 FROM after a
                    WHERE a.user_id = u.id AND a.permission = 'identity:role:write')
);
```

三条路径（解除用户角色 / 停用用户 / 移除角色权限）传入不同的"变更后"投影，共用同一个判定函数。

**并发**：现有实现靠 `lockRoleByCode("ROLE_ADMIN")` 取 `sys_role` 的行锁，"先锁后判"串行化所有相关路径。B
下这把锁不够了——承载这两项权限的角色集合随数据变化，无法事先确定要锁哪几行。

**选择**：在三条路径入口统一取**固定键的事务级 advisory lock**（`pg_advisory_xact_lock(<常量>)`），锁内判定、锁内写入，事务提交即释放。

**替代方案**：

- 先读出相关角色集合再逐行加锁 —— 集合本身就是竞态对象（读出后另一事务可能新增一个承载该权限的角色），且多行加锁需定义顺序防死锁。
- `SERIALIZABLE` 隔离级别 —— 需要在上层加序列化失败重试，影响面超出本变更。

固定 advisory lock 让这些管理写操作全局串行。代价可接受：它们是低频管理动作，不在检索或文档读取热路径上。这也正好延续现有"
先锁后判"的思路，只是把锁的粒度从"ROLE_ADMIN 这一行"提升为"一把管理面不变量锁"。

### D7：角色删除的双侧前置条件

硬删除，受理条件为**双侧无引用**：`sys_user_role` 中无绑定，且 `kb_space_acl` / `kb_document_acl` 中无
`principal_type = ROLE AND principal_id = <角色>` 的行。任一侧非空返回 409，错误信息说明需要先撤销哪一类。

**为什么不级联**：`sys_user_role` 有 `ON DELETE CASCADE`，删角色会悄悄解除全部用户绑定——那是一次无审计的批量资格变更。ACL
行连外键都没有，级联更无从谈起。显式前置条件让每一次解绑/撤销各自留下审计，事后能看清这个角色曾被授予给谁、曾被授权在哪些空间。

**为什么 ACL 侧也要求为空**：角色无人持有时那些 ACL 行确实不授予任何人任何东西，删除**当下**无害。风险在标识重用——备份恢复、
`setval` 回退、手工指定 id 插入之后，新角色会凭空继承旧角色的全部空间与文档授权。查询成本不是问题：
`idx_space_acl_principal (principal_key, permission)` 与 `idx_doc_acl_principal` 都能按 `principal_key = '2:<roleId>'`
走索引。

**附带收益**：这个前置条件让「删除角色」不可能破坏 D6 的不变量——绑定为空的角色不贡献任何人的任何权限，删除它不改变任何权限并集。所以删除路径
**不需要**重复判定不变量，守卫数量从五条降到三条。

### D8：`code` 创建后不可变

`PATCH` 只接受显示名。请求体携带与现值不同的 `code` 时返回 400，而不是静默忽略。

**理由**：`SaTokenRoleProvider` 实时查库，所以改 `code` 的效果在下一次请求即全量生效——把某个角色的 code 改写成另一个承载更高资格的
code，等于在零条角色分配审计的前提下批量提升它的全部持有者。而 `code` 本来就被定义为稳定标识（与 `sys_dept.dept_code`、
`sys_user.username` 同类），改名的真实需求由 `name` 承接。

在 D1（枚举定义权限）之下，"改 code 提权"的具体路径虽然被堵住了（code 不再直接映射资格，资格来自 `sys_role_permission`），但
code 仍是 ACL 主体引用与引导逻辑的锚点，保持不可变仍然正确且更简单。

## Risks / Trade-offs

- **D5 被实现成 `hasAnyAdminPermission()`** → 静默扩权，现有测试不会发现。缓解：spec
  已钉死「持有其他管理权限但未持有跨空间管理权限」场景；实现时必须新增该用例，不得复用既有的二值夹具。
- **忘记为新权限写 INSERT 迁移** → 升级后 `ROLE_ADMIN` 静默少一项能力。缓解：D3 的启动期 WARN 自检。
- **advisory lock 使管理写操作全局串行** → 批量操作（如一次停用大量用户）会变慢。缓解：接受。这些路径低频，且正确性优先于吞吐。若将来确实需要批量，应在一个事务内完成并只取一次锁。
- **既有测试夹具按 `ROLE_ADMIN` 构造管理员** → 改造后这些测试仍会通过（`ROLE_ADMIN` 绑定全集），因而**不会暴露细分是否真的生效
  **。缓解：每个细分权限都要有一个"只持有该权限"的正向用例与一个"持有相邻权限"的反向用例，不能只改夹具了事。
- **升级期窗口**：V13 迁移写入 `ROLE_ADMIN` 的全部绑定，若新代码先于迁移生效，管理员会短暂失去全部资格。缓解：迁移由 kb-app
  在启动时执行（Flyway 在上下文初始化期完成，早于 `ApplicationRunner`），单部署单元下不存在该窗口；多实例滚动升级时应确保迁移先完成。

## Migration Plan

1. **V13**：建
   `sys_role_permission(role_id BIGINT REFERENCES sys_role(id) ON DELETE CASCADE, permission TEXT, PRIMARY KEY(role_id, permission))`
   ；为既有 `code = 'ROLE_ADMIN'` 的角色行插入全部 16 条绑定。按 code 定位而非硬编码 id——V3 种子里是 id=1，生产引导出来的不一定是。
2. 代码改造：枚举 → 判定组件 → 25 个调用点 → 角色 CRUD → 引导。
3. **引导调整**：`AdminBootstrapRunner:85` 的 `countEnabledHolders("ROLE_ADMIN") > 0` 改为 D6 的不变量判定；`ensureRole()`
   之后增加 `ensureRolePermissions(全集)`。保持"不覆盖既有状态"语义——若 `ROLE_ADMIN` 行已存在但权限集被人改残了，**不修复
   **，只记 WARN（与现有"配置的管理员已存在但被停用只记 WARN"同款处理）。
4. **回滚**：V13 只新增表与行，不改既有表结构。回滚代码即可恢复旧判定（旧代码不读 `sys_role_permission`
   ）；表可以留着，不必回滚迁移。这是把权限绑定放在新表而不是给 `sys_role` 加列的附带好处。

## Open Questions

- 权限目录是否需要在 OpenAPI 中以枚举形式暴露给前端，供管理界面渲染权限勾选框？不影响 spec、判定语义与任务拆解，可在实现期决定。
- 角色列表接口是否需要返回每个角色的持有者数量？对"删除前要先解绑多少人"有用，但属于体验优化，可后补。
