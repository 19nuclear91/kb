# Design

## Context

动机与用户确认的首期范围见 [proposal.md](proposal.md)。本变更跨 app、Worker、gateway、common 和 web，涉及发布事务、个人关系、周周期及通知兼容，因此需要设计文档。

当前接入点已核对：

- app 的 `document/service/ReviewApprovalService`、`AutoPublicationTransaction` 和 Worker 的 `parse/ChunkPublisher` 均保存 `VERSION_PUBLISHED`；原事件含文档/版本和参与者，没有发布时空间/标签。
- V23 的协作事实追加不可改。原发布事件没有订阅收件人；`NotificationType.IGNORED` 包含发布，V24 的 DONE projection 不允许重开。
- V24/V38 的收件箱支持协作、知识问题两种来源，以复合外键约束来源与收件人，通知只允许首次设置已读。列表、未读数和 Java 目标复核必须使用一致权限规则。
- 标题、标签和空间是可变化的文档级事实；标签最多 20 个。标签停用保留旧关联，空间搬迁清除文档例外并保留标签。不能在异步处理时读取它们来代表发布时状态。
- 人工说明属于固定版本，可在发布后补写/更正；说明基准可能早于实际上一发布版本。版本对比要求两侧当前可读且有结构快照。
- 新近答案卡代码使用 `controller/answercard`、`service/answercard`、`service/impl/answercard`、`mapper/answercard` 和 `domain/{req,dto,vo}/answercard`，新订阅代码沿用这套外层技术分层。原通知、文档代码在原包内维护。

## Goals / Non-Goals

**Goals:**

- 将发布时的订阅匹配、频率归属和提醒生成时的取消规则分别持久表达，后台延迟不改变原意图。
- 使用现有数据库事务和租约构成可重试派生，不在发布事务中遍历订阅者，不要求新中间件。
- 证明每周来源匹配已经完整后再发送；现有通知、本人更新、周报各自有清晰身份和当前可见范围。
- 每个写入有明确原意图及回执；每个源/提醒状态转换有唯一键、事务设计及进程内行为验证，测试使用受控替身模拟竞争。

**Non-Goals:**

- AI 生成摘要、外部 IM/邮件/浏览器推送、管理员代订及跨用户关注统计。
- 修改审核流程、文档发布资格、检索、模型上下文或原件访问规则。
- 旧发布历史回填、自动将收藏变成订阅、每条订阅独立频率、用户自定义周报时区/时刻。
- 自动删除订阅历史、更新或通知；这些保留策略未来需要独立设计。
- 需要前后端联调、启动应用/HTTP 测试服务器、真实浏览器或外部数据库、Redis、对象存储等组件的测试及其测试入口。
- 说明文档存在性、指定文字/章节、示例内容的断言测试，以及用迁移 SQL/Mapper 字符串包含检查代替行为测试。

## Decisions

### 1. 四类事实与一个原子发送边界

```text
Publication + immutable context
                |
                v
Bounded matching + subscription/frequency history
                |
                v
Personal update + original matching generations
                |
        +-------+-------+
        |               |
        v               v
Per-publication     Weekly closure
        |           + complete matching
        |               |
        +-------+-------+
                v
Current access + original cancellation check
                |
                v
Delivery fact + shared inbox (one transaction)
```

本人更新表示发布时曾命中订阅的无正文关系，不证明当前阅读权或已经发出提醒。站内通知与 `NOTIFIED` 提醒事实同事务提交，定义为“已发”；用户打开、标记已读另有事实。取消先于该提交可阻止提醒，提交后取消保留历史。

协作来源维持原 VERSION_PUBLISHED 忽略行为。新来源使用独立进度与提醒身份，不修改旧源事件收件人、不重开旧 DONE，也不复用 Worker 运维告警 outbox。

### 2. 用数据库事务顺序定义生效边界

采用单行 `kb_knowledge_order` 保存事务性递增 `position` 与非递减 `business_time`。订阅新代次、取消、频率修改、正式发布上下文、周期封口、提醒终结使用同一数据库函数取得顺序；该行锁保持至事务提交，回滚不保留位置。位置用于先后关系，业务时间取锁后 `GREATEST(clock_timestamp(), last_business_time)`，业务周按 `Asia/Shanghai` 转换。不得使用 app/Worker 的 `Instant.now()` 来判断订阅区间。

成立条件：订阅代次 `start_position < publication_position` 且 `end_position IS NULL OR publication_position < end_position`。频率取该用户最后一个早于发布位置的偏好修订；没有显式修订为 WEEKLY。每次真实变更取得不同位置，因此没有同位置争议。相同时间戳也可按位置判断。新发布的证据时间、协作事件时间和上下文时间统一使用本次数据库时间。

这是一项补充实现选择：首期接受相关短事务末段的数据库串行化，换取可验证的提交先后与周期完整性。仅用时间戳/普通 sequence 会遗漏较早取号晚提交事务；异步读取当前订阅会把迟到处理变成历史补发；全套 CDC/提交 LSN 管理引入额外运维和恢复协议，首期不采用。

锁顺序必须统一：请求回执互斥 → 必要的既有资源锁（空间、文档、版本按原顺序）→ knowledge order → 新订阅/偏好/提醒/周期记录。持有 knowledge order 后不得再申请资源行锁、调用外部服务或等待另一派生工作。最终提醒在取得 order 后以普通集合查询复核当前资源，不能反向锁文档。匹配批次不取得 order。

发布在分块、快照等批量操作后、实际推进发布的短末段取得 order；成功路径随后仅写有界上下文和原事务末段事实。新增锁等待上限 3 秒、终结短事务上限 10 秒；失败使用现有业务/Worker 可恢复失败处理，不吞掉超时宣称成功。真实 PostgreSQL 锁序与吞吐不在本变更测试范围，离线调用顺序和故障处理测试不证明这些数据库行为。

### 3. 最小数据模型与不可变边界

拟在下一迁移（当前最高 V44，实施前重核编号）引入：

| 表/结构 | 核心含义及约束 |
| --- | --- |
| `kb_knowledge_order` | 单例 position、非递减业务时间、协议版本及覆盖起点；只通过统一函数推进 |
| `kb_knowledge_subscription` | UUID、本人、DOCUMENT/SPACE/TAG、目标 ID、状态、当前 generationId、单调 stateVersion；本人/类型/目标唯一 |
| `kb_knowledge_subscription_period` | generationId、subscriptionId、生效位置/时间、至多一次关闭位置/时间；同订阅至多一个未关闭区间 |
| `kb_knowledge_preference` | 本人当前模式、version；无行读取为 WEEKLY/version=0，读取不创建记录 |
| `kb_knowledge_preference_revision` | 本人、位置、模式、时间；追加式，供发布时频率查询 |
| `kb_knowledge_command_receipt` | 本人、请求标识、操作、规范载荷绑定、预期版本/代次和最小结果；不存名称/正文 |
| `kb_knowledge_publication` | eventId 与原发布复合关联、position 唯一、文档/版本、spaceId、排序去重 tagIds（0–20）、业务时间、业务周；追加不可改 |
| `kb_knowledge_match_projection` | 发布来源、状态、租约、claimToken、最后已提交 recipient 游标、退避和固定错误类别 |
| `kb_knowledge_update` | UUID、本人、eventId、当时模式、周；本人/eventId 唯一，追加不可改 |
| `kb_knowledge_update_match` | updateId 与全部原命中 generationId；复合归属约束，重试不遗漏第二条匹配依据 |
| `kb_knowledge_week` | weekStart、start/end、dueAt、closePosition、状态、租约/claimToken及收件人发现游标；每周唯一，封口和完成不可倒退 |
| `kb_knowledge_delivery` | UUID、本人、PER_PUBLICATION/WEEKLY、updateId 或 weekStart 恰有一个、状态、租约/token、dispatchPosition/时间；本人/update 和本人/week 各自唯一 |
| `kb_user_notification.knowledge_delivery_id` | 第三来源复合外键 `(delivery_id, recipient_user_id)`；三来源恰有一个，类型与来源一致 |

`subscriptionId` 是本人/目标关系身份，重新订阅可沿用该身份但必须创建新 `generationId` 并推进 stateVersion。取消请求绑定两者与预期版本；旧取消请求重放只能返回旧代次回执。新建/重订阅请求携带该关系的预期状态版本，完全未知目标为 0；受保护候选/状态查询返回本人当前关系版本。

原始目标的历史 spaceId/tagIds 仅是不可变数值，不给空间目标建立会阻止合法空空间删除的引用外键；文档/版本和源事件使用复合外键，保证同一来源归属。目标 ID 不复用，删除后名称不能被另一个同名空间继承。主实体和所有字段交付中文说明；迁移包含用途、操作范围和实际表/列 COMMENT。

约束保护原始匹配、模式和来源不可改，代次只能首次关闭，提醒终态不可重开。保留 V24/V38 原唯一键、外键、通知身份、创建顺序及首次 readAt；更新通知保护函数时完整覆盖第三来源，禁止借扩展放宽原不可变列。

### 4. 发布上下文在三个生产路径原子保存

app 审核批准、app 自动发布和 Worker `ChunkPublisher` 使用同一个数据库 order/capture 协议。持有原文档/版本锁时取得新的发布顺序与时间，实际推进版本、保存原发布事件并插入上下文。tagIds 读取与 metadata/搬迁共用文档锁，保证空间/标签来自同一串行事实。上下文最多一行及 20 个标签，不计算订阅者。

新增延迟至事务结束校验的约束触发器：覆盖起点之后，版本从 NONE 到 PUBLISHED 必须存在原稳定发布事件及完整上下文，三者文档/版本/时间一致；插入新发布事件也必须满足该关系。它只验证本事务已保存结果，不在提交阶段补取 order 或补读当前标签。旧缺上下文发布写入会失败，不能成功漏发。应用重放已提交批准返回原回执，不重新取顺序。

Worker 新启动检查协议版本；迁移只由 app 执行。仅候选 indexed 或未真正推进发布指针时不保存上下文，已观察旧当前版本不回填。提醒开关只控制消费者。

### 5. 异步匹配保留所有原命中代次

app 通过新上下文与 projection 反连接发现待匹配发布，`ON CONFLICT DO NOTHING`。使用数据库时钟的租约、`FOR UPDATE SKIP LOCKED` 和 claimToken；每批先复核令牌，在同事务保存整批 update、全部匹配代次及进度，终页后才 DONE。不能“先写 update 成功即跳过匹配依据”，否则重叠订阅会丢失。

按发布空间、文档与最多 20 个标签查历史区间，先合并用户，再以 userId 升序分页；为每个用户取得发布位置时的偏好。查询包含已关闭但发布时仍有效的历史代次，之后的重订阅不会命中。每用户该发布最多命中 22 条不同目标关系；每批用户默认 100、最大 500，单轮每事件最多 10 批，跨轮保存游标。

匹配关系只存无正文订阅意图，即使当前不可读也不向用户输出；内容投影与实际通知生成必须另查当前授权。此区分使“后台停机后才处理取消前的发布”与“取消前已经处理”得到相同个人历史。处理时不重算历史权限快照，不将命中关系称为授权证据。

源发现用反连接，不假设序列最大值代表完整；历史区间、source/week 及 recipient/order 查询交付索引定义。超时保留重试，不丢弃大订阅者集合或把截断标为完成；本变更不交付或运行真实 SQL 执行计划测试入口。

### 6. 发送与取消采用同一个可串行边界

每个逐次 update 登记一个 delivery；领取发送工作须确认该更新所属发布匹配已 DONE，防止未完整保存原匹配依据时发送。终结事务先取得 order，再锁定该工作并校验租约，随后复核当前主体/固定已发布版本访问和原命中代次状态。原命中至少一个在 dispatchPosition 之后才关闭（或尚未关闭）时，才支持提醒；新代次不能替补。合法时保存 NOTIFIED、dispatchPosition 与收件箱同事务；已确认没有资格保存 NO_ELIGIBLE，依赖失败则回滚并退避。

取消同样取得 order 后关闭原 generation。它不批量扫描和删除所有未发记录：发送查询按原代次关闭位置判定即可覆盖尚未派生的工作。已发历史读取使用保存的 dispatchPosition 判定取消资格，不使用“现在是否订阅”重新过滤；资源资格仍每次按当前事实查询。

原提醒事实一旦 NO_ELIGIBLE 不因后来获权自动重发；个人无正文更新关系可在之后有阅读资格时显示。订阅者本人参与发布也按订阅处理，避免 AUTO 的 null actor 与人工审核者产生不一致；旧协作/问题来源自身排除不变。

### 7. 周周期先封口，再等匹配，再按用户终结

日历固定 `Asia/Shanghai`。对每个到期周，在周一 09:00 后取得 order，保存 weekStart、半开区间、dueAt 和 closePosition。因 order 事务串行，取得封口位置时所有更早位置已提交或回滚；非递减业务时间确保之后的新发布不会再落入已封口周。不能只检查墙钟或等固定九小时就宣称完整。

周期状态为 WAITING_MATCHES → READY → DONE。WAITING_MATCHES 检查该周、覆盖起点之后且不超过 closePosition 的全部 publication 是否已有 DONE 匹配；缺 projection、处理中或失败都继续等待。只有完整后才按该周 WEEKLY updates 发现用户工作，分批持久推进收件人游标；所有用户登记完成后周期 DONE 表示“派发工作已登记”，不是已送达或已读。

每个 `(user, week)` 一个 WEEKLY delivery。终结前以同一 order 规则计算未在发送前取消的原匹配范围，并用集合式 EXISTS 确认其中至少一个当前可读固定版本。成功则生成一条通知并保存 dispatchPosition；否则为 NO_ELIGIBLE。终结不在一个事务内复制整周正文或无界加载全部项目。

周报成员通过完整匹配后的固定 update 集合、WEEKLY 模式、原周和原代次在 dispatchPosition 的有效性定义。资源权限未被冻结：发送时至少一项可读，读取时对固定成员集合重新过滤；之后获权可恢复原成员，之后撤权可隐藏成员。取消之后不改变已发周报成员，后来补写说明不改变成员，之后的新订阅和逐次项不能进入该周报。

恢复扫描从覆盖起始周到已到期周，以持久周游标分批补建周期（默认每轮最多 20 周）。重启、周一停机、匹配积压均处理原周，不把迟到项挪入下一周，也不在已发周报后补第二条提醒。个人 `/updates` 独立支持任意业务周的两种模式查询；它不是另一次周报发送。

### 8. 内容读取、说明和数量共用授权边界

新增只读查询先确认 update/delivery 属于本人，再基于既有版本 INTERACTIVE 同源规则及真实 PUBLISHED 证据限制来源；继承主体、view、密级、删除、文档/空间归档语义。周报存在性/未读数使用同一成员过滤的 EXISTS。组查询、组数量和明细使用 REPEATABLE READ 请求快照；数据库查询先过滤再分组、计数、排序及取 limit+1，禁止取原始前 N 条后在 Java 丢弃。

列表返回当前可读文档标题，明确它是文档当前标题；仅在展开固定发布详情时读取人工说明。详情调用现有说明读服务并做安全投影，显示 noteVersion、corrected、createdAfterVisible。若说明原基准当前不可读，新增聚合响应不给其 ID/版本号和对比入口，也不改写原说明；说明文本仍是目标版本现有授权内容。原说明接口不在此变更中扩大可见范围。

没有说明基准时，由服务端选择同文档较旧、有 PUBLISHED/OBSERVED_CURRENT 证据且当前可读的最近基准，仅作为明确的对比建议；没有则不可对比。进入原对比页重新鉴权两侧。缺快照/超限/超时保留原状态，读取失败不能转成“无说明”或“无差异”。详情不缓存正文或把说明写入 delivery/inbox。

取消订阅无需目标仍可读，只允许本人取消自己的 generation。订阅列表的不可用项是本人的管理记录，不是文档占位：仅 subscriptionId/generationId、kind、状态/版本及时间可见，隐藏 targetId/label。正常内容列表没有隐藏项占位或数量。空空间可在 view 下订阅；文档例外可发现其所在空间，但后续匹配仍逐文档授权。标签新候选须 enabled 且有当前可读关联；已停用标签的原关系继续使用。

### 9. API 与命令恢复

统一 `R<T>`、no-store 和当前认证代次。以下为首期接口设计；JSON schema、字段中文说明、准确泛型及错误码须在实施时完整交付。

| 方法/路径 | 契约 |
| --- | --- |
| `GET /api/knowledge-subscriptions` | 本人关系列表，state/kind/limit/cursor，隐藏目标的管理投影 |
| `POST /api/knowledge-subscriptions/candidates` | kind、query、limit、cursor；只返回可选目标及本人已有关系版本/代次 |
| `POST /api/knowledge-subscriptions/target-state` | kind、targetId；按当前候选资格读取该目标的本人关系版本/代次，供已授权文档详情订阅按钮使用 |
| `POST /api/knowledge-subscriptions` | kind、targetId、expectedVersion；新建或重新激活，返回 subscriptionId/generationId/version/effectiveAt |
| `PUT /api/knowledge-subscriptions/{id}/cancel` | generationId、expectedVersion；返回旧代次取消事实 |
| `GET /api/knowledge-subscription-preferences` | mode、version、当前固定周报日历 |
| `PUT /api/knowledge-subscription-preferences` | mode、expectedVersion；返回频率生效事实 |
| `POST /api/knowledge-subscriptions/receipts/query` | requestId；只读本人请求状态，CONFIRMED/UNKNOWN分别表达，不把不可见的在途事务猜成已失败或未执行 |
| `GET /api/knowledge-updates` | 可选 weekStart、limit、cursor；两种频率的本人发布列表 |
| `GET /api/knowledge-updates/{updateId}` | 固定发布详情、当前人工说明及可用对比基准 |
| `GET /api/knowledge-digests` | 本人已生成且当前有可读成员的周报，limit/cursor |
| `GET /api/knowledge-digests/{digestId}` | 周范围与当前可见文档组，limit/cursor |
| `GET /api/knowledge-digests/{digestId}/documents/{documentId}` | 该组可见发布明细，limit/cursor |

所有变更命令 X-Request-Id 为 1–128 非控制 code point；读回执不把 requestId 放 URL。请求 JSON 拒绝未知/重复字段、非法 Unicode、尾随内容；正文限 16 KiB。UUID/正整数严格解析，数据库 ID、版本、计数用精确十进制字符串。分页默认20/最大100；候选 query 最多128字符按字面匹配，私有条件不进入 URL/日志。周日期严格 ISO 周一日期。

回执唯一 `(actor, requestId)`，保存操作域、原目标关系/代次、预期版本及规范完整载荷绑定。先校验当前可信身份与本人关系再读回执，匹配重放优先于旧 expectedVersion 判断；已提交创建即使目标后来不可读，也只能返回去掉目标 ID/名称的原回执，不能产生新订阅。首次创建则必须重新核对当前候选资格。

并发重复请求使用唯一约束和事务等待后重读，不把唯一键竞争直接暴露成 500。无实际改变/重复有效订阅返回固定 409；状态冲突、绑定冲突、上限冲突分开。未知结果只读查询不能因为看到当前 active/weekly 就推断某命令成功；回执查无记录不证明在途事务不会稍后提交，只允许原意图重试或显式放弃后重新读取和确认。

### 10. 收件箱三来源兼容

新增类型和最小目标：

| type | target |
| --- | --- |
| `SUBSCRIBED_VERSION_PUBLISHED` | `{kind: KNOWLEDGE_UPDATE, updateId: UUID}` |
| `KNOWLEDGE_WEEKLY_DIGEST` | `{kind: KNOWLEDGE_DIGEST, digestId: UUID}` |

逐次目标指 update，周报 digestId 为 WEEKLY delivery 身份；服务端必须核对该 delivery 已 NOTIFIED、本人及精确来源。固定提示不含标题/说明/数量。更新与周报的 read 模块提供目标可见性的同源 SQL，通知读取做独立 Java 复核，列表与计数共用过滤；不一致返回 503，不能丢弃异常目标后声称完整页。

缺省契约继续旧4类；KNOWLEDGE_GAPS_V1 继续旧4+8问题类型；KNOWLEDGE_SUBSCRIPTIONS_V1 为上述12+2订阅类型。能力不是资格，列表与未读数必须一致绑定；旧游标保持原格式和校验，新游标绑定新契约。后端继续严格拒绝未知/重复/合并头，前端不降级重试。旧服务不认识新值时拒绝，由协调发布解决，不能吞头回退。

现有派生总开关 `kb.notification.derivation-enabled` 继续作为站内提醒后台总开关；新增 `kb.knowledge-subscriptions.derivation-enabled` 默认 true 控制匹配/周报/提醒，新任务只有两者都开启才调度。两开关均不影响 source capture、本人管理或读取。现有审核/问题循环失败隔离保留，新来源单独预算，不能耗尽原通知批次。

### 11. 前端交互与分层

- 路由：`/subscriptions`、`/updates`、`/updates/:updateId`、`/digests/:digestId`，按现有 router 动态加载。本人订阅页提供三类候选、当前关系与频率设置；文档详情增加文档订阅按钮并独立重核资格。
- 我的更新按周查看全部本人匹配记录；周报按文档组分页，展开再加载固定发布明细与说明，避免一页同时加载无界历史。
- 写入模型复用项目“原请求＋精确载荷＋预期版本＋结果未知”模式。原上下文关闭编辑后再次打开可继续核对；换号/隐藏/离页清除内存，恢复只读刷新，不自动重发。只读重核不能清掉同上下文未确认绑定。
- 导航和通知页统一声明新契约。点击更新/周报只打开目标，显式已读继续原流程；切换账号、隐藏、离线清理/暂停及低频轮询保持既有规则。
- app 新增类按 `controller/subscription`、`service/subscription`、`service/impl/subscription`、`mapper/subscription`、`domain/subscription`、`domain/{req,dto,vo}/subscription`、`enums/subscription`，调度/参数在 `config`；只建立实际需要目录。匹配器/周期判定是业务 service 实现，不能塞工具包。
- Worker 只扩展现有 `parse` 发布类/mapper 和必要协议检查配置。common 仅放共享值/端点协议，不放数据库访问。gateway 补显式路由、认证、改密和响应策略。新增 Java record、注解、实体和 API 按现有规范完整展开，依赖注入在同类中保持统一。

### 12. 资源限额与观测

`kb.knowledge-subscriptions` 拟提供：派生默认启用，扫描间隔5秒，发现/领取每类50项（1–500），用户批次100（1–500），每事件每轮10批（1–50），租约60秒（至少3倍10秒事务预算），退避30秒至900秒，周期追赶每轮20周（1–52）。非法配置启动失败。本人有效关系上限200为首期业务合同，不以调大配置偷偷改变 API。

新低基数指标记录 match/weekly/delivery 阶段未完成量、最老年龄、失败/接管和采样成功时间/可用性，固定标签仅 stage/outcome。指标 query 超时3秒、采样默认30秒，HTTP 抓取不临时查询数据库。缺依赖/失败不清零；发布成功、匹配完成、提醒生成、用户已读不混用同一计数。首期交付运维查询和验证方式，新增外部告警平台或面板不作为隐含范围。

### 13. 独立离线验证范围

本变更仅编写和运行无需服务联调、无需外部组件的测试。后端采用进程内服务/状态机、内存仓储、事务/协议替身、虚拟时钟及不监听端口的控制器/过滤器测试；前端独立使用模拟接口和模拟 DOM。保留编译、类型检查、静态构建、包体隔离、OpenSpec 结构校验，以及无连接的映射解析和参数绑定检查。

业务行为覆盖三类订阅、重复命中、原代次取消、重订阅与旧回执、频率切换、个人与周报不同口径、受控交错和迟到结果、分批中断/失租、空周与依赖故障、权限组合、说明更正与缺快照、三档通知合同及原意图恢复。模拟事务冲突、唯一键冲突、协议缺失和数据读取失败时，只验证调用方的实际处理行为，不据此声称数据库锁、触发器、约束或提交顺序已通过验证。

不创建或执行真实 PostgreSQL/Redis/对象存储/模型集成测试、Docker/Testcontainers 测试、前后端联调、应用/HTTP 测试服务器启动、真实浏览器端到端或部署验收任务。迁移、函数、约束和业务代码仍按规格交付，真实数据库行为、SQL 执行计划、跨实例效果及部署仅注明未验证，不以“可选测试入口”或待完成清单重新纳入本变更完成条件。

文档工作为编写和维护使用、API、运维及发布说明，不增加文档文件是否存在、是否包含文字/章节/示例等断言或逐项文字核对任务；迁移 SQL 和 Mapper 不做静态字符串包含测试。验证记录只陈述实际执行的离线命令及结果。当前仅调整规划，没有执行实现、迁移或部署。

## Risks / Trade-offs

- [全局 order 串行热点] → 仅在相关短事务末段持有，不包含扇出/正文，并限制等待。锁等待和发布吞吐未实测，离线结果不作为容量结论；不能直接换普通 sequence 破坏顺序语义。
- [一个旧周的匹配积压延迟整个周报] → 保留明确 WAITING_MATCHES 和最老年龄，持续分批恢复；保障完整性优先于伪造准点送达。不同周/来源使用独立预算。
- [历史区间和事件持续增长] → 首期保留、索引及有界查询，实际扫描成本未验证；不通过删除源/回执偷偷破坏取消与重放语义。
- [权限可变化导致历史数量变化] → 列表、组、明细和计数每次共同过滤，asOf 标明读取时点；历史提醒不代表持续授权。
- [人工说明后来更正] → 展示说明版本和更正/补写标记，保留原基准，不声称是发布时快照或 AI 结论。
- [日历/机器时间差异] → 一律数据库非递减时间及固定 Asia/Shanghai，用户本地时区只影响可选展示；不复用其他功能可配置的业务时区来改变已生成周身份。
- [新通知破坏旧客户端] → 保留两个旧合同、旧游标和精确类型集合；新增来源需完整迁移、后端先行和显式新能力。
- [解析缺口影响新文件可发布] → 规划不补造解析结果；可用现有已发布资料/受控夹具验证订阅逻辑，真实新上传仍需 E-01 接入。

## Migration Plan

以下为交付文档需要说明的部署顺序及兼容约束，本变更只交付相关实现和说明，不执行迁移、部署、联调或真实环境验收。

1. 实施前核对最高 Flyway 版本；准备新增表/列/函数/触发器/索引及完整中文元数据注释。迁移不回填旧发布的空间、标签、订阅或提醒。
2. 数据变更原样保留旧协作/问题通知的 ID、创建顺序、首次已读、唯一键、外键；第三来源 XOR 与保护函数在同次迁移内完整更新。
3. 暂停新上传发布相关写入并排空旧 app/Worker 写入事务；由升级 app 单点执行迁移，登记新协议覆盖起点，升级全部 app 和 Worker，并检查发布协议。禁止旧写入实例继续运行。
4. 三个发布入口必须都使用保存完整上下文的新协议，再开放发布写入；新派生初始可关闭，不影响新事实捕获。升级 gateway 路由/过滤器后发布新 web；旧 web 保留旧合同可继续读取旧类型。
5. 派生启用后按持久覆盖起点和原周期处理积压，调度启动不等于通知已生成或用户已读。
6. 止损先撤下新 web，再关闭新派生；保留新事实、关系历史、回执、已读和通知。后端/Worker 只可回退到仍理解新发布协议与通知结构的兼容版本，不反迁移、不删除新列、不修复 Flyway 历史、不重开旧 DONE。恢复按持久原周期继续。

## Open Questions

无阻塞性产品决策待确认。生产订阅规模和实际运行性能未验证，不属于本变更测试任务；已确认的生效、取消、频率及周报口径保持既定含义。
