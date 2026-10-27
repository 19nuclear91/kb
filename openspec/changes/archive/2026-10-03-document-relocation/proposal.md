# Proposal

## Why

`space-authorization` 把知识空间确立为授权主单位，文档级例外的鉴权依据因此是「文档**当前**
所属空间的管理资格」。但系统至今没有任何修改文档所属空间的能力，这条依据从未被撬动过。一旦实现搬迁却不做配套清理，会留下两个确定的越权，且都属于"
功能看起来正常、权限已经漏了"那一类：

1. `kb_document_acl` 里由原空间管理员授出的例外记录仍然生效，而授出者在搬迁后已不再有资格管理这篇文档——等于让一个已经失去资格的人保留对文档的授权遗泽。这条约束已记在
   `DocumentAclService` 的类注释和归档变更 `2026-10-03-space-authorization/proposal.md` 的 Impact 一节里，本变更就是它的兑付方。
2. `kb_doc_chunk.space_id` 是从 `kb_document` 冗余下来、专供检索过滤的副本（`V2__search_schema.sql:23`），而
   `SearchMapper.xml:28` 正是按 `c.space_id IN (...)` 过滤。只改 `kb_document.space_id` 会让文档继续被**原空间**
   的人搜到，同时对新空间的人搜不到。

现在做，是因为搬迁是文档管理迟早要有的常规动作。等它作为"顺手的小功能"被加进来时，上面两条不会有人想起。

## What Changes

- 新增 `PATCH /api/admin/documents/{documentId}/space`，把文档迁入另一个空间。要求**源空间与目标空间都具备 manage 资格**
  ；请求携带 `fromSpaceId`、`toSpaceId` 与 `X-Request-Id`。
- 搬迁在同一个事务内完成四件事：改 `kb_document.space_id`、同步该文档全部 `kb_doc_chunk.space_id`、**清空该文档的全部例外
  ACL**、写审计。任何一步失败整体回滚——半完成的搬迁就是一次越权。
- 响应回执两个数字：被清理的例外条数，以及目标空间中因密级而当前看不到这篇文档的主体数量（沿用授权接口已有的
  `clearanceBlockedPrincipalCount` 口径）。前者让管理员知道有哪些授权需要在新空间重新授出，后者说明"搬过去了但他们还看不到"
  不是搬迁失败。
- `ChunkPublisher` 必须与搬迁串行化。它用 `SELECT ?, ?, d.space_id, ...` 在发布时现读所属空间（`ChunkPublisher.java:105`
  ），与搬迁并发时可能写入带旧 `space_id` 的分块。这和它已经处理过的「删除 vs 迟到 Worker」是同一形状的竞态，解法也应当同形。
- 请求必须声明 `fromSpaceId`——调用方认为该文档当前所在的空间；与实际归属不一致即返回冲突。这取代了"给 `kb_document`
  加乐观锁版本列"的做法：搬迁的全部鉴权都压在源空间上，调用方必须把他赌的那个源空间写出来。否则文档若已被别人搬走，服务端会去校验
  **另一个**空间的 manage，而那一次校验可能恰好通过，于是执行了一次调用方从未打算做的搬迁，并清掉了他从未看见的例外。取舍详见
  design。
- 新增 `AuditScope.DOCUMENT_RELOCATE("document")`。按 `AuditScope` 的类注释要求带实体前缀，不得复用裸操作域。

### 明确不在范围内

这三条是本变更刻意不做的，不是遗漏：

- **调整密级**。`space-authorization` 的「尝试借授权接口调整密级」场景要求密级变更必须是独立的、受全局管理员保护的操作。本变更完全不动
  `security_level`，把它留给后续的「角色与密级管理」变更。
- **改标题及其他文档元数据**。与授权一致性无关，不借本变更捎带。
- **批量搬迁**。单篇搬迁的语义先立住；批量的部分失败语义是独立问题。

### 需要确认的取舍

以下三条按最保守的解释定稿，若业务侧另有要求应在实现前推翻：

- **要求两侧 manage，而不是两侧 edit**。搬迁会把文档从源空间整个授权名单里撤出、放进目标空间的整个授权名单——这是对两个空间的授权变更，不是一次内容编辑。只要
  `edit` 就允许搬迁，等于让源空间的普通编辑者把内容推送给目标空间的全部受众。
- **清空全部例外，而不是只清理"在目标空间无权"的那些**。筛选式保留会让一批没有任何人在新空间重新授出过的授权继续生效，且很难向管理员解释哪些留下了。清空加回执是可解释的。
- **拒绝迁入已归档空间，允许迁出已归档空间**。迁入归档空间会让文档对所有人立即消失，用搬迁接口做隐藏是误导性的路径；迁出则是正当的抢救动作。

## Capabilities

### New Capabilities

- `document-relocation`: 文档迁入另一个知识空间的能力契约——谁有资格执行、目标空间必须满足什么条件、搬迁如何让授权依据、读路径过滤和检索归属同时切换到新空间，以及它与并发解析发布的关系。

### Modified Capabilities

- `space-authorization`: 「文档例外授权只能扩权且依据文档当前所属空间」只规定了依据是哪个空间，没有规定那个空间**发生变化**
  时例外怎么办。补齐这一面：原空间下授出的例外不得在搬迁后继续生效。
- `document-parsing`: 「解析元数据与分块发布必须原子且可重试」需要补齐分块的空间归属义务——发布不得写入与文档当前所属空间不一致的归属。这是
  Worker 实现者只读 `document-parsing` 时必须看到的约束。

## Impact

- **数据库**：**无需结构变更**。`kb_document.space_id` 与 `kb_doc_chunk.space_id` 都已存在，`sys_identity_audit` 只是新增一个
  `DOCUMENT_RELOCATE` 的 `operation_scope` 取值。不加乐观锁列的理由见 design。
- **对象存储**：**不涉及**。原件 key 形如 `documents/<uuid><ext>`（`DocumentUploadService.java:62`），不编码空间，所以搬迁不需要移动任何对象。
- **kb-app**：新增文档搬迁 service / controller / mapper / DTO；复用 `SpaceAuthorizer.requireSpaceManager` 做两侧判定，复用
  `DocumentAclMapper.findOwningSpaceId` 取源空间，复用 `IdentityAuditService` 的 `(operation_scope, request_id)`
  幂等；需要新增"按 doc_id 清空例外 ACL"与"同步分块空间归属"两条写 SQL。
- **kb-worker**：`ChunkPublisher` 的发布事务需要与搬迁串行化，属于跨模块的必要改动。不做这一步，搬迁在有并发解析时仍会留下陈旧归属。
- **读路径**：检索按 `kb_doc_chunk.space_id` 过滤，必须随搬迁更新；列表、详情、下载、预览经 `PermissionScope` 读
  `kb_document.space_id`，自动继承新归属，无需逐处改动。两条路径口径不同是这个变更最容易漏的地方。
- **kb-gateway**：无需改动。新接口位于已纳入 kb-app 路由的 `/api/admin/**` 之下。
- **错误语义**：复用 `IdentityException` 与 `IdentityErrorCode`，直接返回 403/404/409/400，不依赖全局错误码改造。
- **文档**：README 能力表与第八节「下一步」第 6 项需随本变更落地更新；`DocumentAclService`
  类注释里那条跨变更约束在实现后应改为指向本能力而不是"待实现"。
