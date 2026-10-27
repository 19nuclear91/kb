# Proposal

## Why

`IdentityManagementAuthorizer` 用 `StpUtil.checkRole("ROLE_ADMIN")` 判定全局管理资格，但仓库里没有任何 `StpInterface`
实现，登录流程也不往会话写入角色。Sa-Token 因此没有角色来源，`hasRole("ROLE_ADMIN")` 没有任何途径为真——space-authorization
与 document-relocation 两个已归档变更交付的全部管理接口（`/api/admin/identity`、`/api/admin/spaces`、
`/api/admin/documents/{id}/acl`、`/api/admin/documents/{id}/space`、`/api/admin/parse`）对任何调用者都返回 403。README 5.7 /
5.8 的验证步骤要求携带 `$ADMIN_TOKEN`，而当前没有任何途径能签发一个真正带 ROLE_ADMIN 的会话，这两节因此无法执行。V3 种子已经把
ROLE_ADMIN 赋给 alice 并注明她是本地管理操作者，缺的只是那段从 `sys_user_role` 到会话资格的桥。

这条断线之所以在 65 个测试全绿的情况下存活，是因为所有相关用例都 `mock(IdentityManagementAuthorizer.class)`——被 mock
掉的恰好是唯一接触 Sa-Token 角色的那一层。

同时 `sys_user.clearance_level` 没有任何写入口。密级是权限判定"与"关系的一半，document-relocation 的 spec 已经明确承诺"
密级调整需要独立操作"，该操作至今不存在，spec 处于空口许愿状态。

## What Changes

- 新增 kb-app 的 `StpInterface` 实现，从 `sys_user_role JOIN sys_role` **实时解析角色 code**，使 `ROLE_ADMIN`
  成为可判定的功能资格；角色撤销在下一个请求即生效，不依赖会话过期
- `getPermissionList` 返回空列表，并新增守护测试，阻止未来引入 `StpUtil.checkPermission` 后得到静默恒假的判定——那将是与本次完全同形的缺陷
- 新增防回归测试，覆盖"持有 ROLE_ADMIN 的用户经真实接线判定为全局管理员、撤销关联后判定为否"；该测试**不得**通过 mock
  `IdentityManagementAuthorizer` 满足
- 新增用户密级调整接口 `PATCH /api/admin/identity/users/{userId}/clearance`，要求 ROLE_ADMIN、`version` 乐观锁、
  `X-Request-Id` 幂等与不可变审计，沿用 V8 既有机制
- 操作者**不得**调整自己的 clearance，否则返回 403：ROLE_ADMIN 之上已无更高资格可供兜底，不挡住自提权等于让密级对管理员失效
- 密级取值校验改为查询 `sys_security_level` 字典，替换 `DocumentUploadService` 中硬编码的 `0..3`，使本变更对"密级分几级"
  这一仍待业务确认的信息不敏感

**不在本变更范围**：角色 CRUD、角色分配、文档密级调整（含 `kb_doc_chunk.security_level` 冗余同步与 Worker 行锁竞争）、密级字典
CRUD、生产首个管理员的引导机制。

## Capabilities

### New Capabilities

无。本变更不引入新能力：功能角色资格与用户密级都是既有 `identity-department` 能力的组成部分，新建能力会与它构成近重名。

### Modified Capabilities

- `identity-department`: 新增两条需求。其一，功能角色资格必须从服务端持久事实解析，且撤销必须及时生效——现有需求"
  身份与部门管理操作必须受保护"只约定了"仅允许具备身份管理权限的操作者"
  ，没有约定该资格从何而来，正是这个缺口让接线缺失成为合规实现。其二，用户密级调整必须是独立、受保护、可审计且禁止自我提权的操作，现有需求完全未覆盖密级写入。

## Impact

**代码**

- `kb-app`：新增 `StpInterface` 实现与角色 code 查询；`PermissionMapper` 增加按 userId 查角色 code 的方法；
  `IdentityService` / `IdentityManagementController` 增加密级调整路径；`AuditScope` 增加 `USER_CLEARANCE_CHANGE`
- `kb-app`：`DocumentUploadService` 的密级校验由硬编码区间改为字典查询
- 测试：新增不可被 mock 绕过的角色接线回归测试，以及 `getPermissionList` 守护测试

**不受影响**

- `kb-gateway`、`kb-ai`：两者的 `SaReactorFilter` 只做 `StpUtil.checkLogin()`，不做角色判定。角色解析需要数据库，而 kb-ai
  刻意不具备数据库依赖，因此 `StpInterface` 只落在 kb-app——这是既有架构边界的延伸，不是遗漏
- 数据库 schema：`sys_role`、`sys_user_role`、`sys_security_level` 均已存在，`sys_user.version` 乐观锁已由 V8 提供，本变更不需要新迁移

**已知边界（本变更不解除上生产条件）**

- 生产环境必须移除 V3 种子，而角色分配接口在范围外，因此**生产将没有任何 ROLE_ADMIN 且无法制造第一个**。本变更使管理接口在
  dev 环境可验证，不使其可上线；引导机制归后续变更
- 禁止自我提权挡住单人作案，但**两个管理员互相提权未被阻止**。与首期"文档例外只能扩权、不能收紧"同样处理：承认边界、写入
  spec、依赖审计事后追溯，而不是假装已解决
- 实时查库使撤销及时生效，但也意味着每次管理路径判定多一次查询。该路径不在检索热路径上，且与后续权限缓存变更共享同一套失效语义
