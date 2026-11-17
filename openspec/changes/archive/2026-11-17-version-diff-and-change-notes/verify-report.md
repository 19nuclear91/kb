# 核验报告：version-diff-and-change-notes

归档状态：2026-11-17 用户授权后已同步七份主规格并归档，见[归档记录](archive-summary.md)。下文保留各轮核验发生时的工作区状态与证据边界。

核验及修复复核日期：2026-11-17。对象为 `D:\project-my\kb` 当前未提交工作区；schema 为 `spec-driven`，OpenSpec 根为当前仓库。首次核验只生成报告，随后按用户指令修复 W1/W2 并补充回归；未修改任务勾选或主规格，未归档。

当前结论：**0 项未解决 CRITICAL、0 项未解决 WARNING、0 项 SUGGESTION**。首次核验发现的两项警告已修复，新增 11 项详情页回归；前端全量 127 文件、1420 项通过。下文保留首次发现的复现与原因，修复证据见“修复复核”。

## 再次核验（2026-11-17）

按用户再次调用 `openspec-verify-change version-diff-and-change-notes` 的指令，重新读取项目根、schema 状态、apply 上下文及规划产物。仍为仓库本地 `spec-driven`，任务跟踪可用，36/36 完成；7 份增量规格共 36 项 ADDED 要求、99 个场景，无移除或重命名要求。完整性、正确性、一致性的六项检查均有可用证据，无跳过项。

复查 W1/W2 的实际代码及回归断言，并检查其对身份/目标生命周期的影响；复核后端鉴权与状态优先级、快照写入、说明锁后重读及历史→审计→回执顺序，沿用本会话已完成的逐要求映射和其余未改动实现的审查证据。未发现新的要求缺失、行为偏差或显著代码模式问题。

为确认父页面持有请求绑定后仍正确清理，临时复制实际详情页测试壳，增加身份世代变化、强制改密后恢复、隐藏后先核对身份再恢复、离开后重新进入共 4 项验证。运行整个临时文件：**26 项通过、0 失败、0 跳过**（22 项现有详情页测试及 4 项临时生命周期验证），耗时 5.52 秒；没有恢复旧请求入口、正文草稿或更正原因，没有自动提交。临时测试副本已删除，不计入正式全量的 1420 项。

本轮 `openspec validate version-diff-and-change-notes --type change --strict --json` 再次通过，0 问题；`git diff --check` 退出 0。此前修复轮次的前端全量 1420 项、类型检查、构建和实际包体隔离，以及实施轮次的后端 94 项离线结果继续作为证据，本轮未无必要地重复全量执行。仅更新核验报告，没有修改业务代码、任务或主规格，也没有归档。

再次核验结论：**W1/W2 均保持关闭，无新增 CRITICAL、WARNING 或 SUGGESTION；按既定离线范围可进入归档流程。**真实数据库、解析服务、浏览器、联调及部署仍未验证，不将本轮结果扩大为真实环境验收。

## 三维结果

| 维度 | 结果 |
| --- | --- |
| 完整性 Completeness | 36/36 任务完成；7 份增量规格、36 项 ADDED 要求均找到实现；没有 REMOVED、RENAMED 或 MODIFIED 条目 |
| 正确性 Correctness | 36/36 要求完成实现映射，99 个场景均核对了实现和证据类型；父页面重读及切换对比目标的偏差已修复并补充回归，见 W1/W2 及修复复核 |
| 一致性 Coherence | 核对 D1–D11、模块边界及既有代码模式；持久化请求绑定和默认基准计算已修复，未留下已知偏差 |

| 检查项 | 状态与依据 |
| --- | --- |
| Task Completion | 已核对。CLI 的 `taskTrackingConfigured=true`，`progress={total:36,complete:36,remaining:0}`，无不可读跟踪文件；逐组核对交付物及验证记录 |
| Spec Coverage | 已核对。36 项要求均有实现，没有发现整个要求缺失 |
| Requirement Implementation Mapping | 已核对，原 2 项警告已关闭；具体映射见后文 |
| Scenario Coverage | 已核对，W1/W2 的交互覆盖缺口已补齐；证据包含直接测试、组合证据及按计划交付后的静态审查，不能统称为 99 个运行测试通过 |
| Design Adherence | 已核对。D1–D11 的包边界、数据结构、发布事务、读取一致性、预算、算法、接口、写入顺序、前端入口和发布顺序均有对应交付；前端问题见 W1、W2 |
| Code Pattern Consistency | 已核对。复用版本权限服务、独立 Mapper/模型/服务/控制器、固定错误投影、懒加载及既有写入协调；未发现需单独报告的显著风格偏差 |

六项检查均执行，无跳过检查。真实基础设施行为是任务明确排除的验证范围，不因此新增归档前置条件；具体证据边界见文末。

## CRITICAL

无。

## 已关闭的 WARNING（保留首次核验记录）

### W1：详情页重读操作资格会丢失未确认说明的原请求绑定（已修复）

- 对应要求：`specs/user-web-documents/spec.md:33`，同一身份和固定版本内重读不能隐式放弃原请求；关联“重新读取后看到新文本”场景。
- 位置：`kb-web/src/pages/DocumentDetail.vue:75`、`:95`、`:206`；`kb-web/src/changeNotes/useChangeNoteWrite.ts:8`；`kb-web/src/admin/hooks.ts:66`。
- 触发：说明保存遇到网络错误，已经出现“按原内容重试”，随后点击详情页“重新核对操作资格”，身份和文档版本均不改变。
- 实际：资格重读改变 `factsKey`，并清空详情读取数据；`versionKey` 也包含读取修订号。说明组件因此卸载、重建。`useChangeNoteWrite` 没有给 `useAdminWrite` 传跨挂载的 `bindings`，卸载清理会令 `local=null`，组件内草稿也丢失。重读完成后“按原内容重试”和“继续核对说明结果”均不存在，无法再用原请求标识核对回执。
- 影响：共享协调状态仍可能保留“结果未确认”，但用户失去核对该请求的入口，只能放弃未确认状态。原请求可能已经在服务端执行；读取和组件重建不应替代显式放弃。
- 复现证据：复制既有 `reviews/documents.test.ts` 的实际详情页测试壳，令说明 PUT 抛出 `ApiError(0)`，确认重试入口已出现，再点击资格重读。断言两个核对入口至少保留一个，结果为 `expected null not to be null`。1 个定向用例失败；11 个原用例因 `-t` 筛选而跳过。
- 建议：把未决绑定、精确载荷及恢复编辑所需数据放在不会随同一目标资格重读卸载的上下文中，按身份世代、文档和版本隔离；保留切换身份/目标、隐藏和离开时的清理规则。不要只改组件 key，因为详情读取期间的条件渲染同样会卸载子树。补充父页面层面的旧值、新值及 503 重读测试，最后核对重试使用同一请求标识和载荷。

现有 `kb-web/src/changeNotes/editor.test.ts:54` 仅触发说明面板自身的重读，没有覆盖父详情页重读导致的卸载，因此未检出此问题。

### W2：切换对比目标后未读取该目标的说明，默认基准可能错误（已修复）

- 对应要求：`specs/user-web-documents/spec.md:49`，默认基准优先取目标说明的基准；关联“目标有变更说明”场景。
- 位置：`kb-web/src/changeNotes/CompareSelector.vue:9`、`:17`；`kb-web/src/pages/DocumentDetail.vue:206`、`:212`。
- 触发：详情当前选中 v4，用户在对比选择器中把目标改为 v3。可见版本为 v1–v4，v3 的说明基准是 v1，而最近的较旧已发布版本是 v2。
- 实际：选择器只收到详情当前选中版本 v4 的说明。切换目标不会读取 v3 的说明，也不会切换详情选中版本；`note.versionId !== target` 时直接按“没有说明基准”计算，默认选中 v2，未使用 v3 的说明基准 v1。
- 影响：默认生成的对比链接与目标说明所描述的版本对不一致；用户仍可手选正确基准，但实现不符合默认值规则。
- 复现证据：使用实际详情页测试壳提供四个可见版本，并让说明读取替身为 v3 返回基准 v1。将目标从 v4 切到 v3，断言基准 ID 为 `11`，实际为 `12`：`expected '12' to be '11'`。1 个定向用例失败；11 个原用例因筛选而跳过。
- 建议：为选择器中的目标单独读取说明，或使选择器目标与已读取说明的详情目标同步；正确丢弃旧目标的迟到响应，并仅在可见版本中选基准。扩展 `reviews/documents.test.ts`，覆盖切换目标后其说明基准不同于最近旧发布版本，以及目标切换期间旧说明迟到的情况。

首次核验时的默认值测试只覆盖初始化时的详情选中版本，没有覆盖选择器内部改变目标后的说明读取。

## 修复复核

- W1：新增 `kb-web/src/changeNotes/useChangeNoteDraft.ts`，由 `DocumentDetail.vue:74` 持有请求绑定、说明草稿和表单内容；面板把绑定交给既有 `useAdminWrite`。同一目标资格重读导致卸载时保留状态，身份/文档/版本变化、页面隐藏或离开时清除；面板卸载期间显式放弃也会清除。`reviews/documents.test.ts:98` 覆盖重读旧值、新值和 503 后沿用原请求标识及完整载荷；`:121` 覆盖详情 503 恢复及卸载期间放弃；`:137` 覆盖在途写入重建和迟到响应；`:161` 覆盖切换版本不恢复旧草稿。
- W2：`kb-web/src/changeNotes/CompareSelector.vue:11` 为选择器中的其他目标独立读取说明，按身份和文档/版本隔离请求；读取失败提示手选基准，旧响应和后到默认值不覆盖新目标或手选值。`reviews/documents.test.ts:178` 覆盖切换目标后的说明基准、无说明、基准不可见及 503；`:197` 覆盖旧目标迟到和保留手选基准。
- 修复后执行 `npm run typecheck`、`npm test -- --run`、`npm run build` 和实际包体隔离检查均退出 0；全量 **127 文件、1420 项通过，0 失败、0 跳过**，耗时 138.81 秒。新增 11 项详情页回归包含在全量结果中。
- 隔离报告：`kb-web/.bundle-analysis/version-diff-fixes-report.json`，入口与文档列表均未引入 `src/changeNotes/`、`src/versionDiff/`。命令和验证边界补记于 `verification.md`，场景映射已更新至 `traceability.md`。

## SUGGESTION

无。

## 要求到实现的映射

以下路径缩写相对于仓库根：`Diff/` 为 `kb-app/src/main/java/com/zhishiku/kb/app/document/diff/`，`Note/` 为同级 `changenote/`，`Worker/` 为 `kb-worker/src/main/java/com/zhishiku/kb/worker/parse/`，`Web/` 为 `kb-web/src/`。规格路径均相对于本变更的 `specs/`。

| 规格与要求 | 主要实现证据 | 核验结果 |
| --- | --- | --- |
| document-parsing：解析发布必须同时保存版本结构快照 | `Worker/DefaultParsePipeline.java:109`、`VersionSnapshotWriter.java:25`、`ChunkPublisher.java:185` | 归一化结构块、完整表格、发布事务内替换；真实原子提交未执行 |
| document-diff：只使用与当前索引一致的快照 | `kb-app/src/main/resources/mapper/diff/DocumentDiffMapper.xml:31`、`Diff/service/DocumentDiffService.java:94` | 单次表头读取核对 task/generation/indexed；缺失优先于超限 |
| document-diff：版本归属并分别鉴权 | `Diff/service/DocumentDiffService.java:50` | 批量 INTERACTIVE 判定在快照读取前，任一不可读统一 404 |
| document-diff：严格校验参数 | `Diff/web/DocumentDiffController.java:22` | 正整数、唯一基准参数、拒绝未知参数及相同版本 |
| document-diff：以结构块表达差异 | `Diff/service/BlockDiff.java:27`、`InlineDiff.java:14`、`Myers.java:14` | 块对齐、相似配对、移动、逐字片段与长块省略；另做最短编辑核验 |
| document-diff：保留表格行列语义 | `Diff/service/TableDiff.java:19` | 行对齐、单元格差异及 RESTRUCTURED 两侧矩阵 |
| document-diff：只从修改内容提取数值 | `Diff/service/ValueChangeExtractor.java:21` | 四类模式、规范值多重集合、顺序配对；跳过新增及移动块 |
| document-diff：返回解析质量提示 | `Diff/service/DocumentDiffService.java:94` | PARSER_DIFFERS 与分侧 TRUNCATED |
| document-diff：无法可靠对比返回明确状态 | `Diff/service/DocumentDiffService.java:50` | 缺失、超限、超时、低相似度不返回部分明细 |
| document-diff：实例并发上限 | `Diff/service/DiffAdmission.java:17`、`DiffExecutor.java:41` | 无排队执行，计算实际退出才归还名额 |
| document-diff：安全故障且不留存结果 | `Diff/web/DiffAdvice.java:23`、`DiffResponseFilter.java:16` | 安全错误、no-store、无结果存储与正文日志路径 |
| document-change-notes：独立人工声明 | `Note/service/ChangeNoteReadService.java:34`、V40 说明表 | 无记录投影、独立版本事实、无索引或 AI 依赖 |
| document-change-notes：写入时确定基准 | `Note/service/ChangeNoteWriteService.java:42`、说明 Mapper | 待审重算、已可见已有说明固定原基准、无基准拒绝 |
| document-change-notes：读取沿用交互访问规则 | `Note/service/ChangeNoteAccess.java:33`、`ChangeNoteReadService.java:34` | 当前身份、INTERACTIVE、独立 editable 判定 |
| document-change-notes：候选写权限和审核冻结 | `Note/service/ChangeNoteAccess.java:53`、`ChangeNoteWriteService.java:42` | view/edit/候选可读共同要求，锁后判断 IN_REVIEW |
| document-change-notes：发布后首次免原因、修改附原因 | `Note/service/ChangeNoteWriteService.java:42` | 首次补写、更正标记、原因必需、禁止清除 |
| document-change-notes：不可删除且不暴露操作者的历史 | `Note/service/ChangeNoteReadService.java:59`、V40 历史表及触发器 | 有界游标、固定字段；触发器仅静态审查 |
| document-change-notes：严格输入校验 | `Note/model/ChangeNoteInput.java:31` | JSON/UTF-8/字段/体积/code point/控制字符边界 |
| document-change-notes：绑定请求、冲突拒绝 | `Note/service/ChangeNoteCommands.java:39`、`ChangeNoteWriteService.java:42` | 请求摘要、重放再鉴权、预期版本与 NO_CHANGE |
| document-change-notes：历史、审计、回执原子提交 | `Note/service/ChangeNoteWriteService.java:42` | 事务边界及历史→审计→回执顺序；真实回滚未执行 |
| document-change-notes：安全表达失败 | `Note/web/ChangeNoteAdvice.java:25`、`ChangeNoteResponseFilter.java:16` | 固定状态/原因码，不回显敏感输入，no-store |
| user-web-document-diff：固定文档和两个版本 | `Web/versionDiff/VersionComparePage.vue:11`、`api.ts` | 路由、合法 ID、同版拒绝、统一 404 |
| user-web-document-diff：安全纯文本差异 | `Web/versionDiff/DiffOperations.vue`、`DiffTable.vue` | Vue 文本插值、折叠、片段、全文回退与表格 |
| user-web-document-diff：数值变化与识别范围 | `Web/versionDiff/ValueChanges.vue:5` | 分组、单侧值、定位与空结果文案 |
| user-web-document-diff：质量提示和不可用状态 | `Web/versionDiff/CompareContent.vue:23` | 提示与状态分离、指定版本预览、429 手动重试 |
| user-web-document-diff：目标说明及基准核对 | `Web/versionDiff/CompareContent.vue:31`、`changeNotes/ChangeNotePanel.vue` | 说明独立读取，基准不符时显示正确版本对及链接 |
| user-web-document-diff：身份及迟到结果隔离 | `Web/versionDiff/VersionComparePage.vue`、既有 `reviews/lifecycle.ts` | 身份世代、隐藏恢复、强制改密和目标切换清理 |
| user-web-documents：选中版本说明展示 | `Web/pages/DocumentDetail.vue:206`、`changeNotes/ChangeNotePanel.vue` | 纯文本、标记、历史，错误与空记录区分 |
| user-web-documents：按状态提示写入规则 | `Web/changeNotes/ChangeNoteEditor.vue` | 原因、清除、冻结、无基准、冲突和重读 |
| user-web-documents：未确认时保留原请求 | `Web/changeNotes/useChangeNoteDraft.ts`、`useChangeNoteWrite.ts:8`、`ChangeNotePanel.vue` | W1 已关闭：详情页持有绑定和表单，父页面重读后仍可按原请求重试 |
| user-web-documents：详情操作协调 | `Web/documents/operations.ts`、`changeNotes/useChangeNoteWrite.ts` | 在途/未确认与删除互斥，身份及目标生命周期清理 |
| user-web-documents：版本历史对比入口 | `Web/changeNotes/CompareSelector.vue:11` | W2 已关闭：切换目标独立读取说明，并隔离迟到响应和手选基准 |
| user-web-document-review：与当前发布版本对比入口 | `Web/reviews/ReviewContext.vue:66` | 固定候选、无当前版本或仅最小状态时无入口 |
| user-web-document-review：只读展示所审版本说明 | `Web/reviews/ReviewContext.vue:71` | readonly、冻结提示、失败不阻断审批 |
| audit-query：进入既有审计范围 | `kb-app/src/main/java/com/zhishiku/kb/app/auth/service/AuditScope.java:117` | 新域目标为 document-version，读取无审计写入 |
| audit-query：固定结构投影 | `kb-app/src/main/java/com/zhishiku/kb/app/audit/service/AuditSummaryProjector.java:144` | 固定 ID/布尔/版本字段，排除说明正文、原因和未知字段 |

## 场景与设计证据

| 规格 | 要求数 | 场景数 | 主要测试或审查证据 |
| --- | ---: | ---: | --- |
| document-parsing | 1 | 5 | Pipeline、SnapshotWriter、ChunkPublisher、SchemaCheck 测试及事务调用路径 |
| document-diff | 10 | 28 | Properties、Mapping、Block/Inline/Table/Similarity/Value、Execution、Service、Controller 测试 |
| document-change-notes | 10 | 31 | Input、Mapping、WriteService、ReadService、Controller 测试；V40 静态审查 |
| user-web-document-diff | 6 | 16 | `versionDiff/api.test.ts`、`page.test.ts`、路由、导航及实际包体隔离 |
| user-web-documents | 5 | 10 | `changeNotes/detail.test.ts`、`editor.test.ts`、`reviews/documents.test.ts`、写入协调；W1/W2 补充复现 |
| user-web-document-review | 2 | 5 | `reviews/page.test.ts`、`command-lifecycle.test.ts` 及只读组件组合 |
| audit-query | 2 | 4 | AuditScope、AuditSummaryProjector、写服务顺序及模块边界 |
| 合计 | 36 | 99 | 场景详细对应关系见 `traceability.md`；该文档中详情页证据需结合本报告 W1/W2 阅读 |

D1 的独立包及依赖限制由实现与 ModuleBoundaryTest 对照；D2 的五表、外键、约束、索引和不可修改触发器仅交付审查；D3 的归一化、空块处理、完整表格、发布顺序和启动检查有离线证据；D4 的表头一致性和重复读取隔离已核对；D5 的七项配置、准入、预算、状态优先级和安全错误有测试；D6/D7 的算法和提取规则有纯函数证据；D8 的字段与前端解析相配；D9 的请求锁、目标锁、再鉴权和写入顺序与设计一致；D10 的路由及包体隔离成立，W1/W2 已修复；D11 的部署/回退顺序已写入交付文档，未演练。

## 首次核验执行及复用的验证（修复前）

本次新增执行：

- `openspec validate version-diff-and-change-notes --type change --strict --json`：1 项通过，0 问题。
- `git diff --check`：退出 0；仅提示两份既有修改文档未来可能发生 LF→CRLF 转换，没有空白错误。
- 在 `kb-web` 执行 `npm test -- --run src/changeNotes/editor.test.ts src/reviews/documents.test.ts`：2 文件、27 项通过，0 失败、0 跳过。
- W1 和 W2 各一个临时定向测试：共 2 项失败，失败内容如上。使用既有详情页测试壳和模拟 DOM，没有浏览器或真实服务。测试副本已删除，未将核验用失败测试留在源码中。
- 用临时 Java 程序调用已构建的 `Myers.diff`，穷举长度 0–7 的二元序列，共 65,025 对；与动态规划 LCS 比较最短编辑长度，并重建两侧输入，全部通过。程序位于系统临时目录，未修改生产算法。

复用了同会话实施阶段的验证结果，而没有无必要地重复全量执行：

- 本次重新解析白名单对应的 Surefire XML：app 18 类/74 项，worker 6 类/19 项，gateway 1 类/1 项，合计 25 类/94 项，失败、错误、跳过均为 0；报告时间均为 2026-11-17。
- 全模块测试编译、前端类型检查、127 文件/1409 项全量测试、生产构建、12 项包体工具测试及实际模块隔离的此前执行记录，见 `verification.md`。这些是实施阶段结果，不冒充本次重新执行；本次发现 W1/W2 也说明全量通过不等于规格完全符合。

## 证据边界与归档判断

任务明确限定离线验证。没有运行 V40 或真实 PostgreSQL、Redis、容器、解析服务、浏览器、前后端联调、在线网关、部署与回退。SQL 约束、触发器、锁竞争、级联删除、事务原子性和执行计划仅作实现审查；服务替身的调用顺序不证明真实事务生效。没有为迁移 SQL、Mapper SQL 或文档内容编写断言测试。

按核验技能的分级：没有未解决的 CRITICAL、WARNING 或 SUGGESTION，检查未跳过；原 W1/W2 已修复并补充详情页回归，可进入归档流程。本报告不表示已获归档指令，也不表示真实环境验收或部署完成。
