# Design

## Context

动机和已确认边界见 [proposal.md](proposal.md)，外部行为以本变更的七份 `specs/*/spec.md` 为准。以下是本变更的技术决策，不是运行证据；用户已另行授权实施，实际结果见 [verification.md](verification.md)，七份规格的逐场景证据见 [traceability.md](traceability.md)。

2026-11-14 已按用户授权将七份增量规格合并到主规格并归档，当前业务合同见 [主规格目录](../../../specs/)。本设计保留实施时的技术取舍；归档不改变后文离线验证范围，也不表示迁移或部署已执行。

已核对的实现约束：

- `PermissionService` 只在请求内复用主体；`AdminQueryAccess.current()` 覆盖启用主体、有效主部门及强制改密，不能缓存当前参与或功能资格。
- `IdentityQueryService` 有最小身份投影模式，但现有完整人员检索要求 `identity:user:read`，不能直接作为本功能选择器。
- `DocumentVersionAccessService` 支持固定版本鉴权；`PublicationEvidence` 为 NONE、PUBLISHED、OBSERVED_CURRENT。仅“indexed”或旧兼容可读不足以证明版本发布。
- 审核命令已有绑定请求、短事务和并发版本模式，但 `ReviewCommandBinding` 与既有回执表绑定文档域；不直接伪装成审核命令。
- V24 的通知 projection 外键指向协作事件，收件箱外键指向协作收件人；现有协作事件、Java record 和 SQL 均要求文档版本。前端 `notifications/api.ts` 对旧类型及目标字段严格匹配。
- 当前正式迁移最高 V36，只有 app 执行 Flyway；`ModuleBoundaryTest` 限制跨模块 Mapper 和通知调用源业务写服务。`ProductionSettings` 按模块列出敏感 Mapper 日志前缀，新包必须补入。
- `navigation.ts`、`routing/lazyPages.ts`、路由配置与管理资格 store 共同控制受保护页面及安全登录返回。问答临时流与已保存轮次已经区分。

## Goals / Non-Goals

**Goals:**

- 新业务由 app 持久化，保持文档与私有会话原契约不变；无需 AI、解析器或新的外部服务即可完成说明型闭环。
- 问题访问、动作资格、资料授权分别验证；列表、详情、历史、候选、通知及回执不存在不同的授权真相。
- 短事务实现可重放且不可复活的状态机，保留明确分享的业务记录。
- 在旧收件箱保持原身份、顺序、已读和去重语义的前提下增加独立问题来源，旧浏览器不因新类型报错。

**Non-Goals:**

- 不建立通用工单平台、共享聊天、AI 自动处理、文件附件、隐藏内部评论或新的文档审核流。
- 不对用户人工粘贴的文本承诺语义撤回，不把本人确认解释为资料永久授权或模型答案真实性证明。
- 不改造全局协作事实为通用事件总线，不引入消息中间件，不回填历史搜索或问答反馈。

## Decisions

### D1 独立问题模块与两层授权

在 `kb-app` 新增 `knowledgegap/{web,service,mapper,model}`，复用现有身份入口，自己持有问题、记录、事件和回执。`kb-ai`、Worker 无新职责，不读取它们的内部数据库表或 Mapper。

问题可见谓词：

```text
valid current identity
AND (
  reporter_user_id = current_user
  OR (assignee_user_id = current_user AND current handle permission)
  OR current read permission
)
```

四个 code 固定为 `knowledge:gap:read/assign/handle/close`，每项独立。assign、close 在目标查询前检查精确动作资格，再检查问题可见性；处理写入还须为当前负责人。本人确认只取原提问者身份，不因其兼具其他角色而失效，也不因其他人有处理资格允许代确认。

采用无跨请求缓存的同源 SQL 片段与服务判定。详情返回当次 `capabilities`，只用于交互提示，不能作为下一次写入凭据。失去一种身份但仍有另一种身份时按剩余资格继续；管理员名称、历史参与或通知持有不是身份。

**替代方案**：直接复用空间管理或身份目录权限会扩权，也无法承接无法归类到空间的需求，故不采用。

### D2 单一负责人、明确的六态模型

初始问题版本为 0；每次新的成功动作递增一次，补充说明也递增。任一问题最多一名当前负责人，不增加会签或共同处理人。

| 动作 | 允许状态 | 动作身份 | 结果 |
| --- | --- | --- | --- |
| create | 无 | 有效用户 | SUBMITTED，无负责人 |
| assign | 三种非终态 | assign + 可见 | IN_PROGRESS，设置新负责人、递增分派代次，清除当前待确认方案 |
| note | 三种非终态 | 提问者、当前合格负责人或 assign + 可见 | 状态不变，追加共享说明 |
| return | IN_PROGRESS、AWAITING_CONFIRMATION | 当前合格负责人 | SUBMITTED，清空负责人及待确认方案，建立新受理队列信号 |
| solution | IN_PROGRESS | 当前合格负责人 | AWAITING_CONFIRMATION，保存新的固定方案身份 |
| confirm | AWAITING_CONFIRMATION | 提问者 | RESOLVED |
| reject-solution | AWAITING_CONFIRMATION | 提问者 | IN_PROGRESS，清除待确认身份，保留原负责人 |
| withdraw | 三种非终态 | 提问者 | WITHDRAWN |
| close | 三种非终态 | close + 可见 | CLOSED |

RESOLVED、CLOSED、WITHDRAWN 为不可重开终态，保留最后分派关系供仍有资格者读取，不提供追加或编辑。相同负责人用新标识重复分派视作无效状态变化，返回 409；原标识匹配重放仍按幂等处理。退回、拒绝方案、撤回、关闭均须原因。

改派使旧方案不可确认，历史仍保留；仅追加说明不改变当前分派/队列/方案身份。负责人失效时不改写业务事实，队列返回不含具体停用原因的 `needsReassignment`。提问者失效不触发自动解决，受理人员可明确关闭。

**替代方案**：把每次发布、超时或处理人提交直接作为解决，会混淆用户确认，因此拒绝；多负责人使当前访问和收尾权复杂化，首期不做。

### D3 业务存储与来源内容隔离

计划新增两份迁移：`V37__knowledge_gaps.sql`、`V38__knowledge_gap_notifications.sql`。实施前核对最新版本，仅顺延新迁移号，不修改既有迁移。

核心表：

| 表 | 主要字段与约束 |
| --- | --- |
| `kb_knowledge_gap` | BIGINT id、reporter、category、source、明确分享的 description、consent_version、state、version、assignee、assignment_epoch、pending_solution_id、queue_event_id、数据库时间；状态与当前方案/负责人组合检查 |
| `kb_knowledge_gap_entry` | 不可变 UUID 身份、gap_id、单内顺序、kind、author、body、可选 document_id/version_id；仅方案允许文档身份，二者同空或同非空，固定关联回问题 |
| `kb_knowledge_gap_event` | UUID event_id、gap_id、操作种类、结果版本、操作者、分派代次、必要方案/说明身份、服务端时间；唯一 gap_id + result_version，不含描述、标题、正文或原因 |
| `kb_knowledge_gap_event_recipient` | event_id + user_id 主键，源事务固定意图，排除自身；无正文 |
| `kb_knowledge_gap_command` | 全局 request_id 唯一、actor、operation、gap_id、expected_version、规范化输入摘要、最小已确认回执；无正文/资料身份回执 |
| `kb_knowledge_gap_actor_guard` | reporter_user_id 主键，只用于串行化本人新建容量检查，不保存凭据或权限 |

当前方案和队列事件使用含 gap_id 的复合引用，禁止串单；创建时预分配事件 UUID，循环引用使用延迟到事务提交的外键，不靠临时关闭约束。entry、event 和 recipient 的既有行禁止更新/删除，命令绑定不可变且只允许一次完成回执。创建命令的绑定目标为集合入口，不把首次生成的 gapId 当作重试必须提供的输入；实际新问题身份只来自首次结果。

description 首次写入后不可编辑；人工说明、方案、拒绝及终结原因只在 entry 中保留，不复制进事件、收件箱、回执或通用审计摘要。历史读取先鉴权再分页，按需联合 entry，资料另行投影。保留期已由用户选择为首期保留，不创建清理调度或删除 API。

只保存 source 枚举，不保存搜索、会话或轮次 ID；源页面弹窗只预填原始用户输入。人工处理文本明确按问题范围分享，提示不要粘贴不应分享的内容；不执行自动摘要或正文搬运。

**替代方案**：持久会话快照会破坏私有会话与删除语义；将问题塞进文档评论会要求已知且可读的文档版本，均不采用。

### D4 命令绑定、锁顺序与原子写入

所有修改使用短事务，事务超时 5 秒、语句超时 3 秒、锁等待最多 1 秒，不进行模型、存储、Redis 或通知网络调用。

1. 流式限制 JSON 正文 64 KiB，严格解析重复键、未知字段、尾随文档和非法 Unicode；先校验身份与动作功能资格。
2. 涉及已有问题时先做不泄露目标存在性的当前访问和动作关系检查，再领取 `kb_knowledge_gap_command` 的唯一 request_id。规范化载荷包括操作、actor、目标、expectedVersion、必要 solutionId、说明及资料身份。
3. 用 `INSERT ... ON CONFLICT DO NOTHING` 领取命令；冲突后在新语句读取绑定，不捕获已中止事务继续查询。绑定不符为 409；匹配回执仍先重核当前访问及动作身份/权限，但不重做已经失效的状态前置条件。
4. 新建先领取本人 actor_guard 行锁、检查非终态数量小于 100；其他新操作领取 gap 行锁，复核访问、预期版本、当前负责人及状态。统一顺序为命令绑定 → 创建额度锁（仅 create）→ 问题锁，避免跨操作反向锁序。
5. 当前请求复核候选/文档资格；更新问题、entry、无正文 event、收件人意图、回执同事务提交。任何一步失败整体回滚。

回执只含 gapId、resultVersion、state、eventId、必要 solutionId 与 replayed，不返回描述、原因、文档身份或当前详情；因此旧成功回执不能替代重读。退回或改派已导致操作者失去访问时，重放同样拒绝，即使这会使未知结果只能由受理人员另行核对，也不延续历史授权。

不同命令竞争同一 expectedVersion 只允许一个成功；确认额外绑定当前 pending_solution_id。数据库资格检查按当次请求事实完成，不承诺撤回已经送达的内容，后续请求仍重新核对。

**替代方案**：只靠前端按钮互斥或单独写审计/通知无法解决多实例竞争与部分提交，不采用。

### D5 有界 API 与错误投影

返回统一 `R` 成功封装；列表结构为 items、hasMore、nextCursor，详情含问题元数据、共享描述、当前方案和能力，不内嵌无界完整历史。历史独立分页。所有整数身份与版本为 JSON 整数并由前端精确处理，方案和事件身份为 UUID。

| 入口 | 用途及载荷 |
| --- | --- |
| `POST /api/knowledge-gaps` | category、description、source、shareAcknowledged；服务端绑定 consent_version=GAP_SHARE_V1 |
| `GET /api/knowledge-gaps` | view=mine/assigned、status、limit、cursor |
| `GET /api/knowledge-gaps/{id}` | 当前问题详情 |
| `GET /api/knowledge-gaps/{id}/history` | limit、cursor，操作位置正序 |
| `POST /api/knowledge-gaps/{id}/notes` | expectedVersion、text |
| `POST /api/knowledge-gaps/{id}/return` | expectedVersion、reason |
| `POST /api/knowledge-gaps/{id}/solutions` | expectedVersion、text、可选 documentId/versionId |
| `POST /api/knowledge-gaps/{id}/confirm` | expectedVersion、solutionId |
| `POST /api/knowledge-gaps/{id}/reject-solution` | expectedVersion、solutionId、reason |
| `POST /api/knowledge-gaps/{id}/withdraw` | expectedVersion、reason |
| `POST /api/knowledge-gaps/{id}/solution-documents` | nameQuery、limit、cursor，只限当前合格负责人 |
| `GET /api/admin/knowledge-gaps` | status、limit、cursor；read 资格 |
| `POST /api/admin/knowledge-gaps/{id}/assignees/search` | nameQuery、limit、cursor；assign + 可见，不产生业务写入 |
| `POST /api/admin/knowledge-gaps/{id}/assign` | expectedVersion、assigneeUserId |
| `POST /api/admin/knowledge-gaps/{id}/close` | expectedVersion、reason |

只有修改端点要求 X-Request-Id；候选 POST 是只读，不领取命令、不生成成功事件。除列表/历史明确允许的非敏感参数外拒绝 URL 查询参数。分页默认 20、最大 100；人员和资料 nameQuery 最多 128 code point；描述/方案最多 4000，补充/原因最多 2000。先验证长度不静默截断，原搜索或提问超限须由用户在弹窗手动缩短。

`QueryCursor` 延续现有校验模式，绑定 user、视图/目标及筛选，不作授权凭据。未知/重复参数、未知枚举、非整数身份/版本、NUL 和损坏游标为 400；无身份 401、主体或独立资格拒绝 403、无问题访问或不存在 404、版本/状态/容量/候选变化 409、超体积 413、依赖故障/数据库超时 503。错误只给固定类别，不回显输入。

**替代方案**：把所有筛选、操作或目标合成一个宽松通用接口会模糊动作权限和载荷绑定，故采用明确动作端点。

### D6 处理人候选不借用身份管理权限

在 auth 服务层增加受控候选与单人合格性检查契约，复用其自身身份/角色查询，不让 knowledgegap 调用 auth Mapper。统一核对用户启用、主部门有效、mustChangePassword=false 及持久 handle。候选输出仅 userId 和 label，游标绑定当前 actor、gapId 和查询；实际 assign 在事务内再次校验。

领域读取历史操作者标签时仅在已核对问题访问后调用 `IdentityQueryService.labels`，不查询角色、部门、密级或外部绑定。队列只投影负责人是否仍合格，不解释具体生命周期原因。

**替代方案**：直接读取 `/api/admin/identity/users/search` 要求更宽目录权限，违反最小分享，故不采用。

### D7 方案资料先发布、双方可读，之后逐次鉴权

资料候选从 document 服务提供的窄接口取得：处理人当前可 view、文档/空间未归档未删除、当前版本的发布证据非 NONE。不因候选查询向处理人暴露提问者的 ACL；提交时才分别取得双方当前身份并检查固定版本。

使用既有 `DocumentVersionAccessService` 的交互可读边界并补充明确发布证据核验，而不是仅依赖 MODEL_SOURCE 的旧兼容规则；通过 document 服务提供的专门方法组合，不跨模块 Mapper。PUBLISHED、OBSERVED_CURRENT 视为已知发布，时间未知仍未知；NONE 即使可读或 indexed 也不合格。

entry 只保留固定文档/版本身份，标题临时按当前查看者权限投影，正文只走原受保护文档/引用接口，不在问题单复制或自动下载。不可访问的关联统一给 `referenceState=UNAVAILABLE`，不返回 documentId/versionId、标题或数量；无关联为 NONE。权限查询失败为整体安全 503，不以 UNKNOWN 放行。

文档型 confirm 再核对提问者对该固定发布版本的当前资格；失效为 409，仍允许 reject-solution 或 withdraw，不自动降为说明型。已经 RESOLVED 后发生资料撤权只影响资料展示，不改写既有本人确认事实。人工输入的处理说明按问题共享，不承诺识别人手粘贴内容的来源。

**替代方案**：仅凭处理人能读就发给提问者会形成扩权；存标题/正文快照会逃逸撤权，均拒绝。

### D8 问题事件独立，收件箱仍统一

保留 V23/V25 协作事件、收件人及审核/评论派生语义，不把无文档问题写入其 record 或利用 `CollaborationEventService` 的默认审核覆盖逻辑。

V38 增加 `kb_knowledge_gap_notification_projection`，复用现有 PENDING/WAITING_SOURCE/DONE、数据库时间租约、claim_token、收件人游标及退避算法，但外键指向问题事件。notification 内部新增自己的 gap 来源 Mapper/短事务适配器；源模块不依赖 notification，消费者不调用 knowledgegap 写服务。调度器有界轮询两类来源，各自失败不跳过另一类；发现用反连接而非全局高水位，晚提交不丢失。当前 metrics 的名称/标签保持，backlog 从两类完整来源一次合并，失败不得导出假零。

统一收件箱 `kb_user_notification` 做加法扩展：

- 原 `event_id` 允许空，增加可空 `gap_event_id`；检查二者恰有一个，原协作复合外键和新问题收件人复合外键分别保留。
- 原 `(event_id, recipient_user_id)` 唯一键保留，增加 `(gap_event_id, recipient_user_id)` 唯一键；type 与来源种类一致性由约束检查。
- 保留全部既有 id、notification_id、created_at、read_at 和单调序列；更新防篡改触发器的不可变列元组，纳入 gap_event_id，只允许首次设置 read_at。
- 不回填或复制旧通知，不另建第二套可见顺序。列表与计数使用同一来源分支定义：既有文档分支保持原谓词，问题分支引用同源问题可见性；`UNION ALL` 后再排序、LIMIT 或 count。
- Java 目标改为白名单判别联合：旧两种目标 JSON 字段完全不变；新 `{"kind":"KNOWLEDGE_GAP","gapId":...}` 不带文档空字段、URL 或正文。派生和查询只读取问题事实及同源授权片段，不读 entry.body。

事件与收件人对应规格的八类 GAP_*。CREATED/RETURNED 固定当时 read 池，分派固定新负责人，方案固定提问者，拒绝固定当时负责人，终结按相应本人/负责人意图；使用源事务 SQL 固定收件人而非 Java 无界收集。NOTE_ADDED 只留事实不发通知，所有意图去重并排除操作者。

通知有效性不用普通问题 version：queue_event_id 表达当前待受理工作，assignment_epoch 表达当前分派，pending_solution_id 表达当前待确认方案；note 只递增 revision，不使有效提醒凭空消失。终态提醒核对其不可变终态事件及当前问题可见性。

**替代方案**：移除外键、伪造文档 ID 或一口气通用化所有事件源，会破坏现有数据与报表边界；独立第二收件箱则难以维护统一分页/未读数，故选择独立源、共享收件箱。

### D9 能力协商避免新类型破坏旧浏览器

`GET /api/notifications` 与 `/unread-count` 只允许缺省或单一 `X-Kb-Notification-Contract: KNOWLEDGE_GAPS_V1`。缺省维持原类型集合及旧游标过滤结构；声明时纳入 GAP_*，游标过滤增加契约标识，跨契约使用返回 400。未知或重复头为 400。服务端不根据 User-Agent 推测能力，不新增任意类型过滤接口。

新前端两种请求统一发送该头并严格解析判别联合，旧前端不收到未知类型。无论契约或响应结果均 no-store，新增 `Vary` 不是缓存许可。已读仍按本人 notificationId 设置首次时间，不要求获得目标资格或能力声明。

**替代方案**：直接向旧客户端发送新类型会触发其 exactKeys/type 校验失败；简单丢弃未知项会让列表和未读数不一致，因此不采用。

### D10 前端复用外壳而不引入跨身份草稿

新增 `src/knowledgeGaps/` 的纯 API/绑定逻辑、共享详情和最小反馈弹窗；新页面按路由懒加载。搜索 `SearchWorkspace.vue` 和问答 `ConversationContent.vue` 只在显式打开反馈时加载弹窗，不让登录入口或文档列表依赖问题详情代码。

普通入口为 `/knowledge-gaps`（本人/分派给我视图）、`/knowledge-gaps/new`、正整数详情；受理入口为 `/admin/knowledge-gaps` 及详情。更新实际路由、`lazyPages.ts`、`navigation.ts` 安全返回、管理权限映射与导航。非敏感路由只含目标 ID，不使用 history.state、URL、localStorage/sessionStorage 保存反馈内容。

创建表单确认 `GAP_SHARE_V1` 文案：分享给原提问者、当前合格处理人和授权受理人员；会保留业务记录，撤回不删除；不要粘贴不应分享的内容。取消不发请求；问答只允许已持久确认轮次预填其原问题，不访问原答案或来源。首期没有服务端私人回看指针。

页面绑定沿用 Session epoch、请求代次及同步互斥；隐藏/换号/撤权清理敏感状态，恢复重核。当前请求的未知结果绑定仅驻内存，刷新后只重读服务端事实，不自动恢复或重发；为小概率难核对结果保留安全提示，而非放宽旧身份权限。

**替代方案**：浏览器持久草稿或服务端自动快照便于恢复，但扩大私人内容和旧身份污染风险，因此不做。

### D11 新入口的网络与日志边界

gateway 的 prod/dev app 路由补入 `/api/knowledge-gaps` 与子路径，admin 入口沿用既有 app 路由；不得转发至 AI。扩展响应策略确保 app 以及网关认证、身份核对、正文拒绝等提前失败均 no-store，能力头原样受控透传。

新输入与异常处理限定在问题端点，避免全局改变旧 JSON 协议。`ProductionSettings` 的敏感 Mapper 清单加入 knowledgegap，覆盖父 logger 和日志组继承；新增路由请求体、候选名称、正文及底层异常不得进入日志/追踪/指标。事件只记录必要 ID 与固定 kind，不将共享正文纳入通用 audit。

**替代方案**：仅在成功控制器设置 no-store 或只压制业务包日志，会遗漏网关提前拒绝和 SQL 参数日志，故必须覆盖整条受控链路。

## Risks / Trade-offs

- [人工文本可能含不应分享的信息] → 明确分享确认和保留提示，不自动搬运模型/资料；这是人工业务记录，不承诺语义撤回。
- [保留记录持续增长] → 限制输入、分页、每人 100 个非终态并监测容量；保留策略变更另立项，不未经确认自动删除。
- [改派后旧操作者无法核对成功回执] → 优先当前权限，返回最小回执并提示由当前受理人员核对，不用历史回执延长读取。
- [负责人失效导致积压] → 保留可接管事实、提供 needsReassignment，人工改派或关闭，不自动扩权或判定解决。
- [文档资格在读取后再次变化] → 每次响应与命令重核，固定版本不自动替换；不能撤回已经交付内容，也不把确认当作永久授权。
- [统一通知查询分支复杂、权限过滤稀疏] → 同源 SQL、先过滤后分页，以离线 SQL 结构检查和混合来源/计数谓词单测降低漂移风险，禁止先取页后应用层过滤；真实执行计划和性能不在本变更验证范围。
- [新通知来源导致旧链路漂移] → 保留协作事实与原外键语义、独立源派生、旧 JSON 完全兼容、显式客户端能力；回归旧四类通知及已有已读时间。
- [离线替身不能证明真实数据库或部署行为] → 本变更不安排外部依赖集成/联调或真实浏览器端到端测试；证据明确真实约束、锁/回滚、执行计划和部署行为未验证，不将这些边界重新列为强制待办，也不用离线通过代替上线验收。

## Migration Plan

1. 备份并核对 V36 基线，实施时顺延可用迁移号。新增问题表/索引、独立事实与回执，V37 一次性给当时 ROLE_ADMIN 绑定四项 code；空库引导用完整目录，不重启补权，不改文档 ACL。
2. 排空不认识新目录/通知结构的旧 app，先发布全部新版 app，由 app 执行迁移和 V38 通知加法扩展，再恢复业务流量。Worker 不运行迁移，新旧 app 不混部。
3. 升级 gateway 的新业务路由、no-store 与能力头处理；缺省通知契约仍服务旧前端。旧四类型列表、计数、旧游标及已读记录的兼容性保留为实现要求，本变更只安排离线契约回归。
4. 发布支持能力头及新目标的前端，按新哈希资产先上传后切换 HTML，保留旧资产；按现有角色管理显式配置受理和处理人员。
5. 本变更的验证交付仅包含离线单测、代码/配置静态检查及构建打包记录，不包含专用数据库、部署联调或真实浏览器验收，也不把这些测试设为入口开放的本变更任务。离线通过不等于迁移、约束或部署行为已验证；本节描述发布顺序和止损边界，不授权本轮实际部署。
6. 止损先撤下新入口并在受控入口停止新增问题写入，保留新版 app 对历史及已有通知的安全读取；不删除记录、迁移或权限。旧前端因缺省契约只见旧通知，可以静态回退，但这不意味着问题数据已删除。
7. 二进制仅回退到仍认识新权限、表和双来源收件箱的兼容版本。不得自动删权限绑定、删表、收件箱反迁移或回退到忽略新门禁的旧 app；必要时维护窗口处理。

## Validation Strategy

下列为已确认的实施验证范围，结果必须另行记录，不从设计文字推定完成。测试仅使用离线单测、内存替身、模拟 DOM 及代码/配置静态检查，不启动应用、真实浏览器、数据库、Redis、容器或其他外部服务，不进行前后端联调。混合测试类按方法选择符合范围的检查。

保留 API、运维及使用说明的编写交付，但不安排文档内容或存在性断言，包括 Markdown、README 和 OpenAPI 描述检查。业务文档访问控制、DTO/JSON 合同和代码/SQL/配置检查仍属于离线测试范围，不与说明文档测试混淆。

| 离线验证层 | 覆盖范围 |
| --- | --- |
| 纯领域与服务单测 | 使用身份/仓储替身检查六态全转移、终态不可重开、角色并集/改派失权、退回导致的回执拒绝、双方资料资格、记录保留及容量边界；不作为真实数据库并发配额证据 |
| SQL/Mapper、控制器与边界 | 离线检查迁移/约束声明、加载实际 MyBatis 配置并检查 BoundSql/显式映射，不连接数据库或执行迁移/SQL；用内存请求/响应检查严格 JSON/Unicode/64 KiB、错误状态、no-store、模块依赖及敏感日志配置 |
| 通知与事务替身 | 模拟两类来源的去重/退避/失租/晚提交、事务失败路径和状态竞争，检查 note 不使待办失效、列表及 unread 同谓词、新旧契约/游标及首次已读；不宣称证明真实锁或回滚 |
| 前端模拟 DOM | 分享确认/取消/超限预填、私有输入边界、说明/文档方案、确认退回、转派关闭保留提示、换号隐藏撤权、未知结果与迟到响应、严格通知联合、懒加载 |
| 网关内存检查 | 绑定实际 YAML 并执行内存路由谓词，使用过滤器依赖替身检查响应策略/能力头，不启动网关、下游、网络客户端或 Redis |
| 类型、构建与打包 | 后端离线白名单及 app/gateway 打包，前端类型检查、Vitest/jsdom、生产构建和包体隔离；不启动服务或真实浏览器 |

全部场景与代码、离线单测/静态检查证据建立映射；无法由离线检查证明的真实约束、触发器、锁/回滚、迁移执行、SQL 执行计划和部署联调行为明确记录为未验证。上述外部验证及文档内容测试不属于本变更任务或完成条件，不以跳过、编译或替身结果宣称其通过。业务状态、保留策略和权限模型不因本次测试删减而改变。
