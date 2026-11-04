# Design

## Context

动机与范围见 [proposal.md](proposal.md)，行为契约见 `specs/` 下三份 delta。本节只列约束实现的既有事实。

- `DocumentManagementService.delete(userId, documentId)` 先按 edit 解析范围并 `requireSummary`，随后 `findObjectKeys` → `cancelTasks`（锁任务行）→ `softDelete`（锁文档行）→ `reviewCancellation.cancelDocument` → 注册事务提交后的 `cleanupObjects`。注释明确要求保持"任务 → 文档"的锁顺序以与 Worker 发布事务一致。`DocumentMapper.softDelete` 只按 `documentId` 更新。
- `DocumentRelocationService` 以"先鉴权再暴露状态"排序：预读归属、按实际归属判 manage、再做状态校验，并以调用方声明的 `fromSpaceId` 作为并发前置条件；`requireOwningSpaceLocked` 为其私有方法。
- `document-administration` 的管理清单对不存在与无权管理返回同形状空页；`space-authorization` 的主体检索接口对两者统一 404；既有 ACL 写与搬迁对无权管理返回 403。
- 审计幂等靠 `UNIQUE (operation_scope, request_id)`；`AuditSummaryProjector.rules` 是无 `default` 的 `switch`，`detail()` 对未知操作域返回空变更列表。
- 前端 `DocumentPanelPage.tsx` 以管理清单的 `owner` 为例外授权与搬迁区块的开关，密级区块独立按功能权限显示；写入经 `ResourceWriteDialog` 与 `useAdminWrite`。`access.tsx` 以 `visible` 谓词控制导航项，`canOpenSpaces` 为知识空间入口条件；`/admin/spaces` 对仅持密级权限的用户不显示。

## Goals / Non-Goals

**Goals:**

- 两条删除入口共用同一段效果代码，差异只在资格判定与幂等/审计包装。
- 管理端删除在不加文档行锁的前提下关闭"判定后被搬迁"的窗口。
- 密级管理员与空间管理员都有一个不依赖 view 的面板入口。

**Non-Goals:**

- 不修改用户端删除的资格、错误契约或 `canDelete` 能力。
- 不为管理清单增加删除能力字段，不改变其三字段投影。
- 不提供回收站、恢复、单版本删除或批量删除。
- 不统一既有 ACL 写与搬迁的 403 语义。

## Decisions

### 1. 抽出无资格判定的删除主体，管理端与用户端共用

把 `delete` 中从 `findObjectKeys` 到注册提交后清理的部分抽为 `DocumentManagementService` 的公开方法，接收 `documentId`、操作者与软删除的空间前置条件，并在前置条件未命中时自行重读以区分 404 与 409；用户端入口保持先 edit 判定再调用。管理端编排（请求校验、幂等重放、归属预读、manage 判定、审计）放在 `space.service` 新建的服务中，再调用该公开方法——`ModuleBoundaryTest` 禁止 `space` 包访问 `document.mapper`，而 `space.service → document.service` 的方向已被搬迁服务使用。归属预读复用搬迁已用的 `MetadataMapper.document(documentId)`，不加锁。保留原有锁顺序注释与 `TransactionSynchronizationManager` 分支。

考虑过复制一份：两处将各自演化，任务取消或审核取消的后续修正容易只落一边；删除效果必须完全一致是规格要求，共用是最直接的保证。

### 2. 预读归属不加锁，以软删除的空间前置条件关闭并发窗口

流程：请求形状校验（400）→ 幂等重放判定 → 预读文档归属（已删除或不存在 → 404）→ 对实际归属判 manage 或 `space:manage-all`（无权 → 与不存在相同的 404）→ `cancelTasks` → 带 `space_id = :authorizedSpace AND status <> 'deleted'` 前置条件的软删除 → 更新行数为 0 时重读：已删除 → 404，归属变化 → 409 → 取消审核 → 写审计 → 注册提交后清理。

考虑过先锁文档行再判定：会把锁顺序改为"文档 → 任务"，与用户端删除及 Worker 发布的"任务 → 文档"相反，引入死锁可能。前置条件方案保持原顺序，并与搬迁的 `fromSpaceId` 前置条件同一思路。

### 3. 无权管理与不存在统一 404

管理清单已对两者返回同形状空页，面板在无权时根本不显示删除区块；删除接口若返回 403 会让不管理该空间的人借 403/404 差异探出文档存在性。既有 ACL 写与搬迁的 403 保持现状，不在本变更统一。

### 4. 重放先核对快照空间的管理资格

`audit.existing(DOCUMENT_ADMIN_DELETE, requestId)` 命中时，从 `before_state` 取首次删除时的 `spaceId`，重新 `requireSpaceManager` 后返回 `{documentId, spaceId, replayed: true}`；不再读取文档（它已删除）。与搬迁"重放前对原源空间判定 manage"一致。

### 5. 审计快照与投影只含定位字段

`before_state` 为 `{documentId, spaceId, spaceArchived}`，`after_state` 为 `{}`；`AuditSummaryProjector` 对新操作域投影 `spaceId`（ID）与 `spaceArchived`（BOOL）。标题、文件名与存储键不进入审计，与管理清单最小投影一致。

### 6. 面板删除区块复用 `owner` 开关，以面板级互斥覆盖四个写入口

删除区块与例外授权、搬迁同用 `owner`；密级区块不参与开关。面板增加一个以 `session/epoch/documentId` 为键的写入协调状态：删除进入确认即禁用其他三个区块的写按钮，其他区块有在途写入时禁用删除提交；取消确认恢复。与用户端详情页的协调上下文同一思路，但范围只在管理面板内，不改 `useAdminWrite`。

成功后重读 `membership` 与 `title`；`owner` 变为空后区块自然收起，面板显示"无可用管理操作"与删除回执。404 时重读管理清单以区分旧服务与目标不可访问；409 时提示归属变化并重读。

### 7. 索引页与导航项

新增 `/admin/documents` 路由与 `DocumentsIndexPage`：一个输入框、一个跳转按钮，前端校验正整数且不超过 64 位有符号整数范围（复用 `validTarget`），通过后 `navigate('/admin/documents/{id}')`。导航项 `visible` 为 `canOpenSpaces(access) || access.has(perm.securityLevelWrite)`。路由注册在 `/admin/documents/:documentId` 之前作为索引，不影响既有面板路由。

考虑过把输入框放在 `/admin/spaces` 顶部：密级管理员看不到该页，无法达成目标；放在导航栏内嵌表单会让导航组件承担表单状态。独立索引页最小且可直接作为登录返回地址。

### 8. 验证范围

- 后端：`DocumentManagementService` 共用主体的调用顺序（`cancelTasks` 先于 `softDelete`）、空间前置条件为 0 行时的 404/409 分流及用户端删除回归；`space.service` 管理端删除服务的资格分支、重放时的资格核对与回执、审计快照内容；控制器的 400/404/409 与 `X-Request-Id` 缺失；`AuditSummaryProjector` 新操作域。以 Mock mapper、审计与对象存储为依赖，不启动 PG 或 MinIO。
- 前端：删除区块显示条件（`owner` 有无、仅密级权限、已归档）、确认文案、互斥、成功重读、404 的两种分流、409、不确定结果；索引页校验与跳转、导航项显示条件、无资格直接访问。类型检查与静态构建。
- 不编写或执行集成测试、跨服务联调、端到端或浏览器验收，不启动或连接 PostgreSQL/ParadeDB、Redis、MinIO、Docker、后端服务或前端开发服务器，不使用 Testcontainers 或内嵌服务替代。说明文档作为交付物，不对 README、Markdown、注释、规格或文案做内容断言。锁顺序、并发、提交后原件清理与对账保留为未验证限制，不列入本变更待办或完成前置条件。

## Risks / Trade-offs

- [预读归属与软删除之间文档被搬迁] → 软删除前置条件拒绝并返回 409，面板提示重读；不写入。
- [旧服务对 DELETE 路由返回 404 与目标不可访问同码] → 前端以重读管理清单分流；部署顺序后端先行。
- [管理端删除是管理员对他人内容的破坏性操作] → 审计记录操作者与归属；确认文案明确不可恢复；不提供批量。
- [软删除不释放空间删除条件] → `countReferencedDocuments` 计入已软删除文档，空间删除仍需无任何文档行；本变更不改变该语义，文档中明确。
- [共用主体重构触及用户端删除] → 保留既有删除单测作为回归，不改变其对外行为。
- [新操作域对回退后的旧 kb-app] → `detail()` 对未知操作域返回空变更列表，不崩溃。

## Migration Plan

以下为部署说明的交付内容；本变更的实施任务不启动服务、不执行部署或真实环境验证。

1. 发布 kb-app：新增路由、操作域与投影为增量，不含数据库迁移；用户端删除行为不变。
2. 发布 kb-web 静态资源：面板出现删除区块，导航出现文档管理面板入口。
3. 定向验证：空间管理员无 edit 删除、已归档空间删除、仅密级权限不显示区块、相同标识重放、404 两种分流、409、索引页跳转与资格显示。
4. 回退：先回退 kb-web 即移除区块与入口；回退 kb-app 后新前端删除得到 404 并提示服务不支持。版本回退不能恢复已删除文档。
