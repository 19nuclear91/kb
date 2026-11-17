# 场景与实现证据

本表对应本变更七份增量规格的全部场景。路径以仓库根目录为准。Java 的测试类与同名实现位于相应模块 `src/test/java/com/zhishiku/kb/...` 和 `src/main/java/com/zhishiku/kb/...`。

“直接”表示离线用例直接检查返回值或交互；“组合”表示服务替身、配置绑定、边界或调用顺序共同提供证据；“交付/核对”表示交付代码或迁移并人工核对，未执行相应真实行为。测试通过不证明真实数据库语义或线上链路成立。命令和统计见 [verification.md](verification.md)。

## document-parsing

实现：`kb-worker/.../parse/DefaultParsePipeline.java`、`ParsePipeline.java`、`VersionSnapshotWriter.java`、`ChunkPublisher.java`、`VersionSnapshotSchemaCheck.java`；迁移 `kb-app/src/main/resources/db/migration/V40__version_diff_and_change_notes.sql`。

| 场景 | 代码与测试证据 | 性质及限制 |
| --- | --- | --- |
| 解析成功并保存快照 | `DefaultParsePipelineTest` 检查归一化块和顺序；`VersionSnapshotWriterTest` 检查任务、执行周期、字符计数及完整单元格；`ChunkPublisherTest.takesTheDocumentRowLockBeforeTouchingChunks` 检查发布顺序 | 直接/组合；SQL 和原子提交未在真实库执行 |
| 同一版本重复处理 | Writer 先删除再插入；`VersionSnapshotWriterTest` 覆盖替换和空块删除 | 组合；实际唯一性与级联行为为迁移交付 |
| 发布过程中失败 | `ChunkPublisherTest.snapshotFailurePropagatesBeforeIndexAndTaskCompletion`；发布方法的事务边界 | 直接异常/顺序；真实回滚未验证 |
| 大表格 | `DefaultParsePipelineTest`、`VersionSnapshotWriterTest`；检索分块与完整快照分别传递 | 直接 |
| 快照不影响检索与重建 | 快照只由 diff Mapper 读取；`TextChunkerTest`、`ParsePipelineBudgetTest` 回归；未改搜索与重建查询 | 组合/人工依赖核对，非真实检索验收 |

补充启动证据：`VersionSnapshotSchemaCheckTest` 以 JDBC 替身覆盖两表存在、任一缺失；真实部署顺序未演练。

## document-diff

实现：`kb-app/.../document/diff/`；映射 `kb-app/src/main/resources/mapper/diff/DocumentDiffMapper.xml`。

| 场景 | 代码与测试证据 | 性质及限制 |
| --- | --- | --- |
| 旧 Worker 发布的版本；待审版本刷新索引后尚未完成；快照来自更早的执行周期 | headers 单条查询核对 task/generation/indexed；`DocumentDiffMappingTest` 检查绑定及超时；`DocumentDiffServiceTest.missingSnapshotHasPriorityOverOversizeWithoutReadingBlocks` 检查不可用侧及优先级 | 组合；一致性判定 SQL 交付，未在真实库执行 |
| 普通读者对比待审版本；基准版本属于另一份文档；撤权后再次请求 | `DocumentDiffServiceTest.rejectsUnreadableCandidateForeignBaseAndRevokedAccessBeforeReading`；`DocumentVersionAccessService` INTERACTIVE 批量鉴权 | 直接服务替身；既有权限规则复用 |
| 合格审核人对比待审版本 | `DocumentDiffServiceTest.successfulAndUnrelatedComputationsHaveCorrectStatus` 的允许访问路径；`ModuleBoundaryTest` 限定权限服务入口 | 组合；真实权限及 SQL 组合未联调 |
| 对比同一个版本；携带未知参数 | `DocumentDiffControllerTest.rejectsInvalidParametersWithoutCallingServiceAndAlwaysNoStore` 同时覆盖缺失、重复及非法基准 | 直接 MockMvc |
| 段落中改了一句话；段落原样移到别处；两个版本内容相同；以较新版本作为基准 | `BlockDiffTest`、`InlineDiffTest`，Myers 对齐、区段配对、移动识别及固定方向 | 直接纯函数 |
| 修改的块过长 | `InlineDiffTest.longModifiedBlocksOmitInlineButRetainFullText`；`BlockDiff.operation` 保留两侧全文 | 直接 |
| 表格中改了一个单元格；表格新增一列 | `TableDiffTest.reportsOnlyChangedCellAndRestructuresDifferentWidths`，`TableDiff` | 直接 |
| 金额被修改；只改了数字写法；新增段落中包含日期 | `ValueChangeExtractorTest.amountsNormalizeWidthThousandsAndIgnoreAddedAndMoved`；日期、百分比及条款顺序/单侧值由同类另一个用例覆盖 | 直接 |
| 两个版本使用了不同的解析器版本；目标版本被截断 | `DocumentDiffServiceTest.timeoutReturnsNoPartialResultsAndQualityWarningsArePreserved`、成功路径，`load` 质量提示 | 直接服务替身 |
| 版本内容超过上限 | `DocumentDiffServiceTest.missingSnapshotHasPriorityOverOversizeWithoutReadingBlocks`；`DocumentDiffPropertiesTest` 配置边界 | 直接；超限不读取块 |
| 计算超时；两个版本几乎完全不同 | `DiffExecutionTest`、`DocumentDiffServiceTest`、`DiffSimilarityTest` | 直接/组合；超时无部分结果，低相似度无明细 |
| 并发请求超过上限；超时计算尚未退出 | `DiffExecutionTest` 的阻塞计算和可控时钟；`DiffAdmission` / `DiffExecutor`，任务 finally 释放票据 | 直接内存并发，不代表线上容量测试 |
| 依赖故障；计算异常的日志 | `DocumentDiffServiceTest.dependencyFailuresAre503WithoutContentInLogsAndReleaseReadTicket`；`DocumentDiffControllerTest` 安全错误与 no-store | 直接 |

额外算法边界：`BlockDiffTest` 覆盖配对矩阵上限，`InlineDiffTest` 500 轮随机 Unicode 输入重建，截止标志测试检查协作停止。未用真实解析文档校准阈值。

## document-change-notes

实现：`kb-app/.../document/changenote/`，`mapper/changenote/`，V40 五张表。写服务在同一事务中使用 request 锁、版本锁、权限重核、历史、审计、回执。

| 场景 | 代码与测试证据 | 性质及限制 |
| --- | --- | --- |
| 升级前已有的版本；新版本不继承说明 | `ChangeNoteReadServiceTest.absentNoteAndFirstVersionHaveZeroVersionAndNullWriteBase`；Mapper 按 document/version 读取，无回填、无复制写入路径 | 直接无记录投影/交付核对；未执行升级 |
| 说明内容不可被搜索 | 新包没有索引、搜索、向量或问答依赖；`ModuleBoundaryTest`；既有检索代码未接入说明表 | 组合依赖核对，不是实际检索验证 |
| 文档的第一个版本；读取第一个版本 | `ChangeNoteWriteServiceTest.freezeMissingBaseArchivedAndUnreadableCandidatesReject`；`ChangeNoteReadServiceTest.absentNoteAndFirstVersionHaveZeroVersionAndNullWriteBase` | 直接 |
| 写入后另一版本先发布；更正已发布版本的说明 | `ChangeNoteWriteServiceTest.publishingAnotherVersionDoesNotRelabelExistingNoteBase`；已发布且有说明时固定原基准 | 直接服务替身 |
| 发布后首次写入；自动发布空间发布后补写 | `ChangeNoteWriteServiceTest.firstPostPublicationWriteNeedsNoReasonButCorrectionsDoAndRemovalIsForbidden`；可见状态而非空间策略决定规则 | 直接/组合 |
| 无候选访问资格；有 edit 但无候选访问 | `ChangeNoteReadServiceTest.unreadableCandidatesAndMismatchedOwnershipAre404AndDependenciesAre503`；写服务拒绝路径；`ChangeNoteAccess` 同时要求 INTERACTIVE、view、edit | 直接替身/组合既有授权 |
| 读者查看已发布版本的说明 | `ChangeNoteReadService.get` 独立版本可见性检查，editable 单独计算；读取服务及控制器测试 | 组合 |
| 审核中冻结；撤回后修改再送审 | `ChangeNoteWriteServiceTest.freezeMissingBaseArchivedAndUnreadableCandidatesReject`；`draftWritesAndClearsWithoutReasonRecomputingBaseEachTime` | 直接状态分支/组合；完整送审周期未联调 |
| 提交前版本已经发布；修改已发布说明但缺少原因 | 写服务在版本锁后重读 target；`firstPostPublicationWriteNeedsNoReasonButCorrectionsDoAndRemovalIsForbidden`；前端 REASON_REQUIRED 测试 | 组合；真实锁竞争未验证 |
| 附原因修改已发布说明；尝试清除已发布说明；更正历史版本的说明 | 同一写服务测试分别检查更正标记、原因必需、禁止清除；`publishingAnotherVersionDoesNotRelabelExistingNoteBase` 检查固定历史目标 | 直接服务替身 |
| 读者查看说明的修改历史 | `ChangeNoteReadServiceTest.boundedCursorBindsActorDocumentVersionAndHistoryHasNoActor`；分页只投影时间、前后值、可见状态和原因 | 直接 |
| 尝试修改或删除历史 | V40 历史与命令不可修改触发器、无更新删除接口 | 交付/人工核对；真实触发器未执行 |
| 说明只有空白；说明超长；说明包含换行 | `ChangeNoteInputTest` 严格 UTF-8、code point、控制字符、代理项及 32KiB/字段/JSON 边界 | 直接 |
| 响应丢失后重试；同一标识用于不同文本；撤权后重放 | `ChangeNoteWriteServiceTest.matchingReplayReauthorizesAndMismatchedBindingFails`；`ChangeNoteCommands` 精确绑定 actor/target/version/digest | 直接服务替身；真实 advisory lock 未执行 |
| 并发修改 | `ChangeNoteWriteServiceTest.concurrentVersionAndIdenticalTextRejectWithoutNewFacts`，校验 expectedNoteVersion 和 NO_CHANGE | 直接分支；真实并发事务未验证 |
| 审计写入失败 | `ChangeNoteWriteServiceTest.historyThenAuditThenReceiptAndAuditFailureDoesNotSaveReceipt`，事务注解及异常传播 | 直接调用顺序/组合；真实回滚未验证 |
| 依赖故障；写入失败时的错误内容 | `ChangeNoteReadServiceTest`、`ChangeNoteControllerTest` 检查 503、安全错误体、原因码、请求标识及 no-store | 直接 |

Mapper 的全部语句由 `ChangeNoteMappingTest` 检查参数绑定和 3 秒超时，未用 SQL 字符串断言。约束、索引、外键、触发器和命令锁均只交付。

## user-web-document-diff

实现：`kb-web/src/versionDiff/`，复用 `changeNotes/ChangeNotePanel.vue`、`office/VersionPreview.vue` 及 `reviews/lifecycle.ts`。

| 场景 | 离线证据 |
| --- | --- |
| 直接打开合法链接 | `versionDiff/page.test.ts` 路径参数和版本标题；`routing/lazy-routes.test.ts` 直接地址只加载该模块；`navigation.test.ts` 登录返回白名单 |
| 链接中的 ID 非法 | `versionDiff/api.test.ts`、`page.test.ts` 非正整数、相同版本均不发送 |
| 任一版本不可访问 | `page.test.ts` 404 只显示统一提示 |
| 片段包含 HTML 标记 | `page.test.ts` 检查原样文本且没有 img/script 元素 |
| 大段未变化内容 | 同文件检查 details 初始折叠与点击展开 |
| 修改块未提供逐字片段 | 同文件检查并列两侧全文及说明 |
| 查看金额变化 | 同文件检查分组、前后值、单侧值及 scrollIntoView 调用 |
| 没有识别到数值变化 | 同文件检查明确识别范围文案，保留修改块 |
| 缺少快照 | 同文件四种无法对比状态参数化用例，缺失侧及两版本预览 URL；`api.test.ts` 非 AVAILABLE 忽略明细 |
| 对比繁忙 | `page.test.ts` 429 不自动重发，点击才重试；503 单独提示 |
| 解析器不同 | 同文件检查 PARSER_DIFFERS、TRUNCATED 提示与差异共存 |
| 说明基准与本页一致；说明基准与本页不同 | 同文件参数化检查本次说明或明确 vA 到 vY 及正确链接 |
| 说明读取失败 | 同文件 503 只影响说明区域 |
| 切换对比版本后旧结果迟到 | 同文件检查 abort 及旧结果丢弃 |
| 切换账号 | 同文件检查退出、世代变化、强制改密与新身份重读；隐藏恢复额外检查先 refresh 身份 |

以上是模拟 DOM 直接交互证据，预览只检查沿用现有组件的指定版本路径，不代表浏览器或文件服务已验证。`bundle-report.test.mjs` 检查隔离规则的失败路径；生产报告检查实际模块集合。

## user-web-documents

实现：`kb-web/src/changeNotes/`、`pages/DocumentDetail.vue`、`documents/operations.ts`。

| 场景 | 离线证据 |
| --- | --- |
| 查看已更正的说明 | `changeNotes/detail.test.ts` 基准、两种标记、分页历史、无操作者及纯文本 |
| 说明读取失败 | 同文件 503 不显示为没有说明；版本切换丢弃旧读取 |
| 发布后首次补写 | `changeNotes/editor.test.ts` 不要求原因及补写提示 |
| 版本在编辑时已经发布 | 同文件 REASON_REQUIRED 后重新读取、用户重新编辑并手填原因 |
| 并发冲突 | 同文件 NOTE_VERSION_CONFLICT 后重读，不自动覆盖 |
| 重新读取后看到新文本 | `editor.test.ts` 检查面板重读；`reviews/documents.test.ts` 另覆盖父详情页资格重读后旧文本/新文本/503、详情 503 后恢复、关闭后继续核对，核对同一请求标识及精确载荷；`useChangeNoteDraft` 在详情页持有绑定与表单内容 |
| 切换版本后响应迟到 | 同文件版本/身份/改密/隐藏/离开清草稿和旧回调；`documents/write-coordination.test.ts` 说明在途与未确认阻止删除 |
| 目标有变更说明；目标没有变更说明 | `reviews/documents.test.ts` 在实际详情页检查初始默认值和切换对比目标后的独立说明读取，覆盖有说明、无说明、基准不可见、503、旧响应迟到和手选基准；`CompareSelector` 按当前目标取说明 |
| 只有一个可见版本 | 同文件无对比入口，另检查不可见说明基准不进入选项 |

待审清除、发布后原因必需、冻结、无基准、显式放弃及删除独占期间禁止重试也由 `editor.test.ts` 直接覆盖。`reviews/documents.test.ts` 另外检查面板卸载期间显式放弃、在途保存时资格重读和旧成功响应隔离、切换详情版本后不恢复旧草稿。真实删除及写入联调未执行。

## user-web-document-review

实现：`kb-web/src/reviews/ReviewContext.vue`。

| 场景 | 离线证据 |
| --- | --- |
| 审核人对比待审版本 | `reviews/page.test.ts` 检查链接绑定 currentVersionId 和固定候选版本 |
| 文档尚无发布版本 | 同文件无 currentVersionId 时没有入口 |
| 只能查看本人最小状态 | 同文件不展示说明或对比入口，不额外读取私有内容 |
| 审核人查看说明 | ReviewContext 以 readonly 接入，`reviews/page.test.ts` 检查冻结提示和无编辑按钮；`changeNotes/detail.test.ts` 直接覆盖说明和历史内容 |
| 说明读取失败 | `reviews/page.test.ts` 说明 503 不禁用通过操作；`reviews/command-lifecycle.test.ts` 既有审核生命周期回归 |

审核展示为组合组件证据，不代表真实审批、发布及数据库事务已验证。

## audit-query

实现：`AuditScope.DOCUMENT_CHANGE_NOTE_UPDATE`、`AuditSummaryProjector`、`ChangeNoteWriteService`，复用既有审计读资格及固定窗口。

| 场景 | 代码与测试证据 | 性质及限制 |
| --- | --- | --- |
| 按操作域查询说明写入 | `AuditScopeTest`、`AuditSummaryProjectorTest`；新域目标为 document-version，既有查询按枚举接受操作域 | 组合/既有查询复用，无真实审计库查询 |
| 读取对比不产生审计 | diff 模块边界禁止依赖审计；说明读取无写入调用；写服务仅成功路径追加且重放前返回 | 依赖与调用路径核对，非真实审计事件查询 |
| 附原因更正说明 | `AuditSummaryProjectorTest` 固定前后字段、可见状态和 reasonProvided；写服务测试历史→审计→回执 | 直接投影/组合写入 |
| 载荷混入文本 | `AuditSummaryProjectorTest` 排除说明、原因、标题及未知嵌套字段，错误字段类型投影不可用 | 直接 |

## 未执行范围

真实数据库迁移和 SQL 执行、外键/约束/不可修改触发器、级联删除、事务回滚与锁竞争、SQL 执行计划均未运行；真实第三方解析、真实浏览器、前后端联调、在线网关、部署和回退均未运行。没有将数据库替身的调用顺序写成锁已生效或事务已成功回滚的证明。

V40、接口文档、解析接入说明、审计说明、根 README、前端 README 与路线图均为交付物，未为其内容编写断言。2026-11-17 后续已按用户指令同步七份主规格并归档，详见[归档记录](archive-summary.md)。
