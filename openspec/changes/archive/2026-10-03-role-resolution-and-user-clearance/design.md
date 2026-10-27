# Design

## Context

动机见 `proposal.md` 的 Why。这里只列塑造方案的现状与约束。

**角色资格的断点**：`StpUtil.checkRole` 经 Sa-Token 的 `StpInterface` 取角色，仓库内无任何实现，登录也不写会话角色，因此没有角色来源。

**角色在本系统有两种并存表示**，必须都保留：

```
sys_user_role --(role_id)--> "ROLE:<id>" --> kb_space_acl.principal_key   [ACL 主体，已在用]
sys_role.code --------------> "ROLE_ADMIN" --> StpUtil.checkRole          [功能资格，待接线]
```

ACL 侧的 `ROLE:<id>` 已经写进了生产数据形状（`kb_space_acl.principal_key`），不可改动；功能侧需要的是 code 字符串。
`ResolvedPrincipal.roles` 当前只带 `List<Long>`，不含 code。

**会话跨服务共享**：`sa-token.is-share: true`，kb-app / kb-gateway / kb-ai 三者都依赖 `sa-token-redis-jackson`
。会话里放什么，三个服务都会携带。

**kb-ai 没有数据库依赖，且由 `NoDatabaseAccessTest` 守住**，因此它物理上无法解析角色。

**会话寿命很长**：`timeout` 默认 2592000（30 天），`active-timeout: -1`（永不空闲过期）。任何依赖会话自然失效的撤销策略，延迟上限就是
30 天。

**既有写操作机制可直接复用**：V8 提供了 `sys_user.version` 乐观锁与 `sys_identity_audit`；`AuditScope` 以 `<实体>_<操作>`
枚举承载 `UNIQUE (operation_scope, request_id)` 幂等键；`IdentityWriteSupport` 提供 version / requestId 校验。

**两处需要特别注意的现状**：

- `IdentityManagementController` 的全部 13 个端点都调用 `authorizer.requireManager()`，而该方法只做 `checkLogin` +
  `checkRole`，**不校验用户是否仍启用**。`SpaceAuthorizer` 则刻意先查 `resolvePrincipal` 再判全局管理员，正是为了堵这个洞——两条路径的防护强度目前不一致。
- `sys_user.clearance_level` 与 `kb_document.security_level` 都有 `REFERENCES sys_security_level (level)` 外键，而
  `DocumentUploadService` 另外硬编码了 `0..3`。

## Goals / Non-Goals

**Goals**

- 让功能角色资格有唯一、可信、服务端的来源
- 角色撤销在下一个请求生效，不依赖会话过期
- 让这条接线的缺失在未来可被测试发现，而不是被 mock 掩盖
- 密级写入口与既有身份写操作共享同一套授权、幂等、乐观锁与审计语义
- 让本变更对"密级分几级"这一未决信息不敏感

**Non-Goals（设计层边界，范围边界见 proposal）**

- 不引入权限缓存。实时查库是本变更的选择，缓存与其失效语义归后续变更
- 不改动 ACL 侧的 `ROLE:<id>` 表示，也不统一两种角色表示
- 不扩展 `StpInterface` 的权限（permission）语义，只做角色
- 不改变 `requireManager()` 之外的既有鉴权调用链

## Decisions

### D1：`StpInterface` 实时查库，不把角色写进会话

|            | 实时查库（选定）                         | 登录时写入会话            |
|------------|----------------------------------|--------------------|
| 撤销生效       | 下一个请求                            | 最长 30 天            |
| 每次判定成本     | 一次 `sys_user_role JOIN sys_role` | 零                  |
| 角色事实的位置    | 只在 kb-app（唯一有库权限者）               | Redis 共享会话，三个服务都携带 |
| 与后续权限缓存的关系 | 同一套失效语义，一处解决                     | 另需会话踢出机制           |

选实时查库的决定性理由不是性能，而是**事实归属**：会话共享意味着把角色塞进会话会让 kb-gateway 和 kb-ai 替 kb-app 搬运权限事实，而
kb-ai 刻意不具备解析这些事实的能力。角色留在唯一有库权限的服务里，是"kb-ai 不得直连数据库"这道既有边界的延伸。

成本上，`checkRole` / `hasRole` 当前只出现在 `IdentityManagementAuthorizer` 两处，全部位于管理路径，不在检索热路径上。

**已考虑并否决**：登录时写会话 + 角色变更时主动踢出相关会话。它把一次查询换成了一套分布式会话失效机制，而那套机制的正确性比它省下的查询重要得多。

### D2：`StpInterface` 只落在 kb-app

kb-gateway 与 kb-ai 的 `SaReactorFilter` 都只做 `StpUtil.checkLogin()`，全仓库 `checkRole` / `hasRole` 仅 kb-app
两处。给另外两个模块补实现既无必要，对 kb-ai 还不可能（无数据库依赖）。

### D3：`getRoleList` 必须过滤已停用用户

这是本设计里安全权重最高的一条。`requireManager()` 不查 enabled，所以若 `getRoleList` 不过滤，**一个已停用但仍持有
ROLE_ADMIN 的账户将获得全部 13 个身份管理端点的访问权**——它的既有会话最长还能活 30 天。

在 `getRoleList` 的查询里过滤 `u.enabled = true`，而不是去给 `requireManager()` 补一次 enabled 校验，理由有两条：

1. **一处生效，覆盖全部调用方**。补在 `requireManager()` 只修一条路径；补在角色来源处，任何现在或将来调用 `checkRole` /
   `hasRole` 的代码都自动受保护。
2. **与既有 SQL 约定一致**。`PermissionMapper` 里 `findClearanceLevel` 带 `AND enabled = true`，`findDeptChainIds` 带
   `u.enabled = true AND d.enabled = true`，`findDepartmentId` 同样。停用用户在这个代码库里的既有语义就是"
   在权限解析层面不存在"。

`SpaceAuthorizer` 既有的 `requireEnabledOperator` 前置校验保留不动——它防的是另一件事（`resolvePrincipal` 抛出的停用异常不被降级成
`denyAll`），两者互补而非重复。

### D4：`getPermissionList` 返回空列表，并用测试钉住这个事实

没有任何代码使用 `checkPermission`，所以返回空当下是正确的。但它是一颗哑雷：将来谁写一行
`StpUtil.checkPermission("doc:read")`，会得到**静默恒假**的判定——和本变更要修的缺陷完全同形，且同样能被 mock 掉而测试全绿。

因此需要一个守护测试，断言"当前没有任何生产代码调用 Sa-Token 的 permission API"。这与 `NoDatabaseAccessTest`、
`ReactiveStackTest` 是同一类东西：把注释拦不住的约束钉成测试。一旦有人真要用 permission 语义，测试会先失败，迫使他先实现
`getPermissionList`。

**已考虑并否决**：让 `getPermissionList` 抛异常。那会把"误用"变成运行时故障而非编译期/测试期阻止，且 Sa-Token 可能在未预期的位置调用它。

### D5：防回归测试不得能被 mock 满足

本缺陷存活至今的原因是 `IdentityManagementControllerTest` 与 `SpaceAuthorizerTest` 都
`mock(IdentityManagementAuthorizer.class)`，而被 mock 掉的正是唯一接触 Sa-Token 的那一层。所以新测试不能再测 authorizer
的行为契约（那已有覆盖），必须测**接线本身**：

- 给定 `sys_user_role` 中持有 ROLE_ADMIN 的用户，`StpInterface` 实现返回包含该 code 的列表；撤销关联后不再返回
- 给定已停用用户，即使 `sys_user_role` 仍有关联，也返回空列表（D3）
- 存在一个 `StpInterface` Bean：没有它，`checkRole` 回落到框架默认实现并恒为假

前两条可在服务/映射层验证，第三条是装配断言。既有的 `mock` 用例全部保留——它们测的是另一层。

### D6：密级调整复用既有身份写操作模式，不新建机制

新增 `AuditScope.USER_CLEARANCE_CHANGE`，`targetType` 为 `user`，从而自动参与既有的 `UNIQUE (operation_scope, request_id)`
幂等命名空间。version 乐观锁、`X-Request-Id`、审计前后摘要全部沿用 `IdentityService` 中用户归属变更的既有路径。

升级与降级共用一个审计域：用户侧只有**升**是危险方向（看到更多），降只是收紧。这与文档侧相反——那边**降**才是不可逆的信息暴露——但文档密级在范围
3，两侧各用自己的审计域即可，现在不必拆成 RAISE / LOWER。

### D7：自我提权判定在服务端比较操作者与目标

`actorUserId == targetUserId` 时拒绝。操作者 ID 取自已验证会话，与既有审计的 actor 同源，不接受请求体声明。

### D8：密级取值先查字典，外键作为兜底

两侧外键已存在，但外键违例只会抛出数据库完整性异常，转换成面向调用方的错误信息既不稳定也不可读。所以在服务层先查一次
`sys_security_level`，给出明确的 400；外键继续作为最后防线。同时把 `DocumentUploadService` 的 `0..3`
硬编码换成同一个字典校验——这一处严格说属于上传路径，带进来是为了让"密级级数"不再散落在代码里，否则本变更声称的"
对级数不敏感"只有一半成立。

## Risks / Trade-offs

- **部署后既有 ROLE_ADMIN 持有者立即获得管理权** → 这正是本变更的意图，但它是一次**权限扩张**。上线前必须先审计目标环境的
  `sys_user_role` 内容，确认每一行都是预期的管理员。dev 环境会让 alice（V3 种子）获得权限，符合预期。
- **每次管理判定多一次数据库查询** → 管理路径非热路径；检索、文档读取等热路径完全不经过 `checkRole`。
- **禁止自我提权，但两名管理员互相提权未被阻止** → 已在 spec 中标为已知边界，依赖审计事后追溯。拦截它需要第二人复核或更高一级资格，均超出本变更。
- **生产将没有任何 ROLE_ADMIN 且无法制造第一个**（V3 种子上生产前必须移除，角色分配接口在范围外）→ 本变更不解除上生产条件，由范围
  3 承担引导机制。proposal 已明确记录。
- **密级字典移除仍被引用的级别会失败** → `sys_user.clearance_level` 与 `kb_document.security_level` 的外键默认
  RESTRICT，PG 会直接阻止，不会产生悬空取值。字典 CRUD 本身在范围外。
- **`getRoleList` 过滤 enabled 后，停用用户的既有会话仍能通过 `checkLogin`** →
  它们只能访问不需要角色的端点，与现状一致；彻底踢出停用用户的会话是另一个问题，不在本变更。

## Migration Plan

无数据库迁移：`sys_role`、`sys_user_role`、`sys_security_level` 均已存在，`sys_user.version` 由 V8 提供。

部署步骤：

1. 上线前审计目标环境 `sys_user_role` 中 ROLE_ADMIN 的持有者名单（见 Risks 第一条）
2. 部署 kb-app。管理端点从"对所有人 403"变为"按持久角色判定"
3. 验证：持有 ROLE_ADMIN 的账户可访问 `GET /api/admin/spaces`；不持有者仍 403；撤销关联后同一会话立即被拒
4. 验证通过后方可执行 README 5.7 / 5.8——在此之前它们缺少可用的 `$ADMIN_TOKEN`

回滚：移除 `StpInterface` Bean 即回到当前状态（管理端点全部 403）。密级调整接口可独立回滚，不影响接线部分。

## Open Questions

- **密级分几级、与用户 clearance 如何对应**（决策记录第 10 节"仍待收集"第 5 项）。D8 的字典驱动校验使这个问题退化为数据问题：答案到位时往
  `sys_security_level` 增删行即可，不改代码、不改本变更的 spec 与任务拆分。
- **范围 3 中生产首个管理员的引导形态**（配置引导 vs 角色分配接口 vs 其他）。不影响本变更的接口与数据形状。
