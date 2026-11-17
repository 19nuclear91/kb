# Tasks

按组依赖推进。行为以本变更的七份增量规格为准，技术取舍见 design.md D1–D11。

本清单只安排离线验证：内存替身的服务与事务测试；加载 MyBatis 配置检查 BoundSql 参数绑定与语句超时（不连接数据库，不断言 SQL 文本片段）；控制器与 JSON 输入测试；纯函数算法测试；模块边界测试；前端 Vitest 模拟 DOM；类型检查、构建与打包。不安排需要前后端联调，或需要启动数据库、Redis、容器、浏览器才能执行的测试；也不安排检查文件内容的测试，包括 Markdown、README、规格文件等文档，以及迁移脚本和 Mapper 的 SQL 文本中是否存在某段内容。文档和迁移脚本只作为交付物。离线结果不能作为真实约束、锁顺序、触发器、级联删除或 SQL 执行计划已生效的证据，也不能代表真实解析服务下的对齐效果。

## 1. 持久化与配置基础

- [ ] 1.1 核对迁移号后新增 V40：快照表头、快照块、变更说明、说明历史、说明命令五张表，以及 D2 列出的约束、索引和禁止修改的触发器，不改动现有表；验证：交付迁移脚本，不执行迁移，也不对 SQL 文本做断言。
- [ ] 1.2 新增 `kb.document-diff` 配置（D5 表中七项，含 `KB_DOCUMENT_DIFF_*` 环境变量覆盖）及启动校验；验证：`DocumentDiffPropertiesTest` 覆盖默认值、非正数、相似度不在 0 到 1 之间、并发为 0 时阻止启动。
- [ ] 1.3 在 `AuditScope` 新增 `DOCUMENT_CHANGE_NOTE_UPDATE`（目标类型 document-version），在 `AuditSummaryProjector` 登记 D9 的固定摘要字段；验证：扩展 `AuditSummaryProjectorTest`，覆盖前后基准、前后是否有说明、前后说明版本、visibleToReaders、reasonProvided，以及说明文本、原因、标题和未知嵌套字段不进入投影、字段类型错误时标记摘要不可用。

## 2. Worker 结构快照

- [ ] 2.1 把 `TextChunker.render` 的块文本归一化抽为共用静态方法；`ParsePipeline.ParsedDocument` 增加 `blocks` 字段并保留原构造函数；`DefaultParsePipeline` 把归一化后的非空结构块放入结果，表格块不拆行；验证：现有 `TextChunker` 测试结果不变，新增或扩展流水线测试覆盖块顺序、空白块被跳过、大表格在结果中仍为一个完整块。
- [ ] 2.2 实现 `VersionSnapshotWriter` 并接入 `ChunkPublisher.publish`：插入分块之后、`indexed` 之前删除旧快照、写表头（含 task_id 与 execution_generation）、批量写块；blocks 为空时只删除；验证：扩展 `ChunkPublisherTest` 的 InOrder 断言覆盖写入顺序、执行周期取自领取的任务、快照写入抛错时异常向上传播而不完成任务、已有发布证据时不触碰快照、删除或失租的早退路径不写快照。
- [ ] 2.3 Worker 启动时用 `to_regclass` 检查两张快照表，缺失时启动失败并提示先完成 app 迁移；验证：启动检查测试用 JDBC 替身覆盖表存在、任一表缺失两种情况。
- [ ] 2.4 在 `docs/third-party-document-parsing.md` 补充结构快照的保存时机、与分块的原子关系、不参与检索与重建，以及 Worker 启动检查；验证：交付文档，不做内容断言。

## 3. 版本对比后端

- [ ] 3.1 实现快照 Mapper：两侧表头与 D4 一致性判定在一条 SQL 中完成，块按 `block_idx` 读取；验证：`DocumentDiffMappingTest` 离线检查 BoundSql 参数绑定（块查询只绑定传入的版本 ID）和 3 秒语句超时。
- [ ] 3.2 实现 D6 对齐算法（块级 Myers、区段相似配对、移动识别、段内逐字差异与碎片合并、表格行与单元格差异、相似度计算），算法循环定期检查截止标志；验证：`BlockDiffTest`、`InlineDiffTest`、`TableDiffTest`、`DiffSimilarityTest` 覆盖改一句话、原样移动、两版相同、以较新版本为基准时方向不变、长块不做逐字比较、改一个单元格、新增一列标为 RESTRUCTURED、区段超过配对上限、相似度低于下限、截止标志触发后停止。
- [ ] 3.3 实现 D7 关键数值提取；验证：`ValueChangeExtractorTest` 覆盖“5,000 元”改为“8,000 元”、全角与千分位写法变化不报告、ADDED 和 MOVED 块中的数值不列入、“10万人”不算金额、各种日期写法、中文与阿拉伯数字条款编号、按出现顺序配对和仅前值或仅后值。
- [ ] 3.4 实现 `DiffAdmission` 与 `DiffExecutor`（固定线程池、SynchronousQueue、AbortPolicy，名额在计算真正退出时释放）；验证：`DiffExecutionTest` 用可控时钟和阻塞替身覆盖满额时立即返回 429、超时后计算未退出期间名额不释放、计算退出后名额恢复、读取阶段失败时名额立即释放。
- [ ] 3.5 实现 `DocumentDiffService`，按 D5 顺序完成鉴权、准入、只读事务读取、计算和状态优先级，附 PARSER_DIFFERS 与 TRUNCATED 提示；验证：`DocumentDiffServiceTest` 用替身覆盖普通读者对比待审版本返回 404、基准属于另一文档返回 404、撤权后返回 404、执行周期不一致或版本未索引为 SNAPSHOT_MISSING 并标明一侧、超限时为 TOO_LARGE 且不读取块、超时为 TIMEOUT 且无部分结果、依赖失败为 503 而不是空结果、状态优先级，以及日志捕获中不含正文、片段或数值。
- [ ] 3.6 实现控制器、no-store 响应过滤器和错误投影；验证：`DocumentDiffControllerTest` 覆盖 baseVersionId 缺失、重复、非整数、等于路径版本和未知参数返回 400，各状态返回 200，429 与 503 的响应形状，成功与错误均为 no-store，且路径不与既有版本接口冲突。
- [ ] 3.7 扩展 `ModuleBoundaryTest`，限定 `document/diff` 只依赖 D1 列出的服务；验证：测试通过。
- [ ] 3.8 新建 `docs/version-diff-and-change-notes.md` 的对比部分：接口字段、状态与提示、配置项与默认值、只需 view 且返回解析文本而非原件、SNAPSHOT_MISSING 的常见原因与排查顺序；验证：交付文档，不做内容断言。

## 4. 变更说明后端

- [ ] 4.1 实现说明行、历史、命令表和基准查询的 Mapper 及显式映射；验证：`ChangeNoteMappingTest` 离线检查各语句的 BoundSql 参数绑定和 3 秒语句超时。
- [ ] 4.2 实现严格输入解析：三个必需字段、text 与 reason 去首尾空白后 1–2000 个 code point、控制字符与孤立代理项、请求体 32 KiB；验证：`ChangeNoteInputTest` 覆盖每类 400 和 413、只有空白、2001 个 code point、保留换行、未知或重复字段、尾随 JSON、NUL。
- [ ] 4.3 按 D9 实现写入服务；验证：`ChangeNoteWriteServiceTest` 用替身覆盖待审写入不要求原因、待审清除说明、IN_REVIEW 返回 409、有 edit 但无候选访问返回 404、发布后首次写入不要求原因并记录发布后补写、修改已有说明缺少原因返回 REASON_REQUIRED、附原因后 corrected 为 true、清除已发布说明返回 NOTE_REMOVAL_NOT_ALLOWED、更正历史版本、归档返回 404、第一个版本返回 NO_BASE_VERSION、待审每次写入重新确定基准、已发布说明更正时基准不变、写入后另一版本先发布时基准保持 V1、匹配重放、绑定不符、撤权后重放被拒、并发冲突、NO_CHANGE，以及审计失败时不保存回执的调用顺序。
- [ ] 4.4 实现单项读取（含写入时将使用的基准）和历史分页；验证：`ChangeNoteReadServiceTest` 覆盖候选不可见和版本归属不符时返回 404、第一个版本的写入基准为 null、历史不含操作者、游标跨版本或损坏时返回 400、limit 越界返回 400、依赖失败返回 503。
- [ ] 4.5 实现控制器、no-store 过滤器和错误投影；验证：`ChangeNoteControllerTest` 覆盖全部状态码与原因码、错误响应不回显文本或原因、请求标识校验、`/change-note/history` 路径解析正确。
- [ ] 4.6 扩展 `ModuleBoundaryTest`，限定 `document/changenote` 只依赖 D1 列出的服务，且不依赖 `applicability` 包；验证：测试通过。
- [ ] 4.7 补充 `docs/version-diff-and-change-notes.md` 的变更说明部分（写入规则表、错误码、历史可见范围、基准规则与边界情况），并在 `docs/admin-audit-web.md` 登记新操作域；验证：交付文档，不做内容断言。

## 5. 网关

- [ ] 5.1 为对比与说明的新路径补充路由回归测试，确认它们走现有 kb-app 路由且网关配置不变；验证：仿照 `ReviewRouteTest` 的离线路由测试通过。

## 6. 用户端前端

- [ ] 6.1 新增 `kb-web/src/changeNotes/` 接口封装与文案；验证：`changeNotes/api.test.ts` 覆盖严格解析、ID 精确保留、未知状态码的安全处理。
- [ ] 6.2 文档详情接入只读说明面板与修改历史，读取失败显示暂不可确认；验证：`changeNotes/detail.test.ts` 覆盖没有说明、已更正、发布后补写、503、历史不显示操作者、正文按纯文本显示。
- [ ] 6.3 实现说明编辑器：待审与已发布的不同提示、首次免原因、修改须原因、清除只对待审开放、审核冻结、没有基准时的说明、REASON_REQUIRED 后重新读取、冲突与结果未确认的处理，并加入详情页写入协调；验证：`changeNotes/editor.test.ts`，并扩展 `documents/write-coordination.test.ts` 覆盖与删除互斥、切换版本后响应迟到、结果未确认时重新读取到新文本仍保留原请求标识。
- [ ] 6.4 新增 `kb-web/src/versionDiff/` 接口解析与文案；验证：`versionDiff/api.test.ts` 覆盖各状态与提示的解析、非 AVAILABLE 时忽略明细、未知操作类型被拒绝。
- [ ] 6.5 实现 `VersionComparePage` 及差异、表格、数值列表组件；验证：`versionDiff/page.test.ts` 覆盖链接 ID 非法时不发请求、404 不区分一侧、HTML 标记按文本显示、未变化块折叠与展开、修改块逐字标记、未逐字比较时并列全文、移动块、RESTRUCTURED 表格、数值列表点击定位与空列表文案、四种无法对比状态与预览入口、PARSER_DIFFERS 提示、429 手动重试不自动重发、503、说明基准一致与不一致、说明读取失败、切换版本后旧结果迟到被丢弃、切换账号后清除内容。
- [ ] 6.6 注册懒加载路由，`safeReturnPath` 白名单加入对比页路径，并在 `scripts/bundle-report.mjs` 登记新路由及 D10 的隔离规则；验证：扩展 `routing/lazy-routes.test.ts` 与 `navigation.test.ts`（含带查询串、非正整数段被拒），`npm run bundle:test` 通过。
- [ ] 6.7 文档详情版本历史新增对比入口与默认值计算；验证：扩展文档详情测试，覆盖默认基准取说明基准、没有说明时取最近已发布旧版本、只有一个可见版本时不显示入口、不可见版本不出现在选项中。
- [ ] 6.8 审核上下文接入“与当前发布版本对比”链接、只读说明与冻结提示；验证：扩展 `reviews/page.test.ts`，覆盖没有当前发布版本时不显示入口、只能看本人最小状态时不显示、说明读取失败不影响审核操作。
- [ ] 6.9 在 `kb-web/README.md` 补充对比页、变更说明和两个入口的说明；验证：交付文档，不做内容断言。

## 7. 整体核对

- [ ] 7.1 运行后端离线测试白名单和全模块 `test-compile`，把命令与结果记录到本变更的 `verification.md`；验证：白名单 0 失败、0 错误，并如实记录跳过数。
- [ ] 7.2 运行前端 `npm run typecheck`、`npm test -- --run`、`npm run build` 和包体隔离检查，确认对比页按需加载、不进入入口和文档列表；验证：命令均成功，结果记入 `verification.md`。
- [ ] 7.3 建立规格场景到代码和测试证据的映射，记入本变更的 `traceability.md`；验证：交付映射文档，其中明确列出未经真实数据库、真实解析服务、浏览器和部署验证的范围，不做内容断言。
- [ ] 7.4 更新根 README 功能概览和 `docs/development-roadmap.md` 中 F-03 的状态，注明已实现、未部署；验证：交付文档，不做内容断言。

## Workflow follow-up

- 实施和核验完成后，经用户另行确认，再同步主规格并归档本变更（`openspec archive` 默认会校验规格格式）。
- 归档后核对主规格同步结果和路线图维护记录。
