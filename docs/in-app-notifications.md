# 站内通知

用户可在 `/notifications` 查看发给本人的审核、评论回复及按契约纳入的知识问题提醒，未读数显示在导航上。通知只是提醒，不是待办系统：审核待办仍以 `/reviews` 实时工作台为准，问题处理以当前问题详情及合格受理队列为准。标记已读也不等于已经处理。

知识问题单扩展的后端、新前端、网关及配置已接入独立问题来源、共享收件箱、能力协商和受保护目标，离线验证已完成，2026-11-14 已同步主规格并[归档](../openspec/changes/archive/2026-11-14-knowledge-gap-feedback-loop/)。合同见[通知主规格](../openspec/specs/user-notifications/spec.md)及[通知界面主规格](../openspec/specs/user-web-notifications/spec.md)。本轮未执行迁移或部署，顺序和兼容回退见 [协调发布清单](knowledge-gap-rollout.md)，离线通过及归档不等于真实环境验收。问题处理状态以问题详情为准，通知已读不等于确认解决。

## 数据生命周期

三类事实分开保存，各自有生命周期：

| 事实 | 表 | 所有者 | 说明 |
|---|---|---|---|
| 源业务事件 | `kb_collaboration_event`、`kb_collaboration_event_recipient` | 审核、评论 | V23 追加式事实（V25 扩展评论种类），不含正文。收件人是源事务当时记录的提醒意图，不是授权快照 |
| 派生进度 | `kb_notification_projection` | 通知模块 | 每个事件一行，记录状态、租约、claim token、收件人游标、退避次数和固定错误分类 |
| 问题源业务事件 | `kb_knowledge_gap_event`、`kb_knowledge_gap_event_recipient` | 问题模块 | 独立无正文事实和源事务固定收件人意图，不写入协作事件 |
| 问题派生进度 | `kb_knowledge_gap_notification_projection` | 通知模块 | 独立外键及进度，复用同一有界领取/分批算法 |
| 本人通知与已读 | `kb_user_notification` | 通知模块 | 原 `(event_id, recipient_user_id)` 唯一键保留；新增 `(gap_event_id, recipient_user_id)` 唯一键，二者来源恰有一个 |

- 通知不复制标题、正文、审核意见、驳回原因、文件名、存储键或凭据。每次读取时按当前权限重新判断是否可见。
- 源事件不加“已发送”标记，也不会因为派生而删除；派生失败不会撤销已提交的审核。反过来，源事件写入失败时，源业务事务会整体回滚，这与派生失败是两种不同的故障。
- 收件人：`REVIEW_SUBMITTED` 发给送审当时记录的合格审核池；`REVIEW_APPROVED`/`REVIEW_REJECTED` 发给本轮送审者（不是文档 owner）。事件操作者本人不会收到。`REVIEW_WITHDRAWN`、`REVIEW_CANCELLED`、`VERSION_PUBLISHED` 不单独产生通知。`COMMENT_REPLIED` 发给根评论作者，回复本人的根评论不记录收件人；`COMMENT_CREATED`、`COMMENT_DELETED` 不产生通知。
- 派生时不会按当前角色重算历史收件人，避免断点续建时名单发生变化。送审后才加入审核池的人不会补收旧提醒，可以通过实时审核工作台看到待办。
- 首期不自动清理。触发器只允许首次写入 `read_at`，并拒绝删除。清理需要单独定义覆盖范围、幂等墓碑和用户预期，本次不做隐式删除。
- 本模块不复用 Worker 的 `TaskAlertOutbox`。那张表面向解析告警，payload 和生命周期都不能保存用户已读状态，它的 dispatch 还会在事务内调用适配器。通知派生只写数据库，不调用 HTTP。

### 评论接入

`versioned-document-comments` 已接入回复提醒，详见 `docs/version-comments.md` 的“回复通知接入”：

1. V25 把 `COMMENT_CREATED`、`COMMENT_REPLIED`、`COMMENT_DELETED` 加入 `kb_collaboration_event.kind` 约束。回复事务写入事件和根评论作者收件人，回复本人不记录收件人。
2. notification/service 注册了 `CommentNotificationTargets`，`UserNotificationMapper.xml` 的 `visible` 片段增加 `COMMENT_REPLIED` 分支：`discussable` 引用 document 模块的评论目标发布证据片段并要求当前 view/密级，`reply_readable` 引用评论模块的 `liveReply` 片段。通知模块不依赖评论模块的 Java 或 Mapper。
3. 目标的 `commentId` 是回复的 UUID。V1 上的回复在 V2 发布后打开，仍定位 V1，不显示 V2 的评论。
4. 没有解析器的类型仍保持 `WAITING_SOURCE` 并按退避重试；部署解析器后，等待中的工作在下一个退避到期时继续消费。

### 问题单接入

V38 增加独立问题 projection 和收件箱 `gap_event_id`，保留旧协作复合外键、唯一键及全部原通知身份、创建顺序和首次已读时间，不复制或回填旧通知。源事务先提交状态、历史、事件和固定意图；派生只消费无正文事实，不调用问题写服务，也不读取 `entry.body`。

| 事件 | 源事务固定收件人 |
| --- | --- |
| `GAP_CREATED`、`GAP_RETURNED` | 当时有效且持有 read 的受理池 |
| `GAP_ASSIGNED` | 新负责人 |
| `GAP_SOLUTION_SUBMITTED` | 原提问者 |
| `GAP_SOLUTION_REJECTED`、`GAP_RESOLVED`、`GAP_WITHDRAWN` | 当时负责人 |
| `GAP_CLOSED` | 原提问者及当时负责人 |

全部排除操作者并去重；`NOTE_ADDED` 只记历史，不发提醒。后来获得 read/handle 的人员不补收旧意图。源命令匹配重放不新增事件；消费者重试按来源和 eventId/recipientUserId 去重，不刷新已读时间。意图、生成通知、用户已读和业务解决是四种不同事实，projection=DONE 也不代表用户已查看或问题已解决。

提醒的时效按工作身份判定：创建/退回要求 SUBMITTED 且队列事件仍当前；分派/拒绝要求 IN_PROGRESS 且负责人、分派代次匹配；方案要求 AWAITING_CONFIRMATION 且方案身份匹配。终态提醒核对对应不可变终态事实及当前问题访问。追加说明只增加问题版本，不使仍有效的工作提醒消失；改派或新方案使旧提醒不再充当当前待办。

## 部署与回退

1. 先部署 `versioned-document-review`，确认其事件、收件人意图和主规格已同步。然后由 kb-app 单点执行 `V24__in_app_notifications.sql`，该脚本不修改 V23 源事件的 schema 语义。
2. 部署顺序：先 kb-gateway（增加 `/api/notifications/**` 业务路由）和 kb-app，再发布前端。评论解析器随 `versioned-document-comments` 上线，在此之前未知类型会安全等待。
3. 新 app 可以从已有的可信审核事件派生通知。通知的 `createdAt` 是实际生成时刻，源事件保留原来的 `occurred_at`。不能从旧日志或未提交状态补造提醒。
4. 回退：先撤下前端，再设置 `KB_NOTIFICATION_DERIVATION_ENABLED=false` 停止派生调度，保留源事实、通知和已读数据。源审批不依赖消费者运行；恢复后按原事件身份继续派生。不要通过 Flyway repair 或删除迁移历史来回退。

以上只说明部署边界，本变更没有执行迁移或联调。

问题扩展必须先排空旧 app，再由全部兼容新版 app 执行 V37/V38，随后升级 gateway，最后发布声明能力的新前端。旧 app 不认识新权限目录和双来源结构，不可混部。旧前端可按缺省契约继续读取旧通知；静态回退不删除问题数据，也不能把 app 回退到不认识新目录的版本。完整顺序和止损边界见 [问题运维说明](knowledge-gap-operations.md)。

## 派生运行

| 配置（前缀 `kb.notification`） | 默认 | 范围／说明 |
|---|---|---|
| `derivation-enabled` | `true` | `false` 时不注册调度器 |
| `scan-delay-ms` | 5000 | 两轮扫描之间的固定延迟 |
| `discover-limit` | 50 | 每轮最多登记的新事件数，范围 1–500 |
| `claim-limit` | 50 | 每轮最多领取的事件数，范围 1–500 |
| `recipient-batch-size` | 100 | 每批收件人数，每批一个短事务，范围 1–500 |
| `max-batches-per-event` | 10 | 单事件单轮的批次上限，超出后放回队列，保留游标 |
| `lease-seconds` | 60 | 租约时长，至少为批事务超时（10 秒）的 3 倍 |
| `backoff-initial-seconds` | 30 | 首次退避，之后每次翻倍 |
| `backoff-max-seconds` | 900 | 退避上限（15 分钟） |
| `metrics-delay-ms` | 60000 | 积压指标的采集间隔 |

一轮的处理流程如下：

现有调度入口依次对协作和问题来源各运行一轮，分别应用上表的发现、领取及批次数预算，一类失败不跳过另一类。两类共用开关和低基数指标，不添加 source/user/event 标签。关闭派生会停止两类提醒生成，不撤销已经提交的源业务；恢复时按原意图继续。

1. 发现：用 `NOT EXISTS` 反连接找出还没有 projection 的事件，按 `ON CONFLICT DO NOTHING` 幂等登记。不使用全局高水位，因为序列或时间的分配顺序不等于提交顺序，较早位置的事件晚提交时仍能在后续轮次被发现。
2. 领取：选出到期且没有有效租约的工作，用 `FOR UPDATE SKIP LOCKED` 加锁，推进 `claim_token`，租约时间取数据库时钟。接管过期租约会计入接管次数。
3. 分批：每批读取下一组收件人（按用户 ID 升序，排除操作者本人），在同一事务里先推进 `recipient_cursor` 并校验 token，再写通知。token 不匹配就整体回滚，旧执行不能推进新进度。通知唯一键冲突时不刷新 `created_at` 或 `read_at`。读到末页才把 projection 标记为 `DONE`。
4. 失败：本批回滚，已提交的游标保留，projection 按退避重新排期（`DERIVATION_FAILED`）。如果连退避也写不进去，就等租约到期后由其他实例接管。

### 发现查询的成本

两类 projection 不删除，完成事件也一直保留。各来源的发现查询需要对相应事件与 projection 做反连接，即使已有大量 DONE 事件。`discover-limit` 只限制每轮登记的数量，不保证扫描行数有界；10 秒语句/批事务超时约束等待预算，不能证明扫描成本。本变更没有实测执行计划或耗时。

`kb.notification.derivation.discover` 记录每轮发现查询的耗时，成功和失败都计入。如果耗时持续上升并接近批事务超时（10 秒），可以先调大 `scan-delay-ms` 降低频率。根本的优化是只扫描安全水位之后的事件，但滞后时长必须大于最长的源事务时长加上时钟偏差，设错会漏发通知，需要单独立项设计和验证。

### 积压观测

指标只带固定标签，不包含用户、文档或事件 ID：

| 指标 | 标签 | 含义 |
|---|---|---|
| `kb.notification.derivation.backlog` | `state=pending\|waiting_source` | 两类完整来源未完成事件数，尚未登记 projection 的事件归入 pending |
| `kb.notification.derivation.oldest_age_seconds` | — | 最老未完成事件距源事件发生的秒数 |
| `kb.notification.derivation.failures` | `stage=discover\|claim\|lease\|batch\|postpone\|backlog` | 各阶段失败次数 |
| `kb.notification.derivation.takeovers` | — | 接管过期租约的次数 |
| `kb.notification.derivation.discover` | — | 每轮发现查询的耗时（Timer），成功和失败都计入 |
| `kb.notification.read.mismatch` | — | 读取列表时 SQL 结果与 Java 复核不一致的次数，每次都对应一个 503 |

积压一次聚合两类完整来源，各 LEFT JOIN 独立 projection 后合并；DONE 排除，最老年龄取两类未完成源事件的最早时间，最低为 0。它是事件数，不是收件人数、当前待办数或送达证明；无提醒意图的事件也会在发现并完成前短暂计入。发现失败仍能观测未登记事件，但采集本身失败、空值或非法计数时只增加 `failures{stage=backlog}`，保留上次可靠指标，不能把旧值或启动初值当作当前零积压。日志只写固定消息，不带标识、正文或原始异常。

### 恢复步骤

- `pending` 或 `oldest_age_seconds` 持续增长：先检查 `failures` 的分阶段计数和数据库可用性，确认 `derivation-enabled` 没有被关闭，调度线程池没有被其他任务长期占用（`spring.task.scheduling.pool.size`）。
- `waiting_source` 不为零：说明存在没有解析器的事件类型，例如评论生产者先于解析器上线。安装对应解析器后，这些工作会在退避到期时自动恢复，不需要手动修改数据。
- 实例崩溃：其他实例在租约到期后接管，并从已提交游标继续，已生成的通知不会重复。
- `discover` 耗时持续上升：按上面“发现查询的成本”处理。
- `failures{stage=backlog}` 增长：积压快照不再可靠；先检查依赖和聚合超时，不将保留值当作当前无积压，也不靠清零指标掩盖故障。
- `read.mismatch` 不为零：检查 `ReviewNotificationTargets`、`CommentNotificationTargets` 或 `GapNotificationTargets` 与同源授权/工作身份 SQL 是否同步。受影响列表返回 503，未读数只按 SQL 计数，可能仍有数字；需要修正代码，不能靠修改数据恢复。
- 问题已提交但没有提醒：先从本人或合格受理入口重读问题事实，再区分源意图、独立 projection 退避和当前提醒有效性；改派/旧方案隐藏、操作者排除、旧客户端未声明能力均不是源处理失败。不得因此重发方案、代确认或查询他人收件箱。
- 不要手工删除 projection 或通知（触发器会拒绝）。也不要把派生失败当作源业务失败去重放审批。

## 接口

三个接口都只处理当前服务端会话对应的用户，不接受 `userId` 或收件人覆盖参数。管理、审计或空间资格都不能用来查询他人的通知。成功返回 HTTP 200 并使用 `R` 封装。应用响应和网关的提前拒绝响应都带 `Cache-Control: no-store`。

| 方法与路径 | 输入 | data |
|---|---|---|
| GET `/api/notifications` | 可选 `limit`（1–100，默认 20）、`cursor` | `{items, hasMore, nextCursor, asOf}` |
| GET `/api/notifications/unread-count` | 无 | `{count, asOf}`，`count` 是精确的十进制字符串 |
| PUT `/api/notifications/{notificationId}/read` | 无正文、无查询参数 | `{notificationId, readAt}` |

列表项只包含 `notificationId`、固定 `type`、服务端固定的 `prompt`、`createdAt`、`readAt` 和结构化 `target`：

```json
{ "kind": "DOCUMENT_VERSION_REVIEW", "documentId": 11, "versionId": 21, "reviewId": "<uuid>", "commentId": null }
{ "kind": "DOCUMENT_VERSION_COMMENT", "documentId": 11, "versionId": 21, "reviewId": null, "commentId": "<回复 uuid>" }
{ "kind": "KNOWLEDGE_GAP", "gapId": 31 }
```

列表项不包含标题快照、评论正文、审核意见、用户目录、存储键或任意外链。`target` 只描述固定版本和轮次，或固定版本上的那条回复。打开目标时仍由源业务重新鉴权，前端不能拿“最新版本”去代替。

问题目标只含 kind/gapId，不附加文档空字段、description、方案、solutionId 或任意 URL；旧两个文档目标仍精确保留五字段。打开问题只能重读相同 gapId 的当前授权详情，不从通知恢复旧方案正文或执行任何处理动作。

### 客户端能力合同

- 列表和未读数缺省只返回旧四类及其可见计数，保留旧游标合同。不会根据 User-Agent 推测支持能力，旧客户端不能看到问题提醒。
- 新客户端必须在两种读取中均发送单值 `X-Kb-Notification-Contract: KNOWLEDGE_GAPS_V1`，才合并八类 GAP_*；不得混用新列表和旧计数，或失败后去掉能力头重试。
- 未知、空白、重复或逗号合并的头值返回安全 400。新游标绑定本人及契约，跨契约/跨人使用为 400；契约或账号改变应清空原游标及计数，从首页重新读取。
- 能力声明不是资格，不扩大本人归属、问题或文档权限。已读入口不要求能力声明，仍只设置本人通知首次已读时间；所有响应 no-store，包括错误。

### 当前权限

列表和未读数在 SQL 中使用同一个集合式可见性谓词，先过滤再分页或计数，不会把整个收件箱加载到内存。授权片段直接引用 document 模块的同源规则：结果提醒使用版本 INTERACTIVE 访问片段；待审提醒使用 `DocumentReviewEligibilityMapper.pendingReviewer`，审核工作台待办也引用同一个片段，修改一处即两边同时生效；回复提醒使用评论目标发布证据片段和评论模块的 `liveReply` 片段。

读取列表时，`ReviewNotificationTargets`（回复提醒为 `CommentNotificationTargets`）会按 SQL 投影出的事实再独立判断一次。两边结论不一致时，整页返回 503，并让 `kb.notification.read.mismatch` 加一，不会输出未经确认的目标。未读数只执行 SQL，不做这次复核。收紧 `pendingReviewer` 时，列表和未读数都会隐藏该项。放宽时必须同步修改 `ReviewNotificationTargets`，否则列表会持续返回 503，而未读数仍按放宽后的 SQL 计数，两者会不一致。

- 待审提醒：同一轮仍是 `IN_REVIEW`，文档没有搬迁、没有发布，收件人当前具备 view、review 和密级，且不是本轮送审者或上传者。已经由他人处理的轮次会被隐藏，提醒数也不能当作审核待办数。
- 结果提醒：收件人是本轮送审者，轮次结果没有变化，且当前可以交互读取该固定版本。只有 edit 权限的送审者看不到这条提醒，但仍可以在 `/reviews?kind=mine` 读取最小状态。
- 回复提醒：收件人当前可以 view 该文档且密级足够，文档和空间未归档、未删除，版本有发布证据，且回复本身未删除。根评论被删除不隐藏合法回复。
- 问题提醒：按原提问者、当前仍有 handle 的负责人或 read 的问题范围及上述工作身份共同判定。问题分支引用 `KnowledgeGapMapper` 的同源片段，列表投影由 `GapNotificationTargets` 独立复核。方案资料不可读不扩大或取消问题资格；进入详情时另行隐藏资料身份和标题。
- 撤权、升密、归档、删除后，下一次读取的列表和未读数会同时隐藏该项。隐藏项没有占位、旧标题或计数。权限恢复后，仍保存的关系可以从首页重新出现。
- 无法完成可见性检查时返回 503，不会返回旧计数、空列表或虚假的零。
- 列表和未读数各自带 `asOf`，两次请求之间不保证冻结一致。

### 已读不等于已处理

PUT read 只修改本人通知的 `read_at`，幂等，并保持首次已读时间。即使目标已经不可见，本人的旧通知 UUID 仍可以标记已读，但响应不会回显目标。不存在的通知和他人的通知都返回 404。标记已读不会触发审核、回复或重发提醒；待审提醒被标记已读后，审核待办仍按业务状态存在。

### 错误

| 状态 | 场景 |
|---|---|
| 400 | 未知、重复或空参数，`limit` 越界，游标损坏或属于其他用户、查询或契约，能力头非法或重复，ID 不是 UUID，GET 带正文，PUT 带正文或查询参数（包括伪造的收件人、标题或外链） |
| 401 | 没有会话或会话失效 |
| 403 | 主体或主部门已停用，或需要强制改密 |
| 404 | 通知不存在或不属于本人（仅 PUT） |
| 503 | 依赖故障，或列表的可见性复核不一致 |

错误体使用固定消息，不包含原始异常、SQL、目标或身份信息。读取不会创建业务审计、模型用量或新通知。

## 用户页面

以下既有文档/回复交互保持。问题页面扩展完成后，新通知列表和导航计数将一致声明新能力；问题提醒只打开同一问题的受保护详情，点击不会自动已读或确认解决。权限及工作身份变化时，重读结果为准，不回退其他问题或新方案。

- 主导航中的“站内通知”对应 `/notifications`，支持直接访问、刷新和登录后返回。返回地址只接受精确的 `/notifications`，带查询参数、`userId`、子路径或站外地址时都会回到文档中心。页面不依赖 AI 开关或管理资格，普通用户即可使用。
- 页面按服务端游标续页。列表为空、读取失败、仍有更多、已到末页分别显示不同提示，不显示总数或隐藏条数，也不补查隐藏目标的标题。每条通知显示本地固定提示（例如“有版本等待你审核”）、生成时间、已读状态和文档/版本编号，不渲染服务端或源业务文本。
- “打开目标”只在页面内打开通知记录的固定版本和轮次的审核面板，不跟随最新版本，也不提供文件或外链入口。面板按当前权限重新读取；如果该版本的轮次已变化，会提示先核对，撤权时清理旧内容并提示不可访问。打开目标不会标记已读，也不会提交审批。回复提醒打开的是固定版本上的那条回复，先按原版本核对目标评论，失效时清理并提示，不改为显示其他版本的讨论。
- “标记已读”必须由用户明确点击，提交期间不能重复点击，成功后更新该条并刷新未读数。如果结果不确定，页面会提示“待核对”，可以重试同一条，或刷新列表确认；已不存在的通知会从列表移除。
- 导航上的未读数默认 60 秒刷新一次（`VITE_NOTIFICATION_POLL_MS` 可调，最低 30 秒）。同一标签页的导航和页面共享一个请求。标签页隐藏、离线、退出或身份未确认时停止刷新，并清除旧数字；恢复时先核对身份再读取。读取失败时显示“?”（待确认），不会把旧数字当作当前事实。本版本不使用 WebSocket 或浏览器推送。

### 权限失效

通知不是持续授权。撤权、升密、归档、删除或审核已被他人处理后，下一次读取时对应条目会从列表和未读数中消失，已打开的目标在核对失败后会被清理。已经送达浏览器的内容无法远程撤回。列表、游标、回执、目标和计数只保存在当前页面内存中，不写入 localStorage 或 service worker。退出、切换账号、强制改密、标签页隐藏或离开页面时都会取消请求并清理；迟到的旧响应不会写进新身份的页面。

## 验证范围

本变更只做离线验证：独立内存单测、无连接 MyBatis 映射、ArchUnit 架构检查、路由规则和过滤器单测、前端组件和状态单测、虚拟时间、类型检查和静态构建。来源 service、认证和下游客户端全部使用 Mock 或 stub。没有执行迁移，没有启动应用、网关、数据库、Redis 或浏览器。真实数据库的提交原子性、行锁和 `SKIP LOCKED` 互斥、提交顺序、查询性能和多实例行为都没有验证，不能根据离线结果宣称生产可用。

可见性片段同样没有执行过。测试覆盖了 Java 复核规则、列表与计数绑定同一组参数，以及待审提醒与审核工作台待办引用同一个 `pendingReviewer` 片段，但没有用真实数据证明 SQL 会隐藏撤权项，或在过滤之后才分页。第一次有真实数据库时，建议按下面的清单核对一遍：

1. 为同一收件人准备几条通知：一条正常待审，以及撤销 view/review、升密、文档或空间归档、轮次已由他人处理、文档搬迁、版本已发布、edit-only 送审者的结果提醒各一条。确认列表只返回正常项，未读数与列表一致。
2. 恢复其中一项权限，确认该项从首页重新出现。
3. 准备多条较新但不可见、较旧但可见的通知，确认首页填满可见项，`hasMore` 正确。
4. 用同一收件人对比 `/reviews?kind=pending` 与待审提醒，两边应列出同一组轮次。
5. 查看列表、计数和发现查询的执行计划与耗时，确认 `kb.notification.read.mismatch` 为 0。

## 本地检查记录（2026-10-25，2026-10-26 复核修正后重跑）

以下命令均在 Windows PowerShell 中执行，测试只运行明确列出的白名单，没有跑包含集成测试或外部组件的全量套件。

项目根目录：

```powershell
mvn -o -pl kb-app,kb-gateway -am test '-Dtest=NotificationModelTest,NotificationMappingTest,ReviewNotificationContractTest,NotificationDiscoveryTest,NotificationLeaseTest,NotificationRetryTest,ReviewNotificationVisibilityTest,UserNotificationServiceTest,NotificationControllerTest,ModuleBoundaryTest,EmbeddingUuidMappingTest,DocumentReviewMappingTest,ReviewCollaborationFactTest,DocumentFavoriteQueryTest,DocumentFavoriteControllerTest,DocumentFavoriteResponseTest,EnvironmentIsolationTest,RecordOpenApiSchemaTest,QueryCursorTest,QueryParametersTest,GlobalExceptionHandlerTest,PasswordChangeRequiredInterceptorTest,SaTokenConfigTest,ApiHttpContractTest,NotificationRouteTest,NotificationResponsePolicyTest,ReviewRouteTest,ReviewResponsePolicyTest,FavoriteResponsePolicyTest,SaTokenGatewayConfigTest,ConversationRouteTest,CorsConfigurationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
mvn -o package '-DskipTests' '-Dstyle.color=never'
```

结果：kb-app 112 项、kb-gateway 13 项全部通过，失败、错误、跳过均为 0。其中新增通知测试 57 项（kb-app 54、kb-gateway 3）：

- 模型与无连接映射：不含正文或凭据的结构、精确大整数与 UUID 映射，列表与计数绑定同一组归属/范围/可见性参数，以及待审提醒与审核工作台待办引用同一个 `pendingReviewer` 片段（用带唯一参数的探针替换该片段后，两个语句都绑定了该参数）。
- 指标：发现查询耗时在成功和失败时都记录；读取侧 SQL 与 Java 复核不一致时计数，依赖故障不计入。
- 审核协议契约：使用真实 `CollaborationEventService` 写入；验证送审池、本轮送审者、自身排除，以及撤回/取消/发布不产生提醒。
- 发现：反连接与较晚提交的事件。
- 租约与分批：快照回滚的内存事务管理器配合真实 Spring 事务拦截器，覆盖批次中断、过期接管、旧 token 拒绝、行锁跳过和单轮预算。
- 退避与等待：WAITING_SOURCE 不热循环、失败保留工作、指标标签固定、重复派生不重置 readAt。
- 资格矩阵、服务、控制器、过滤器与架构规则。
- 网关路由与响应策略。

其余为受影响的既有回归。`mvn -o package -DskipTests` 下五个模块打包成功，打包阶段不执行测试。

`kb-web` 目录：

```powershell
npm test -- --run
npm run typecheck
npm run build
```

结果（2026-10-26 重跑，结果不变）：105 个测试文件、644 项全部通过，其中通知相关 20 项、导航/路由新增 3 项。新增测试覆盖白名单目标、大整数、共享单在途请求、60/30 秒间隔、隐藏/离线暂停与身份核对、账号切换迟到结果、分页/空页/故障区分、显式已读互斥与待核对、固定版本与轮次以及目标切换。全部使用 mock 会话、受控 Promise、虚拟计时器和 jsdom。类型检查通过。静态构建通过，主 chunk 为 1,210 kB，略高于 1200 kB 的告警阈值，这是告警，不是失败。

以上结果不包含迁移执行、真实 SQL 权限与执行计划、事务回滚和锁互斥、提交顺序、多实例并发、网关或应用启动、浏览器端到端，以及说明文档或 SQL 文本断言。没有启动数据库、Redis、MinIO、Docker 或模型服务。
