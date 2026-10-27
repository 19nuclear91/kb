# Design

## Context

动机见 proposal.md，行为以四份增量规格为准。依赖的审核、通知、评论需先实施并同步；报表读取源业务事实，而非通知 projection。当前只有 `aiusage` 查询与 `kb-web/src/admin/AiUsagePages.tsx`，已有 `AiUsageReadService` 使用只读 REPEATABLE_READ、固定安全投影、精确十进制字符串及覆盖元数据，可复用这些模式但不能借用 AI 的权限、维度或事实表。

已核对 `kb_document` 有 status、space_id、current_version_id，`kb_document_version` 有 file_name、mime_type、index_status 和固定版本身份；没有可靠的历史阅读、下载或搜索行为事实。V1 的 in_review 文档状态不代表新增版本审核轮次，indexed 也不是发布或审批证据。

## Goals / Non-Goals

**Goals:** 单请求内一致的全局固定汇总；库存与事件各自明确；缺失历史保留未知；模块间不通过他方 Mapper 或 HTTP 拼凑统计。

**Non-Goals:** 不建立数仓、物化历史库存、行为采集、个人／部门排行、自定义维度、导出或推送；不复制 AI 查询或把统计资格扩成资源读取资格。

## Decisions

### D1. 独立 reporting 读模型和权限

新增 `kb-app/reporting` 的 web/service/model/mapper，入口为 GET `/api/admin/reports/operations`。通过 auth/service 的 `AdminQueryAccess.require` 精确要求新增 `Permission.OPERATIONS_REPORTS_READ`；验证有效用户／主部门及改密状态，不按 ROLE_ADMIN code 放行。

reporting 自己的只读 Mapper 在明确的统计边界内查询文档库存及协作事实，SELECT 只投影固定桶、计数和时间，不返回完整实体或源 JSON。不得调用 document/review/comment/auth 的 Mapper，不跨服务请求 AI；为新模块扩展 ArchUnit 规则，controller 仍只能调用 service。此处全局元数据是专门授权的数据产品，不对操作者逐文档 ACL 求交，也不返回可钻取 ID。

替代方案：复用 audit:read/space:manage-all 会扩大既有资格；用逐资源 ACL 的个人面板则不是已确认的全局概览，均不采用。

### D2. 一个响应、一个已提交快照

服务方法使用 readOnly + REPEATABLE_READ，事务超时 6 秒，统计语句默认 5 秒；先在数据库取得固定 asOf 及读取快照，所有库存、窗口、UTC 桶和覆盖查询共用同一连接／事务，不用异步并行连接。授权也在本次请求内重新确认，无跨请求快照或内容缓存。

asOf 是本次读取的时间锚点，不是可任意回放的历史库存时间或“之后绝不会有迟到提交”的保证。事务开始时尚未提交的业务事实不进入本次响应，下一次查询可纳入相同 occurred_at 窗口的迟到提交。不使用 MAX(id) 高水位封存历史，不把序列分配当成提交顺序。

from/to 均缺省时用 asOf 推导最近 7×24 小时；显式输入必须成对、带偏移、精度统一至数据库微秒、跨度不超过 31×24 小时且终点不晚于 asOf。严格拒绝重复键、空值和其他参数，不借用 AI Query 中个人／部门／groupBy 的宽输入集合。

替代方案：多个面板独立请求成本低但会混合批准前后状态；分库／异步汇总需额外覆盖水位与刷新延迟，本期不引入。

### D3. 库存定义版本 OPERATIONS_V1

以下库存均为当前快照，与 from/to 无关：

| 字段 | 来源与口径 |
| --- | --- |
| documentCount | `kb_document.status <> 'deleted'`，每篇计一次；包括 draft、published 和归档内容 |
| archivedDocumentCount | 上述文档中空间 archived 或文档 status=archived 的并集，每篇一次 |
| versionCount | 上述未删除文档所辖全部版本，不限当前或已发布，不连接 chunks |
| formats | 对版本持久 file_name 的末尾扩展名不区分大小写映射 pdf/txt/md/doc/docx/xls/xlsx/ppt/pptx，其他或缺失归 UNKNOWN |
| parsingStates | 每版本 index_status 对应 pending/parsing/indexed/failed，未知归 UNKNOWN；不是审核或发布状态 |
| pendingReviewCount | 真实 review 表中的 IN_REVIEW，文档未删除未归档且空间未归档；不从文档 status=in_review 推断 |

格式仅表示入库原件的声明扩展名，不推断真实 MIME、解析内容或格式质量；不向响应、日志或遥测输出 file_name。固定桶即使计数为零也按同一枚举返回；解析分布与格式分布各自总和等于 versionCount。

文档先独立聚合，再关联版本聚合，不能用多版本／多分块 JOIN 后的 COUNT(*) 作为文档数。归档是库存的一部分而非当前可处理待审的一部分，页面分别说明。

### D4. 窗口事件与时长

统一读取审核和评论生产者同事务写入的不可变协作事实，按 occurred_at 在 `[from,to)` 内过滤，并按事件协议唯一业务键保证一事实一次：

- approvedCount / rejectedCount：仅 REVIEW_APPROVED / REVIEW_REJECTED，每审核轮次计一次；不计 VERSION_PUBLISHED、撤回或取消。
- rootCommentCount / replyCount：仅 COMMENT_CREATED / COMMENT_REPLIED；COMMENT_DELETED 不冲减创建量，不能对当前 body 非空行 count 代替。
- durationSampleCount / durationUnknownCount：已决定轮次中，submitted_at/decided_at 完整且非负者为有效样本，其余计未知；同一版本不同轮次独立。
- decisionElapsedMsTotal：对每个有效自然耗时先 floor 至毫秒，再使用 numeric 累加；meanDecisionMs = total / sampleCount，ROUND_HALF_UP 保留两位小数，无有效样本为 null。未知样本不当零耗时。

历史事件不再连接当前文档状态来筛掉已删除或搬迁内容；它们记录过去确实提交的决定与创建，不保留评论／意见正文。通知重复消费或仍未生成不影响统计。所有 totals 与日桶使用同一 kind／时间／覆盖谓词，避免两套定义漂移。

固定的关系、连接、筛选与聚合表达式由 reporting/model 的 `OperationsReportDefinition` 定义。MyBatis 只把该定义渲染为固定 SELECT，用户时间仍作为 JDBC 参数绑定；生产继续在数据库端聚合，不把原始实体或事件读入 Java。独立单测对同一表达式运行原始内存表与协作事实，验证删除／归档、轮次去重和逐样本时长口径，随后把结果交给真实读取服务验证完整响应。内存求值不进入线上读取路径，也不作为 PostgreSQL 执行语义或性能证明。

替代方案：按当前 review 状态或剩余评论行数推算会遗失重新送审、删除及撤权前事实；通知条数又会被收件人数和已读行为放大，均不采用。

### D5. 覆盖和日桶中的 null 与零

读取前置 `kb_collaboration_coverage` 的 REVIEW、COMMENT 各自起点。首期生产者无可关闭采集开关，状态／事实同事务，且最小事实不自动清理，因此每个已开始能力从起点到 asOf 为连续覆盖；源系统升级／恢复若破坏该前提，必须先恢复可靠覆盖元数据，否则报表安全失败，不能默认完整。

对每能力和每窗口（含日桶）计算覆盖交集：

- COMPLETE：交集覆盖全部请求区间，真实无事件返回精确字符串 "0"。
- PARTIAL：交集非空但未覆盖全部，返回已观察部分的值，并返回 effectiveFrom/effectiveTo 明示该值不是完整窗口总量。
- NOT_STARTED：没有覆盖记录，或窗口全部早于 coverageStartedAt，指标为 null，effectiveFrom/effectiveTo 为 null。

coverageStartedAt 原样回显；不能从最早事件、旧 indexed 或 OBSERVED_CURRENT 推断更早起点。数据库表缺失、查询故障或互相矛盾的覆盖事实为 503，不伪装 NOT_STARTED。

UTC 日桶以窗口起点所在 UTC 日起始，首尾截取窗口边界；最多 32 桶（最长 31 天可跨 32 个日历日）。每桶有 day、from/to、partialDay，review/comments 各带自己的覆盖与计数。覆盖内缺少事件可补零，覆盖外保持 null；库存不做历史日线。

### D6. 固定响应与精确数据

R.data 的固定一级字段为 definitionVersion、asOf、from、to、dayTimeZone、inventory、review、comments、days。inventory 为独立当前值及固定分布；review 含 pendingReviewCount、窗口决定／时长及 coverage；comments 含两类创建量及 coverage；days 只含有界安全日桶。

所有整数计数、样本数和总毫秒均用 BigInteger/BigDecimal 精确校验后输出规范十进制字符串；平均值使用两位小数字符串。NOT_STARTED 的窗口字段为 null，库存和当前待审不因窗口历史未知而改为 null。禁止把 Mapper map 原样序列化：用明确 DTO 或字段白名单丢弃意外 ID、名称及载荷。

若任何必需统计失败，整个请求 503，不返回半套面板或读取旧缓存。成功／错误和网关提前拒绝均 no-store；不为读取新增业务成功事件或身份审计，不记录窗口内容、聚合值及原始异常，只允许低基数耗时／固定失败阶段指标。

### D7. 前端复用生命周期而不复制 AI

在 `kb-web/src/admin` 增加 OperationsReportsPage、API 类型和独立查询 hook，沿用 `useAdminAccess` 的当前 permissionFactsKey 与 Session epoch；不依赖 spaces 的成功与否。固定路由 `/admin/reports/operations` 和安全返回白名单只接受无筛选页面路径。

页面按库存、审核、评论及趋势分区，展示 definitionVersion、UTC 窗口／asOf、覆盖说明和自然时长样本。根／回复创建量明确不是剩余评论数。计数不转 number，趋势优先用精确字符串表格或无需失真数值的展示，不引入图表库；不绘制未知点或按零计算增减。

默认进入读一次，显式查询／刷新才再读，不自动后台轮询。客户端仅验证时间格式、成对关系和 31 天跨度，未来终点由后端固定的数据库 asOf 判断，不能用浏览器机器时钟额外拒绝合法窗口。隐藏、切换或资格变化清理统计与筛选，恢复先核对权限再按默认 7 天读取；请求序号阻止旧条件覆盖新条件。只有另有 ai:usage:read 才显示到既有 `/admin/ai/usage` 的链接，不预取或嵌入 AI 数据，不改原 AI 导航和权限。

### D8. 查询成本与部署边界

事件按 kind/occurred_at、审核按活动状态与文档归属编写必要索引迁移；库存重用 doc/status、version/doc_id 索引，不为报表复制正文或维护额外行为账本。实施时依据前置迁移定义仅编写缺失索引脚本，不实际执行迁移、执行计划分析或负载测试，也不对 SQL 文本做测试断言。无连接 Mapper 和内存单测仅覆盖查询参数、映射及计算逻辑，不能作为实际性能证据。

若真实规模超出短事务上限，先安全 503 并评估索引／查询优化；物化报表涉及新的新鲜度和覆盖契约，必须另行规划，不可静默改口径或省略字段。网关沿用 `/api/admin/**`，只需检查既有 no-store 提前拒绝覆盖新路径。

## Risks / Trade-offs

- [全局汇总揭示未发布库存的数量] -> 明确只授予独立运营角色，不向普通文档读者开放；不提供细粒度资源筛选或明细。
- [上线前事件未知] -> 分能力起点和部分覆盖，null 与真零分开，不用旧状态补历史。
- [长事务或全库库存扫描成本] -> 限窗、索引、短只读事务和安全超时，不降级成不一致旧数；真实执行计划及负载保留为未验证限制，本变更不安排性能验收。
- [序列高水位和晚提交] -> 同次快照保证内部一致，但不承诺历史查询结果永久封存，刷新可纳入迟到提交。
- [新权限使旧实例无法启动] -> 排空旧 app 后迁移和部署，维持未知 code 拒绝启动，不删用户绑定来掩盖回退风险。

## Migration Plan

1. 确认前三个变更实施、验证并同步，审核／评论事实与覆盖可读；本变更不制造或回填业务历史。
2. 排空旧 app，追加报表权限及必要索引迁移，仅一次性向当时 ROLE_ADMIN 绑定新权限；正式空库引导新角色用完整目录，既有撤权不恢复。
3. 部署顺序为只读查询与固定投影先于前端入口，原 AI 页面独立运行，不依赖报表可用性。本条只说明部署边界；任务通过 Mock 查询结果的独立单测覆盖库存／日桶／覆盖／时长及权限、超时分支，不安排真实环境联调。
4. 回退先撤下报表入口或禁用新读取部署，保留权限绑定、事实及索引；不能直接换回不认识新 code 的旧 app，二进制回退须维护窗口和经确认的兼容版本，不清除业务历史。

本变更验证仅限独立内存单测、无连接映射、权限与架构检查、前端组件／状态单测、类型检查及静态构建；数据库时钟、查询、权限和网关下游使用 Mock／stub。不得启动完整应用或网关，不运行集成测试、跨服务联调、浏览器端到端验收、真实执行计划或性能测试，不启动或连接数据库、Redis、对象存储、消息中间件或第三方 API，不使用 Testcontainers／内嵌服务替代。迁移任务仅编写脚本，不实际执行；说明文档作为交付物，不对 README／Markdown／文案或 SQL 文本做断言。真实迁移、快照隔离、事务一致性、负载和生产环境保留为未验证限制，不列入本变更待办或完成前置条件，也不得据离线检查宣称已通过真实环境验证。
