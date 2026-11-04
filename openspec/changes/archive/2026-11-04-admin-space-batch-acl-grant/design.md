# Design

## Context

动机与范围见 [proposal.md](proposal.md)，行为契约见 `specs/space-authorization/spec.md` 与 `specs/admin-web-spaces/spec.md`。本节只列约束现有实现的事实。

- `SpaceAclService.grant` 一次只处理一个主体一个权限位：先规范化权限位（400），再按是否 manage 分别走 `requireGrantManage` / `requireSpaceManager`（403），再对 ROLE 主体校验存在，最后 `countAcl` + `grantAcl`、按主体计算密级受阻数并写一条 `SPACE_ACL_GRANT` 审计。USER/DEPT 主体不校验存在。
- 审计幂等靠 `sys_identity_audit` 的 `UNIQUE (operation_scope, request_id)`；`after_state` 是 JSONB，`IdentityAuditService.existing(scope, requestId)` 返回含 `afterState` 字符串的记录。单条授予的重放实现 `existing()` 忽略传入的主体键，返回 `listAcl(spaceId).get(0)`，即空间中任意一条 ACL 而非本次授出的那条。
- `AuditSummaryProjector.rules` 是对 `AuditScope` 枚举无 `default` 的 `switch` 表达式，新增枚举值不补 case 无法编译；`detail()` 对无法识别的操作域捕获异常并返回空变更列表。
- 前端 `AclPanel.tsx` 按 `kind` 同时服务空间与文档面板；`PrincipalPicker.tsx` 在类型或搜索键变化时清空选择并回调 `null`；授予通过 `ResourceWriteDialog` + `spaceWrites.grant` 提交，一次写入对应一个 `X-Request-Id`，结果不确定时只允许按原内容重试或重读核对。

## Goals / Non-Goals

**Goals:**

- 一次人工确认对应一个请求标识、一个事务、一条审计事件和一份逐条回执。
- 批量路径的重放从审计快照重建回执，不继承单条路径的 `get(0)` 实现。
- 空间面板的多选不改变文档例外面板的单选行为。

**Non-Goals:**

- 不修改既有单条授予与撤销接口的行为、错误契约或重放实现。
- 不为 USER/DEPT 主体补充存在性校验，不收紧单条授予已接受的输入。
- 不提供批量撤销、跨空间批量或文档例外批量。
- 不在审计页逐条展开批次主体。

## Decisions

### 1. 服务端单事务、全有或全无，而不是前端循环单条接口

授权条目彼此独立且幂等，原子性对数据正确性并非必需；选择单事务的原因在前端：`useAdminWrite` 以单请求单结果为模型，循环发送会在中途网络不确定时同时出现已确认、待核对与未发送三类条目，现有"按原内容重试 / 重读核对 / 放弃"三选一无法表达。服务端整批处理把这一复杂度收回为一个结果。

校验顺序沿用单条授予：先做不触库的请求形状校验（列表非空、上限、重复、权限位合法，400），再判资格（403），再做需要读库的角色存在性校验，最后写入。资格先于角色校验，避免无资格调用者借 400 探测角色存在。

### 2. 资格按整批中最严格的权限位一次判定

权限位列表含 manage 则整批走 `requireGrantManage`，否则走 `requireSpaceManager(spaceId)`。考虑过按条目分别判定并部分放行：这会让"空间管理员提交含 manage 的批次"变成"manage 条目被静默丢弃、其余写入"，回执必须表达部分拒绝，审计也要区分；复杂且与"整批校验通过才写"矛盾。整批判定更简单，拒绝时信息也更清楚。

### 3. 新操作域 `SPACE_ACL_BATCH_GRANT`，一批一条事件，回执从快照重建

重放必须先通过当前请求的资格判定，再核对审计记录中的 `actorUserId`、`targetId` 与当前操作者和空间一致；绑定缺失或不匹配返回通用 409，不读取或暴露原快照。解析后还须核对快照 `spaceId`，不一致同样返回 409。

`(operation_scope, request_id)` 唯一约束排除了用 `SPACE_ACL_GRANT` 写 N 条的做法。新操作域的 `after_state` 保存 `spaceId`、`items[]`（每项 `principalType`、`principalId`、`permission`、`alreadyGranted`）、`grantedCount`、`alreadyGrantedCount`、`clearanceBlockedPrincipalCount`。重放时解析 `existing()` 返回记录的 `afterState` 原样重建回执，不重新查询 ACL、不重算受阻数，保证与首次一致。`before_state` 为 `{}`。

`AuditSummaryProjector` 为新操作域只投影 `spaceId`、`grantedCount`、`alreadyGrantedCount`、`clearanceBlockedPrincipalCount`，与现有计数级字段同一 `Kind`；逐条主体留在 JSONB 供取证查询，不进审计页。

### 4. 主体上限 50，权限位上限 6

50 与搬迁回执 `clearedExceptions` 的截断上限对齐；权限位只有六个合法值，去重后自然不超过 6。乘积最多 300 条，审计快照与单事务规模都可控。超限返回 400 并在消息中说明上限，不截断、不拆批。

### 5. 密级受阻数按主体计算，口径与单条一致

对去重后的每个主体，若其在本批至少有一条新写入条目，则按 `countClearanceBlocked` 的现有口径判定：有有效成员，且空间内存在密级高于这些成员最高密级的已发布文档时计为受阻；无有效成员不计入。这表示至少有一篇文档因密级不可访问，不表示所有文档均不可访问。全部条目已存在的主体不参与计算，与单条 `already ? 0 : count` 一致。结果是受阻主体数，不是受阻条目数。

### 6. 前端新增多选选择器与批量写意图，空间与文档面板分流

批量写入的未决绑定保存在会话内存中，按身份世代及空间操作域隔离，并通过 `useAdminWrite` 的 `bindings` 参数恢复。关闭弹窗及同一身份的资格重核不丢弃绑定；重开时仍待核对，禁止新提交，重试使用原内容和原请求标识。确认成功、明确拒绝、显式放弃核对时清除该绑定，身份变化时清空旧身份绑定，不写入浏览器持久存储。

`spaceApi.ts` 新增 `spaceWrites.grantBatch(spaceId, principals, permissions)` 与 `BatchGrantResult` 类型；主体与权限位列表在构造意图时去重并校验上限。新增多选主体选择器复用现有候选读取（类型、搜索、游标），维护独立的已选清单，类型或搜索键变化只重置候选列表，不清空已选；`PrincipalPicker.tsx` 保持原状供文档面板使用。`AclPanel.tsx` 在 `kind === 'spaces'` 时渲染多选表单与复选权限位（manage 仍受 `space:grant-manage` 控制显示），`kind === 'documents'` 时沿用原单选路径。

考虑过给 `PrincipalPicker` 加 `multiple` 开关：会把两种选择状态塞进同一组件，单选路径的"失败即清空"规则与多选的"保留已选"规则互相缠绕，测试面扩大；分开两个组件更直接。

### 7. 批量路由不可用时授予整体不可用

新前端只走批量接口；对该路由的 404/405 显示"当前服务不支持批量授予"并保留已选内容，撤销与列表读取不变。考虑过 1×1 时退回单条接口：需要维护两套回执渲染与两条写路径，且只在部署顺序错误时才有价值。部署顺序已明确为后端先行，不为此付出长期复杂度。

### 8. 验证范围

- 后端：`SpaceAclService` 批量路径的单元测试覆盖整批校验顺序、manage 资格、重复与上限、部分已存在、按主体计算受阻数、审计快照内容、从快照重放；控制器测试覆盖请求形状与错误码；`AuditSummaryProjector` 新操作域投影测试。测试以 Mock mapper 与审计服务为依赖，不启动 PG。
- 前端：多选选择器的累积、去重、移除与候选故障保留行为；`AclPanel` 空间模式的确认摘要、提交意图内容、回执逐条渲染、404/405 处理；文档模式回归不变。类型检查与静态构建。
- 不编写或执行集成测试、跨服务联调、端到端或浏览器验收，不启动或连接 PostgreSQL/ParadeDB、Redis、MinIO、Docker、后端服务或前端开发服务器，不使用 Testcontainers 或内嵌服务替代。说明文档作为交付物，不对 README、Markdown、注释、规格或文案做内容断言。事务原子性与并发写入保留为未验证限制，不列入本变更待办或完成前置条件；本次只验证"校验失败时不调用写入 mapper"的调用顺序。

## Risks / Trade-offs

- [整批拒绝使一个坏条目阻塞其余合法条目] → 回执与错误消息指明具体条目；前端保留已选内容供移除后重提，不自动拆批。
- [事务原子性只在单测中以调用顺序代替] → 明确记入验证范围；真实 PG 验证归入部署环境清单，不将单测通过视为已验证。
- [新操作域对回退后的旧 kb-app] → `detail()` 对未知操作域返回空变更列表而非抛错，审计页可读但无字段投影；回退不需要清理审计行。
- [快照体积随批次增大] → 上限 300 条、每条四个短字段，JSONB 无需截断；不提供截断标记。
- [单条路径的 `get(0)` 重放缺陷仍在] → 明确为非目标；批量路径不复用该方法。
- [多选累积跨类型后，部门条目含子部门继承] → 语义与单条一致；确认界面保留"部门授权对子部门生效"提示。
- [前端先于后端上线] → 授予显示不可用而非静默失败；撤销不受影响；回退前端即恢复。

## Migration Plan

以下为部署说明的交付内容；本变更的实施任务不启动服务、不执行部署或真实环境验证。

1. 发布 kb-app：新增路由、操作域与投影均为增量，不含数据库迁移；旧前端不感知。
2. 发布 kb-web 静态资源；空间面板授予切换为批量路径，文档面板不变。
3. 定向验证：含 manage 与不含 manage 的批次、部分已存在、角色不存在、超限与重复、相同标识重放、审计页对新操作域的摘要显示。
4. 回退：先回退 kb-web 即恢复单条授予交互；回退 kb-app 后新前端的授予显示不可用，已写入的批量审计行保留且可读。
