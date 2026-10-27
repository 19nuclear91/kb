# Design

## Context

动机见 proposal.md 的 Why。以下只列影响方案选择的现状约束。

- 权限解析只有一个入口：`PermissionService.resolve(userId, permission)` 返回 `PermissionScope`（密级 + 可见空间集合 +
  主体复合键），所有读路径把它下推成 SQL 的 WHERE 条件。
- `PermissionMapper.findVisibleSpaceIds(principalKeys, permission)` 本来就按权限位参数化，`PermissionScope`
  本身也与权限位无关。因此新增 manage 维度**不需要改动 SQL 或模型类**，只需新增一个权限位常量。
- 既有身份/部门管理接口已经确立了写操作约定：`X-Request-Id` 幂等、`version` 乐观锁、审计在业务事务内写（审计失败则业务回滚）、
  `IdentityException` 自带 httpStatus。
- `sys_identity_audit` 的幂等键是 `UNIQUE (operation_scope, request_id)`，其中 `operation_scope` 由调用方代码传入，
  `request_id` 由客户端传入。现有部门操作传的是裸值 `CREATE` / `RENAME` / `MOVE` / `ENABLE` / `DISABLE`，用户操作传的是带前缀的
  `USER_*` / `EXTERNAL_*`。
- `kb_space` 没有 `version` 列。`kb_document.space_id` 的外键没有 `ON DELETE` 子句；`kb_space_acl.space_id` 是
  `ON DELETE CASCADE`。
- V1 的 `permission` CHECK 约束允许六个取值，其中 `manage`、`review`、`comment` 在全部 Java 代码与 mapper 中没有任何引用。

## Goals / Non-Goals

**Goals:**

- 让委派授权在不新增权限解析路径的前提下落地——manage 走现有的同一条解析链。
- `archived` 在**单点**生效，而不是依赖每条读路径各自记得过滤。
- 消除审计幂等键的跨实体碰撞，并为后续实体（角色、密级）确立命名规范。
- 新接口的错误语义（403/404/409）不依赖全局错误码改造即可正确。

**Non-Goals:**

- 不引入 deny 语义，不实现"空间可见但某文档更严"的收紧。
- 不引入空间密级上限或空间级密级校验。
- 不实现权限解析缓存——缓存的失效事件由本变更与后续角色/密级变更共同决定，先把事件源做齐。
- 不给 `review` / `comment` 赋予执行语义。

## Decisions

### D1 授权模型采用空间级委派，而非仅全局管理员

`manage(S)` 的持有者管理空间 S 的授权；全局管理员负责建空间、授出 manage、改密级，并对全部空间兜底。

- **理由**：空间是既定的主授权单元；若只有全局管理员可授权，空间数量上去之后实践中必然退化为"给所有人发全局管理员"，比委派更危险。
  `manage` 这个槽位在 V1 就已预留。
- **承认的代价**：`manage(S)` 实际上等于 S 上的全部权限——持有者可以把 `edit` 授予自己所在部门，从而获得 edit。方案上无法回避，因此写进
  spec 并在接口文档明示：**授出 manage 的分量等同于授出 edit + download**。
- **备选（仅全局管理员）**：与现有鉴权器完全一致、改动最小，但不可委派，否决。

### D2 manage 的授予与撤销限定为全局管理员

实现上，`permission = manage` 的授权请求跳过空间级资格判定，只认全局管理员。否则 manage 持有者可以授出
manage，管理资格会沿着空间传染，委派退化为无界扩散。这个限制的实现成本接近于零。

### D3 密级不参与委派

`clearance_level` 的修改永久保留给全局管理员。密级是 ACL 之外的第二道闸，与 ACL
是合取关系；一旦空间管理员能改密级，合取就失去意义。因此后续"角色与密级管理"变更中的密级部分**不走** D1 的委派模型。

### D4 跨密级授权：接受，并以审计留痕作为约束手段

场景：密级为 1 的空间管理员，其空间内有密级 3 的文档（他本人读不到），他可以把 view 授予一个密级 3 的主体，从而决定一份自己无权查看的文档给谁看。由于
ACL 是空间级而空间内密级混合，这是常态而非边缘情况。

- **选定**：接受该信息流，依靠"manage 的发放完全由全局管理员把关"作为前置约束，并要求审计完整记录授权者、被授权主体与权限位，使其可事后追溯。首期没有
  deny 语义、没有对外分享外链，暴露面是收敛的。
- **否决（要求 manage 持有者的密级 ≥ 空间内最高密级）**：空间一旦接收更高密级的文档，现任管理员会自动失效——这种失效模式比它要防的风险更难运维。
- **否决（给空间增加密级上限列，超限文档禁止入库）**：引入了"空间密级"这一新概念，牵动上传校验与文档搬迁，应当单独立项而不是搭车。
- **配套要求**：授予响应必须回报因密级而当前不生效的主体数量。既然允许跨密级授权，管理员就更无法预判授权是否生效；开发种子里
  alice 对文档 4 的例外授权正是这个形状（`clearance=2` 对 `security_level=3`，授权存在但不可见，且 `verify_permission.sql`
  把"不可见"写成了预期）。没有这个回执，它会变成高频工单。

### D5 `archived` 的过滤点放在 `findVisibleSpaceIds`

在按主体反查可见空间的 SQL 里 JOIN `kb_space` 排除已归档空间。检索、列表、详情、下载、预览全部经由 `PermissionScope`
取得可见空间集合，因此单点改动即全链路生效。

- **manage 不过滤**。否则空间一被归档，被委派的管理员连"取消归档"都无法执行（全局管理员仍可兜底，但被委派者会永久失去该空间）。所以归档过滤按权限位区分：读权限位过滤，manage
  不过滤。
- **备选（各读路径分别补 WHERE）**：容易漏，且每新增一条读路径就多一个漏点，否决。

### D6 空间删除即归档，仅空空间允许真删

这条由 schema 直接推出而非设计选择：`kb_document.space_id` 外键没有 `ON DELETE` 子句，所以关联文档的空间 DELETE 必然被外键拒绝。而
`kb_space_acl.space_id` 是 `ON DELETE CASCADE`，因此空空间真删时授权记录自动清理，恰好满足 spec 中"不留下悬挂授权"的要求。

### D7 审计 scope 命名空间化，并回填部门的 `DEPARTMENT_*`

新实体一律使用带实体前缀的 scope：`SPACE_*`、`SPACE_ACL_*`、`DOC_ACL_*`。同时把部门的裸 scope 改为 `DEPARTMENT_*`。

不回填的后果是具体的：部门创建与空间创建撞上同一个客户端 `request_id` 时，空间创建会命中部门创建的审计行，被判定为重试，于是按既有实现返回
`get(previous.getTargetId())`——把一个部门 ID 当成空间 ID 查出来返回，而请求的空间根本没有被创建，调用方却收到成功响应。
`request_id` 完全由客户端提供且只校验非空，所以这不是理论风险。

该改名是 breaking，但只影响历史审计行的 scope 可读性与旧 `request_id` 的重放去重，不触及任何业务数据。

### D8 错误类型复用 `IdentityException` / `IdentityErrorCode`

两者已覆盖 `FORBIDDEN` / `NOT_FOUND` / `CONFLICT` / `INVALID_VERSION` / `INVALID_REQUEST`，且 `IdentityException` 自带
httpStatus，因此新接口直接获得正确状态码；而 `ApiException` 的 forbidden / notFound / badRequest 目前全部折叠为 500。代价是类名带
`identity` 语义债——不改名，以免牵动既有身份接口；可在后续错误码改造中统一。

### D9 `kb_space` 增加 `version` 列（V11）

使空间元数据写操作与既有身份/部门接口共用同一套乐观锁约定，前端只需记一套规则。ACL 的授予与撤销是 insert / delete 且有
`UNIQUE (space_id, principal_type, principal_id, permission)` 约束兜底，天然幂等，不需要 version。

### D10 可授予权限位限定为 view / download / edit

`review` 与 `comment` 没有任何执行代码。开放授予它们等于再造一个 `archived`：管理员点了、系统存了、什么也不会发生。等相应功能落地再放开。

## Risks / Trade-offs

- **manage 的分量等于空间全部权限** → 写进 spec 并在接口文档明示；发放由全局管理员把关（D1、D2）。
- **跨密级授权形成的信息流** → 以审计留痕换取可运维性，二期考虑空间密级上限（D4）。
- **manage 不蕴含 view**，因此会存在"能管名单但读不了内容"的操作者 → 这其实是职责分离的好性质，但反直觉，接口文档需明示，否则会被当缺陷上报。
- **`DEPARTMENT_*` 改名使旧 `request_id` 的重放去重失效** → 影响窗口仅为迁移瞬间仍在途的重试请求；当前只有开发环境存在审计数据。
- **文档搬迁所属空间后，原空间管理员开设的例外 ACL 遗留** → 本变更不实现文档搬迁，但该能力的实现必须清理遗留例外（授权依据随搬迁失效）。已在
  proposal 的 Impact 与 tasks 中记录为跨变更约束。
- **归档过滤给权限热路径增加一次 JOIN** → `findVisibleSpaceIds` 是按主体反查空间，结果集本身很小；必要时可对
  `kb_space (archived)` 补索引。

## Migration Plan

1. **V11**：`kb_space` 增加 `version BIGINT NOT NULL DEFAULT 0` 及非负 CHECK，与 V8 给 `sys_dept` / `sys_user` 加 version
   的做法一致。生产首次部署时 `kb_space` 无数据，种子空间只存在于开发环境。
2. **审计 scope 改名无需数据迁移**。历史行保留原有裸值；审计查询按 `target_type` + `target_id` 检索（现有索引即
   `(target_type, target_id, created_at DESC)`），不依赖 scope，因此历史记录仍可查。
3. **部署顺序**：V11 与代码同批发布。归档过滤与 manage 权限位是纯新增语义，对现有数据的效果是"
   没有任何空间被归档，因此可见性不变"。
4. **回滚**：V11 只加列，回滚代码时可保留该列不做反向迁移。已写入的 `SPACE_*` 审计行与 ACL 行在回滚后仍然是合法数据——ACL
   行会继续被权限解析读取，这是期望行为（授权不应因回滚而失效）。

## Open Questions

- 空间 `code` 的命名约束（长度、字符集、是否允许层级前缀）尚未与业务确认。暂沿用 `sys_dept.code` 的"1-128 非空白"约定。答案不影响
  spec、方案选择或任务拆分。
- 已归档空间内的文档是否应继续参与解析与索引重建。当前不阻断（归档只影响读取可见性），可按运维需要后续收紧。
