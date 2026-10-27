# Design

## Context

动机见 proposal.md 的 Why，行为契约见 `specs/` 下三个 delta。这里只记录实现路线上几处非显然的取舍。

约束来自既有代码的三个事实：

1. **空间归属在两处存在**。`kb_document.space_id` 是权威值；`kb_doc_chunk.space_id` 是专供检索过滤的冗余副本（
   `V2__search_schema.sql:22-24` 的注释即如此声明）。文档读取路径（列表/详情/下载/预览）经 `PermissionScope` 读权威值，检索路径（
   `SearchMapper.xml:28`）读副本。一次搬迁必须同时推进两处，否则两条路径对同一主体给出相反结论。
2. **分块发布会现读权威值**。`ChunkPublisher.publish` 是 `@Transactional` 的，其
   `INSERT ... SELECT ?, ?, d.space_id, ... FROM kb_document d WHERE d.id = ?`（`ChunkPublisher.java:101-107`
   ）在语句执行时读取当时已提交的归属。它已经处理过同形的「删除 vs 迟到 Worker」竞态（同文件 `version.status == 'deleted'`
   分支），本变更引入的是第二例。
3. **原件 key 不编码空间**。`documents/<uuid><ext>`（`DocumentUploadService.java:62`）。搬迁因此完全不涉及对象存储，也不需要
   after-commit 清理。

## Goals / Non-Goals

**Goals:**

- 一次搬迁后，不存在任何按原空间参与过滤的残留——无论是 ACL、权威归属还是分块副本。
- 与并发的分块发布串行化，而不是靠"事后补扫"把泄露窗口做小。
- 复用 `space-authorization` 已经立住的三套约定：`SpaceAuthorizer` 的资格判定顺序、`(operation_scope, request_id)`
  幂等、密级受阻回执。

**Non-Goals:**

- 不做跨空间搬迁的权限"继承"或"映射"。目标空间的授权名单就是搬迁后的唯一依据。
- 不保留任何形式的例外授权迁移建议清单供一键重授。回执给出被清除的摘要即止；重授是目标空间管理员的独立决定，这正是本变更要恢复的那个决定权。
- 不碰 `kb_doc_chunk.security_level`。搬迁不改密级，该副本天然保持一致。

## Decisions

### 1. 用 `fromSpaceId` 前置条件，而不是给 `kb_document` 加乐观锁版本列

**选择**：请求体必须声明 `fromSpaceId`。服务端解析出实际归属，不一致即 409，且 manage 判定仍然针对**实际**归属而非声明值。

**为什么不加 `version` 列**（V11 给 `kb_space` 加版本列是先例，所以这个偏离需要理由）：

- 它防不住本操作真正的危险。搬迁的全部鉴权压在源空间上。若文档已被他人从 A 搬到 C，一个带着"我以为它在 A"的请求打进来，服务端会去校验
  manage(C)。调用方若恰好也管 C，这次校验**会通过**
  ——于是执行了一次他从未打算做的搬迁，并清掉了一批他从未看见的例外。版本列只能告诉他"有人动过"，`fromSpaceId`
  能让他把赌注写明，服务端据此拒绝。
- `kb_document` 上一个叫 `version` 的列会和 `kb_document_version`（内容版本）、`current_version_id` 撞语义。同一张表里"版本"
  指两件事，是注释压不住的那种歧义。
- 文档写操作目前没有版本约定可破坏（`delete`、`retry`、`uploadVersion` 都不带版本），所以这里不存在"打破既有一致性"
  的代价，只存在"与空间/身份接口不同"的代价。

**代价**：搬迁检测不到与它并发的其他文档变更（改标题、发布新版本）。这是可接受的——那些变更都不改变搬迁的正确性，而唯一会改变它的变更正是
`space_id` 本身，恰好被盯住了。

### 2. 搬迁与分块发布靠文档行锁串行化

**选择**：两侧都在写之前取 `SELECT ... FROM kb_document WHERE id = ? FOR UPDATE`。

- 搬迁事务：锁文档行 → 校验 `fromSpaceId` → 改 `kb_document.space_id` →
  `UPDATE kb_doc_chunk SET space_id = ? WHERE doc_id = ?` → 清例外 → 写审计。
- `ChunkPublisher.publish`：在既有的 `deleted` 判定附近加同一把锁，之后才 DELETE/INSERT 分块。

两者于是只能一前一后。发布在后 → `INSERT ... SELECT d.space_id` 读到新归属；搬迁在后 → 它按 `doc_id`
更新，覆盖发布刚提交的那批行。两条次序都收敛。

**被否决的替代方案**：

- **去掉冗余，检索时 join `kb_document` 取 `space_id`**。一劳永逸地消灭这类 bug，但它直接撞在本项目风险最高的那条选型假设上：README
  5.3 第 3 条要求确认权限条件真的下推进了索引，并写明"如果 `space_id`/`security_level`
  变成了索引外的过滤，意味着先召回再过滤，在权限稀疏的场景下召回质量会塌掉"。在那条假设还没有被真实 ParadeDB 验证之前，不能先把它拆掉。
- **搬迁后异步补扫分块归属**。补扫之前那批分块仍按原空间参与检索，也就是留一个泄露窗口。本变更的目的正是消灭这类窗口。
- **只加 `FOR UPDATE` 在搬迁一侧**。不够：发布侧不取锁时，它的 `INSERT` 可以在搬迁的 `UPDATE`
  之后提交，那批新行就带着旧归属落地。锁必须两侧都取才构成串行化。

**代价**：`ChunkPublisher` 的发布事务多持一把行锁，持有时间覆盖分块批量写入。解析发布是后台任务、本就不与前台争这一行，可接受；但要记进
Worker 运维文档。

### 3. 清空全部例外，回执给摘要

**选择**：`DELETE FROM kb_document_acl WHERE doc_id = ?`，返回被删条数与被删的 `principalKey:permission` 摘要（设上限并带截断标记）。

理由见 proposal 的"需要确认的取舍"。补一条实现层面的观察：文档级例外物理上只可能是 view/download/edit——
`DocumentAclService` 已在写路径拒绝 `manage` 与 comment/review。所以摘要的取值域是封闭的，不需要考虑"清掉了一个 manage"
这种情况。

上限与截断标记是为了防审计 JSON 失控。例外按设计应当稀少；若某文档的例外多到被截断，那本身就是该空间组织方式出了问题的信号，值得在审计里看得见。

### 4. 代码落在 `app.space` 包，而不是 `app.document`

搬迁的实体是文档，但它的全部判定都是空间级的，且要操作 `kb_document_acl`——而文档例外的 service/controller/mapper 已经在
`app.space` 包里（`DocumentAclService`、`DocumentAclController`、`DocumentAclMapper`）。接口路径也与它平行（
`/api/admin/documents/{id}/space` 对 `/api/admin/documents/{id}/acl`）。放进 `app.document` 会让空间级鉴权散到两个包，并让"
谁负责文档 ACL"变成两个答案。

因此：`app.space.service.DocumentRelocationService`、`app.space.web.DocumentRelocationController`，写 SQL 加到
`DocumentAclMapper` 或与之并列的新 mapper。

### 5. 密级受阻回执沿用授权接口的口径

目标空间中持有 `view` 的每个主体展开为启用成员用户，取其中**最高**密级；该最高密级低于文档密级的主体计入受阻数。与
`DocumentAclService.countClearanceBlocked` 完全同口径，包括"混合密级的部门不计入受阻"这个已经立住的含义。

不自创口径的理由很实际：两个接口回执同名字段却算不同的东西，比没有回执更糟。

## Risks / Trade-offs

- **搬迁成功但调用方把回执当失败** → 回执的两个数字都需要在 README 5.x 的验证清单里写明含义，特别是"受阻数不为 0
  不代表搬迁没生效"。这与 5.7 已经写过的 `clearanceBlockedPrincipalCount` 说明是同一类陷阱。
- **分块归属更新在大文档上是一次全表行更新** → 按 `idx_chunk_doc (doc_id)`
  定位，范围是单文档的分块数，可控；但它发生在持有文档行锁的事务内，长文档会拉长锁持有时间。需要在实现时记一条：这是搬迁延迟与锁时长的主要来源。
- **`fromSpaceId` 与空间接口的 `version` 约定不一致** → 调用方需要为文档搬迁单独处理一种冲突前置条件。用接口文档和错误消息说明，而不是为了对齐而加一个歧义列。
- **Worker 侧改动需要和 kb-app 同期部署** → 只部署 kb-app 时，搬迁本身正确，但与并发发布的竞态仍在。部署顺序应为先 Worker 后
  kb-app；两者之间的窗口里竞态概率等同于现状（现状是必然错，窗口期是偶发错），不构成回退风险。
- **三条需求没有验证任务** → 「搬迁必须让检索与读取立即按新空间过滤」「搬迁与并发解析发布不得留下陈旧空间归属」，以及例外清理的端到端可见效果，都需要
  PG + MinIO + Worker 同时在位才能验证。`tasks.md` 按要求不包含任何依赖外部组件的任务，因此这三条需求在本变更里**只被实现、不被验证
  **：单测能守住"调用了同步 SQL""取锁发生在写入之前"这类结构性事实，守不住"原空间的人真的搜不到了"。需求本身仍写在 spec
  里，补测与 `space-authorization` 留下的 4.7/7.2 是同一堵墙。

## Migration Plan

1. 无数据库迁移。`sys_identity_audit` 只是多一个 `operation_scope` 取值，旧行不受影响。
2. 部署顺序：先 kb-worker（取锁），再 kb-app（搬迁接口）。反过来会留一个"接口可用但竞态未收口"的窗口。
3. 回滚：下线 kb-app 的搬迁接口即可。已搬迁的文档不需要回滚——它们的归属、分块副本和例外集合已经是自洽的。被清除的例外不可恢复，这一点必须在接口文档里讲明，它不是回滚能解决的。
