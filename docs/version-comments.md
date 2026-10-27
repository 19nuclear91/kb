# 固定版本评论

> 实施与定向离线检查已完成，任务记录见 `openspec/changes/versioned-document-comments/tasks.md`。
> 本文件不代表已部署或生产可用；真实迁移、事务、锁互斥与跨服务边界见文末验证记录。

用户可以在文档详情所选的已发布版本下发表纯文本评论，并对根评论做单层回复。评论绑定完整的 `documentId`/`versionId`，不会随新版本发布迁移，也不按 chunk 重定位。评论正文不进入全文/向量索引、Embedding、RAG 或会话来源，私有审核意见也不会因批准而变成评论。

## 版本与资格

### 哪些版本可以讨论

评论读取和发表都经过 document 模块的 `DocumentCommentTargetService`，访问目的为 `COMMENT_TARGET`。判断依据是版本的发布证据，不看 `index_status`，也不看上传者、送审人或审核池身份。

| 版本状态 | 读取评论 | 发表/回复 | 说明 |
|---|---|---|---|
| `PUBLISHED` | 可以 | 需另有 comment | 实际发布过的版本，包括已不是当前版本的旧发布 |
| `OBSERVED_CURRENT` | 可以 | 需另有 comment | 升级时观察到的当前发布版本；实际发布时间未知不影响讨论 |
| `NONE` + `LEGACY_COMPAT` | 不可以 | 不可以 | 旧兼容访问标记单独不够，不补造发布记录 |
| `NONE` 的待审/候选版本 | 不可以 | 不可以 | 上传者可预览或审核人可审核，但只能使用私有审核意见 |

### 读取、发表与删除分别检查

| 条件 | 读取 | 发表/回复 | 删除本人评论 |
|---|---|---|---|
| 有效会话、主体启用、主部门有效、已完成强制改密 | 需要 | 需要 | 需要 |
| 目标版本当前 view（空间授予 ∪ 文档例外） | 需要 | 需要 | 不需要 |
| 当前密级不低于文档密级 | 需要 | 需要 | 不需要 |
| 文档未删除未归档、空间未归档 | 需要 | 需要 | 不需要 |
| 版本有发布证据 | 需要 | 需要 | 不需要 |
| comment（空间授予 ∪ 文档例外） | 不需要 | 需要 | 不需要 |
| 本人是评论作者 | 不适用 | 不适用 | 需要 |

- view 与 comment 是两次独立解析的范围，都按用户、角色和祖先部门主体计算，文档例外只扩权。comment 不暗含 view，因此只有 comment 没有 view 的用户读写都得到 404。view、edit、review、manage、所有者和角色名称都不能代替 comment。
- 列表的 `canCreate`、详情的 `canReply` 以及每项的 `isOwn`/`canDelete` 只是本次读取的界面提示，不是授权凭证。写事务在锁定文档后会重读实际空间、密级和授权，不沿用页面快照。
- 搬迁会清除全部文档例外，包括 comment。之后的读写按目标空间和新例外判断，原 comment 记录不保留跨空间资格。

### 授权管理

- 空间 ACL 可委派 view、download、edit、review、comment，manage 仍受全局管理员限制。文档例外只接受 view、download、edit、comment，授予 review 或 manage 返回参数错误。
- comment 可以授予或撤销给用户、角色、部门，部门按祖先链继承。授予 comment 不提升密级，也不附带 view。
- 删除某用户的文档 comment 例外不是 deny：如果其有效空间主体仍持有 comment，空间授予继续生效。
- 升级不会给已有 ACL 自动补 comment，需要管理员按空间或文档显式授予。

### 审核与评论隔离

- 评论只在有发布证据的版本上开放。待审版本的讨论只能写在审核模块的私有意见里，两者使用不同的表、接口和页面区域。
- 批准不会把审核意见转成评论，读取评论也不会预取审核意见，或把审核通过当作意见公开。
- ArchUnit 规则限制 comment 包只经 document 与 collaboration 的 service 契约取事实和写事实，不依赖 notification、search、conversation、embedding、aiusage、parse、storage、space 包或其他模块的 mapper。其他模块的 Java 代码也不依赖 comment 包。

### 旧发布证据边界

V23 只在升级时父文档为 `published` 且 `current_version_id` 指向该版本的情况下记录 `OBSERVED_CURRENT`。其他旧 indexed 版本只保留旧兼容读取，不能评论。本变更不回填发布证据，也没有历史评论需要迁移，因为此前不存在评论表。

### 本人删除的独立身份门禁

删除只按 `documentId`/`versionId`/`commentId` 加 `author_user_id` 锁定本人记录，不检查 view、comment、密级、归档或发布证据，也不读取文档标题或其他评论。它只要求当前会话有效、主体启用、主部门有效、已完成强制改密。这样撤权后的作者仍可清除自己的正文，而这条路径不会返回目标内容，也不授予任何读取资格。

## 升级、部署与回退

### 迁移脚本

追加 `kb-app/src/main/resources/db/migration/V25__versioned_document_comments.sql`，不修改 V1–V24。本次只交付脚本，没有执行。

- `kb_document_comment`：内部 `id` 单调递增，只用于排序和游标，对外只暴露 UUID `comment_id`。版本归属使用复合外键，回复的 `root_id` 必须指向同文档同版本的评论。正文最多 4000 个字符，`body IS NULL` 当且仅当已删除。
- 触发器 `trg_document_comment_guard`：拒绝回复的回复和回复已删除的根，拒绝物理删除，身份列不可变，正文只能清除一次。
- 索引：根评论 `(doc_id, version_id, id DESC) WHERE root_id IS NULL`，回复 `(root_id, id) WHERE root_id IS NOT NULL`。
- `kb_comment_command`：主键 `(operation_domain, request_id)`，只保存操作者、目标、父节点、规范化正文摘要和回执引用，不保存正文副本或原始请求。它对版本表没有外键，以免在文档锁之前对版本行取得键共享锁。触发器保证绑定字段不可变、回执只写一次、记录不可删除。
- `kb_collaboration_event` 新增可空列 `comment_id`、`root_comment_id`，扩展事件种类为 `COMMENT_CREATED`、`COMMENT_REPLIED`、`COMMENT_DELETED`，并对评论列加复合外键。`kb_collaboration_coverage` 的 domain 增加 `COMMENT`。

### 执行前核对约束名

V23 的相关 CHECK 没有显式命名，V25 按 PostgreSQL 的默认命名规则删除它们，并且不使用 `IF EXISTS`：名称不符时迁移直接失败，而不是静默保留旧约束。

| 表 | V25 删除 | 对应 V23 约束 |
|---|---|---|
| `kb_collaboration_event` | `kb_collaboration_event_kind_check` | `kind` 列的种类白名单 |
| `kb_collaboration_event` | `kb_collaboration_event_check` | 表级“发布事实或审核轮次”结构约束 |
| `kb_collaboration_coverage` | `kb_collaboration_coverage_domain_check` | `domain = 'REVIEW'` |

表级决策时间约束（默认名通常为 `kb_collaboration_event_check1`）保留不动，它对评论种类恒为真。执行前请先在目标库核对实际名称和定义：

```sql
SELECT conrelid::regclass AS table_name, conname, pg_get_constraintdef(oid) AS definition
FROM pg_constraint
WHERE conrelid IN ('kb_collaboration_event'::regclass, 'kb_collaboration_coverage'::regclass)
  AND contype = 'c'
ORDER BY 1, 2;
```

如果名称与上表不同（例如曾经手工重建过约束），先停下来确认，不要直接改 V25 或使用 Flyway repair。

V25 修改约束时会对 `kb_collaboration_event` 和 `kb_collaboration_coverage` 取得 `ACCESS EXCLUSIVE` 锁，并在新增 CHECK 和外键时扫描已有事实。事实表越大耗时越长，期间对这两张表的读写（包括审核写入和通知派生）都会等待。本变更没有在真实数据量上测过耗时，建议放在低峰时段执行。

### 部署顺序

1. 确认 `versioned-document-review` 与 `in-app-notifications` 已实施、验证并同步主规格，V23、V24 已执行。
2. 由 kb-app 单点执行 V25。旧 app/Worker 不写评论列，新增列对它们为空，可以通过新的 CHECK，不需要为此排空。
3. 部署 kb-common、kb-app 和 kb-gateway。`/api/documents/**` 已有业务路由，不需要新增路由；网关通过共享的 `DocumentCommentEndpoints` 给评论路径的提前拒绝加上 `no-store`。评论生产者和回复通知解析器 `CommentNotificationTargets` 在同一个 kb-app 版本中，确认所有写入实例都是新版本后再开放入口。滚动发布期间如果旧实例先领取了评论事实，它没有对应解析器，只会把该工作置为 `WAITING_SOURCE` 并退避，之后由新实例继续处理，只造成延迟。
4. COMMENT coverage 起点由第一条新版评论事实在同一事务中写入，不回填历史。根创建与回复创建按事件种类分别计数，报表不能用剩余正文条数或通知条数代替。
5. 最后发布前端，再由管理员按需授予 comment。

### 回退

- 停止新建：设置 `KB_COMMENT_CREATION_ENABLED=false`（配置项 `kb.comment.creation-enabled`，默认 `true`）并重启 kb-app。之后发表、回复和匹配重放都返回 403，列表的 `canCreate`、详情的 `canReply` 均为假，页面不再显示发表框。读取、本人删除、墓碑和已提交事实不受影响，也不需要撤销任何 comment 授予。重新开启后，原请求标识仍按首次绑定重放。前端入口可以同时下线。
- 保留新版 kb-app 的本人删除路径、正文清理、墓碑和源事件。回退到不认识评论的旧 app 后，作者将无法删除自己的正文，应尽量避免。
- 不删除评论表、命令绑定、协作事实或迁移历史，不使用 Flyway repair，不重放根创建来“回退”。
- 如果通知解析器不可用，已登记的评论事实会保持 `WAITING_SOURCE` 并按退避等待，恢复后继续派生，不需要手动改数据。回到没有 `COMMENT_REPLIED` 读取分支的旧 app 后，已生成的回复提醒在列表和未读数中不可见，但不会被删除。

以上只说明部署边界，本变更没有执行迁移、部署或联调。

## 创建与单层回复

### 请求

`POST /api/documents/{documentId}/versions/{versionId}/comments`

- 只接受 `Content-Type: application/json`，请求体最大 64 KiB，不接受查询参数。
- 必须带且只能带一个 `X-Request-Id`，长度为 1–128 个非控制 Unicode code point。前端每次新提交用 `crypto.randomUUID()` 生成。
- 请求体只允许 `body` 和可选 `parentId`，拒绝重复字段、未知字段，以及任何身份、创建时间或删除状态覆盖：

```json
{ "body": "这一段的口径需要和上一版一致吗？", "parentId": "<根评论 UUID，发表根评论时省略>" }
```

### 正文规则

服务端 `CommentText` 与前端 `commentBody` 使用同一规则：

- 只去首尾空白，按 Java `String.strip()` 判定空白，不剥离不换行空格（U+00A0、U+2007、U+202F）。不做 Unicode 兼容折叠，不改写内部换行，不静默截断。
- 去空白后长度为 1–4000 个 code point，补充平面字符计 1，允许换行。
- 拒绝 NUL 和孤立代理。超长、空白或非法输入返回 400，不写入任何正文或事件。
- HTML、脚本、Markdown 图片和链接都只作为文本保存和展示。

### 请求绑定与重放

绑定内容是操作域 `COMMENT_CREATE`、请求标识、操作者、`documentId`、`versionId`、`parentId`，以及规范化正文的长度前缀 SHA-256 摘要。正文本身不进入绑定记录。请求标识在操作域内全局唯一，不按用户隔离，所以他人复用同一标识只会冲突，拿不到别人的回执。

| 场景 | 结果 |
|---|---|
| 新标识 | 201，`replayed=false` |
| 同标识、绑定完全一致 | 重新检查当前读取和发表资格后返回 200，`replayed=true`，评论身份和创建时间与首次相同 |
| 同上，但原评论已由本人删除 | 200，`deleted=true`、`replayed=true`。不重写正文，不更新时间，不重发事件或通知 |
| 同上，回复已提交但其根随后被删除 | 照常返回原回复回执。重放不重新套用“根必须未删”这一新建前置条件 |
| 同上，但当前已无 view 或 comment | 分别为 404 或 403，不返回回执 |
| 同上，但新建开关已关闭 | 403，不返回回执；重新开启后可重放 |
| 同标识，但操作者、目标、父节点或正文不同 | 409，不返回原回执 |
| 同标识的并发请求 | 后到者在命令主键上等待；先到者提交后走重放，回滚后由等待者自己创建 |

### 事务与锁顺序

创建在一个 10 秒超时的短事务中完成：

1. 锁外 `inspect`：分别解析 view 与 comment 范围，确认可读（否则 404），再确认新建开关已开启且有 comment（否则 403）。
2. `claim`：`INSERT ... ON CONFLICT DO NOTHING` 写命令绑定。冲突时用另一条语句读取已有绑定并进入重放，不在已中止的事务里继续查询。
3. `lockFacts`：对文档行 `FOR SHARE OF d`，重读实际空间、密级、授权和发布证据。只锁文档行，不锁版本行，避免和 Worker 的版本锁顺序交错。
4. 回复时 `lockRoot`：对根评论 `FOR SHARE OF c`，确认它是同版本未删除的根。不存在或在其他版本为 404，目标是回复或根已删除为 409。
5. 插入评论，再在同一事务中写协作事实、收件人意图和（首次时）COMMENT coverage，最后 `complete` 把 `comment_id` 写回命令绑定。任何一步失败，整个事务回滚，不返回已确认的评论。

整体顺序是 command → document（`FOR SHARE`）→ root → child（插入时外键对版本行取 KEY SHARE）→ event/receipt，与审核和 Worker 发布“先文档后版本”的顺序一致。创建和删除都不获取 Embedding 控制锁或解析任务锁，也不调用通知消费者。根删除对根行取 `FOR UPDATE`，与回复的 `FOR SHARE` 互斥，所以并发时只有两种结果：回复先提交并保留，或根先删除、新回复得到 409。这个顺序只在代码和内存替身中检查过，没有在真实 PostgreSQL 上验证。

### 回执

```json
{
  "commentId": "<uuid>",
  "parentId": null,
  "documentId": 11,
  "versionId": 21,
  "createdAt": "2026-10-27T08:00:00.123456Z",
  "deleted": false,
  "replayed": false
}
```

回执不含正文、作者标签、文档标题或文件名。需要正文时，通过受保护的读取接口按当前权限获取。

## 读取、删除与数据保留

### 接口

所有端点都在 `/api/documents/{documentId}/versions/{versionId}/comments` 下，用 `R` 封装，204 无正文。应用响应（包括拒绝）和网关的提前拒绝都带 `Cache-Control: no-store`。路径中的数字 ID 必须是规范正整数，评论 ID 必须是规范 UUID。

| 方法与路径 | 输入 | 成功 |
|---|---|---|
| GET `.../comments` | 只接受 `limit`（1–100，默认 20）、`cursor`；无请求体 | 200，`{items, hasMore, nextCursor, canCreate}`，根评论按创建位置倒序 |
| GET `.../comments/{rootId}/replies` | 同上 | 200，`{items, hasMore, nextCursor, canCreate}`，回复按创建位置正序，`canCreate` 在根已删除时为假 |
| GET `.../comments/{commentId}` | 无参数、无请求体 | 200，`{item, canReply}`，固定评论目标详情，用于回复通知定位 |
| POST `.../comments` | 见上一节 | 201 新建，200 重放 |
| DELETE `.../comments/{commentId}` | 无查询参数、无请求体 | 204，本人重复删除仍为 204 |

列表项字段：

| 字段 | 未删除评论 | 墓碑 |
|---|---|---|
| `commentId`、`documentId`、`versionId`、`createdAt` | 有 | 有 |
| `parentId` | 回复为根 UUID，根为 `null` | 同左，保留结构 |
| `deleted` | `false` | `true` |
| `author` | `{userId, label}`，只含内部 ID 和当前显示标签 | `null` |
| `body` | 纯文本 | `null` |
| `isOwn`、`canDelete` | 本人为 `true` | 均为 `false` |

- 不返回总数、隐藏条数、用户名、部门、密级、角色或外部绑定。作者标签按页批量解析，单次最多 200 个用户，墓碑不解析旧作者。
- 根列表不嵌入回复，回复按根独立分页。SQL 只取 `limit + 1` 行。
- 游标绑定查询种类、`documentId`/`versionId`，回复游标还绑定根评论。把 V1 游标用到 V2、其他根评论或另一种查询返回 400。
- 读取的检查顺序：当前身份 → 参数格式 → 版本资格（不可读为 404） → 游标校验。游标问题不会泄露目标是否存在。

### 错误

错误体固定为 `{"code":500,"message":"评论请求未完成","data":{"errorCode":"..."}}`，HTTP 状态码表达实际分类：

| 状态 | `errorCode` | 场景 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 未知/重复参数，`limit` 越界，游标损坏或不属于当前查询，ID 或 UUID 不规范，GET/DELETE 带正文，POST 带查询参数，缺少或重复 `X-Request-Id`，JSON 不合法或含未知字段，正文不合规 |
| 401 | `UNAUTHENTICATED` | 没有会话或会话失效 |
| 403 | `FORBIDDEN` | 主体或主部门停用、需要强制改密；可读但没有 comment，或新建开关已关闭时发表或回复 |
| 404 | `NOT_FOUND` | 文档、版本或评论不存在，归属不符，无 view、密级不足、归档、删除或无发布证据；删除时目标不是本人评论 |
| 409 | `STATE_CONFLICT` | 回复目标是回复或已删除的根；同一请求标识绑定了不同内容 |
| 503 | `SERVICE_UNAVAILABLE` | 版本、权限、评论或作者标签无法可靠查询，或读出的数据结构不一致 |

错误体不含原始异常、SQL、正文或资源明细，评论模块也不为失败写日志。依赖故障时不返回旧缓存、空列表或虚假的零评论。

### 删除与墓碑

- 首次删除在同一事务中把 `body` 置空、写 `deleted_at`，并写一条无收件人的 `COMMENT_DELETED` 事实。本人重复删除直接返回 204，不再写事实。
- 保留 `comment_id`、作者、版本归属、根结构和命令绑定，因此创建重试不能复活正文，历史创建数量也不会因删除被重算成“从未发生”。
- 删除根评论不级联删除他人的回复。已有回复继续按版本资格显示，根显示无正文占位，新回复返回 409。
- 他人评论和不存在的评论都返回 404。空间 manage、`space:manage-all` 或其他管理资格都不能代删。

### 数据保留

- 首期不自动清理墓碑、命令绑定或协作事实。物理销毁、备份保留和治理删除需要单独设计。
- 命令绑定只保存正文摘要，没有密钥。短正文可以被猜测后比对摘要，所以 `kb_comment_command` 的数据库访问控制应与评论表一致。
- 删除只清除数据库中的当前正文，不承诺删除他人的回复、数据库备份，或已经送达并显示在其他浏览器页面中的副本。
- 本期不提供编辑、恢复、管理员代删、富文本、附件、提及或点赞接口。

## 回复通知接入

- 回复事务写入 `COMMENT_REPLIED` 事实，收件人意图是根评论作者。回复本人的根评论时不记录收件人。根创建（`COMMENT_CREATED`）和删除（`COMMENT_DELETED`）不产生个人提醒，派生时直接完成。
- 事实按 `KIND:commentId` 业务键去重，只含 `comment_id`、`root_comment_id`、文档/版本、操作者和服务器时间，不含正文。
- 通知目标为 `{"kind":"DOCUMENT_VERSION_COMMENT","documentId":...,"versionId":...,"reviewId":null,"commentId":"<回复 UUID>"}`，其中 `commentId` 是 UUID，定位固定版本上的那条回复，不跟随最新版本。
- 列表和未读数共用 `UserNotificationMapper.xml` 的 `visible` 片段。`COMMENT_REPLIED` 分支要求 `discussable`（文档当前 view、密级、未删除未归档，且版本有 `PUBLISHED`/`OBSERVED_CURRENT` 证据，引用 document 模块的 `commentTargetEvidence` 片段）和 `reply_readable`（引用评论模块的 `liveReply` 片段：回复仍是同版本未删除的回复）。通知模块只以 SQL 片段引用评论规则，不依赖评论模块的 Java 或 Mapper。
- 读取列表时，`CommentNotificationTargets` 会按 SQL 投影的事实再判断一次。两边不一致时整页返回 503，并让 `kb.notification.read.mismatch` 加一。修改 `liveReply` 或 `commentTargetEvidence` 时必须同步这个类。
- 根评论被删除不会隐藏合法回复的提醒。回复被删除、撤权、升密、归档或删除后，提醒在下一次读取时从列表和未读数中隐藏，但不会删除已生成的通知或已读状态。权限恢复且回复仍存在时，提醒可以重新出现。
- 解析器未安装时，回复事实保持 `WAITING_SOURCE` 并按退避等待，安装后继续派生，不修改源事件、收件人或已读状态。派生失败只会让 projection 退避重试，不会回滚已提交的回复，也不会再次创建回复。
- 重复的事实被业务键拒绝；派生进度丢失后重放，通知唯一键冲突时不重复插入，也不重置 `read_at`。

## 页面使用

### 文档详情

- 在文档中心选中某个版本后，审核进度下方出现“版本评论 · 固定版本 N”。只有当前状态确认该版本有 `PUBLISHED` 或 `OBSERVED_CURRENT` 证据且可读时才请求评论；否则显示“该版本尚无发布证据，不开放评论”，不请求正文，也不按索引状态推断。
- 根评论按倒序显示，“更多评论”按服务端游标续页。每条根评论下点击“查看回复”才读取回复，“更多回复”独立续页，不会预取全部回复。正在读取、暂无评论、已显示全部和读取失败分别有不同提示；失败时显示“重新读取评论/回复”，不会当作没有评论。续页失败时保留已显示的项。
- 有 comment 时显示“评论内容”输入框和“发表评论”，回复区显示“回复内容”和“提交回复”；只有 view 时提示可以阅读但没有发表资格。回复只能挂在根评论下，回复项没有回复、编辑或附件入口。
- 输入框下方显示按 code point 计算的字数。超过 4000 时提示删减，不会截断；含 NUL 时不能提交。正文用纯文本显示，脚本、HTML、图片标记和链接都不执行或加载。
- 提交期间按钮不可重复点击。成功后清空草稿并重新读取当前页，不乐观追加未确认的评论。结果不确定时提示“待核对”，可以“按原内容重试”（复用原请求标识）、“重新读取核对”或“放弃核对”；放弃后修改正文再提交会使用新标识。重放成功时提示“已核对为同一次提交，未重复发表”，原评论已删除时提示未重新发表。
- 发表时如果返回 403 或 409，页面停止提交并重新读取，不按旧按钮状态放行。每次重新读取首页（包括发表成功后）返回之前，已确认的评论继续显示，但“发表评论”“提交回复”和“按原内容重试”都不可用，并提示“正在重新核对发表资格…”，直到服务端重新确认资格。

### 删除本人评论

- 只有本人未删除的评论显示“删除评论”。确认框“删除本人评论”说明删除后正文立即清除且无法恢复，点击“确认删除”提交。
- 成功后正文立即换成“该评论已删除”；删除根评论时同时清理该根下的回复草稿，保留他人回复，并禁止追加新回复。他人评论没有删除入口，管理资格也不会出现删除按钮。
- 删除结果不确定时可以重试同一目标。目标已不存在或不属于当前账号时会提示，不再提交。

### 撤权与通知目标

- 读取得到 401、403 或 404 时，立即清理页面中的评论正文、作者、草稿和游标，提示该版本评论当前不可访问。如果当前身份在本页已知自己的评论，会列出“删除本人评论”（只显示 ID 前缀，不显示正文），不会为此重读讨论。点击“重新核对”后重新读取。
- 在站内通知中打开回复提醒，会按通知记录的固定版本核对那条评论，然后可以“查看本版本讨论”。目标失效时清理旧内容并提示，不改为显示其他版本或最新版本的讨论。打开目标不会标记已读。

### 生命周期

评论正文、作者、草稿、游标、写意图和已知本人评论 ID 只保存在当前页面内存中，不写入 URL、localStorage、日志或遥测。切换版本或文档、离开页面、标签页隐藏、退出、强制改密或切换账号时，会卸载评论面板、取消请求并清空上述内容。恢复时先核对身份，再读取当前资格，旧账号迟到的列表、回执或删除目标不会进入新页面。

### 授权管理页面

空间和文档的 ACL 面板都可以选择 comment，并说明它只允许在可查看的已发布版本上发表评论与回复，不授予 view、密级或审核资格，也不会自动补授给已有授权。review 只出现在空间面板，文档面板不提供。主体仍从当前资源上下文选择，沿用管理写意图。

## 验证范围

本变更只做离线验证：独立内存单测、无连接 MyBatis 映射、ArchUnit 架构检查、网关路由和响应策略单测、前端组件和状态单测、类型检查和静态构建。版本、权限、评论、通知等依赖使用 Mock、stub 或内存替身，没有组装跨模块运行环境。

没有执行迁移，没有启动应用、网关、数据库、Redis、对象存储或浏览器。以下内容都没有验证，不能根据离线结果宣称生产可用：

- V25 在真实库上的执行、约束名、锁等待和耗时，以及触发器和外键的实际行为。
- 事务原子性和回滚、`ON CONFLICT` 等待、`FOR SHARE`/`FOR UPDATE` 互斥，以及与搬迁、删除、Worker 发布事务的锁顺序。并发测试只使用 `CommentFixture` 的内存命令等待，不能证明 PostgreSQL 行锁或隔离级别。
- 资格 SQL、`liveReply` 与 `commentTargetEvidence` 片段对真实数据的过滤结果、分页和执行计划。
- 通知派生与评论提交在多实例和真实调度下的行为、跨服务联调、浏览器端到端，以及生产环境的性能和容量。

第一次有真实数据库时，建议至少核对：执行前的约束名查询；同一用户对同一回复的并发重试只产生一条评论和一条事实；根删除与新回复并发只出现两种合法结果；撤销 view、升密、归档、搬迁后列表、详情和回复提醒同时隐藏；本人在撤权后仍能删除评论。

## 本地检查记录（2026-10-27）

以下命令在 Windows PowerShell 中执行。测试只运行明确列出的范围，排除了需要 `KB_TEST_POSTGRES_URL` 的 PostgreSQL 烟测，没有运行包含集成测试或外部组件的全量套件。

项目根目录：

```powershell
mvn -o -q install -pl kb-common '-DskipTests'
mvn -o -pl kb-common test '-Dtest=com/zhishiku/kb/common/review/**/*Test,com/zhishiku/kb/common/security/**/*Test' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
mvn -o -pl kb-app test '-Dtest=com/zhishiku/kb/app/comment/**/*Test,com/zhishiku/kb/app/notification/**/*Test,com/zhishiku/kb/app/document/**/*Test,!ParseTaskRecoveryPostgresSmokeTest,com/zhishiku/kb/app/space/**/*Test,!SpaceAuthorizationPostgresSmokeTest,com/zhishiku/kb/app/auth/**/*Test,com/zhishiku/kb/app/collaboration/**/*Test,com/zhishiku/kb/app/conversation/ReviewSourceIsolationTest,ModuleBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
mvn -o -pl kb-gateway,kb-worker test '-Dtest=ReviewResponsePolicyTest,ReviewRouteTest,VersionedChunkPublicationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
```

结果：kb-common 26 项（含 `VersionAccessRulesTest` 的 COMMENT_TARGET 目的与 `CollaborationEventTest` 的评论事实结构），kb-app 840 项，kb-gateway 3 项，kb-worker 4 项，全部通过，失败、错误、跳过均为 0。其中与评论直接相关的 kb-app 测试：

- 模型与输入（`CommentModelTest`）：正文规范化、code point 上限、NUL/孤立代理、请求绑定、UUID 路径和墓碑投影。
- 无连接映射（`DocumentCommentMappingTest`、`DocumentCommentTargetTest`）：评论行与命令绑定的精确映射，命令绑定只存摘要不存正文，每条读取和锁语句都绑定完整文档版本路径，view/comment 两组范围分开绑定，语句不投影文档或存储列。
- 资格矩阵（`DocumentCommentTargetTest`、`VersionAccessPurposeTest`）：`PUBLISHED`/`OBSERVED_CURRENT`、`LEGACY_COMPAT` 单独不足、候选拒绝、view 与 comment 合取、密级和主体/归档/删除门禁；空间 comment 与文档例外是独立的并集分支，删除例外后空间授予照常绑定。
- ACL（`CommentPermissionScopeTest`、`SpaceAclServiceTest`、`DocumentAclServiceTest`、`DocumentRelocationServiceTest`、`SpaceControllerTest`）：用户/角色/祖先部门、空间与例外并集、只扩权、搬迁清除、文档 review 拒绝、其他权限不暗含 comment。
- 创建（`DocumentCommentCreateTest`）：并发匹配请求只产生一条评论和一条事实、先到者回滚后等待者自建、不同正文/操作者冲突、深层/跨版本/已删根拒绝、事实写入失败时不确认成功、根作者收件人与自身排除、事务配置；新建开关关闭时发表、回复和重放为 403（不可见目标仍为 404），读取提示同步关闭，本人删除不受影响，重新开启后原标识可重放。`CommentPropertiesTest` 按实际 `application.yml`/`application-dev.yml` 离线绑定 `kb.comment`，确认默认开启且 `KB_COMMENT_CREATION_ENABLED` 可以关闭和重新开启。
- 删除（`DocumentCommentDeleteTest`）：重复删除幂等、撤权后仍可无正文删除、他人与管理员统一 404、根删除不级联且禁止新回复、删除后重放只返回 deleted 回执。
- 读取（`DocumentCommentQueryTest`）：`limit + 1`、跨版本/跨根游标 400、墓碑无正文、作者标签批量投影、固定评论目标不跟随新版本。
- 控制器（`DocumentCommentControllerTest`）：400/401/403/404/409/503、禁止客户端身份/时间覆盖、未授权读取无资源明细、no-store。
- 通知（`CommentNotificationContractTest`、`NotificationMappingTest`、`NotificationRetryTest`）：合法回复定位、已删回复/撤权/升密/归档/无证据隐藏、SQL 与 Java 复核不一致返回 503、列表与计数共用类型、只提醒他人回复、`WAITING_SOURCE` 恢复、重复事实与重新派生不重置已读、派生失败保留源事实。
- 架构（`ModuleBoundaryTest`）：评论与通知、检索、会话、Embedding 解耦，其他模块不依赖评论包。

网关的 `ReviewRouteTest`、`ReviewResponsePolicyTest` 覆盖评论路径的路由与 no-store；Worker 的 `VersionedChunkPublicationTest` 是受影响的发布回归。

`kb-web` 目录：

```powershell
npx vitest run
npm run typecheck
npm run build
```

结果：58 个测试文件、670 项全部通过。评论新增 24 项（`comments/api.test.ts` 8、`comments/panel.test.tsx` 14、`comments/lifecycle.test.tsx` 2），其中包括重读首页期间暂停发表与“按原内容重试”，覆盖发布证据门控、V1/V2 切换与迟到结果、根/回复独立游标、故障不冒充空讨论、code point 校验、纯文本显示、显式匹配重试与新标识、单层回复、本人删除与墓碑、删除结果不确定、发表前撤权、撤权遮蔽与无正文删除、通知目标固定版本、隐藏/账号切换清理。另外更新了通知、ACL 和文档页测试（评论目标打开、comment 选项、文档面板无 review、版本切换时评论跟随所选版本）。全部使用 mock 会话、受控 Promise 和 jsdom。类型检查通过。静态构建通过，主 chunk 为 1,229.13 kB，高于 1200 kB 的告警阈值，这是告警，不是失败。

以上结果不包含迁移执行、真实 SQL 权限与执行计划、事务回滚和锁互斥、多实例并发、网关或应用启动、浏览器端到端，以及说明文档或 SQL 文本断言。没有启动数据库、Redis、MinIO、Docker 或模型服务。
