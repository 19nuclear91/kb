# 检索限界与降级

## 输入和 HTTP 契约

`POST /api/search` 使用 JSON 请求体和当前 Sa-Token 会话。query 必须非空白、无 NUL 或孤立代理项，默认最多 8000 个 Unicode code point；补充字符算一个字符，不静默截断。请求体按实际读取字节限制为 128 KiB，未声明或错误声明 Content-Length 也受约束。

candidates 和 topK 各为 1–200。candidates 默认 50，topK 默认等于 candidates。topK 大于 candidates 合法，结果数量仍不超过实际候选数。身份、权限范围和向量配置由服务端决定。成功 JSON 继续使用 `R<List<ChunkHit>>`；来源仍绑定原文档、版本和分块。

组合筛选的字段和兼容变化见[知识搜索组合筛选](search-filtering-api.md)。空间、分类及后代、标签、当前发布文件类型和更新时间在每路候选截取前共同生效；分类关系和标签计算计入原召回阶段预算，不额外重置截止。Embedding/profile 降级保留同一筛选，rerank 回退使用本次已筛选候选，不以删除条件填满结果。

| HTTP 状态 | 含义 |
| --- | --- |
| 400 | 查询字符、数量或请求格式无效 |
| 401 / 403 | 现有会话与资格门禁拒绝 |
| 413 | 搜索请求体过大 |
| 429 | 本用户在该实例的实际在途搜索达到上限 |
| 503 | 实例满载、预算耗尽或核心依赖不可用 |
| 500 | 未归类的内部错误 |

容量拒绝包含正整数秒 Retry-After，默认 1。它是重试建议，不保证下次成功，不触发客户端自动重发。错误文案和日志使用固定类别，不包含查询、正文或上游错误体。

## 配置

app 通过 Spring 配置绑定以下键；未设置时采用表内默认值，显式非法值拒绝启动。

| 配置键 | 默认值 |
| --- | --- |
| `kb.search.execution.total-timeout-ms` | 8000 |
| `kb.search.execution.embedding-timeout-ms` | 2000 |
| `kb.search.execution.retrieval-timeout-ms` | 3000 |
| `kb.search.execution.rerank-timeout-ms` | 1500 |
| `kb.search.execution.response-reserve-ms` | 500 |
| `kb.search.execution.max-concurrent` | 8 |
| `kb.search.execution.max-concurrent-per-user` | 2 |
| `kb.search.execution.retry-after-seconds` | 1 |
| `kb.search.limits.max-query-codepoints` | 8000 |
| `kb.search.limits.max-request-bytes` | 131072 |
| `kb.search.limits.max-embedding-response-bytes` | 1048576 |
| `kb.search.limits.max-rerank-response-bytes` | 262144 |
| `kb.search.limits.max-rerank-request-bytes` | 2097152 |
| `kb.search.limits.max-response-bytes` | 4194304 |

所有值必须为可安全换算的正值；响应预留小于总预算，每个阶段上限不超过可执行总预算，用户并发上限不超过实例上限。部署初值不是已实测的吞吐或延迟承诺。

## 预算与实际容量

预算从合法输入进入搜索门面开始，包含权限解析、控制读取、连接/锁等待、外部调用、召回和 JSON 准备。上传请求、前置会话门禁与网络发送时间不计入这 8 秒。内部 List 入口也执行准入与响应编码限界。

设总截止为 D，工作截止为 W=D−500ms。权限与控制共享 W；Embedding 可分配 `min(2s, W−当前时间−3s)`，为召回保留 3 秒；召回使用 `min(3s, W−当前时间)`；rerank 使用 `min(1.5s, W−当前时间)`。可选阶段无正预算直接跳过，响应准备使用 D。每次返回和提交成功前复核期限，迟到成功不再消费。

每实例默认 8 个实际在途请求，每可信用户在该实例最多 2 个。原子检查用户限制优先；超限直接拒绝，不排队。主工作与外部调用各有固定容量的零队列执行器，取消工作走独立有界通道。多副本会放大整体容量，这些值不是集群级额度。

请求已响应但主工作、外部子工作或已调度取消动作尚未实际退出时，继续占用名额，计入 lingering。只有全部实际退出才归还，重复取消和关闭不会重复释放。Embedding 超时后 BM25 成功，也不能提前释放仍运行的 Embedding 子工作。用户计数归零即移除。

阶段结束时先关闭定时回调的有效状态，再取消调度句柄；即使已取出的回调延迟执行，也不能取消后续响应准备阶段。

客户端 AbortController 取消或离页不等于服务端工作退出。同步 MVC 等待期间，容器可能尚未检测到断连；线程中断被观察到时触发取消，无法检测时由总截止兜底。HTTP 已交付错误也不证明供应商计算停止。持续不退出的本地工作会占住容量，使后续请求快速拒绝。

## 数据库预算边界

搜索线程取得的 DataSource 连接经过局部包装，连接等待前后检查预算；Hikari 等待收到主工作中断，迟到连接立即归还。权限及控制语句同样登记取消，MyBatis 准备和 JDBC 实际执行前收紧 query timeout。小于一秒的正预算向上取整为一秒，绝对期限另由 watchdog 约束；取消后不能再开始 SQL 或接受迟到行。

召回通过每次新建的动态 TransactionTemplate 开启短事务，在该事务连接上用 `set_config(..., true)` 设置局部 `statement_timeout`、`lock_timeout`，再取得共享锁、复核 profile/epoch 并执行授权 SQL。设置不写入连接的会话默认值。实际 SQL 返回或回滚之后才关闭连接；Embedding 与 rerank 不在此事务内。非搜索线程仍使用原连接路径与应用超时。

搜索服务边界将连接拒绝等已知事务资源故障映射为 503；嵌套的搜索取消/截止原因优先保留，事务超时仍分类为 TIMEOUT。事务使用错误等未知程序故障继续走 500。

`SearchJdbcTest` 的连接/Statement 替身覆盖迟到连接关闭、禁止后续 SQL、取消登记、秒级收紧和非搜索路径；`SearchTransactionTest` 使用内存连接验证同连接设置及提交/回滚顺序；`QueryConsistencyTest` 使用事务、权限和 Mapper 替身验证锁内身份复核与核心故障传播。结论限定为进程内调用及生命周期行为，不表示已验证真实 PostgreSQL 锁或驱动取消效果。

## 降级与供应商响应

| 阶段与故障 | 处理 |
| --- | --- |
| 可靠解析的空权限范围 | 成功空结果 |
| 权限、控制读取、连接、共享锁或 SQL 故障 | 明确失败，不绕过权限或使用旧控制事实 |
| 无可用 QUERY profile、Embedding 预算不足或已知传输/协议失败 | 按本次授权范围召回 BM25 |
| rerank 预算不足、容量、载荷或已知响应失败 | 本次候选原排序 topK |
| 整体截止、取消、未知程序错误 | 结束请求，不按可选故障吞掉 |

查询通过纯 JDK `CallBudget` 显式传递剩余预算、响应上限和取消登记。原批量入口继续供 Worker 使用，调用预算不进入 profile 指纹。查询 transport 使用连接配置与当前剩余时间的较小值，读取和解析也受阶段截止约束，不只限制首字节。响应按实际字节上限加一探测：Embedding 1 MiB，rerank 256 KiB；错误 HTTP 响应不读取供应商正文。rerank 出站 JSON 编码最多 2 MiB，超过时不发送。

rerank `results` 必须恰好含 `min(topK, actualCandidates)` 条；`index` 为 JSON 整数，拒绝字符串、小数形式、负数、溢出、重复与越界；`relevance_score` 必须数值且有限。缺项或多项都整批回退。分数降序、同分按原下标升序，结果只映射原 `ChunkHit`，不信任远端正文和来源字段。重复 JSON 关键字段、多个根值和超过 64 层的结构均拒绝。接入供应商时需确认它严格返回请求的 `top_n` 条。

降级没有自动重试或额外本地重排。取消会中断本地发送/读取并登记响应流关闭；只有本地工作实际退出才释放搜索容量。这不能保证供应商停止已经接收的计算，也不代表它未计费。

## AI 检索错误契约

`kb.ai.retrieval.timeout-ms` 默认 10000，`max-response-bytes` 默认 4194304，独立于模型生成超时。`kb.ai.retrieve-top-k` 为 1–200，候选数为 `min(200, max(50, 4 × topK))`。

客户端只接受 HTTP 200、整数 `code=200`、数组 data。合法 `data=[]` 发出一个空列表；204、空 body、null、包装失败、缺字段、超限、解析损坏及非空全坏引用全部显式失败。混合引用保留合法项；旧单轮允许未知版本，多轮非空但全无可用版本身份则失败。不读取远程 message，不自动重试。

原始响应正文只订阅一次：200 响应在首个越限数据块处取消并释放已累计缓冲，非 200 响应立即取消而不请求正文，WebClient 自动清理不能再次读取上游。追问查询使用真实换行拼接历史主题，分隔符按一个 code point 计入 8000 上限；当前问题不截断。

单轮和多轮流发送固定 `SEARCH_UNAVAILABLE` 错误，不发成功 Done 或故障伪装的无结果正文。多轮先尝试 FAILED 及 NOT_INVOKED/零 token 结算；费用未确认不覆盖已保存 FAILED。持久终态无法确认时按 cleanup 上限结束流，并由租约恢复与前端状态核对收敛。匹配重放不会再次调用检索，新请求标识才按当前权限与序号重新处理；失败轮次不参与完整历史上下文。

## 网关与指标

prod/dev 的 `kb-search` 路由单独匹配 `/api/search`，默认等待 10000 毫秒，可用 `KB_SEARCH_RESPONSE_TIMEOUT_MS` 调整。AI SSE 路由保持 `response-timeout=-1`，由 AI 自身结束流。状态、正文和正整数 Retry-After 原样转发；搜索响应及提前拒绝保持 no-store，CORS 暴露 Retry-After。

| 指标 | 标签及含义 |
| --- | --- |
| `kb.search.requests` | `outcome=SUCCESS/EMPTY/FAILED/REJECTED/CANCELLED`；每次门面调用结束记录一次 |
| `kb.search.stage.duration` | 固定 stage 和 outcome；阶段响应等待耗时，失败原因来自固定枚举 |
| `kb.search.degradation` | 固定 stage、reason；成功降级仍是 SUCCESS 或 EMPTY |
| `kb.search.rejections` | `reason=USER_LIMIT/CAPACITY`，区分两层拒绝 |
| `kb.search.inflight` | 尚未完全退出的已准入请求 |
| `kb.search.lingering` | 响应已结束但仍有主工作、子工作或取消动作未退出的请求 |

搜索分类/标签候选共用 inflight 与 lingering，使用相同准入和阶段预算。其请求、拒绝和阶段耗时分别记录到 `kb.search.options.requests`、`kb.search.options.rejections`、`kb.search.options.stage.duration`，额外固定标签 `operation=CATEGORIES/TAGS`，不会把目录条数计为正文搜索命中。候选输入上限为 16 KiB，结果上限沿用 `kb.search.limits.max-response-bytes`。

HTTP 正文/输入校验在门面前完成；这一层的拒绝不计入门面请求计数。阶段耗时与实际资源占用是不同口径：可选阶段超时可先完成等待计时，实际工作仍体现在 inflight/lingering。重复取消、退出、关闭不增加请求计数或重复释放。指标没有用户、query、requestId、凭据或任意 profile 标签；日志只记录固定阶段、类别和状态，不附原始异常。

业务应用的上述指标从独立管理端口 `/actuator/prometheus` 导出；显式 dev 未配置管理端口时沿用业务端口。`kb.search.stage.duration` 与 `kb.search.options.stage.duration` 默认 SLO 边界为 1.5s、2s、3s、8s，可通过 `kb.telemetry.histograms.search` 覆盖，必须为递增正值。抓取协议与端口迁移见[遥测运维](telemetry-operations.md)。

AI 侧另以 `kb.ai.retrieval.failures{reason}` 记录失败：`REJECTED` 为请求或准入拒绝，`TIMEOUT` 为等待超时，`UNAVAILABLE` 为依赖/连接故障，`PROTOCOL` 为响应格式损坏。错误流使用固定 `SEARCH_UNAVAILABLE` 码，与正常零命中分开统计；这些标签不记录远程错误体或查询。

同一保密规则覆盖追踪跨度属性、指标标签和 exemplar。HTTP 观测只记录路由模板、方法、状态、结果及异常类名；客户端以固定目标类别记录，不记录完整 URL、查询串、请求头、正文、筛选值或候选名称。exemplar 只用于关联 trace，不携带问题或资源身份。导出前过滤 `http.url`、`url.full`、`http.request.header.*` 和 `net.*`；新增埋点仍须遵守上述白名单。Embedding 和 rerank 使用隔离客户端，默认路径与预算路径都不向供应商传播追踪头或 baggage。

搜索主工作 `search-*` 与可选阶段 `search-external-*` 在提交前捕获遥测白名单快照，仅包含 Observation、独立 span 和本地 requestId；执行期间恢复，退出时清理或恢复先前值，不复制其他 MDC、认证或事务上下文。降级日志因此可与入口 trace 关联。取消响应先交付时，仍执行的底层工作保持自己的作用域直至实际退出；准入、预算、容量与取消记账不变。追踪关闭时不创建跨度，requestId 仍在搜索日志中可用。

已有查询向量在共享锁内因 profile、epoch 或维护状态变化而失效时，记录一次 `kb.search.degradation{stage=RETRIEVAL,reason=PROFILE_UNAVAILABLE}`。合法向量路径和原本没有向量的 BM25 路径不会重复记录该降级。

## 发布与回退

1. 先发布识别可选 SEARCH_UNAVAILABLE 的 Web，以及独立检索预算和严格成功包装校验的新 AI；它们可以消费旧 app 的合法响应。
2. 再发布 app 的限界、准入和有界传输，并对齐网关搜索路由。AI/网关默认 10 秒、app 默认 8 秒；修改 app 总预算时同步调整外层等待，保留至少默认 2 秒余量。
3. 核对 app 实例数、8/2 容量、数据源连接容量和供应商 top_n 契约。每实例限制会随副本数放大；灰度期间旧实例不具备新保护。Worker 仍使用原批量预算，查询预算不改变向量指纹。
4. 观察 SUCCESS/EMPTY 与 FAILED、降级原因、拒绝及 lingering。持续 lingering 表示本地工作没有退出，应按依赖和实例状态处理；单纯增加等待不能释放实际资源。本变更不包含数据库迁移。
5. 回退优先恢复 app/网关配置与实现，新 AI 继续兼容旧 app。若 AI 也回退，检索故障会重新成为旧 NO_RESULTS 行为，须记录这一语义回退。保留已保存 FAILED、结算意图及历史；不重写历史或自动重发。Web 的可选错误码支持可以保留。

以上为交付的部署说明，本轮未执行发布。内存替身结果不证明真实数据库锁、JDBC 驱动取消、网络代理或供应商实际计算行为。

## 离线验证记录

验证仅使用显式单元测试名单、内存替身和静态构建。没有集成、端到端或针对文档内容的测试，不启动数据库、Redis、对象存储、模型服务或浏览器。

2026-10-31：`SearchPropertiesTest` 3 项通过；`SearchInputTest,SearchFailureTest,SearchTextOnlyControllerTest` 11 项通过，均无失败、错误或跳过。命令如下：

```powershell
mvn -o -pl kb-app -am test '-Dtest=SearchPropertiesTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
mvn -o -pl kb-app -am test '-Dtest=SearchInputTest,SearchFailureTest,SearchTextOnlyControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
```

执行器阶段：`SearchBudgetTest,SearchAdmissionTest` 4 项、`SearchExecutorTest` 4 项通过。门面阶段：`SearchFacadeTest,SearchServiceTest,QueryConsistencyTest,SearchInputTest,SearchTextOnlyControllerTest` 20 项通过；增加线程调度替身后单独执行 `SearchFacadeTest` 4 项通过。

数据库阶段：`SearchJdbcTest` 4 项、`SearchTransactionTest` 1 项通过；修复测试参数匹配器后，单独执行 `QueryConsistencyTest` 4 项通过。命令使用 `mvn -o -pl kb-app -am test`，`-Dtest` 明确列出上述类，并设置 `-Dsurefire.failIfNoSpecifiedTests=false`。

外部调用阶段：以下测试 common 11 项、app 36 项、worker 9 项全部通过；追加 `SearchFallbackTest` 10 项通过。跳过测试的编译通过。

```powershell
mvn -o -pl kb-app,kb-worker -am test '-Dtest=EmbeddingCallBudgetTest,EmbeddingProfileTest,RemoteProfileEmbeddingClientTest,SearchHttpTransportTest,RemoteRerankTest,SearchServiceTest,QueryConsistencyTest,SearchExecutorTest,EmbeddingProfilesPropertiesTest,EmbeddingBatchGeneratorTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
mvn -o -pl kb-app -am test '-Dtest=SearchFallbackTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
mvn -o -pl kb-common,kb-app,kb-worker -am compile '-DskipTests' '-Dstyle.color=never'
```

AI 阶段：`RetrievalPropertiesTest,RetrievalClientTest` 47 项通过；`ChatServiceLifecycleTest,ChatControllerTest,AiObservedChatTest,MultiTurnChatServiceTest,MultiTurnReplayControllerTest,ConversationSettlementTest` 最终 52 项通过；app 的 `TurnRetrievalFailureTest,ConversationServiceTest` 9 项通过。多轮结算确认改为延迟构造后，单独重跑 `MultiTurnChatServiceTest` 11 项通过。各命令均为 Maven 显式 `-Dtest` 名单，带 `-o` 和 `-Dsurefire.failIfNoSpecifiedTests=false`。

前端：在 `kb-web` 执行 `npx vitest run src/search.test.tsx src/stream.test.ts src/assistant.test.tsx src/lifecycle.test.tsx`，4 文件、46 项通过；`npm run typecheck` 与 `npm run build` 通过，构建有超过 1200 kB 的单包体积提示。

网关/指标：`SearchMetricsTest,SearchExecutorTest,SearchFallbackTest` 17 项通过；修正 Gateway Route 构建 API 后，`SearchRouteTest` 2 项通过。最后针对阶段校验和路由兼容调整运行 `SearchServiceTest,SearchFallbackTest,SearchFacadeTest,SearchTextOnlyControllerTest,ConversationRouteTest`，app 23 项、gateway 1 项通过。实际命令：

```powershell
mvn -o -pl kb-app,kb-gateway -am test '-Dtest=SearchMetricsTest,SearchExecutorTest,SearchFallbackTest,SearchRouteTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
mvn -o -pl kb-gateway -am test '-Dtest=SearchRouteTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
mvn -o -pl kb-app,kb-gateway -am test '-Dtest=SearchServiceTest,SearchFallbackTest,SearchFacadeTest,SearchTextOnlyControllerTest,ConversationRouteTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
```

### 核验问题修复记录（2026-10-31）

本轮指定 13 个单元测试类，共 110 项不同测试通过；分组执行的重复回归不重复累计。各组最终结果均为零失败、零错误、零跳过，Maven 同时完成受影响模块的编译。

| 修复/覆盖范围 | 测试类与最终结果 |
| --- | --- |
| 权限、控制、召回阶段的事务资源故障映射，取消/超时保留及程序错误传播 | `SearchFallbackTest,SearchTransactionTest,SearchFailureTest`：16 项 |
| 单条/两条历史、补充字符、真实换行与 8000 code point 边界 | `ConversationBudgetTest`：5 项 |
| 分段超限单次订阅、HTTP 错误零正文需求、精确字节边界、取消释放 pooled 缓冲 | `RetrievalClientTest`：49 项 |
| 锁内向量身份失效的固定降级指标、空权限成功响应与零下游调用 | `QueryConsistencyTest,SearchMetricsTest,SearchServiceTest`：10 项 |
| 完成/取消/截止交错、实际工作与取消动作占位、单次释放、失效定时回调 | `SearchExecutorRaceTest,SearchExecutorTest,SearchBudgetTest,SearchMetricsTest,SearchJdbcTest,SearchTransactionTest`：20 项 |
| 检索错误交付、FAILED 确认、结算过程与取消交错；单次收尾及 NOT_INVOKED/零 token | `MultiTurnChatServiceTest`：14 项 |

竞争测试首先复现了已结束阶段的定时回调误取消后续响应准备的问题，增加回调有效状态保护后通过。分段响应替身在 1024 字节上限、每块 256 字节时只读取 1280 字节（首个越限块），订阅和取消各一次，已分配 Netty 缓冲的引用计数全部归零。多轮测试分别控制错误交付、终态响应和结算完成信号，验证取消后已开始的收尾仍完成一次，且不封存 NO_RESULTS、不追加故障正文、不调度模型。

实际通过的命令：

```powershell
mvn -o -pl kb-app -am test '-Dtest=SearchFallbackTest,SearchTransactionTest,SearchFailureTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
mvn -o -pl kb-ai -am test '-Dtest=ConversationBudgetTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
mvn -o -pl kb-ai -am test '-Dtest=RetrievalClientTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
mvn -o -pl kb-app -am test '-Dtest=QueryConsistencyTest,SearchMetricsTest,SearchServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
mvn -o -pl kb-app -am test '-Dtest=SearchExecutorRaceTest,SearchExecutorTest,SearchBudgetTest,SearchMetricsTest,SearchJdbcTest,SearchTransactionTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
mvn -o -pl kb-ai -am test '-Dtest=MultiTurnChatServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
```
