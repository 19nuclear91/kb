# Design

## Context

动机与范围见 [proposal.md](proposal.md)，行为契约见 `specs/space-authorization/spec.md` 与 `specs/admin-web-spaces/spec.md`。以下只列约束实现的现状。

- `SpaceAclService.grantBatch`（`kb-app/.../space/service/SpaceAclService.java:115-184`）的顺序为：形状校验 → 资格 → `audit.existing` 重放 → 角色存在性 → 逐条写入 → `audit.success`。重放只核对 `actorUserId`、`targetId` 与快照 `spaceId`，不核对请求内容；快照解析失败抛 `IllegalStateException`（500）。快照 `after_state` 自上线起即包含 `items[]`（主体类型、主体 ID、权限位、alreadyGranted）。
- `IdentityAuditService.success` 先查后插，唯一键冲突时补查一次；在 PostgreSQL 中插入失败会使当前事务进入中止状态，补查同样失败，因此同标识并发提交的后到者可能得到 500（推断，未在真实库验证）。`appendSuccess` 为 `Propagation.MANDATORY`，要求调用方已串行化。
- `SpaceMapper.revokeAcl` 返回影响行数，0 表示原本不存在；单条撤销不校验角色存在性。
- 事务级 advisory lock 已有先例：`ParseDeadLetterMapper.lockReplayRequest`，`SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(#{key}, 0))`。
- 前端 `AclPanel.tsx` 的授权列表每行一个单条撤销按钮，无多选；批量授予使用 `useResourceWriteBindings` 保存待核对绑定、`useResourceNotice` 保存草稿；`onChanged` 在空间详情页同时重读空间摘要并触发 `access.check(true)`。`useAdminWrite` 以方法、路径与请求体生成意图键，内容变化即生成新请求标识，结果待核对时禁止新提交。
- `AuditSummaryProjector.rules` 对 `AuditScope` 无 `default`；`SPACE_ACL_BATCH_GRANT` 按计数级字段投影。

## Goals / Non-Goals

**Goals:**

- 一次人工确认对应一个请求标识、一个事务、一条审计事件和一份逐条回执，与批量授予对称。
- 两个批量接口共用同一套重放判定：串行化、绑定核对、内容核对，均从审计快照得出结论。
- 空间授权列表的多选不改变单条撤销、批量授予与文档例外面板的既有行为。

**Non-Goals:**

- 不修改单条授予与撤销的重放实现（`existing()` 返回 `get(0)`、撤销命中即返回）。
- 不为批量路由加入 reasonCode 投影白名单，与批量授予保持一致。
- 不在审计页逐条展开批次条目，不新增审计查询过滤条件。
- 不验证真实 PostgreSQL 的锁互斥、事务原子性与并发时序。

## Decisions

### 1. 按显式条目撤销，以（主体, 权限位）标识

请求体为 `items: [{principalType, principalId, permission}]`。考虑过两种替代：

- 沿用批量授予的"主体列表 × 权限位列表"：用户在列表上勾选的是独立行，笛卡尔积会移除未勾选的组合，语义错误。
- 以 ACL 行 ID 标识：行 ID 在"撤销后重新授予"时改变，页面读取后的旧 ID 会让撤销静默落空；且单条撤销、审计快照、批量授予回执都以（主体, 权限位）表达，换成行 ID 需要两套标识互转。

上限 300 与批量授予的最大条目数（50 × 6）对齐，审计快照规模可控。

### 2. 路由用 POST `/acl/batch-revoke`

单条撤销是带请求体的 DELETE，部分代理与客户端会丢弃 DELETE 请求体；批量请求体更大，改用 POST。路径与 `/acl/batch` 并列，网关 `/api/admin/**` 已覆盖，无需改路由配置。

### 3. 资格与校验顺序沿用批量授予

形状校验（400，不触库）→ 资格（403；条目含 manage 时整批 `requireGrantManage`，否则 `requireSpaceManager`）→ 加锁与重放 → 写入。不校验角色存在性：撤销已删除角色的残留授权正是需要支持的场景，且单条撤销也不校验。服务端不提供"最后一名 manage"保护，规格以 `space:manage-all` 兜底；风险由前端确认提示呈现（决定 7）。

### 4. 重放：先串行化，再从快照核对绑定与内容

两个批量接口在资格判定之后、查询审计之前，调用新增的 `SpaceMapper.lockBatchRequest(key)`，key 为 `操作域 + ":" + requestId`，执行 `pg_advisory_xact_lock(hashtextextended(key, 0))`。锁在事务结束时释放；不同 key 的哈希碰撞只会造成无关请求短暂串行，不影响正确性。

持锁后查询 `audit.existing(scope, requestId)`：

1. `actorUserId` 或 `targetId` 缺失或与本次不一致 → 409。
2. 解析 `after_state`；解析失败、`spaceId` 不一致或 `items` 缺失 → 409（不再抛 500，避免把绑定问题暴露为服务端故障）。
3. 内容核对：
   - 批量授予：从快照 `items` 取主体集合与权限位集合，与本次请求（已去重校验）的两个集合比较。快照自上线起即逐条记录主体与权限位，旧快照无需特殊分支。
   - 批量撤销：快照 `items` 的（主体, 权限位）集合与本次条目集合比较。
   - 不一致 → 409。
4. 全部一致 → 从快照重建回执，`replayed=true`，不查询 ACL、不重算计数、不写审计。

考虑过在快照中额外存一份请求内容摘要（哈希）：既有快照没有摘要，需要"无摘要只核对绑定"的兼容分支；而 `items` 已完整表达请求内容，直接比较集合更简单，且对旧快照同样严格。409 的消息沿用"请求标识已用于不同操作"，不区分是绑定还是内容不一致，不返回原快照任何字段。

持锁后审计改用严格的 `appendSuccess`：同标识已被串行化，插入冲突只可能来自绕过锁的路径，直接传播并回滚比补查更安全。

### 5. 撤销写入与回执

逐条调用 `revokeAcl`，`removed = 影响行数 > 0`。回执 record `SpaceAclBatchRevokeResult(spaceId, items[Item(principalType, principalId, permission, removed)], removedCount, absentCount, replayed)`，与 `SpaceAclBatchGrantResult` 同包、同风格，字段带 `@Schema` 说明。请求 record `SpaceAclBatchRevokeRequest(items)`，条目复用 `SpaceAclPrincipal` 的字段约束并加 `permission`。

审计：新增 `AuditScope.SPACE_ACL_BATCH_REVOKE("space-acl")`，operation 为 `batch-revoke`，`before_state` 为 `{}`，`after_state` 为 `{spaceId, items[], removedCount, absentCount}`。投影规则只取 `spaceId`（ID）、`removedCount`、`absentCount`（COUNT），与批量授予同一 Kind，不展开条目。考虑过把被移除条目放进 `before_state` 以表达"移除"方向：批量只投影计数，方向问题不出现；保持与批量授予相同的快照布局更便于取证查询。

### 6. 控制器

`SpaceController` 新增 `POST /{spaceId}/acl/batch-revoke`，与批量授予一致：请求标识只取 `X-Request-Id`，路径参数 `@Positive`，不加 `@Valid`（形状校验集中在服务内，保证 400 文案与上限说明一致）。

### 7. 前端：多选列表与确认提示

- `spaceApi.ts` 新增 `BatchRevokeItem`、`BatchRevokeResult` 与 `spaceWrites.revokeBatch(spaceId, items)`：构造意图时按（主体类型, 主体 ID, 权限位）去重，校验 1–300 条与权限位白名单，主体 ID 以精确整数回传。
- `AclPanel.tsx` 仅在 `kind === 'spaces'` 时渲染多选：
  - 可撤销行（manage 行要求 `space:grant-manage`）带复选框，选择键为 `principalType:principalId:permission`，不使用行 ID；选择保存在 `useResourceNotice('acl-revoke-draft:...')`，与批量授予草稿同样跨资格重核保留。
  - 渲染时以当前列表过滤选择，列表中已不存在的键不计入、不提交。
  - 确认摘要显示条目数与 manage 条目数；超过 300 或为 0 时禁用提交。
  - 提示条件只用页面已有事实：本人直接 manage = 选中行中 `principalType === 'USER' && principalId === me.user.userId && permission === 'manage'`；全部直接 manage = 列表中全部 manage 行都被选中且至少一条。不尝试推断角色或部门带来的资格。
- 提交使用独立的 `ResourceWriteDialog`，scope 为 `spaces:{id}:revoke-batch`，与批量授予共用 `useResourceWriteBindings()` 表但 scope 不同，互不影响。执行函数把 404/405 改写为"当前服务不支持批量撤销"，并在草稿中记录 unsupported 以禁用批量撤销按钮；单条撤销、授予不受影响。
- 成功：清空选择与其他回执，显示逐条已移除/原本不存在及两类计数，调用 `refresh()` 与 `onChanged()`。空间详情页的 `onChanged` 已触发 `access.check(true)`，本人失去资格时沿用既有"清除详情并显示无管理资格"流程，无需新增分支。

## Risks / Trade-offs

- [批量授予重放收紧] → 只有"同标识不同内容"受影响；现有前端内容变化即换标识，不会触发。非本前端的调用方可能从"拿到首次回执"变为 409，部署说明中写明。
- [一批坏条目阻塞其余条目] → 整批拒绝时保留选择，错误消息指明原因；不自动拆批。
- [撤光 manage] → 服务端按规格放行；前端两类提示覆盖可识别的情形，角色或部门带来的资格明确不在提示范围。
- [锁与原子性只能以调用顺序验证] → 单测断言"形状校验与资格失败时不加锁、不写入"以及"加锁先于审计查询"；真实互斥与回滚归入部署环境验证，不视为已验证。
- [列表规模] → `listAcl` 不分页，多选在单页内进行；空间授权条目超过数百条时页面性能未评估。
- [回退] → 回退 kb-app 后新前端批量撤销显示不可用；已写入的 `SPACE_ACL_BATCH_REVOKE` 审计行在旧版本中投影为摘要不可用，仍可读。

## Migration Plan

以下为部署说明的交付内容；本变更的实施任务不启动服务、不执行部署或真实环境验证。

1. 发布 kb-app：新增路由、操作域与投影，批量授予重放核对收紧；无数据库迁移。
2. 发布 kb-web：空间授权列表出现多选与批量撤销；文档面板不变。
3. 定向验证：含与不含 manage 的批次、部分不存在、超限与重复、相同标识与内容重放、相同标识不同内容 409、批量授予旧快照的重放、审计页对新操作域的摘要显示。
4. 回退：先回退 kb-web 即恢复无多选的列表；回退 kb-app 后批量授予恢复旧重放行为，新前端的批量撤销显示不可用。
