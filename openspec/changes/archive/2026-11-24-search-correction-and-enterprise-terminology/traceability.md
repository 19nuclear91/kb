# 需求与命名场景的离线证据映射

2026-11-24，按本变更的 10 份增量规格人工核对实现、测试及交付说明。实际运行白名单、结果、版本和未验证范围见 [verification.md](verification.md)。同一行列出该需求的全部命名场景，证据可以由多个独立用例与代码审阅共同支撑，**不表示这些场景都运行过完整动态链路**。

证据类型：**直接**为纯规则／独立被测对象／模拟 DOM 行为；**组合**为多个独立被测对象的参数、结果与调用顺序加实现审阅，不是集成测试；**仅交付**为迁移、SQL 语义、发布方案或保持不变的既有链路人工审阅。所有真实数据库、网络、应用、多实例、浏览器与质量行为仍未验证；这些事实不转为额外完成／归档任务。文档及 SQL 无内容断言、快照或存在性测试。

首次核验的 W1～W3 已修复，补充 21 项正确行为回归，最新前端白名单为 30 文件／419 项；定位与关闭依据见 [verify-report.md 的当前状态](verify-report.md#修复后定向复核当前状态)。固定写意图需求额外覆盖旧回执不能结清新意图及同意图的旧核对代次；管理生命周期额外覆盖所有读取 403 的共享清理、有界重核、慢响应和重挂载；查询限额额外覆盖非默认上限快照、合法长 ORIGINAL／ASSISTED、扩展后边界和协议正文隔离。证据均为独立替身／模拟 DOM，不补称真实环境通过。

后端类名对应 `kb-app/src/test/java/com/zhishiku/kb/app/`，网关类对应 `kb-gateway/src/test/java/com/zhishiku/kb/gateway/`；前端路径相对 `kb-web/`。业务实现主要为 `search/term/`、`search/assistance/`、`search/expansion/`、`SearchService`、`SearchController` 及 `src/search/`、`src/searchTerms/`。

## 1. enterprise-terminology

| 需求 | 命名场景 | 证据及准确边界 |
| --- | --- | --- |
| 稳定身份和名称关系 | 创建带简称的术语；旧称不代表制度有效性 | 直接：`TermWriteServiceTest`、`TermInputTest`、`SyntheticQuerySamplesTest`。组合：`SearchCitationMappingTest` 与相邻前端固定版本／适用期回归，旧称仅影响检索表达，不修改版本发布与适用期；真实召回未验证。 |
| 统一规范化与有界校验 | 大小写及规范等价输入；超长和非法文本 | 直接：`TermInputTest`、`TermWriteServiceTest`；Unicode 空白、NFC、ASCII 折叠、组内去重、长度／数量／代理项与拒绝后无业务写入。 |
| 唯一名称与跨组歧义 | 简称有两个含义；标准名称与其他组别名碰撞；停用名称仍被占用 | 直接：`TermMatcherTest`、`SearchQueryPlanTest`、`TermWriteServiceTest`、`TermMeaningQueryTest`；仅启用组贡献歧义，跨组共享合法，停用标准名称仍占用。仅交付：V42 唯一索引与跨表检查 SQL；真实约束未验证。 |
| 单向错词映射 | 登记常见错词；错词与合法术语冲突；目标改名 | 直接：`CorrectionWriteServiceTest`、`TermWriteServiceTest`、`SearchQueryPlanTest`、`SearchSelectionContextLifetimeTest`；稳定目标、双向占用检查、建议使用当前名称、错词不反向加入扩展。 |
| 公司公开与文档权限分离 | 当前没有可见文档；没有管理读取资格 | 直接：`AssistedSearchServiceTest`、`TermAccessTest`、`TermPermissionTest`、`TermAdminControllerTest`；无文档范围仍有公开建议，hits 空且不调用模型，公开建议不授予管理资格。 |
| 启停约束扩展及采用 | 目标组停用再启用；在途搜索与停用竞争 | 直接：`CorrectionWriteServiceTest`、`TermMatcherTest`、`SearchSelectionContextLifetimeTest`。组合：同修订 `TermSnapshotReader` 与不可变计划、修订重核；真实并发快照效果未验证。 |
| 一致全局修订 | 两个组同时调整同一简称；写入事务失败 | 组合：`TermSnapshotReaderTest`、`TermDatabaseTest`、`TermWriteOutcomeTest`、`TermCommandTest` 核对同一快照回调、统一修订锁顺序、版本推进与异常传播。仅交付：REPEATABLE READ、事务提交和回滚 SQL／定义；真实并发、原子性与回滚未验证。 |
| 绑定意图和版本冲突 | 编辑与停用竞争；响应丢失后匹配重试；撤权后重放 | 直接：`TermCommandTest`、`TermWriteServiceTest`、`TermReceiptServiceTest`、`TermWriteOutcomeTest`；原载荷与 expectedVersion 绑定、陈旧版本、本人回执及锁后撤权。组合：事务替身只验证分支与调用，不证明真实锁竞争。 |
| 用途有界目录 | 名称包含通配符；分页期间修改词典；跨目录或身份复用游标 | 直接：`TermReadServiceTest`、`TermCursorTest`、`TermMappingTest`；字面查询参数、limit+1、用户／目录／条件／修订签名及精确 ID。仅交付：LIKE 转义及数据库查询语义人工审阅。 |
| 安全输入和失败 | 未声明长度的大正文；目录依赖不可用 | 直接：`TermInputTest`、`TermAdminControllerTest`、`SearchTermGlobalFailureTest`、`SearchTermResponseFilterTest`；读取至第 32769 字节即停、固定 413／503、data:null 与提前拒绝 no-store；未执行 HTTP 联调。 |

## 2. search-query-assistance

| 需求 | 命名场景 | 证据及准确边界 |
| --- | --- | --- |
| 显式增强且保留原查询 | 原查询包含简称；AI 使用既有搜索入口 | 直接：`AssistedSearchInputTest`、`AssistedSearchServiceTest`、`SyntheticQuerySamplesTest`；增强独立入口与对象，旧入口绕过词典且返回数组。仅交付：AI／采集工具仍调用旧端点的代码审阅和全模块编译，不进行真实 AI 调用。 |
| 字面匹配及原文坐标 | 英文缩写边界；Unicode 原始位置；未登记近似词 | 直接：`NormalizedQueryTest`、`TermMatcherTest`、`SyntheticQuerySamplesTest`，以及 `src/search/assistedApi.test.ts`、`src/components.test.ts`；OA 边界、完整字素簇、code point 坐标、相似未登记输入无猜测。 |
| 固定重叠优先级 | 长短术语重叠；相同片段有多个组；扩展包含另一术语 | 直接：`TermMatcherTest`、`SearchQueryPlanTest`；最左最长、全部启用组参与、生成文本不递归匹配。 |
| 唯一同义扩展不传播歧义 | 搜索简称与搜索正式名称；组内别名在其他组有歧义；同一组重复匹配 | 直接：`SearchQueryPlanTest`、`SearchQueryPlanBoundsTest`、`SyntheticQuerySamplesTest`；双向同义、排除共享别名、稳定去重和重复位置说明。 |
| 歧义等待明确选择 | 尚未选择 PM 的含义；选择其中一种含义 | 直接：`SearchQueryPlanTest`、`TermMatcherTest`、`AssistedSearchServiceTest`、`src/search/assistance-page.test.ts`；未选不扩展两组，选择只替换该位置且保留最初原文。 |
| 人工错词不自动／反向应用 | 错词已有搜索结果；同一查询既有简称又有错词；仅采用一个位置 | 直接：`SearchQueryPlanTest`、`SyntheticQuerySamplesTest`、`src/search/assistance-page.test.ts`；错词与 hits 独立，OA 自动扩展与报消待确认并存，多位置逐项选择。组合：实际 hits 通过共享检索替身取得，不验证生产错词命中质量。 |
| 条件及身份绑定 | 更改草稿筛选后点击旧建议；跨用户或认证代次采用；累积选择 | 直接：`SearchSelectionContextTest`、`SearchSelectionContextLifetimeTest`、`AssistedSearchServiceTest`、`SearchQueryPlanTest`、`src/search/submitted.test.ts`、`src/search/assistance-page.test.ts`；精确原文、当前令牌指纹、authEpoch、全部筛选与最终数量绑定；合法多位置累计核验。 |
| 过期须重新取得 | 目标已改名或停用；无关词条更新；读取后再次并发修改 | 直接：`SearchSelectionContextLifetimeTest`、`SearchQueryPlanTest`、`src/search/assistance-page.test.ts`；数据库时间 Stub、到期端点、全局修订变化／恢复原值仍失效、拒绝后无自动重发。组合：不可变 R 计划与短快照实现审阅；真实并发未验证。 |
| ORIGINAL 绕过词典 | 采用纠错后恢复原词；词典不可用但用户选择原词 | 直接：`AssistedSearchServiceTest`、`SearchQueryPlanTest`、`src/search/submitted.test.ts`、`src/search/assistance-page.test.ts`；原文与完整已提交筛选、空选择／上下文／修订，不读取 Reader 或密钥；仅用户主动切换。 |
| 有界一致检索表达 | 文档只包含正式名称；降级到关键词召回；无词典匹配 | 直接：`SearchQueryPlanTest`、`SearchQueryPlanBoundsTest`、`AssistedSearchServiceTest`、`SearchFallbackTest`；统一 executionText 传递、单次查询向量与召回、合法降级沿用计划、零匹配恒等。仅交付：参数化 `|||` 与完整 ACL／筛选 SQL 人工审阅；真实分词、召回及 topK 未验证。 |
| 实际采用与待确认响应 | 空命中仍有建议；响应结果与说明来自不同版本 | 直接：`AssistedSearchResponseTest`、`AssistedSearchServiceTest`、`TermSnapshotReaderTest`、`src/search/assistedApi.test.ts`；同一不可变计划生成说明和执行文本，缺必需事实整体失败、精确投影、空 hits＋建议合法。 |

## 3. admin-web-terminology

| 需求 | 命名场景 | 证据及准确边界 |
| --- | --- | --- |
| 独立读写资格 | 只读管理者；只有写资格 | 直接：`src/searchTerms/access.test.ts`、`src/searchTerms/pages.test.ts`；入口只按 read，动作另按 write，无空间／身份目录依赖。 |
| 公开范围与有类型表单 | 建立简称与旧称；错词目标读取失败 | 直接：`src/searchTerms/api.test.ts`、`src/searchTerms/pages.test.ts`；标准名称／释义／有类型别名，规范化预览、启用目标目录与故障禁用保存。组合：`TermEditor.vue` 公开范围及不授予文档资格提示人工审阅，不做文案内容测试。 |
| 歧义与冲突可辨认 | 同一简称用于两个组；标准名称与错词冲突 | 直接：`src/searchTerms/pages.test.ts`、`src/searchTerms/api.test.ts`；共享 PM 可保存、含义分页、NAME_CONFLICT／EXPRESSION_CONFLICT／VERSION_CONFLICT 固定分类、草稿保留不改名。 |
| 完整启停影响 | 停用被纠错映射引用的组；停用后旧建议仍留在他人页面 | 直接：管理 pages／api 的独立 enabled 意图与目标停用分离。组合：`CorrectionWriteServiceTest` 与 `TermEditor.vue` 影响提示；只承诺后续修订约束，不撤回他人已读文本，未验证真实多用户浏览器。 |
| 已提交目录与修订 | 分页间有人维护词典；切换管理视图 | 直接：`src/searchTerms/pages.test.ts`、`src/searchTerms/api.test.ts`；草稿不改续页，409 废弃旧页，视图切换隔离迟到，items／hasMore／cursor／revision 严格解码。 |
| 固定写意图与本人回执 | 连续点击保存；创建已提交但响应丢失；编辑与停用冲突 | 直接：管理 pages／api、`src/admin/hooks.test.ts`；在途互斥、相同标识／完整载荷重试、409 不覆盖、回执成功及随后刷新失败分开。组合：创建幂等由 `TermCommandTest`／`TermWriteServiceTest`，响应未知交互由编辑器同一写机制验证，不运行跨层创建链路。 |
| 身份和页面生命周期 | 换号后旧保存返回；后台恢复且资格已撤销；大整数和标记文本 | 直接：管理 pages／access／api、`src/admin/hooks.test.ts`、`src/rendering-safety.test.ts`；隐藏／身份／资格清理、迟到隔离、恢复不写、关闭重开原绑定、精确身份与纯文本。仅交付：不会撤销已发出的服务端写入。 |

## 4. admin-web-access

| 需求 | 命名场景 | 证据及准确边界 |
| --- | --- | --- |
| 按词典读取资格开放入口 | 空间目录故障；只有写权限或缓存模块；模块加载期间撤权 | 直接：`src/searchTerms/access.test.ts`、`src/searchTerms/pages.test.ts`，相邻 `src/admin/access.test.ts`、`src/admin/access-spaces.test.ts`、懒路由回归；词典固定路由不发空间读取，缓存模块不绕过当前 read，下载迟到不挂载。 |
| 登录返回和恢复重核 | 登录后返回管理页；返回地址携带词条查询；写入未知后恢复页面 | 直接：词典 access／pages、`src/routing.test.ts`、`src/navigation.test.ts`、`src/session.test.ts`；只接受固定路径，拒绝 query／fragment／子路径／站外，隐藏恢复重新核对且不重放写入。 |

## 5. functional-authorization

| 需求 | 命名场景 | 证据及准确边界 |
| --- | --- | --- |
| 精确独立词典读写 | 目录显示新资格；只有词典读取资格；其他管理者尝试维护词典 | 直接：`PermissionCatalogTest`、`TermPermissionTest`、`TermAccessTest`；完整目录 36 个 code、多角色并集、读写不蕴含、角色名／资源管理／相似 code 不替代。 |
| 搜索与管理分开 | 无管理资格的普通用户；写资格撤销后重放；无资格者猜测目标；管理资格与资料授权分开 | 直接：`TermPermissionTest`、`TermAccessTest`、`TermLookupTest`、`TermReceiptServiceTest`、`AssistedSearchServiceTest`；先鉴权再目标／回执，锁后清缓存与重核，文档 scope 与公开词典分离。 |
| 初始化、撤权和旧目录兼容 | 存量系统升级；撤销后重启；空库首次引导；旧实例回退 | 直接：`TermPermissionTest` 的引导／validator 替身验证新角色完整目录、重复引导不补授、未知 code 仍拒绝。仅交付：V42 一次性 ROLE_ADMIN 绑定与旧 app 排空／保留数据回退说明，未执行迁移、启动旧实例或升级演练。 |

## 6. audit-query

| 需求 | 命名场景 | 证据及准确边界 |
| --- | --- | --- |
| 既有持久审计范围 | 按术语查询维护历史；只有词典管理资格；目标停用后查询 | 直接：`TermAuditQueryTest`、`AuditQueryServiceTest`、`TermAuditTest`，前端 auditApi／auditQuery／audits／audit-access；四操作域、两目标类型、稳定 ID 精确筛选与独立 audit:read。仅交付：历史不依赖当前词条启停的查询实现，真实持久历史未验证。 |
| 固定安全摘要 | 修改名称和别名；修改错词目标；载荷混入查询或名称 | 直接：`TermAuditProjectionTest`、`AuditSummaryProjectorTest`、`src/admin/auditApi.test.ts`、`src/admin/audits.test.ts`；实体适用固定字段、changedFields 名称集合、前后目标／版本，未知正文不投影，损坏摘要保留事件元数据。 |
| 回执同事务且不记录搜索历史 | 匹配重试；审计提交失败；用户连续采用建议 | 组合：`TermAuditTest`、`TermWriteOutcomeTest`、`TermCommandTest`、`TermMaintenanceBoundaryTest` 核对首次写入／回执／审计调用与异常传播、重放不追加及只允许维护服务产审计。仅交付：真实同事务原子性、提交／回滚未验证；采用不持久化历史由实现及编译后依赖审阅。 |

## 7. search-filtering

| 需求 | 命名场景 | 证据及准确边界 |
| --- | --- | --- |
| 显式增强且旧搜索兼容 | 新调用仅有合法 query；原调用提交增强字段；原 AI 和采集调用 | 直接：`AssistedSearchInputTest`、`AssistedSearchServiceTest`、`SearchServiceTest`、`SearchCitationMappingTest`、`src/search/api.test.ts`；增强默认值、旧字段白名单、旧数组和固定版本。仅交付：AI／工具保留原端点的实现与全模块编译。 |
| 选择严格解码和绑定 | 选择原文之外的位置；选择身份精度；修改条件复用建议 | 直接：`AssistedSearchInputTest`、`SearchSelectionContextTest`、`SearchQueryPlanTest`、`src/search/assistedApi.test.ts`；精确 JSON 整数、区间／重叠／累计集合、全部筛选与数量绑定，任一非法整批拒绝。 |
| 可验证完整响应 | 原词模式或空命中；请求落到旧服务；有效表达被损坏 | 直接：`AssistedSearchResponseTest`、`AssistedSearchServiceTest`、`src/search/assistedApi.test.ts`、`src/search/assistance-page.test.ts`；双版本头、ORIGINAL 恒等与完整 hits、旧数组／404／405／缺头／冲突／损坏字段整体拒绝，无旧接口重发。 |
| 每路截取前完整条件 | 扩展词仅命中无权文档；文档例外与扩展；采用建议后降级；公开术语没有可见分类 | 组合：`AssistedSearchServiceTest`、`SearchFilterServiceTest`、`QueryConsistencyTest`、`SearchExecutionMappingTest`、`SearchFiltersTest` 与 `SearchMapper.xml` 人工审阅；相同 scope／完整 filters／expression 参数、无范围空 hits、例外不受空间候选额外白名单、维护／降级沿用条件。前端 selectors／page 回归核对目录仍使用原文档候选 API。真实 SQL 授权隔离与召回语义未验证。 |

## 8. search-query-confidentiality

| 需求 | 命名场景 | 证据及准确边界 |
| --- | --- | --- |
| 只用 POST 正文检索 | 合法检索请求；缺省数量；无效请求体或 URL 参数；调用方伪造身份或向量；请求触发新增限界；增强查询和采用上下文；GET 增强搜索 | 直接：`AssistedSearchInputTest`、`AssistedSearchProtectionTest`、`EpochAuthenticationInterceptorTest`、`SearchServiceTest`、`SearchExecutorTest`、`src/search/assistedApi.test.ts`、`src/components.test.ts`；严格字段、固定路径、数量、400／413／容量、可信身份及 GET 方法投影。仅模拟请求，不运行 HTTP 服务器。 |
| 日志与遥测保密 | 异常内容回显检索词；旧 GET 到达受控网关；发布前核查前置代理；搜索候选异常携带名称；追踪与指标输出；建议过期或损坏 | 直接／组合：app 新路径保护、全局失败、gateway 保护、`SearchMetricsTest`、出站传输测试，及既有安全日志分派实现审阅；固定路由／阶段／原因，不记录正文、词条、queryString、凭据或原始异常链。**仅交付**：`docs/user-web-operations.md` 与词典运维中的前置代理核对步骤；端到端日志、实际遥测导出和代理未验收。 |
| 候选正文及所有响应 no-store | 标签名称查询；网关提前拒绝候选请求；网关拒绝增强请求 | 直接：`SearchRouteTest`、`SearchTermGatewayProtectionTest`、`SearchTermResponseFilterTest`、`AssistedSearchProtectionTest` 和前端 selectors／api；原候选正文协议不变，增强成功／拒绝与管理段边界 no-store，版本头仅透传。真实代理与网络缓存未验证。 |
| 无隐式历史且不扩大出站 | 生成歧义候选；用户可见的上下文；同义扩展需要外部向量 | 直接：`SearchSelectionContextTest`、`SearchSelectionContextLifetimeTest`、`AssistedSearchServiceTest`、`EmbeddingSupplierIsolationTest`、`RemoteRerankTest`、`SearchHttpTransportTest`；候选本地生成、HMAC 载荷无可解码原文／条件、只发统一 executionText 与授权材料，无未采用释义／内部头。组合：无查询账本的依赖审阅，不证明真实供应商网络行为。 |

## 9. search-resilience

| 需求 | 命名场景 | 证据及准确边界 |
| --- | --- | --- |
| 一次共享绝对截止 | 前置阶段耗时；总预算耗尽；没有可见范围；分类和标签筛选耗时；候选目录读取超时；词典查询接近总截止 | 直接／组合：`SearchBudgetTest`、`SearchExecutorTest`、`SearchExecutorRaceTest`、`SearchJdbcTest`、`SearchTransactionTest`、`SearchFilterServiceTest`、`SearchFallbackTest`、`AssistedSearchServiceTest`；可控时钟、同一 SearchContext 剩余窗口、词典必要阶段、无范围仍核验辅助、向量不足按原规则降级。真实数据库等待与网络终止未验证。 |
| 稳定安全失败 | 可选阶段失败但成功返回候选；AI 检索故障到达页面；未知错误码；区分过期和伪造 | 直接：`SearchFallbackTest`、`AssistedSearchResponseTest`、`SearchSelectionContextLifetimeTest`、前端 assistedApi／assistance-page。仅交付：未改变的 `kb-ai` RetrievalFailure／SSE 安全错误链路人工审阅和全模块编译；本轮不执行 AI 流或真实端到端恢复。 |
| 共用准入与实际退出 | 多入口占满同一用户名额；词典读取未退出 | 直接：`SearchExecutorTest`、`SearchExecutorRaceTest`、`AssistedSearchServiceTest`；同一执行器／可信用户准入，无额外队列，TERMINOLOGY 超时后工作未退仍占位、拒绝准入不读词典。线程为进程内可控工作，不是真实 JDBC 取消。 |
| 召回前完整规模检查 | 一个简称有过多含义；合法长查询扩展后过长；多个选择中一项非法 | 直接：`TermMatcherTest`、`TermSnapshotReaderTest`、`SearchQueryPlanBoundsTest`、`SearchQueryPlanTest`、`AssistedSearchInputTest`；8/9 含义／匹配、32/33 表达、含换行 2048/2049、最终查询长度和整批选择，模型前拒绝不截断。 |
| 词典失败不是原词成功 | 空词典与读取失败；词典坏而原检索可用 | 直接：`TermSnapshotReaderTest`、`SearchQueryPlanBoundsTest`、`AssistedSearchServiceTest`、前端 assistance-page；revision=0 恒等与 Reader 故障分开，ORIGINAL／旧接口不访问词典、失败无自动切换。 |
| 完整响应及外部载荷限界 | 结果加建议超过响应限额；重排请求超限 | 直接：`AssistedSearchResponseTest`、`RemoteRerankTest`、`SearchHttpTransportTest`、`SearchFallbackTest`；完整 R 对象一起编码、响应／外部正文有界、重排回退授权顺序，无部分说明或第二次查询。 |
| 有限辅助观测类别 | 辅助失败与正常零匹配；多次维护和查询 | 直接：`SearchMetricsTest`、`AssistedSearchServiceTest`；SEARCH 聚合、TERMINOLOGY 阶段与固定 mode／outcome，NO_TERMS／UNAVAILABLE／LIMIT_EXCEEDED／STALE 分开；不以建议数声明质量。 |

## 10. user-web-documents

| 需求 | 命名场景 | 证据及准确边界 |
| --- | --- | --- |
| 只消费授权检索 | 提交搜索；未命中和依赖故障；查询超过字符上限；服务端拒绝输入；搜索用户并发超限；合法的降级结果；新请求发出后旧请求才返回 | 直接：`src/search.test.ts`、`src/search/page.test.ts`、`src/search/assistedApi.test.ts`、`src/components.test.ts`、`src/favorites/documents.test.ts`；固定 POST、长度／非法字符、最多 20 hits、固定失败、原顺序／版本、不自动重发与请求代次隔离。 |
| 实际采用与待确认展示 | 自动同义扩展成功；同时有纠错建议和结果；公开提示与空结果并存 | 直接／组合：`src/search/assistance-page.test.ts`、`src/components.test.ts`、`AssistedSearchServiceTest`；默认 ASSISTED，OA 已采用与报消／PM 等待明确点击，hits 与建议独立，不把建议当命中。 |
| 保留已提交条件与累计选择 | 草稿与结果筛选不同；逐次处理两个位置；重复点击和迟到响应 | 直接：`src/search/submitted.test.ts`、`src/search/assistance-page.test.ts`、`src/search/page.test.ts`；最初 query、冻结完整 filters、累计选择、在途禁用、清理旧 hits／引用与迟到隔离。 |
| 原词恢复与重新辅助 | 已纠错后切回原词；从原词重新启用辅助 | 直接：submitted／assistance-page；ORIGINAL 用最初原文和已提交条件，清空上下文／选择；主动重新辅助不恢复旧决定或跨会话偏好。 |
| 辅助失败主动恢复 | 点击前词典修改；增强入口不存在；词典故障时继续原词 | 直接：assistedApi／assistance-page；过期清理、超限／词典故障／协议失败／未知码区分、404／405 不转旧端点、不自动选择或放宽条件。 |
| 身份与请求生命周期 | 身份切换后旧建议到达；隐藏后恢复 | 直接：assistance-page、search／page、session、routing／PageLoadBoundary；隐藏／离页／换号／改密清理，迟到回调失效，恢复不重搜；组件直接验证补充字符 code point 切片、安全词条文本与无远程节点。知识需求 feedback 回归确认辅助及纠错后仍只预填最初原文。 |

## 交付与核对结论

- 全部需求按上述直接、组合或仅交付证据核对；不是全系统动态场景验收报告。真实数据库并发、约束／回滚／快照、SQL 查询语义、供应商网络、遥测导出、浏览器和 topK 质量明确未验证。
- 首期只接入知识搜索和词典管理；AI、自动猜测、学习历史、空间私有词典、普通用户完整目录未加入实现。
- 全局审计保留未知但合法域／目标类型精确查询兼容；新增白名单只约束注册与安全投影。
- V42、公开关系维护、原词恢复、签名／密钥轮换、app→gateway→web 升级及保留数据止损说明均人工交付。没有执行迁移、部署或真实浏览器验收；2026-11-24 经用户要求同步 10 份主规格并归档，见 [archive-record.md](archive-record.md)。
- 第二轮完整核验重新核对 57 项需求／168 个场景，W1～W3 保持关闭，新增未修复 W4：已替换旧片段与另一处合法补充表达混合时，前端错误拒绝完整响应。上述场景映射和测试全绿不代表该额外边界正确；独立诊断不计为规格通过用例，详情见 [verify-report.md](verify-report.md)。本次带警告归档，不改实现或缩减需求。
