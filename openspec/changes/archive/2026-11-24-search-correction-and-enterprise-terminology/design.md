# Design

## Context

动机和首期范围见 [proposal.md](proposal.md)，行为以本变更的 [10 份增量规格](specs/) 为准。本设计涉及新数据模型、搜索执行预算、权限与跨版本发布，适用设计产物。

现有实现提供以下接入基础：

- `SearchService` 为浏览器和 AI 共用入口，已在同一次 `SearchExecutor` 执行中完成授权、控制状态、Embedding、召回、rerank 和响应编码。其原始查询文本目前直接传入三个检索阶段。
- `QueryRetrievalService` 在短事务中重核 Embedding profile／epoch；`SearchMapper.xml` 使用参数化 `c.content ||| queryText`、向量召回和 SQL 内 RRF。每路候选截取前共用权限与组合筛选片段。
- `SearchTransaction.executeSnapshot` 已提供受当前搜索截止约束的只读 REPEATABLE READ，适合读取一致词典；`SearchResponseEncoder.encodeValue` 可对完整对象做有界编码。
- `TagService`／`MetadataCommands` 有独立读写资格、锁后重核、完整意图摘要和原子回执的参考模式。但其业务域和表结构绑定文档资料，词典采用同样的模式建立独立业务实现。
- `EpochAuthenticationInterceptor` 将本次核验的 `EpochSessionService.Session` 放入请求属性；持久 authEpoch 用于撤销，正常重新登录会发出新令牌，但不一定推进 authEpoch。
- 网关已路由 `/api/search/**`，其 no-store 过滤器也覆盖该前缀；`kb-common` 的 `SearchEndpoints` 仍只精确识别原搜索和两个候选路径。当前与搜索协议有关的 CORS 暴露头只有筛选版本头。
- 前端 `SearchWorkspace` 已区分草稿与已提交查询，并以会话、AbortController 和请求代次隔离响应；管理路由采用资格通过后加载页面模块的模式。

当前数据库迁移最高为 V41。按本次确认的验证范围，实施只执行独立离线单测、静态检查及构建；真实数据库、网络、浏览器与质量行为均保留为未验证，不作为本变更的测试任务或完成前置条件。

## Goals / Non-Goals

**Goals:**

- 将一个不可变的查询计划同时用于实际执行和辅助说明，使同义扩展、确认替换及原词恢复可以核对。
- 词典读取具有一致修订，修改不需要重建文档索引；搜索执行不会长期持有词典写锁。
- 原搜索、增强搜索和候选查询共用准入、取消及响应边界，词典故障不会成为原词模式的依赖。
- 复用现有会话、权限和前端操作模式，将新增实现集中在搜索业务内。

**Non-Goals:**

- 不引入新的搜索服务、消息队列、分布式词典缓存或外部纠错模型。
- 不改变既有 BM25 分词器、RRF 算法、向量存储维度和资源授权模型；首期不增加逐别名完整检索或学习排序。
- 不把建议凭据解释成人工操作证明或资源授权，也不存储用户搜索历史作为凭据账本。

## Decisions

### D1. 独立协议，共用一次搜索执行

新增 `POST /api/search/assisted` 及独立严格输入类型，旧 `/api/search` 继续只解码原字段并返回数组。抽取可共享的基础搜索字段解码规则，但两种请求分别维护字段白名单，不能让旧入口因复用反序列化器而接受 mode、selectionContext 或 selections。

增强入口从本次认证属性取得 userId、authEpoch。仅 ASSISTED 把当前已核验令牌作为本请求内的短暂绑定输入交给执行器，在 TERMINOLOGY 阶段生成用途隔离的会话指纹后即释放原令牌引用；ORIGINAL 不生成词典凭据或读取其密钥。工作线程不依赖 Servlet／Sa-Token 的隐式线程上下文，可信身份对象不提供包含令牌的自动字符串投影。新增方法在 `SearchService` 内与原入口复用授权及检索阶段，避免外围先调用一次搜索、再为辅助信息重新领取容量。

```text
POST /api/search/assisted
          |
          v
decode + verified caller
          |
          v
SearchExecutor -> authorization
          |
          +--> ASSISTED -> dictionary snapshot -> QueryPlan
          |
          +--> ORIGINAL -----------------------> QueryPlan
                                                    |
                                                    v
                                control -> embedding -> retrieval -> rerank
                                                    |
                                                    v
                                bounded { hits, assistance } response
```

原搜索使用无词典的恒等计划并继续编码原数组。ORIGINAL 使用同样的恒等计划，但编码增强响应。没有文档可见范围时，ASSISTED 仍取得公开词典计划和建议，然后以空 hits 完成，不读取检索控制状态或调用外部模型；appliedTerms 表达本次采纳的计划，不是外部调用或文档命中的证明。

相比在公共接口默认打开词典，这样可以明确保留 AI 和既有采集工具的查询行为；相比先取建议再搜，结果说明不会因第二次词典读取而漂移。

### D2. 五张业务表与统一写入顺序

规划 `V42__search_terminology.sql`，实际实施前复核迁移号是否仍空闲。全部由 app 迁移，词典为空时 dictionaryRevision=0。

| 表 | 主要字段与约束 |
| --- | --- |
| `kb_search_dictionary_state` | 单例 ID=1、非负 BIGINT revision；每次首次成功维护推进一次 |
| `kb_search_term` | 稳定 ID、canonical_name、canonical_key、description、enabled、version、时间；canonical_key 使用 C 排序规则并全局唯一，停用行仍参与唯一性 |
| `kb_search_term_alias` | term_id、alias_text、lookup_key、kind；主键 `(term_id, lookup_key)`，类型白名单，外键到术语；索引 `(lookup_key, term_id)` 支持反向查询 |
| `kb_search_correction` | 稳定 ID、wrong_expression、wrong_key、target_term_id、enabled、version、时间；wrong_key 全局唯一，target 外键为 RESTRICT，索引 target_term_id |
| `kb_search_term_command` | 词典域内唯一 request_id、操作、操作者、目标种类／ID、expected_version、摘要版本、规范化输入摘要、最小回执、时间；append-only |

名称长度、合法类型、版本非负及外键由数据库约束兜底；规范化和跨表冲突在统一写锁内核验。所有词典写路径必须依次：当前写资格检查 → 请求标识事务锁 → 读取既有回执绑定 → 单例修订行 `FOR UPDATE` → 实体读取／更新。首次写入在锁后清除请求内 Mapper 缓存并重核资格；匹配重放也先重核当前资格，再返回原回执，不比较原 expectedVersion 与已推进的当前实体版本。

同一事务内核验标准名称唯一、组内重复、错词与全部现存名称／别名冲突、目标启用状态，执行实体变化、修订推进、回执及审计。任一步失败整体回滚。所有维护都经过修订行，因此跨表冲突不会由两个并发创建同时穿过；唯一索引和外键继续保护各表内部关系。不得出现绕过该写服务的异步词典导入或其他写入口。

术语组标准名称和别名即使停用仍保留占用；改名或移除别名后，只释放已不存在的关系。组不物理删除。停用目标组不批量修改纠错行，纠错实际可用性为自身 enabled 与目标 enabled 的合取。

创建、编辑内容及重新启用纠错时要求目标启用。独立停用一个纠错不重新分配目标，因此即使目标已停用仍允许该停用动作。这使管理者能够保留“目标重新启用后，此映射仍关闭”的决定。

采用独立回执表，复用 `MetadataCommands` 的实现模式而非其文档专用操作域。request_id 接受与既有管理请求相同的 1–128 字符、无控制字符规则；词典域内跨操作重复同一标识也须比较绑定，不允许换操作规避冲突。别名在计算摘要前按比较键、类型及展示值确定排序，顺序变化不产生新意图；保留规范化后的展示大小写，改展示大小写仍可以构成实际修改。修订或实体版本溢出明确失败，不归零。

取舍：单例锁限制的是低频词典维护吞吐，读取不加此锁。采用它可使跨组关系和修订一致性清晰；按词键加多把锁更复杂，首期没有相应吞吐需求。数据库跨表一致性依赖统一写协议，本次内存替身只核对调用顺序和状态分支，不证明真实并发、约束或回滚。

### D3. 规范化保留原文坐标，不加载全量词典

词条入库先去首尾 Unicode 空白（统一使用 `isWhitespace || isSpaceChar`）、NFC，再按规格检查长度；控制字符按 Unicode Cc 拒绝，非法代理项单独拒绝。比较键只折叠 ASCII A–Z。所有键使用确定性比较，数据库不再调用 locale 相关的 lower；展示值与比较键分别保存。

查询原文始终保留。`NormalizedQuery` 使用 Java 21 的扩展字素簇匹配 `\X` 划分原文，在每个簇内做 NFC 与 ASCII 折叠，建立原始 code point 区间与规范化区间的映射。只允许从完整簇边界开始、结束匹配，组合字符、Hangul Jamo 及补充字符不能被截半。对不含组合的普通中文，每个汉字自然成为可匹配边界；ASCII 相邻字符的单词边界按规范化字符检查。前端消费服务端原文位置，不重实现匹配算法。

词典查询在一个 `SearchTransaction.executeSnapshot` 中完成：首次读取修订及数据库时间，之后所有批次、目标组和歧义核验使用同一 REPEATABLE READ 快照。它只覆盖词典计划准备，外部 Embedding 和 rerank 前即结束事务。

有界查询策略：

1. 按查询位置升序，每批处理至多 128 个规范化起点，枚举结束于合法边界、长度 1–64 个规范化 code point 的片段。每批至多 8192 个候选键，去重后以一个 PostgreSQL text[] 参数提交；不把全部输入的候选永久保存在内存。
2. 通过三个精确键索引探测当前有效术语名、别名或纠错。只返回输入键是否有有效关系，关系查询使用 EXISTS／有界探测，避免一个公共简称展开成无界组列表。批次包含起点后至多 64 个字符的完整前瞻，因此跨批次的长词也能正确匹配。
3. 依次作最左最长选择。选定片段覆盖的后续起点跳过；发现第九个不重叠匹配即返回辅助超限。要判定唯一性的选中键，另取不同启用 termId 的前 9 项：第九项只作为超限证据，不能对外截成 8 项候选。
4. 对最多 8 个选中组读取其标准名称和最多 20 个别名；对拟加入的别名按键查询至多 2 个不同启用组，可靠区分唯一和歧义。只有唯一别名进入扩展，目标标准名称按规格保留。

键、批次结果和别名使用请求内有界集合；批次间及循环内检查同一截止和取消信号。复杂度由输入长度和单词长度上限约束，仍可能因索引规模或网络延迟超时，超时必须为 503。首期不维护全量进程词典或跨请求结果缓存，避免修订正确但实际内容过期。

相比“查询开始时拉取所有启用词条”，精确键探测无需规定一个不断增长的全量堆内存上限；相比逐字符单独远程查库，批次控制数据库往返。具体执行计划与边界时延属于本次未验证范围，批次数上限不构成真实延迟承诺。

### D4. 单次生成不可变查询计划

`search.expansion` 中的规划器输出 `SearchQueryPlan`，包含 originalQuery、effectiveQuery、executionText、修订、原文匹配位置、已采用关系、待确认建议及已核验 selections。它只依赖 `search.term` 的只读快照结果，不依赖前端传入的词条正文。

处理顺序固定：严格输入及绑定核验 → 取得当前修订 → 最左最长匹配 → 完整核验累计选择 → 替换明确选择的位置 → 为唯一或已选择组生成合法扩展 → 全量规模检查。自动扩展和替换结果不重新参与匹配。按原文位置重建 effectiveQuery，未选中片段逐字保留；选择更长标准名称不会改变其他选择使用的原文坐标。

扩展按首次命中位置、组 ID、标准名称优先及别名比较键稳定排序。相同比较键只加入一次；已作为本次原文匹配或明确替换表达存在的键不再追加。重复位置保留其说明，共享同一组的表达清单，不倍增模型工作。共享别名只参与待确认含义选择，不沿关系跨组继续扩展。

首期采用 `literal-append-v1`：executionText 为 effectiveQuery，加上去重补充表达，每项以一个换行分隔；没有补充表达时完全等于 effectiveQuery。不加入“同义词”“请搜索”等模板词，也不生成 SQL 查询语言。新增表达及换行共同计入 2048 code point 预算，表达最多 32 个，最终文本还须通过原 query 长度限额。

三个阶段都使用 executionText：

| 阶段 | 接入方式 |
| --- | --- |
| BM25 | 继续参数化 `|||`，传 executionText，保持任一分词命中的既有语义及全部 SQL 权限片段 |
| Embedding | 一个 QUERY 向量请求使用 executionText，沿用 profile 身份、准备和召回前重核 |
| rerank | executionText 与同一批授权候选，沿用返回下标校验和失败回退 |

这使正式名称可以进入关键词召回，也避免后续重排只看到未知简称。它不承诺原词命中总在扩展命中之前，也不改变原有中文分词与短语匹配语义。仅标点组成或分词后无有效 token 的输入仍按现有检索规则处理，不虚构命中。

曾考虑独立原词／扩展多路召回、加权查询和仅改 BM25。前两者引入新的候选预算、SQL 与排序契约；后一种会使向量和重排看不到采用的关系。首期选择一次完整召回的统一文本方案，使用合成样例和传输替身核对表达生成与传递；真实召回收益、噪声和排序效果未验证。

### D5. 无状态建议上下文，绑定会话及修订

不复用现有仅作条件摘要的 `SearchOptionCursor` 为建议签发凭据。新增用途隔离的 HMAC-SHA-256 编解码器，由 app 的独立配置 `kb.search.assistance.context` 提供当前 keyId 和至少 32 UTF-8 字节的随机 secret；所有 app 使用一致配置，可保留一个前任验证密钥用于协调轮换。秘密不复用用户令牌、SSO、模型或内部认证密钥，也不进入 VITE 配置。

`selectionContext` 采用有界、严格编码的 `version.keyId.payload.mac`，整体不超过 4096 字符。payload 仅含用途、词典修订、签发／过期时间和带密钥的上下文摘要；不含 query、筛选或用户原始令牌。上下文摘要对长度前缀编码的以下内容计算 HMAC：

- 用户 ID、服务端 authEpoch 和当前会话令牌的用途隔离 HMAC 指纹；正常重新登录即使 authEpoch 相同也不能复用上下文。
- 原始 query 的精确 UTF-8 字节；绑定不使用规范化查询替代原文。
- 全部筛选的规范表示：空值与默认值统一、tagIds 排序、ID 精确编码、时间转换成同一 Instant 秒／纳秒表示，以及最终 candidates、topK。

凭据中不包含未经加密的普通 query 摘要，防止低熵查询被离线枚举。MAC 覆盖完整载荷，使用恒定时间比较；严格限制 keyId、载荷字段、整数和实际解码字节，不接受重复字段或尾随数据。验证时按凭据 keyId 取得仍受信密钥，并以该密钥重算会话指纹与上下文摘要，不能用当前签发密钥核对前任密钥的指纹。用途前缀隔离建议上下文与管理游标，相关对象的 `toString` 不输出内容。

初次词典快照同时取得数据库时间，签发期限为该时间后 10 分钟；采用请求用其新快照取得的数据库时间校验。先验证结构、MAC、身份和完整条件，再比较有效期与 revision，前者错误为 400，后者变化为 409 ASSISTANCE_STALE。当前身份门禁始终先执行。

selections 不由上下文证明“确实点击过”，而是本次明确提交的选择集合。服务端在同一修订下重新计算所有合法候选，验证种类、原文区间和 termId，然后一次性采用累计集合；不接受凭据替代合法候选检查。相同条件下多次采用属于多次有界读取，不需要一次性消费账本。

响应仍有未确认建议时签发新上下文；无建议则为 null。任一维护推进全局修订都会使旧上下文失效，包括修改后恢复原值。读快照之后的维护不会改写已生成计划；响应说明保留原修订，后续采用再按新事实核验。

密钥缺失或不可用时，将 ASSISTED 及依赖该密钥的管理分页标为安全 503，不使用实例随机密钥或静默无签名令牌；原 `/api/search` 与 ORIGINAL 不读取该密钥。启动可报告固定组件配置状态，不能因此把词典配置故障升级为原词搜索的运行依赖。轮换先让所有实例接受新旧密钥，再统一签发新密钥，待旧建议最长有效期和在途预算结束后移除旧密钥；旧管理游标可通过重新查询恢复。

相比在 Redis 保存完整查询与建议，这种绑定没有额外搜索历史或状态清理任务；代价是词典任何更新都会使建议整体失效，用户须重新搜索。

### D6. 事务快照与执行预算分工

增加必要阶段 `TERMINOLOGY`，其默认上限 1000 毫秒，进一步取当前工作剩余预算。该上限包含规范化、凭据核验、全部词典批次、CPU 匹配和计划生成；配置为正且不得超过总工作窗口。使用 `SearchExecutor.required` 与现有 SearchContext，不新增专用无界线程池，也不为每个批次重开预算。

词典快照由 `SearchTransaction.executeSnapshot` 承载，继续应用动态 statement_timeout、lock_timeout、连接及实际工作退出规则。事务只返回不可变计划所需数据，不将事务跨越外部调用。检索阶段随后使用原 `QueryRetrievalService` 的独立事务，不将词典快照当作资源权限快照。

默认规模采用规格数值：8 个匹配位置、每处 8 个含义、8 项选择、32 个新增表达、2048 个新增 code point。v1 实施先固定这些协议限额；候选生成批次和阶段时限属于内部运行参数。发现超限即在首次模型／召回前返回 ASSISTANCE_LIMIT_EXCEEDED，不截断成“唯一候选”。

搜索准入先于词典工作。取消后结果不可交付，词典 JDBC／CPU 工作实际结束后才释放名额。ORIGINAL 构造恒等计划，不调用词典 Reader、凭据服务或新表。公开词典为空是正常 revision=0；读取失败是 503，不回退成功空集合。

既有 `SearchMetrics.Operation.SEARCH` 同时覆盖两种搜索，保证原聚合告警仍统计全部搜索；新增低基数辅助结果计数，仅区分 mode 与固定结果类别，`TERMINOLOGY` 加入阶段耗时。不能简单增加一个 Operation 后误落入当前 options 指标分支。词条 ID、修订、输入摘要和凭据不作标签。

### D7. API 投影与错误处理

成功增强响应继续使用 R 包装，data 为 `{ hits, assistance }`。hits 复用固定版本分块投影，新响应中的 ID 和版本明确编码为十进制字符串，不修改旧接口的序列化约定。

| assistance 字段 | 具体投影 |
| --- | --- |
| mode、originalQuery、effectiveQuery | 本次模式、最初原文及明确选择后的文本 |
| dictionaryRevision | ASSISTED 的精确修订字符串；ORIGINAL 为 null |
| appliedTerms | 每个已采纳匹配的 start、end、matchedText、termId、termVersion、canonicalName、实际 expressions；表达清单与 executionText 同源 |
| suggestions | kind、start、end、sourceText、options；选项含 termId、termVersion、canonicalName、description，CORRECTION 只有一个选项 |
| selections | 已核验并按原文位置排序的累计 `{ kind, start, end, termId }` |
| selectionContext | 尚有可采用建议时的凭据，否则 null |

ORIGINAL 的 assistance 中 appliedTerms、suggestions、selections 为空数组，修订和上下文为 null；hits 仍是原词检索的实际结果。表达去重的最终清单是 appliedTerms 各项 expressions 按稳定次序的并集；matchedText 和规范关系可在多处出现，但不会重复加入 executionText。空 hits 不触发补搜或丢弃公开建议。

新入口成功必须同时带两个版本头；在 RESPONSE 阶段编码整个对象并核对默认 4 MiB 上限。前端只接受符合请求模式、原文、位置、数量和必需字段的完整对象。校验失败整体丢弃，不能单独保留无法核对的 hits。

错误沿用项目已有 `code/message/data/reasonCode` 形式：HTTP 状态表达分类，reasonCode 来自固定枚举，data=null。至少区分 INVALID_INPUT、ASSISTANCE_LIMIT_EXCEEDED、ASSISTANCE_STALE、ASSISTANCE_UNAVAILABLE、SEARCH_UNAVAILABLE 与既有认证／容量类别。前端映射固定码，不展示远程 message；未知程序错误保留 500。

增加增强请求的有界 body advice／严格解码，复用基础字段规则而非扩大旧字段集。`SearchFailure` 增加辅助类别及状态映射，相关 executor、指标和异常投影一并覆盖。全局异常处理只在新入口返回新 reasonCode，旧调用的成功及失败契约保持兼容。

### D8. 管理接口与分页

全部管理路径位于 `/api/admin/search-terms`，路径只含固定段和稳定数字 ID，名称查询通过 POST 正文发送。写操作要求 X-Request-Id，更新或启停要求 expectedVersion，实际正文上限 32 KiB。

| 方法及相对路径 | 输入与行为 |
| --- | --- |
| POST `/terms/query` | nameQuery、enabled、limit、cursor；名称匹配标准名称及别名，按 termId 分页 |
| GET `/terms/{id}` | 完整当前组、最多 20 个别名及修订 |
| POST `/terms` | canonicalName、description、aliases；初始启用 |
| PUT `/terms/{id}` | 完整名称／释义／别名与 expectedVersion，启用状态不作为编辑副作用 |
| PATCH `/terms/{id}/enabled` | enabled、expectedVersion |
| POST `/terms/{id}/meanings/query` | expression、limit、cursor；先确认表达属于该组，再分页列出当前共享该表达的启用组及释义 |
| POST `/corrections/query` | nameQuery 匹配错误表达，enabled、limit、cursor；返回自身 enabled 与目标状态分别表达的可用性 |
| GET `/corrections/{id}` | 当前映射、目标组最小资料及修订 |
| POST `/corrections` | wrongExpression、termId；初始启用，目标须启用 |
| PUT `/corrections/{id}` | wrongExpression、termId、expectedVersion；不隐式改动自身 enabled |
| PATCH `/corrections/{id}/enabled` | enabled、expectedVersion；独立停用允许保留已停用目标 |
| POST `/commands/query` | requestId；当前写资格者只读核对本人原命令回执，未取得回执的 404 不证明原操作失败 |

管理读取使用短 REPEATABLE READ，目录行与 revision 同源；分页为 ID 升序、limit+1。标准目录每页可以返回每个表达的 ambiguous 标志，通过一次有界关联查询计算；详细含义通过 meanings/query 按需读取，不把一个名称关联的所有组嵌入列表。该查询是词典维护范围内的辅助视图，不读取文档或搜索历史。

管理游标使用 D5 的独立用途签名，载荷为目录用途、条件 HMAC、revision、最后 ID，绑定用户、必要目标与规范化筛选，至多 4096 字符。查询条件保留明确的 enabled 缺省含义，nameQuery 的百分号和下划线按字面转义。合法上下文修订变化为 409 DICTIONARY_CHANGED，伪造或换条件为 400；每次都先核对当前读取资格。

最小写回执只包含实体种类及 ID、首次实体版本、首次 dictionaryRevision、replayed。回执核对须先核对当前写资格与本人归属，不能从查询接口获得其他操作者的回执。NAME_CONFLICT、EXPRESSION_CONFLICT、TARGET_UNAVAILABLE、VERSION_CONFLICT、REQUEST_MISMATCH、NO_CHANGE 分别映射固定冲突提示。

空名称查询可能扫描较多目录行，limit 不是扫描成本保证。管理读取和写入使用明确的短事务／锁等待上限（初值 5 秒），失败返回安全 503；不引入新的全文或 trigram 扩展来优化首期管理目录。

### D9. 前端状态与复用范围

搜索页用单一 `SubmittedSearch` 保存 originalQuery、完整 filters、数量、模式及累计 selections；input draft 与它独立。提交新表单时建立新搜索链并清空选择，点击建议则从 SubmittedSearch 构造请求，与当前草稿无关。成功结果同时保存 hits 与 assistance，只允许当前会话及 generation 更新它们。

“仅搜原词”使用最初输入和已提交筛选，清空选择及凭据；从结果区重新启用辅助使用同一已提交输入，新表单提交则使用新草稿。等待期间禁用重复采用；切换模式等新主动操作递增 generation 并中止旧等待。建议过期时清除旧凭据和待采用项，保留当前页面有效输入，重新搜索从空选择开始。

辅助结果单独使用展示组件，词条文字经 SafeText，原文片段用 `Array.from` 对 code point 区间切片；页面不做模糊纠错或重算词典。分类、标签选择器、引用抽屉、收藏和适用期仍基于当前 hits 工作，新搜索统一清理旧引用及批量结果。知识需求反馈继续预填用户原始输入，不能把自动扩展词和词典释义自动当成用户主动分享的描述。

管理页沿用 `ResourceWriteDialog`／`useAdminWrite` 的意图和未知结果处理，新增词典专用输入及响应解码。词典页自建正确区分 draft/submitted 的列表状态，避免把参考标签页中的即时状态筛选方式直接复制。维护成功提示与随后列表刷新故障分别保存；重读当前值不能结清未确认回执。

路由、lazyPages、管理权限常量、导航及 navigation.ts 登录返回白名单增加固定 `/admin/search-terms`。页面按 read 加载，write 控制动作；新增模块不由搜索页静态引入。恢复和权限收缩复用现有 PageRoute／会话生命周期，并测试迟到模块与迟到请求均不能恢复旧数据。

### D10. 权限、审计与响应保密接入

新增两个 Permission code，按功能权限规格完成一次性管理员绑定与引导目录更新。词典访问使用 `AdminQueryAccess`／当前 principal，先鉴权再查询目标；锁后重核要明确清除 Mapper 一级缓存，不能把同一事务中的旧权限读值当作新核验。回执绑定独立于权限，匹配重试同样核对。

在 `AuditScope` 和 `AuditSummaryProjector` 注册四个操作域及两种目标类型，既有全局筛选支持对应精确查询。保留未知但合法操作域／目标类型的查询兼容，白名单仅约束安全摘要投影，不新增输入拒绝规则。审计只保存／投影规格允许的固定字段和版本变化，完整词条输入摘要保存在命令账本而不进入审计响应。审计前端补充新操作与安全字段展示，沿用已有 audit:read，不增加另一种审计页面。

将 `/api/search/assisted` 加入 `SearchEndpoints`；新增管理路径的共用识别范围，覆盖 app 异常处理、输入拒绝、追踪日志和网关提前拒绝。搜索保密处理不能只绑定新 Controller，因为路由匹配之前、认证及解析失败也可能产生响应。管理路径采用固定前缀边界匹配，不能误匹配 `search-terms-other`。

网关 dev/prod 的既有搜索路由可覆盖新入口，管理路径沿用业务路由；补充 `X-Search-Assistance-Version` 暴露头与管理 no-store 策略，并核对错误不会落到静态页面。app 与 gateway 全路径保证成功和拒绝的 no-store，日志只记录固定阶段及类别，不打印异常链或 DTO 内容。

### D11. 独立离线验证与证据边界

实施以规格场景为依据，只编写和运行本地独立单测、无连接映射、模拟 DOM、静态检查、类型检查及编译构建。服务、身份、仓储、HTTP 和模型依赖均以 Mock／Stub／内存实现替代，不组装完整应用或跨模块运行链路。

| 层次 | 离线证据 |
| --- | --- |
| 纯规则 | NFC／字素簇坐标、ASCII 边界、最左最长、歧义不传播、错词单向、累计选择、原词恢复、全部规模边界 |
| 凭据 | 可控时钟与身份替身下的会话绑定、MAC 篡改、到期、修订改变，以及进程内两个编解码器对象同密钥互验、轮换与用途隔离 |
| 服务及事务替身 | 先鉴权、锁后重核调用、相同／不同载荷重放、冲突判定、目标停用、失败传播和事务代理调用顺序，不证明真实锁和原子回滚 |
| 搜索编排 | 独立被测服务配合下游替身，核对一次准入、预算、迟到结果、无可见范围、原词绕过词典、表达传递与完整筛选参数 |
| 接口与前端 | 模拟请求／响应下的控制器、过滤器和异常投影单测；模拟 DOM 下的草稿、建议、原词恢复、身份隔离与未知回执交互 |
| 静态与构建 | 编译、类型检查、生产资产构建、包体依赖隔离、架构规则和 OpenSpec 结构校验 |

不编写或执行集成、前后端联调、跨服务测试、端到端或真实浏览器验收；不启动或连接数据库、Redis、对象存储、容器、前后端服务及第三方 API，不使用 Testcontainers、嵌入式服务或独立 HTTP 测试服务器替代。回归采用显式离线测试白名单，先核对依赖与测试内容，不运行可能混入这些测试的无差别全量命令。

说明文档及迁移脚本作为交付物人工审阅，迁移不实际执行。不编写或执行针对 README、Markdown、规格、注释、文案、SQL 文本的内容断言、快照或存在性测试；参数绑定、业务 JSON、结果投影和构建模块依赖属于功能行为验证。

新旧库迁移、约束、跨表并发、快照隔离、事务回滚、执行计划、真实网络终止、多实例运行、浏览器和业务排序效果统一记为未验证。原真实环境任务已从任务清单移除，不以可选任务、隐藏待办、核验或归档前置条件恢复；离线通过不能替代这些运行事实。固定合成词典仅用于确定性单测，不要求提供真实业务样例或宣称已通过质量评测。

## Risks / Trade-offs

- **扩展噪声与稀释原意** → 仅人工等价关系、排除共享别名、限制数量并提供原词恢复；本次仅核对表达生成与传递，真实质量未验证。
- **长查询的词典探测成本** → 精确索引、固定批次和同一阶段截止；只返回少量行不证明真实扫描成本，执行计划和实际时延未验证。
- **Unicode 坐标实现错误** → 后端保留规范化前后的字素边界映射，前端只消费原文 code point 区间，覆盖重排组合符和多 code point 字素样例。
- **全局修订导致无关修改也使建议过期** → 首期接受保守失效以保证解释一致，明确提示并由用户重新搜索；局部版本依赖图属于后续优化。
- **词典写入串行化** → 仅限人工维护短事务，搜索读取无写锁；观察锁等待并区分超时，避免把模型调用或无界目录扫描放入写事务。
- **多实例密钥配置不一致** → 协调配置及分阶段轮换，拒绝不可验证上下文，原词检索保持独立；不回退无签名凭据。
- **参考组件的语义不完全相同** → 复用已存在的会话和操作机制，词典独立实现读写协议、游标及提交条件快照，相关复用点必须有行为回归。
- **离线证据不足以证明真实锁、取消和质量** → 按实际执行范围记录证据，明确上述行为未验证，不增加真实环境测试任务来补足本次完成条件。

## Migration Plan

以下为交付文档中的发布与回退方案，不是本次要执行的迁移、联调或部署任务。

1. 实施前确认最新迁移号，新增词典五表、索引、约束、不可变回执保护及两项权限初始化；初始词典为空，不从文档或用户查询自动回填。
2. 准备所有 app 一致的独立上下文密钥配置和阶段预算。先排空不识别新权限 code 的旧 app，由受控 app 实例完成迁移，再升级全部 app；新绑定存在时旧 app 不混部。
3. 发布 gateway 的响应保护与版本头配置，再发布前端。公开词条由有资格的维护者逐项建立；默认空词典的 ASSISTED 行为与无词典匹配一致。
4. 本次交付只记录 D11 的离线验证结果和真实环境未验证边界，不据此宣称部署已验收。词典变更不触发 Worker 重解析、索引重建或 AI 服务配置变更。
5. 止损优先回退前端至仍调用原 `/api/search` 的已知版本，保留能够识别新权限的新 app、词典数据和回执；必要时使用兼容目录的新修复版本。不得删权限绑定、修改既有迁移或降级到不认识新目录的旧 app 以伪装回退。已显示给用户的词条不承诺可撤回。

## Open Questions

- 真实业务词条、部署数据规模和目标环境的排序效果尚不明确，本次只使用合成数据完成离线单测，不要求提供或准备真实样例。
- 128 起点批次的真实时延和 SQL 执行计划未知，作为交付限制记录，不安排环境测量任务，也不影响本变更按既定离线范围完成。
