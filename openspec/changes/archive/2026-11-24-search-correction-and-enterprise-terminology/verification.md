# 实施与最终离线验证记录

## 当前状态

2026-11-24，全部功能和最终离线回归已交付，任务 **51/51** 完成。已按用户要求同步 10 份主规格并归档，主规格严格校验 **76/76** 通过；未部署，保留未修复警告 W4。提交与推送按本次另行授权执行，不属于下述离线证据。归档状态见 [archive-record.md](archive-record.md)，最新完整结论见 [verify-report.md](verify-report.md)。本记录不表示真实环境验收通过；10 份增量规格的逐需求／命名场景证据见 [traceability.md](traceability.md)。

已经交付 V42 五表迁移、独立管理资格、同修订目录与含义分页、12 个管理端点、短事务写入与完整命令绑定、本人首次回执和固定安全审计；独立 HMAC 配置、轮换、会话／原文／完整筛选绑定；字素簇匹配、有界快照读取、不可变 literal-append-v1 计划和 12 个固定合成样例；增强严格输入、一次共享执行、TERMINOLOGY 预算、统一 BM25／单个 QUERY 向量／rerank 文本、完整响应及双版本头；app／gateway 保护和出站投影；知识搜索页累计确认／原词恢复与生命周期隔离、独立词典管理页和审计展示。使用、API、密钥、升级、保密与保留数据回退说明已人工审阅；根 README 的 V42／入口与 F-05 路线图已按实际交付更新。

## 最终执行命令与结果

### 第二轮完整核验（最新）

2026-11-24 第二轮重新执行后端 **59 类／325 项**（app 57 类／320 项，gateway 2 类／5 项）、前端 **30 文件／419 项**，0 失败／错误／跳过；全模块 `mvn -o -DskipTests test-compile`、typecheck、生产构建（2219 模块）、包体工具 **13 项**、包体隔离及变更 strict／diff 检查通过。显式白名单与下方相同，包体输出为 `.bundle-analysis/search-terminology-reverify-report.json`；数值与修复轮相同，不累计重复运行。

W1～W3 保持关闭，但新增 W4：混合位置的明确替换与合法自动扩展被前端错误拒绝。独立纯计划及纯解码探针确认该问题，解码探针的 **1 文件／1 项**仅断言当前错误，不计入上述 419 项或规格通过数。完整定位、复现、建议和范围见 [verify-report.md](verify-report.md)。随后只进行主规格同步、归档及文档更新，没有修复 W4 或新增业务测试。

### 核验反馈修复后的前端证据（历史修复轮）

2026-11-24，在首次核验发现 W1～W3 后按用户要求修复，并新增 **21 项正确行为回归**：

- W1：`TermEditor` 对本人回执核对增加当前绑定对象及查询代次校验；放弃、新意图、同意图重试和资格失效作废旧回调。测试确认未知 B 不被旧 A 回执结清，关闭重开仍沿用 B 的标识和载荷，连续核对只接受最新响应。
- W2：词典页提供共享 `readAccess`，全部管理读取（目录、含义、目标、详情和本人回执）的 403 先同步清理私有状态与在途回调，再有界重核；相同资格重挂载保留拒绝标记防止循环，主动重核完成后从空草稿恢复且不重放写入。API 已知 403 不等待错误体或取消完成，测试使用不结束的流与取消 Promise 替身，不监听服务器。
- W3：集中读取默认配置，并将实际上限冻结在 SubmittedSearch 本地元数据中，采用与模式切换保留；协议正文不新增字段。测试覆盖 9000 上限下 8001 个补充字符的 ORIGINAL／ASSISTED 完整成功、100／9000 扩展后边界，以及独立 32 表达／2048 新增 code point 限额。

修复前首批新增回归在旧实现出现预期失败；修复并补充相邻窗口后，执行下方完全相同的 30 文件前端显式白名单：**419 项全部通过，0 跳过**。`npm run typecheck`、包体工具 13 项、生产构建（2219 模块）、包体隔离、OpenSpec strict 与 `git -c core.safecrlf=false diff --check` 均通过。包体命令本轮为：

```powershell
npm run bundle:report -- --check-isolation --out .bundle-analysis/search-terminology-fixes-report.json
```

| 最新场景 | 文件数 | raw | gzip | brotli |
| --- | ---: | ---: | ---: | ---: |
| entry | 13 | 396836 | 133415 | 115555 |
| search | 40 | 512062 | 181071 | 156796 |
| searchTerms | 28 | 457070 | 157460 | 136539 |

下方 398 项和原包体数字保留为首次实施记录，与新运行重叠，不累计。本轮没有后端修改或 Maven 复跑；59 类／325 项及全模块编译是此前实施与首次核验已执行的证据，不声明为本轮运行。修复后的定向复核与首次报告历史见 [verify-report.md](verify-report.md)。真实环境及工作流边界保持不变。

### 首次实施执行记录

环境：Windows 11、PowerShell 7、Temurin JDK 21.0.7、Maven 3.9.9、Node.js 24.21.0、npm 11.19.0。后端 Maven 均使用 `-o`，不下载依赖。在仓库根目录运行：

```powershell
mvn -o -pl kb-app,kb-gateway -am test '-Dtest=TermInputTest,TermPermissionTest,PermissionCatalogTest,TermAccessTest,TermLookupTest,TermDatabaseTest,TermMappingTest,TermCommandTest,TermWriteServiceTest,CorrectionWriteServiceTest,TermReceiptServiceTest,TermWriteOutcomeTest,TermAuditTest,TermAuditProjectionTest,TermAuditQueryTest,TermMaintenanceBoundaryTest,AuditSummaryProjectorTest,AuditQueryServiceTest,SearchContextKeysTest,SearchSelectionContextTest,SearchSelectionContextLifetimeTest,TermCursorTest,TermReadServiceTest,TermMeaningQueryTest,TermAdminControllerTest,SearchTermResponseFilterTest,SearchTermAuthenticationTest,SearchTermGlobalFailureTest,EpochAuthenticationInterceptorTest,NormalizedQueryTest,CandidateBatchesTest,TermSnapshotReaderTest,TermMatcherTest,SearchQueryPlanTest,SearchQueryPlanBoundsTest,SyntheticQuerySamplesTest,AssistedSearchInputTest,AssistedSearchServiceTest,AssistedSearchResponseTest,AssistedSearchProtectionTest,SearchBudgetTest,SearchExecutorTest,SearchMetricsTest,SearchExecutionMappingTest,SearchCitationMappingTest,SearchFilterServiceTest,SearchFiltersTest,SearchPropertiesTest,SearchTransactionTest,QueryConsistencyTest,SearchServiceTest,SearchJdbcTest,SearchExecutorRaceTest,SearchFallbackTest,EmbeddingSupplierIsolationTest,RemoteRerankTest,SearchHttpTransportTest,SearchRouteTest,SearchTermGatewayProtectionTest' '-Dsurefire.failIfNoSpecifiedTests=false'
mvn -o -DskipTests test-compile
openspec validate search-correction-and-enterprise-terminology --strict
git diff --check
```

- 后端最终白名单：**59 类／325 项，0 失败、0 错误、0 跳过，BUILD SUCCESS**；其中 app 57 类／320 项、gateway 2 类／5 项。按此次明确类名读取 Surefire 结果，未混入目录中的旧报告。`surefire.failIfNoSpecifiedTests=false` 仅允许上游无匹配测试的模块，不跳过白名单内测试。
- 全模块 `test-compile`：父 POM 与 9 个 Maven 模块全部 BUILD SUCCESS，编译主代码和测试代码。该命令不进入 Surefire 生命周期，没有把未执行的其他测试记为通过。
- OpenSpec 针对此变更 strict 校验通过；`git diff --check` 无空白错误。LF→CRLF 提示不是失败。新增文件由补丁交付并人工检查，不以文档存在性测试代替审阅。

在 `kb-web` 目录运行：

```powershell
npx --no-install vitest run src/search/assistedApi.test.ts src/search/submitted.test.ts src/search/assistance-page.test.ts src/search.test.ts src/search/page.test.ts src/search/api.test.ts src/search/selectors.test.ts src/favorites/documents.test.ts src/knowledgeGaps/feedback.test.ts src/components.test.ts src/searchTerms/api.test.ts src/searchTerms/pages.test.ts src/searchTerms/access.test.ts src/admin/auditApi.test.ts src/admin/auditQuery.test.ts src/admin/audits.test.ts src/admin/audit-access.test.ts src/admin/access.test.ts src/admin/access-spaces.test.ts src/admin/hooks.test.ts src/admin/operations-routing.test.ts src/admin/knowledge-gaps-access.test.ts src/navigation.test.ts src/routing.test.ts src/routing/lazy-routes.test.ts src/routing/PageLoadBoundary.test.ts src/routing/errorSource.test.ts src/session.test.ts src/api.test.ts src/rendering-safety.test.ts --maxWorkers=2
npm run typecheck
npm run bundle:test
npm run build
npm run bundle:report -- --check-isolation --out .bundle-analysis/search-terminology-report.json
```

- 前端最终白名单：**30 文件／398 项全部通过**，未跳过、未按用例名称筛选；Vitest 使用 jsdom 和身份／transport／模块替身。类型检查通过；生产静态构建成功，2217 个模块完成转换。
- 包体工具独立单测 **13 项全部通过**，合成构建依赖图与 CLI 失败分支，不启动监听服务器。隔离检查通过：入口及普通搜索不静态包含词典管理实现；`searchTerms` 单独统计，未因此豁免原有场景。
- 最近一次产物报告位于 `kb-web/.bundle-analysis/search-terminology-report.json`（本地忽略产物）；长期数字记录如下，按逐文件压缩后求和，单位字节，不宣称较旧基线减少或网络实测收益。

| 场景 | 文件数 | raw | gzip | brotli | 相对入口新增 raw／gzip／brotli |
| --- | ---: | ---: | ---: | ---: | --- |
| entry | 13 | 396836 | 133415 | 115516 | 0／0／0 |
| search | 40 | 511960 | 181051 | 156750 | 115124／47636／41234 |
| searchTerms | 28 | 455561 | 156948 | 136029 | 58725／23533／20513 |

### 白名单审阅与中间修复

- 运行前审阅新增及相邻测试：身份、仓储、事务、JDBC 连接／结果集和 HTTP 传输均使用 Mock／Stub／内存实现；控制器、拦截器和过滤器各自独立调用，不组装完整 MVC 或应用，不监听端口。可控工作线程只验证退出／迟到分支，不连接数据库。
- 明确排除旧 `SearchInputTest`、`SearchFilterInputTest`、`SearchResponseProtectionTest`、`HttpObservationPrivacyTest` 等含 MockMvc 的文件，以及含真实依赖或 SQL／文档内容断言的非白名单测试。`CorsConfigurationTest` 本轮不执行，真实配置对象／CORS 行为由已审阅的 `SearchRouteTest` 覆盖。权限目录只核对 code，不断言展示说明。
- Mapper 回归核对参数与结果，不比较 SQL 文本；`rendering-safety.test.ts` 只检查组件代码的原始 HTML API 使用，包体测试只检查构建模块图，均不是说明文档、注释或文案测试。
- 早期后端阶段 219 项及后续定向组、前端 172／395／397 项和管理 71／86 项均与最终白名单重叠，不累计。修复期间的失败不计为通过，包括真实 Binder 的 record 构造选择、测试替身初始化调用、收藏按钮查询、词典路由误读空间目录、跨用例懒模块缓存及新增关闭按钮选择器。
- 最终补充验证了未知写入关闭重开后的原标识／原载荷重试，词典审计实体适用字段、布尔／精确版本、固定 changedFields、重复与损坏摘要的元数据保留，以及辅助面板补充字符原文定位、词条标记的安全文本和在途禁用。没有通过跳过测试、降低超时或删除场景来获得通过。

## 全部任务与实际证据

| 任务 | 实际证据 | 边界 |
| --- | --- | --- |
| 1.1 | V42 五表、键、外键、版本及回执保护人工审阅 | 迁移未执行，不验证真实数据库效果 |
| 1.2 | `TermInputTest` | NFC／ASCII、Unicode 空白、64/65、256/257、20/21、代理项、内部空格、关系类型、重复项、严格 JSON 与实际正文边界 |
| 1.3 | `TermPermissionTest`、`PermissionCatalogTest` | 精确独立权限、多角色并集、撤销重读、正式引导与未知 code；初始化 SQL 仅人工审阅 |
| 1.4 | `TermAccessTest`、`TermLookupTest`、`TermDatabaseTest`、`TermMappingTest` | 先鉴权、锁后清缓存、主部门门禁、本人归属、大整数／null／时间映射、参数绑定与超时定义；不连接数据库 |
| 1.5 | `docs/search-terminology-operations.md` 人工审阅 | 仅操作方案，不是发布或回退演练 |
| 2.1 | `TermCommandTest`、`TermMappingTest` | 别名换序等价、展示变化、跨操作／用户／目标／版本冲突、绑定损坏、首次回执及严格解码 |
| 2.2 | `TermWriteServiceTest` | 创建／完整编辑／独立启停、共享别名、名称与错词冲突、无变化／陈旧版本／溢出、锁后撤权与调用顺序 |
| 2.3 | `CorrectionWriteServiceTest`、`TermMappingTest` | 单向稳定目标、名称／别名占用、目标缺失／停用／改名、独立停用与双重可用性 |
| 2.4 | `TermReceiptServiceTest`、`TermLookupTest`、`TermWriteOutcomeTest` | 本人首次回执、未知 404、资格拒绝、匹配重放、实体→修订→回执→审计顺序与失败传播 |
| 2.5 | `TermAuditTest`、`TermAuditProjectionTest`、`TermAuditQueryTest`、`TermMaintenanceBoundaryTest`、既有审计两组测试 | 固定字段、损坏摘要、精确版本、独立审计资格、未知查询兼容、历史身份、仅人工写服务可产维护审计 |
| 2.6 | `docs/search-terminology-api.md`、`docs/admin-audit-web.md` 人工审阅 | 交付阶段服务契约，后由 4.3／4.5 接入控制器及完整文档；不宣称接口联调通过 |
| 3.1 | `SearchContextKeysTest` | 独立前缀绑定、缺失／短／非法／重复密钥、前任验证键与不含秘密的自动投影；缺配置不终止组件构造 |
| 3.2 | `SearchSelectionContextTest` | 精确身份／原文／完整筛选／数量绑定、用途隔离、严格签名载荷和实际 UTF-8 字节边界 |
| 3.3 | `SearchSelectionContextLifetimeTest` | 数据库时间 Stub、精确到期端点、修订推进、错误优先级、两个对象互验与轮换；未启动实例或连接数据库 |
| 3.4 | `TermCursorTest` | 目录／用户／目标／条件／limit 绑定、精确大整数、修订冲突、用途隔离、首个目录页缺配置拒绝 |
| 3.5 | `docs/search-terminology-operations.md` 人工审阅 | 真实配置键、第二验证槽与分阶段轮换、缺配置故障边界；未注入生产秘密或部署 |
| 4.1 | `TermReadServiceTest`、`TermMappingTest`、`TermCursorTest` | 同一次 read 回调中的修订／目录／详情／目标，limit 20/100 与 lookahead，字面名称查询，状态分离、真实空页／故障及修订续页拒绝；不证明真实快照隔离 |
| 4.2 | `TermMeaningQueryTest`、`TermMappingTest`、`TermCursorTest` | 去重的批量歧义参数／投影、只有启用关系贡献标志、名称与别名碰撞、表达归属、最小含义跨页和跨目标拒绝；Mapper SQL 仅人工审阅，不证明数据库执行语义 |
| 4.3 | `TermAdminControllerTest`、`TermInputTest`、`TermAccessTest`、`TermLookupTest`、`TermPermissionTest`、`TermReceiptServiceTest` | 12 个运行时映射、严格 JSON 与数字、URL 拒绝、无长度头 32769 字节即停、各状态、独立资格与本人回执；仅直接调用控制器，不启动服务或 MVC |
| 4.4 | `SearchTermResponseFilterTest`、`SearchTermAuthenticationTest`、`SearchTermGlobalFailureTest`、`EpochAuthenticationInterceptorTest` | 独立被测拦截器／过滤器／全局分派，路由前固定错误、data:null、no-store 重置／提交／错误／异步派发、相似前缀隔离、未知写等待不生成成功及日志探针；未组装跨组件 HTTP 链路 |
| 4.5 | `docs/search-terminology-api.md` 人工审阅 | 全部 D8 端点、当前目录／详情／含义、游标、精确数值、启停与固定失败；当前读值与首次回执明确分开 |
| 5.1 | `NormalizedQueryTest`、`TermInputTest`、`TermMaintenanceBoundaryTest` | 中文及重复位置、ASCII 边界、NFC 重排／收缩／展开、Hangul Jamo、补充字符／ZWJ／区域符号／CRLF 完整簇、原文坐标、不额外折叠和取消回调；不证明真实驱动取消 |
| 5.2 | `CandidateBatchesTest`、`TermSnapshotReaderTest`、`NormalizedQueryTest`、`TermMaintenanceBoundaryTest` | 128 起点／64 code point／8192 键、批内去重与跨批长词、懒生成、text[] 精确绑定、同回调连接与修订／时间、空词典／故障分离、JDBC 释放及取消后停止后续批次；预算、连接、结果集及 SearchTransaction 均为替身，不证明真实快照、执行计划或网络取消 |
| 5.3 | `TermMatcherTest`、`TermSnapshotReaderTest` | 最左最长、重复位置、仅启用组贡献含义、名称与别名碰撞、8/9 匹配及含义、原文坐标、错词单向、混合损坏事实拒绝，9 个组／2 个纠错证明行／21 个别名／2 个关联组的绑定和损坏投影拒绝；SQL 仅人工审阅，未验证真实数据库查询语义 |
| 5.4 | `SearchQueryPlanTest`、`TermMatcherTest`、`TermSnapshotReaderTest`、`AssistedSearchServiceTest` | 双向同义扩展、共享别名排除、错词单向、累计选择整体验证、定位替换、不递归和混合采用／建议；第 6 组核对身份与签名编排，不连接真实服务 |
| 5.5 | `SearchQueryPlanBoundsTest`、`SearchQueryPlanTest`、`NormalizedQueryTest`、`AssistedSearchServiceTest` | 不可变计划、精确投影、说明并集与统一文本；32/33 表达、2048/2049 新增 code point 含换行、8000/8001 最终文本、零修订、ORIGINAL 绕过及整体失败；真实关键词分词、模型与排序未验证 |
| 5.6 | `SyntheticTermDictionary`、`SyntheticQuerySamplesTest` | 12 个固定合成样例分别声明并核对原文匹配位置、有效文本、补充表达及待确认种类，涵盖简称、旧称、长中文、错词、歧义、未登记近似表达、ASCII 边界、无匹配及补充字符；不含真实业务语料或排序分值 |
| 5.7 | `docs/search-terminology.md` 人工审阅 | 公开关系、匹配、原文／确认替换／新增文本、累计选择与恢复、启停及已接入页面；词条不证明可读文档，排序收益未验证 |
| 6.1 | `AssistedSearchInputTest` | 默认／null、基础数量和筛选、严格字段、上下文／选择、区间／身份、实际正文上限、旧入口拒绝增强字段 |
| 6.2 | `AssistedSearchServiceTest`、`AssistedSearchProtectionTest` | 一次 executor 准入，当前认证属性与令牌清理，ORIGINAL／旧入口绕过，词典快照结束后进入模型，无范围仍有公开建议且不调用模型 |
| 6.3 | `AssistedSearchServiceTest`、`QueryConsistencyTest`、`SearchFilterServiceTest`、`SearchExecutionMappingTest`、`SearchCitationMappingTest`、`SearchServiceTest`、`SearchFallbackTest` | 单份 executionText、完整 filters／scope 参数、例外范围、profile／epoch、维护及降级、固定版本与数量；SQL 条件人工审阅，真实隔离与排序未验证 |
| 6.4 | `SearchBudgetTest`、`SearchExecutorTest`、`SearchExecutorRaceTest`、`SearchPropertiesTest`、`SearchJdbcTest`、`SearchTransactionTest`、`SearchFallbackTest` | TERMINOLOGY 必要阶段、剩余截止／取消、容量实际退出、迟到隔离、配置绑定／非法配置及向量预算不足；可控时钟和工作替身，不证明真实连接取消 |
| 6.5 | `AssistedSearchResponseTest`、`AssistedSearchServiceTest`、`AssistedSearchProtectionTest` | 完整 R 对象、ORIGINAL hits、空 hits＋建议、精确身份／版本、4 MiB 整体上限、双版本头与固定错误／Retry-After |
| 6.6 | `SearchMetricsTest`、`AssistedSearchServiceTest` | SEARCH 聚合不误入 options、固定模式／辅助类别、TERMINOLOGY 耗时；不含 query／ID／revision／凭据标签 |
| 6.7 | 搜索筛选／韧性／词典运维文档人工审阅 | 独立端点、兼容旧 AI 与工具、双版本、预算、规模、指标及主动恢复 |
| 7.1 | `AssistedSearchProtectionTest`、`SearchTermResponseFilterTest`、`SearchTermAuthenticationTest`、`SearchTermGlobalFailureTest`、`EpochAuthenticationInterceptorTest` | 精确路径／段边界、提前认证／正文／未知失败、no-store 和固定安全错误；探针不输出原查询／词条／凭据 |
| 7.2 | `SearchRouteTest`、`SearchTermGatewayProtectionTest` | dev/prod 的实际路由谓词及 CORS、正确 app 下游、身份头净化、提前拒绝 no-store、实际版本头透传不补写 |
| 7.3 | `EmbeddingSupplierIsolationTest`、`RemoteRerankTest`、`SearchHttpTransportTest` | 捕获实际构造的请求，仅统一检索文本与授权候选；令牌／内部追踪头隔离、载荷与取消；transport 不发生网络调用 |
| 7.4 | 词典运维及用户端运维文档人工审阅 | app→gateway→web、前置代理保密、密钥轮换与保留数据止损；未发布或演练 |
| 8.1 | `src/search/assistedApi.test.ts`、`src/search/api.test.ts` | 精确数值、完整对象、双头拒绝、旧数组／损坏模式／位置／有效表达、空结果与固定错误，不重发旧接口 |
| 8.2 | `src/search/submitted.test.ts`、`src/search/assistance-page.test.ts`、`src/search/page.test.ts` | 不可变已提交条件、累计选择、草稿隔离、原文恢复、重新辅助、新表单清空及请求交错 |
| 8.3 | `src/search/assistance-page.test.ts`、`src/components.test.ts` | 已采用和待确认区分、OA／报消／PM、空 hits 建议、失效／超限／协议／未知错误主动恢复，无自动采用或重发 |
| 8.4 | 上述页面测试、`src/search.test.ts`、`src/session.test.ts`、`src/rendering-safety.test.ts` | 隐藏／卸载／身份／改密、abort 与迟到、code point 文本及无持久化写入；收藏／引用读取基于当前 hits |
| 8.5 | `src/knowledgeGaps/feedback.test.ts`、`src/favorites/documents.test.ts`、`src/components.test.ts`、`src/search.test.ts` | 辅助和确认后反馈仍仅原始输入，收藏不重排／改引用版本、适用期与新请求清理 |
| 8.6 | 前端 README／使用文档人工审阅＋typecheck＋前端白名单 | 入口、采用／恢复／故障说明与模拟交互一致；真实浏览器未执行 |
| 9.1 | `src/searchTerms/access.test.ts`、相邻管理访问／routing／navigation／lazy-routes 测试 | 固定路由、read-only／write-only、空间故障不参与资格、模块加载撤权、固定登录返回，非法查询／片段／子路径拒绝 |
| 9.2 | `src/searchTerms/api.test.ts`、`src/searchTerms/pages.test.ts` | 两个目录、草稿／已提交、游标／修订、迟到／空／失败／末页、目标读取与共享含义、精确大整数及状态分离 |
| 9.3 | `src/searchTerms/pages.test.ts`、`src/searchTerms/api.test.ts`、`src/admin/hooks.test.ts` | 关系类型、共享别名、固定冲突、独立启停、在途互斥、原意图重试／关闭重开、本人回执、同值不结清与刷新失败单独表达 |
| 9.4 | 管理 pages／access、auditApi／auditQuery／audits／audit-access 测试 | 身份与资格收缩、隐藏恢复、迟到保存、维护与审计资格分离、固定字段／损坏摘要保留元数据、安全文本 |
| 9.5 | 管理／审计／前端 README 人工审阅、前端白名单、typecheck、build、bundle:test／report | 普通搜索无词典管理静态依赖、固定管理场景；只构建静态产物 |
| 10.1 | 本记录与 traceability、最终白名单／全模块编译／类型／构建／隔离／strict／diff 检查、根 README 与 F-05 人工审阅 | 10 份规格按直接、组合及仅交付证据核对，不把真实环境未验证事实包装为通过 |

## 未验证范围与工作流边界

真实数据库唯一约束、外键、锁竞争、快照隔离、提交原子性、回滚、执行计划、真实取消、网络、多实例、浏览器、模型调用与排序效果均未验证。事务替身中的 commit-request／rollback-request 仅表示调用分支，不能证明真实提交或回滚。

迁移、文档及 SQL 没有内容断言、快照或存在性测试。未执行 PostgreSQL／ParadeDB、Redis、对象存储、容器、应用、独立 HTTP 测试服务器、第三方 API 或真实浏览器。上述真实运行事实不转为本变更完成或归档前置任务。

任务 2.5 实施中已确认并同步设计 D10：保留现有 `AuditQueryService` 对未知但合法操作域／目标类型的精确查询兼容；白名单仅用于已知域注册及安全摘要投影，不作为新增输入拒绝规则。

实施及核验阶段未自动提交、推送、部署、同步主规格或归档；后续用户明确授权同步、归档、更新文档及单次中文提交推送。已完成的主规格同步与归档另见 [archive-record.md](archive-record.md)，不改变各历史轮的测试数字或 W4 未修复事实。真实环境未验证事实不是本变更的隐藏待办或完成／归档门槛，部署和迁移仍未执行。
