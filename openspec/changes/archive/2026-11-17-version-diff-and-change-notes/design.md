# Design

## Context

动机和已确认的决定见 [proposal.md](proposal.md)，行为契约见本变更下的七份增量规格。以下只列出影响实现方式的现状：

- **结构块只在 Worker 内存中存在。**`DefaultParsePipeline` 从 `DocumentParser` 得到 `model.ParsedDocument`（含 `DocumentBlock` 列表），交给 `TextChunker` 切块后，只把分块放进 `ParsePipeline.ParsedDocument` 交给 `ChunkPublisher`。分块按 1200 字符切、相邻重叠 200 字符，分块参数不随版本记录。
- **发布事务。**`ChunkPublisher.publish` 在一个事务内依次核对租约、锁文档和版本、删除并重写该版本的 `kb_doc_chunk` 与 `kb_parse_issue`、调用 `VersionPublicationMapper.indexed`、按策略自动发布、完成任务。已有发布证据的版本直接返回，不会被重写。
- **重新索引只有一条路径。**`kb_parse_task` 每个版本一行（`uq_parse_task_version`）。已索引版本只能通过 `CandidateIndexRefreshService` 重新处理，它把任务的 `execution_generation` 加一并把版本重置为待处理，但保留旧分块直到新结果提交。失败版本的重试和死信重放也会推进执行周期，但这些版本没有成功提交过。
- **访问规则。**`DocumentVersionAccessService.visible(handle, keys, INTERACTIVE)` 批量判定固定版本的交互访问，已包含文档未删除、空间未归档、密级、view 授权和未发布版本的参与者限制；`SourceKey(documentId, versionId)` 同时校验版本归属。
- **F-02 的写入模式。**`ApplicabilityWriteService` 的顺序是：绑定请求并加 advisory lock、`FOR UPDATE OF v` 锁版本行、重新鉴权、锁后重读目标、匹配重放、判定版本类别与审核冻结、比较版本、写入、追加历史、写审计、保存回执，全部在 READ_COMMITTED 单事务内。回执在追加写的 `kb_document_validity_command`，其 `operation_scope` 有 CHECK 约束。
- **有界执行的先例。**`SearchAdmission` 按实例计数准入，票据在实际工作退出时释放；`SearchExecutor` 使用 `SynchronousQueue` 加 `AbortPolicy` 的固定线程池。
- **前端。**页面经 `routing/lazyPages.ts` 按需加载；`navigation.ts` 的 `safeReturnPath` 用白名单正则限定登录返回地址，不接受查询串；`scripts/bundle-report.mjs` 的 `checkIsolation` 约束入口和文档列表不得包含详情专用模块。
- **迁移版本。**当前最高为 V39；网关已把 `/api/documents/**` 转发给 kb-app。

## Goals / Non-Goals

**Goals:**

- 快照随分块原子提交，读取时能可靠判断快照是否属于当前索引，不依赖部署顺序之外的人工清理。
- 对比是纯计算：读取两份快照后在 app 内存中完成，不调用外部服务，不写任何数据。
- 变更说明的写入、历史、回执和审计与 F-02 适用期同构，便于复用测试方法和运维经验。

**Non-Goals:**

- 不缓存对比结果，不预计算差异，不做后台任务。
- 不为旧版本回补快照，不从分块重建快照。
- 不新增专用指标；对比接口的耗时与状态码由既有 HTTP 请求指标覆盖。

## Decisions

### D1 模块划分

- **kb-worker**：`ParsePipeline.ParsedDocument` 增加 `blocks` 字段（原构造函数保留并传空列表）；`DefaultParsePipeline` 把解析得到的结构块放入结果；`ChunkPublisher` 在同一事务内写快照，具体写法放在新的 `VersionSnapshotWriter`，由 `ChunkPublisher` 调用。
- **kb-app**：在 `document` 下新增 `diff` 与 `changenote` 两个子包，与 `applicability` 并列，各含 web、service、mapper、model。只依赖 `DocumentVersionAccessService`、`PermissionService`、`IdentityAuditService` 等已公开服务，不依赖 `applicability` 包内部类；严格 JSON 解析和命令绑定按 `ValidityInput`、`ValidityCommands` 的写法在 `changenote` 内另行实现。
- **kb-common**：不变，`VersionAccessPurpose` 继续使用 INTERACTIVE。

**备选：**让 `changenote` 直接复用 `applicability` 的命令与输入类。没有采用：会让 `ModuleBoundaryTest` 出现子包之间的依赖，且 `kb_document_validity_command` 的 CHECK 约束需要修改（见 D2）。

### D2 V40 只新增五张表，存量不回填

| 表 | 主键 | 要点 |
|---|---|---|
| `kb_document_version_snapshot` | `version_id` | 字段：`doc_id`、`task_id`、`execution_generation`、`parser_name`、`parser_version`、`truncated`、`block_count`、`char_count`（Unicode code point）、`created_at`。外键 `(version_id, doc_id)` 引用版本，`ON DELETE CASCADE`，与 `kb_doc_chunk` 一样属于派生数据 |
| `kb_document_version_block` | `(version_id, block_idx)` | 字段：`kind`（PARAGRAPH、HEADING、LIST_ITEM、TABLE、CODE、UNKNOWN）、`text`、`heading_path`、`page_no`、`line_start`、`line_end`、`char_start`、`char_end`、`locator`、`table_cells JSONB NULL`。外键引用快照表并级联删除；`table_cells` 必须为数组，非 TABLE 块为空 |
| `kb_document_change_note` | `version_id` | 字段：`doc_id`、`text NULL`（1–2000 字符）、`base_version_id NULL`、`note_version`、`corrected`、`created_after_visible`、`updated_at`。约束：`text` 与 `base_version_id` 同为空或同非空；外键沿用 V39 的 `(version_id, doc_id)` 写法，不级联 |
| `kb_document_change_note_history` | 自增 ID | 字段：`version_id`、`doc_id`、`changed_at`、`text_before`、`text_after`、`base_before`、`base_after`、`visible_to_readers`、`reason`。约束：`visible_to_readers AND text_before IS NOT NULL` 时 `reason` 必须非空；触发器禁止 UPDATE 和 DELETE；索引 `(version_id, changed_at DESC, id DESC)` |
| `kb_document_change_note_command` | `(operation_scope, request_id)` | 结构仿照 `kb_document_validity_command`，`operation_scope` 只允许 `DOCUMENT_CHANGE_NOTE_UPDATE`；追加写，触发器禁止修改 |

- 没有说明行等同于“没有说明、版本 0”，第一次写入时插入，版本记为 1。待审阶段清除说明时保留行，`text` 和 `base_version_id` 置空，版本照常加一。
- 快照拆成表头和块两张表，是为了在不读块正文的情况下完成一致性判定和规模判定（D4、D5）。
- 块正文不另做压缩：PostgreSQL 会对超过约 2 KB 的 TEXT 值自动做 TOAST 压缩。

**备选：**修改 `kb_document_validity_command` 的 CHECK 约束以共用命令表。没有采用，理由与 F-02 D2 相同：修改已上线追加写表的约束会扩大旧二进制版本的兼容风险。

### D3 快照在发布事务内写入

`ChunkPublisher.publish` 在插入分块之后、调用 `publication.indexed(commit)` 之前：

1. `DELETE FROM kb_document_version_snapshot WHERE version_id = ?`，块随级联删除；
2. 结果的 `blocks` 为空时到此为止（只有旧测试夹具和兼容构造会这样，生产路径总有结构块，因为分块非空）；
3. 插入表头，`task_id` 与 `execution_generation` 取自本次领取的 `ParseTask`；
4. 批量插入块，`block_idx` 从 0 连续编号。

块正文的归一化与 `TextChunker.render` 相同（统一换行、合并空白、去首尾空白），抽成 `TextChunker` 与快照共用的静态方法，空白块不写入。表格块整块保存，不按分块容量拆行，单元格完整写入 `table_cells`。

快照写入失败按其他提交失败处理：整个事务回滚，任务按既有失败分类重试。快照与分块写在同一事务内，所以不会出现“有分块没快照”或反过来的已索引状态。

**Worker 启动检查：**Worker 不执行 Flyway。新 Worker 启动时用 `to_regclass` 确认 `kb_document_version_snapshot` 与 `kb_document_version_block` 存在，缺失时启动失败并给出“需先完成 app 迁移”的明确信息，避免在缺表时把每个解析任务都写成失败。

### D4 读取时用执行周期判定快照是否可用

一侧快照可用，当且仅当：快照表头存在；表头的 `task_id`、`execution_generation` 与该版本 `kb_parse_task` 当前行一致；版本 `index_status = 'indexed'`。三项在一条 SQL 中判定。

- 刷新索引会推进执行周期，所以刷新后、重新提交前，旧快照自动失效。
- 即使发生回退（旧 Worker 重新处理时只替换分块、不动快照），执行周期也已推进，旧快照不会被误用。

**备选：**在 `CandidateIndexRefreshService` 的事务里直接删除快照。没有采用：它无法覆盖“app 和 Worker 都回退到旧版本时刷新”的情况，而执行周期比较在任何部署组合下都成立，且不需要改动审核代码。

### D5 对比执行流程与资源边界

`GET /api/documents/{documentId}/versions/{versionId}/diff?baseVersionId=` 的处理顺序：

1. 严格解析查询参数（400）；
2. `DocumentVersionAccessService.visible` 一次判定两个 `SourceKey`，缺任一个返回 404；依赖失败返回 503；
3. 申请对比名额（`DiffAdmission`，按实例计数，满额返回 429 DIFF_CAPACITY）；
4. 在只读 REPEATABLE READ 事务中读取两侧表头和一致性判定，按优先级得出 SNAPSHOT_MISSING 或 TOO_LARGE；都不是时再读两侧块，保证表头与块属于同一快照；语句超时 3 秒，超时返回 503；
5. 把块交给 `DiffExecutor` 计算，请求线程最多等待 `timeout-ms`；
6. 返回结果，响应 no-store。

状态优先级：SNAPSHOT_MISSING、TOO_LARGE、TIMEOUT、LOW_SIMILARITY、AVAILABLE。

**名额与线程：**`DiffExecutor` 仿照 `SearchExecutor`，线程数等于 `max-concurrent`，`SynchronousQueue` 加 `AbortPolicy`。名额在第 3 步取得，在计算任务真正结束时释放；第 4 步失败时直接释放。计算代码每处理固定步数检查一次截止标志，超时后请求线程立即返回 TIMEOUT，计算线程在下一次检查时退出，退出前名额不释放。

**配置**（前缀 `kb.document-diff`，启动时校验，非法值阻止启动）：

| 配置 | 默认值 | 说明 |
|---|---:|---|
| `max-chars-per-version` | 500000 | 每侧 code point 上限，按表头 `char_count` 判定 |
| `max-blocks-per-version` | 10000 | 每侧块数上限，按表头 `block_count` 判定 |
| `timeout-ms` | 5000 | 计算限时，不含数据库读取 |
| `max-concurrent` | 4 | 每实例同时计算数 |
| `inline-diff-max-chars` | 20000 | 一对修改块任一侧超过时不做逐字比较 |
| `pairing-max-cells` | 250000 | 一个变化区段内“删除块数 × 新增块数”超过时不做相似配对 |
| `min-similarity` | 0.3 | 可对齐内容占比下限 |

**备选：**在前端计算差异。没有采用：需要把两侧全文都传给浏览器，限额和超时难以统一执行，以后加入 AI 摘要时也要在服务端重新实现一遍。

### D6 对齐算法

1. **块序列**：以“类型 + 归一化文本”的哈希为键，用线性空间的 Myers 算法求最短编辑序列，得到 EQUAL 与若干变化区段。不引入第三方 diff 库，因为需要在算法循环内检查截止标志。
2. **区段内配对**：每个区段中删除块与新增块按顺序做单调配对，相似度用字符二元组的 Dice 系数，类型相同且相似度不低于 0.5 才配为 MODIFIED，其余为 ADDED 或 REMOVED。区段规模超过 `pairing-max-cells` 时跳过配对。
3. **移动**：配对后剩下的 ADDED 与 REMOVED 中，键完全相同的配为 MOVED，在目标位置输出，并带基准侧块序号。
4. **段内差异**：MODIFIED 的非表格块按 code point 做 Myers 逐字差异，再合并夹在两处修改之间、长度小于 2 的相同片段，减少碎片。任一侧超过 `inline-diff-max-chars` 时只标记未提供逐字片段。
5. **表格**：两侧所有行的列数一致且相等时，以行内容为键做行级 Myers，区段内按顺序配对后逐格比较；否则标为 RESTRUCTURED。
6. **相似度**：可对齐字符数 =（EQUAL 与 MOVED 块的长度）+（MODIFIED 中相同片段的长度；未逐字比较的按 Dice 系数乘较短一侧长度估算），除以两侧总长度的较大值。低于 `min-similarity` 时返回 LOW_SIMILARITY。

### D7 关键数值提取

- **字符宽度归一化**：把全角数字、全角句点、逗号和百分号逐字符映射为半角，长度不变，因此匹配位置可以直接对应原文，展示时取原文片段。
- **四类模式**：
  - 日期：`yyyy-m-d`、`yyyy/m/d`、`yyyy.m.d`、`yyyy年m月d日`、`yyyy年m月`、`m月d日`；
  - 金额：带 `¥`、`￥`、`人民币`、`RMB` 前缀的数字，或以 `元`、`万元`、`亿元` 结尾的数字；单独的“万”“亿”不算金额，避免把“10万人”识别成金额；
  - 百分比：数字后接 `%`；
  - 条款编号：`第` 加阿拉伯数字或中文数字，再接 `条`、`章`、`节`、`款`、`项`。
- **比较**：每个匹配转为规范值（去掉千分位和空白，日期拆为年月日数字）。对每个 MODIFIED 块和修改的单元格，按类型分别求“前有后无”和“后有前无”的规范值多重集合差，按出现顺序一一配对为前值与后值，余下的列为仅前值或仅后值。
- 中文大写金额（如“伍仟元”）首期不识别，页面说明列表只含按规则识别到的数值。

### D8 响应结构

```text
{
  documentId, base: {versionId, versionNo, parserName, parserVersion, truncated},
  target: {...同上},
  status, missingSides: [BASE|TARGET], warnings: [{code, side?}],
  stats: {unchanged, added, removed, modified, moved},
  operations: [{op, kind, headingPath, base: {index, pageNo, text}?, target: {...}?,
                segments: [{type: EQUAL|INSERT|DELETE, text}]?, segmentsOmitted?,
                table: {status: CELLS|RESTRUCTURED, rows: [...]}?}],
  valueChanges: [{type, before?, after?, operationIndex, headingPath}]
}
```

状态不是 AVAILABLE 时 `operations`、`valueChanges`、`stats` 均为空。EQUAL 块同样返回正文，由前端折叠；请求大小由 D5 的上限约束。

### D9 变更说明写入

沿用 F-02 D4 的顺序：

1. 绑定请求标识，加 advisory lock 并查命令行；
2. 对目标版本行加锁 `FOR UPDATE OF v`，与送审、审批锁的是同一行；
3. 重新鉴权：文档 view 与 edit，再用 INTERACTIVE 确认能访问该版本；
4. 锁后重读目标：文档或空间已归档返回 404；匹配重放返回首次回执；
5. 判定类别：发布证据为 PUBLISHED 或 OBSERVED_CURRENT，或协议为 LEGACY_COMPAT，算“对读者可见”，否则算待审；
6. 待审且有 IN_REVIEW 轮次返回 REVIEW_IN_PROGRESS；对读者可见时，已有说明且 reason 为空返回 REASON_REQUIRED，text 为 null 返回 NOTE_REMOVAL_NOT_ALLOWED；
7. 确定基准：待审版本或当前没有说明的已可见版本，查询同文档中 `version_no` 小于目标、发布证据为 PUBLISHED 或 OBSERVED_CURRENT 的最大版本，没有时返回 NO_BASE_VERSION；已可见且已有说明时沿用原基准；text 为 null 时基准置空；
8. 比较预期版本，检查文本与基准是否都未变化（NO_CHANGE）；
9. 写说明行：版本加一；`corrected = corrected OR (可见 AND 已有说明)`；首次写入且可见时 `created_after_visible = true`；
10. 追加历史，先写审计再保存回执，失败整体回滚。

**基准查询不加锁的理由：**别的版本此时被批准发布，只会让本次写入看起来发生在批准之前，结果仍符合“写入时”的定义；批准目标版本本身则会与第 2 步的行锁串行化。

**审计摘要**只含固定字段：documentId、versionId、前后基准、前后是否有说明、前后说明版本、visibleToReaders、reasonProvided，不含文本和原因。

### D10 前端

- **对比页**：新增 `kb-web/src/versionDiff/`（接口解析、文案、`VersionComparePage.vue` 与差异、表格、数值列表组件），路由 `/documents/:documentId/compare/:baseVersionId/:targetVersionId` 经 `lazyPages.ts` 注册；`safeReturnPath` 白名单加入该路径形态（三个正整数段）。
- **变更说明**：新增 `kb-web/src/changeNotes/`（接口、只读面板、编辑器、历史）。文档详情在 `ApplicabilityPanel` 旁放说明面板；审核上下文 `ReviewContext.vue` 放只读面板和“与当前发布版本对比”链接（使用响应中的 `currentVersionId`）。
- **对比入口**：文档详情版本历史下新增两个版本选择框和“打开对比”按钮，默认值按规格计算，只使用当前可见列表。
- **写入协调**：说明保存通过 `documents/OperationContext` 的 `beginWrite` 加入既有在途与结果未确认事实，与删除互斥，规则与适用期一致。
- **包体隔离**：在 `bundle-report.mjs` 登记新路由，并在 `checkIsolation` 中要求入口和文档列表不包含 `src/versionDiff/` 与 `src/changeNotes/`。

### D11 发布顺序

1. 升级全部 app 实例并应用 V40；旧 Worker 照常工作，只是不写快照。
2. 升级 Worker；新旧 Worker 可以短暂并存，D4 保证不会误用快照。新 Worker 在 V40 之前启动会因 D3 的检查失败。
3. 发布 web。网关配置不变。

回退按 web、Worker、app 的顺序进行，保留新表，不反向迁移。旧 app 不读这些表；回退期间写入的版本没有快照或快照因执行周期推进而失效，重新升级后显示为缺少快照。

## Risks / Trade-offs

- [对比质量取决于解析器一致性] → 两侧解析器不同时提示 PARSER_DIFFERS；E-01 选定解析服务后用真实样本复核配对阈值和相似度下限。
- [存储增加] → 每个版本多存一份约等于抽取文本大小的正文；TOAST 自动压缩大文本；上线后观察表大小，必要时再评估清理或压缩策略。
- [对比占用 app CPU 和内存] → 每侧规模上限、实例并发 4、计算限时 5 秒；名额在计算真正退出后才释放，避免超时请求叠加。
- [计算线程只能协作式停止] → 算法内定期检查截止标志；数据库读取另有 3 秒语句超时。
- [响应体可能达到数 MB] → 由规模上限约束；EQUAL 块正文仍返回，以免折叠展开时再请求。
- [对比让只有 view 的用户更容易读到 Office 文件的全文] → view 本身就是阅读资格，搜索结果和引用核对已经返回分块正文；对比返回解析文本而不是原件，下载仍需 download。这一点在规格和运维文档中写明。
- [待审阶段的说明草稿在发布后通过修改历史对读者可见] → 与 F-02 适用期历史一致；编辑界面提示修改历史对可读该版本的用户可见。
- [基准可能不是实际被替换的版本] → 读取结果和页面都明确显示基准，并提供按说明基准打开对比的入口。
- [数值规则有漏报和误报] → 页面说明列表只含按规则识别的数值；正文差异始终完整展示。

## Open Questions

- 默认上限（500,000 字符、10,000 块、5 秒、并发 4）和两个阈值（配对 0.5、相似度 0.3）需要在 E-01 后用真实文档校准，均可通过配置调整，不影响规格。
- 中文大写金额和“万”“亿”单独作单位的金额是否要识别，可在收到真实样本后再决定。
