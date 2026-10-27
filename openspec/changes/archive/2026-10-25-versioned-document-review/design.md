# Design

## Context

动机见 proposal.md，行为以本变更八份增量规格为准。已核对的实现约束：

- `DocumentWriteService.create` 和 `DocumentWriteMapper.insertDocument` 都写入 published；`ChunkPublisher.publish` 同事务写分块、indexed 和 current_version_id。
- `SearchMapper.xml` 两路只读取 published 文档的当前版本；`DocumentMapper.xml` 的列表、版本、文件、引用和 visibleVersions 主要依赖父文档 ACL，不能直接当候选版本鉴权。
- `DocumentVersionAccessService` 服务于历史回答；必须区分模型来源检查与人工候选核对。
- `PermissionService` 无跨请求缓存，manage 不隐含 view。空间和文档 ACL 当前拒绝 review/comment；已存在用户、角色、部门继承及上下文主体选择器。
- 全库 Embedding 重建仅覆盖未删除文档的当前版本，不能推断非当前候选已刷新。控制行锁、任务 claim token 及 task -> document 顺序必须保留。
- 旧表无版本上传者、审批或发布历史；indexed 只能证明索引完成，不能证明曾被用户发布。

## Goals / Non-Goals

**Goals:**
- 用固定版本状态机实现审核，不以整个文档 in_review 状态替换旧版可读性。
- 统一候选与历史版本的访问服务，并用调用目的防止审核资格扩大 RAG。
- 业务事实在 PostgreSQL 短事务中提交，给后续两个模块稳定输入，不先引入通知组件。

**Non-Goals:**
- 不接第三方解析或生产模型，不修改模型选择、不增加数据库直连 AI。
- 不实现 BPMN、多级会签、待审富文本讨论、审稿对比、管理强制通过、通知和报表页面。
- 不承诺从缺失证据恢复旧发布历史，不修改旧迁移或自动 repair。

## Decisions

### D1. 版本双轴及固定策略

`kb_space` 增加 publication_policy，AUTO 为默认。`kb_document_version` 增加固定 publication_policy、uploader_user_id、协议来源、state_version 与发布证据；旧行允许上传者未知，新行必须为服务端身份。创建版本以同一数据库语句／短事务读取文档实际空间与策略，定义策略快照时点为版本插入所见事实，不在上传到 S3 时提前固定。

| 索引轴 | 审核轮次轴 | 发布事实 |
| --- | --- | --- |
| pending/parsing/indexed/failed | 无申请、IN_REVIEW、APPROVED、REJECTED、WITHDRAWN、CANCELLED | NONE、PUBLISHED、OBSERVED_CURRENT 或旧兼容标记 |

需审版本无申请／被驳回／撤回后显示 DRAFT/待送审；indexed 不等于 published。`kb_document_review` 保存 UUID、doc/version、round_no、submitted_by、submitted_space_id、状态、并发版本、服务器提交/决定时间、decision_by 与私有原因。每版本至多一个 IN_REVIEW，旧轮次保留。先前已发布的版本不可重新进入审核或候选刷新。

新文档无当前版本时保持 draft；首次合法发布同事务设 published 和 current_version_id。已有 published 文档不因 V2 待审改成 in_review。归档／删除不能被解析或审批改回 published。

替代方案：直接复用文档 status 会令 V2 送审时 V1 退出检索；将审核状态混进 index_status 会把维护与业务驳回混淆，均不采用。

### D2. 审核人池与参与者

空间 review 授权沿现有用户／角色／祖先部门展开，只能在空间授予；文档级 review 拒绝。普通送审要求当前 edit 与版本已索引，不能凭 ownerId 送审。候选读取要求 view、密级、未归档未删除，再满足上传者、本版本已记录送审者或合格审核人之一。edit-only 上传者在 `/api/reviews?kind=mine` 只得到本人版本 ID、解析/审核固定状态和无敏感内容回执，不回显标题、文件名、原因、存储或参与者名单。

审批必须当前空间 review + 当前文档 view + 密级；上传者与本轮送审者均不能审批，避免上传者委托别人送审后自批。送审通过服务端查询确认至少一名其余合格审核人；不向提交者提供全局身份目录或隐含授予 view。

私有审核原因留在 review 表，仅向当前有 view 的上传者／历史送审者及当前合格审核人开放，发布后仍不对普通读者开放；事件和通用审计只写枚举、轮次、ID、时间，不存原因原文。没有匿名或管理员绕过。

### D3. 统一版本访问，区分使用目的

在 document/service 扩展版本访问门禁，提供批量文档可见性、版本可见性及以下明确目的；service 对其他模块开放，不能跨模块直调 Mapper。

- `INTERACTIVE`：有发布证据的历史版本按当前 ACL 可读；新未发布版本需要候选参与资格；旧兼容版本保留旧行为。
- `MODEL_SOURCE`：只有有发布证据或旧兼容来源的版本可用；当前检索仍要求 published + current_version_id，禁止将候选参与资格用于模型输入。
- `COMMENT_TARGET`：仅真实发布证据（含升级时 OBSERVED_CURRENT），旧非当前兼容版本不因 indexed 开放评论；此目的由后续评论变更使用。

父文档列表／详情／收藏先检查是否有允许显示的发布版本或当前用户候选资格；普通读者看不到首版候选文档。版本列表在 SQL 过滤后投影，不返回隐藏总数。默认下载／预览仍指 current_version_id；显式版本检查 INTERACTIVE，再独立检查 view/ download。引用、进度、capabilities、批量 visibleVersions、会话历史和内部 source seal 均审查调用目的，不能只改一个前端入口。新候选返回 no-store，目标不存在与不可见同 404。

不把候选角色、发布状态或文档 ACL 存成跨请求缓存。已送达内容无法远程撤回，仍遵守当前项目边界。

### D4. 索引落库与发布门禁

`kb-common` 提供纯 JDK 状态／发布判定值对象，不包含 Spring 或 Mapper。app 与 Worker 各自使用其持久层完成相同门禁；Worker 不调用 app 审核 API，AI 不访问数据库。

Worker 正常提交仍按 control SHARE -> task -> document/version -> chunks 的现有顺序，写入索引身份及 state_version。AUTO 且目标较新、文档空间可用时原子推进 current；REVIEW_REQUIRED 只 indexed，不设置发布事实。新候选通过以后不允许普通任务重写分块。

审批先拿控制共享锁，再锁文档／目标版本及审核轮次，复核当前 ACTIVE、profile/fingerprint、全部分块数量与身份有效、index_status、实际归属、当前版本序号及审批主体。候选生成 epoch 可以早于当前 epoch，但只有实际 profile 身份一致且本轮控制锁保护下合法才可发布；不同 profile 必须刷新，不能仅改标签。批准、发布证据、current 指针、并发版本、业务事件及回执一起提交，不提供已批准待发布的中间业务状态。

审批事务不调用网络、解析器、Embedding 或通知服务。维护控制独占锁阻止审批越过重建范围封存；原有全库重建范围不扩展到待审稿。

### D5. 候选索引刷新而非强制放行

对未发布候选提供独立显式命令 `index-refresh`。它在现有普通解析任务模型中开启新执行周期，推进 claim token，保留历史失败及固定策略；结束旧 IN_REVIEW 为 CANCELLED，原轮次意见仍私有。刷新允许重新调用第三方解析以产生合法当前索引，所以必须重新送审，不能保留原批准。其 task/control/document 锁序沿用重试协议，不借死信重放绕过 indexed 的通常限制。

维护中刷新只入队，待 ACTIVE 领取；已发布及 OBSERVED_CURRENT 版本拒绝此入口。若 AUTO 新版本在发布前因归档无法推进，保留索引结果与明确的未发布状态，空间恢复后由受控的幂等发布巡检重新核对策略、版本序号和 profile，不能凭取消归档无条件发布旧结果。

替代方案：将所有历史候选纳入全库重建会改变既有维护范围；通过审批时同步调用模型会拉长锁持有，均不采用。

### D6. 搬迁、删除、归档与并发

搬迁和删除在原业务事务的文档锁内追加终结活动 review、state_version 与事件，旧轮次不再可处理。搬迁后版本策略不变，新审核使用目标空间池与新的轮次；全部例外仍清除。归档暂停新读写，不隐式修改审核决定。

新增写入先绑定操作域和 requestId，再遵守涉及的 control -> task（仅重试/刷新）-> document/version -> review -> event/receipt 锁序。搬迁、删除不反向获取 control；审核只读取任务完成事实，不在锁住 document 后追锁 task。空间策略快照通过版本插入取值，避免 document -> space 与管理路径构造反向锁序。每个新增 SQL 需审查与既有搬迁、升密、删除和 Embedding 路径的组合。

每个 review decision 使用 expectedStateVersion／round，行数必须精确为 1；两审批人竞争、撤回和批准竞争只允许一个提交。匹配幂等先复核当前身份与资源资格，再比较规范化 digest 和既有绑定，避免原预期版本过期掩盖成功重放。并发唯一冲突采用 ON CONFLICT 后独立语句读取，不能在已 aborted 事务继续查。

### D7. 无正文协作事实是下游边界

此变更创建 `kb_collaboration_event`、`kb_collaboration_event_recipient` 和 `kb_collaboration_coverage`，独立于通知表与管理审计。事件含 event_id、协议版本、固定 kind、doc/version、review_id/round、actor、occurred_at、必要 submitted_at/decided_at；无标题、原件、意见、hash 原文或会话令牌。

事件种类：REVIEW_SUBMITTED、REVIEW_APPROVED、REVIEW_REJECTED、REVIEW_WITHDRAWN、REVIEW_CANCELLED、VERSION_PUBLISHED。唯一业务键以 kind + review/round 或 version 产生，不把一次匹配重试计为新事实。发布审批事务一次生成批准及发布两个不同事实，报表按正确 kind 统计。

送审同事务以受限 SQL 将当时合格审核人池去重为收件人 ID；批准／驳回以本轮 submitted_by 为收件人，不使用文档 owner。排除自身通知，不保存名称；无合格池阻止送审。名单是当时提醒意图，不是授权快照，下游每次读取再鉴权；后来加入者通过实时待办发现，不追发旧池通知。分批消费归 notification 模块，审核无该模块时也能工作。

coverage 保存 REVIEW 自新协议全部生产者启用起的时间；上线前排空旧写入以保证连续性。首期不自动清理这些最小事实，不从 sys_identity_audit 或通知送达条数反推业务计数。

### D8. HTTP 与页面接入

下表是新增契约路径，均保留 R 封装、严格参数和 no-store；现有接口路径不改。写入标识统一 X-Request-Id（1–128），不接受客户端身份／策略／批准时间。

| 接口 | 用途／输入 |
| --- | --- |
| GET/PATCH `/api/admin/spaces/{id}/publication-policy` | 读取；修改 policy + expectedVersion |
| GET `/api/reviews` | kind=mine/pending、limit、cursor；本人最小状态或当前合格待办 |
| GET `/api/documents/{d}/versions/{v}/review` | 当前状态、合法参与者所需私有轮次和版本级操作能力 |
| POST `/api/documents/{d}/versions/{v}/reviews` | expectedStateVersion，手动送审 |
| POST `/api/documents/{d}/versions/{v}/reviews/{r}/approve` | expectedStateVersion，通过即发布 |
| POST `.../reviews/{r}/reject` | expectedStateVersion + reason |
| POST `.../reviews/{r}/withdraw` | expectedStateVersion，本轮送审者撤回 |
| POST `/api/documents/{d}/versions/{v}/index-refresh` | expectedStateVersion，刷新未发布候选 |

review 查询对 edit-only 本人仅返回最小状态与可执行命令，不返回私有轮次原因；完整版本私有投影另受 INTERACTIVE 与意见资格检查。pending 待办先过滤再按稳定提交位置游标分页，无隐藏总数。错误使用安全固定码区分 STATE_CONFLICT、NO_ELIGIBLE_REVIEWER、EMBEDDING_MAINTENANCE、INDEX_REFRESH_REQUIRED，前端不匹配原始异常文案。

`kb-web` 增加 `/reviews`，空间详情增加策略区与 review 授权项；文档详情按所选版本能力呈现。复用 Session、useResource 与管理写意图模式，不将意见或原因持久化。Gateway 为 `/api/reviews/**` 在 prod/dev 同步加业务路由与 no-store 提前拒绝策略；既有 documents/admin 路由保持。

## Risks / Trade-offs

- [旧历史发布事实缺失] -> 显式旧兼容访问，不把历史 indexed 全量改为曾发布；只有当前发布指针可标 OBSERVED_CURRENT，时间未知。
- [审核人全部撤权或停用] -> 待办实时判定，当前空间管理员可配置 review/view，送审者可撤回；不设置强制代审后门。
- [审核池很大导致事务开销] -> SQL 去重、索引及短事务上限；超时整体失败，不静默截断收件人或伪造已送审。本变更不实测真实规模，不宣称事务性能已验证。
- [候选重解析产生不同结构] -> 刷新终结原审核、保留原件、强制重新送审，不复用旧批准。
- [多写入路径遗漏版本门禁] -> 按文档、版本、引用、收藏、会话、source seal 以独立单测和 Mock 依赖覆盖；真实数据库并发不在本变更验证范围，不能由离线结果推定。

## Migration Plan

1. 实施时在最新 Flyway 版本后追加迁移；备份并排空旧 app/Worker，不允许新旧协议混部。schema 不给新行默认 legacy，防止旧程序绕过门禁。
2. 旧版本固定 AUTO 与 LEGACY_COMPAT，上传者保持未知；只为实际 published/current 指针记录 OBSERVED_CURRENT，published_at 保持未知，不生成历史审核／发布事件。新版本必须有协议和上传者。
3. 部署完整门禁和新版 Worker，先保持所有空间 AUTO；审核业务事实覆盖起点记录在全新生产者开始时。管理方检查历史手工 review 行来源后再逐空间启用。
4. 前端在后端、网关 ready 后发布；新增接口未就绪时不能假装免审成功。
5. 回退首选关闭新增入口、保持新安全后端；开关关闭只影响新版本，已有待审不放行。禁止直接恢复会绕过审核的旧 app/Worker；灾难回退需维护窗口及经确认的备份恢复，不删除审批／发布事实。

本变更验证仅限独立内存单测、无连接映射、架构检查、编译、前端组件／状态单测、类型检查及静态构建。服务、控制器、网关和外部客户端的依赖使用 Mock／stub，不启动完整应用，不运行集成测试、跨服务联调或浏览器端到端验收，不启动或连接数据库、Redis、对象存储、消息中间件或第三方 API，不使用 Testcontainers／内嵌服务替代。迁移计划只说明部署边界，实施任务仅编写迁移脚本，不实际执行迁移；文档只作为交付物，不对 README／Markdown／说明文档或 SQL 文本做断言。真实迁移、锁互斥、事务回滚、性能及生产环境均保留为未验证限制，不列入本变更待办或完成前置条件，也不得据离线检查宣称生产可用。
