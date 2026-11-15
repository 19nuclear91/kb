# 独立核验报告：knowledge-validity-and-review

最新的整改后全范围独立核验见 [第二轮报告](verify-report-2.md)；本文件保留首次核验与整改历史。

后续主规格同步与归档见[归档记录](archive-record.md)，不改变本文件的历史核验结论及未验证范围。

## 整改回归结论（2026-11-15）

**本次整改范围内，W1、W2、S1 均已关闭，没有未解决的原报告问题。** 用户已确认 W2 采用“先当前资格鉴权”，并确认更新三份相关增量规格、设计和三项整改任务；未通过提前版本比较来规避安全检查。

| 原问题 | 整改与直接离线证据 |
|---|---|
| W1 | `kb-web/src/validity/ApplicabilityPanel.vue:18–31,48–49` 父级持有原写状态和独立草稿；Editor 接收父级状态，读取分支不再销毁它。`kb-web/src/validity/detail.test.ts` 覆盖 unknown→旧值/新值/503→原内容原标识重试、关闭恢复、区块/整页放弃、删除互斥、版本/身份/强制改密/隐藏/离开后的迟到写回调 |
| W2 | 增量规格与 D5 已明确先鉴权；`kb-app/src/main/java/com/zhishiku/kb/app/document/stewardship/service/StewardshipConfirmService.java:56–67` 安全顺序保留。`StewardshipConfirmTest` 新增三项，直接验证改派先完成时 403、仍合格陈旧版本时 409、撤权/不可访问和匹配旧请求不能返回回执；原确认先完成时 409 测试保留 |
| S1 | `kb-web/src/admin/StewardshipBox.vue:18–27,48–49` 将写状态和原 action 保留在区块父级，关闭/重读不等于放弃，恢复后按原内容原标识重试。`kb-web/src/admin/stewardship-box.test.ts` 覆盖完整关闭/恢复/重读、显式放弃、删除互斥和身份/目标隔离 |

`kb-web/src/validity/useValidityWrite.ts` 同步整页显式放弃与身份/隐藏清理；`kb-web/src/admin/hooks.ts` 增加 discard，丢弃本地旧回调但保留在途请求按实际结果结算。没有把取消前端请求当成撤回服务端写入。

最新复跑：后端 **25 类/218 项通过**，前端 **122 文件/1343 项通过**，均无失败/错误/跳过；全八模块编译、类型检查、构建、包体工具 **11 项**及隔离检查通过。严格规格校验、`git diff --check` 通过，CLI 确认 **37/37、all_done**。最新数量、时间、命令与中途失败修复见 [verification.md](verification.md) 末节，场景映射见 [traceability.md](traceability.md)。

**Not verified 保留**：真实数据库效果和完整动态跨层场景仍未验证；没有执行迁移、数据库/Redis/容器/浏览器/联调或部署，也没有同步主规格或归档。本节是整改实现与离线回归补记，不冒充新的全范围独立核验，不宣布所有真实场景通过或可归档。

## 原独立核验记录（整改前，保留历史）

下文的两项 WARNING、一项 SUGGESTION、215/1322 数量及代码行号属于整改前快照，不是当前未解决问题或本次执行数量。

日期：2026-11-15。工作流：`spec-driven`。核验对象为当前未提交工作区；本报告独立于实施记录 `verification.md`。本轮未修改业务实现、任务或规格，未同步主规格、执行迁移、部署或归档。

## 结论与评分卡

**已执行的检查中未发现 CRITICAL；发现 2 项 WARNING、1 项 SUGGESTION。不能据此宣布全部验收通过或可归档：Requirement Implementation Mapping 的真实数据库效果、Scenario Coverage 的完整动态覆盖仍为部分 Not verified。** 这些限制描述本报告的证据边界，不新增归档前置条件。

| 维度 | 结果 |
|---|---|
| 完整性 Completeness | Task Completion：34/34，未缺失任务描述或跟踪文件；Spec Coverage：九份规格的 42/42 项 ADDED 要求均找到实现/交付位置，未发现整体缺失实现 |
| 正确性 Correctness | 42 项要求、110 个场景已逐项结合实现与测试审查；存在 W1、W2。Requirement Implementation Mapping（真实持久化、约束、锁与权限 SQL 效果）部分 **Not verified**；Scenario Coverage（完整跨层场景及真实环境）部分 **Not verified**，不能把组合证据计作动态通过 |
| 一致性 Coherence | Design Adherence：D1–D11 的静态结构与编排基本遵循，W2 是规格与 D5 鉴权优先顺序的冲突；Code Pattern Consistency：目录、公开服务边界、具体 import、控制流大括号、既有写工具和懒加载结构已检查，S1 建议利用已有绑定保留模式 |

本变更没有 REMOVED/MODIFIED/RENAMED 要求。proposal、design、tasks、九份规格均已读取；没有因不可读或有意跳过而漏检的规划产物。CLI 的 `all_done` 只作为任务状态，不作为正确性结论。

## 本轮独立复跑

全部仅离线运行：内存/Mock、事务代理、MyBatis BoundSql、控制器和模拟 DOM；未启动 app、gateway、数据库、Redis、容器、浏览器或开发服务器。

| 检查 | 本轮结果 |
|---|---|
| 后端明确白名单 | 25 类、215 项通过；kb-common 1 类/98 项，kb-app 22 类/115 项，kb-gateway 2 类/2 项；失败、错误、跳过均为 0 |
| 八模块 `test-compile` | 退出码 0，不执行全量后端测试 |
| 前端全量 Vitest | 122 文件、1322 项通过，20:39:39 开始，187.48 秒；未排除测试 |
| 前端类型检查、生产构建 | 均退出码 0 |
| 包体报告工具 | 11 项通过；`--check-isolation` 退出码 0 |
| OpenSpec 严格校验 | `Change 'knowledge-validity-and-review' is valid` |
| `git diff --check` | 退出码 0；只有 Windows LF→CRLF 提示，没有空白错误 |
| 临时离线问题复现 | 前端 1 文件/2 项、后端 1 类/1 项均成功复现下述现存行为；不是修复后的通过证据，不加到白名单/全量数量中 |

白名单只统计本轮相应 Surefire XML（20:39:51–20:40:05），不累计旧报告或临时复现类。白名单命令与实施记录相同，但以上数量来自本轮实际执行：

```powershell
mvn -o -q -pl kb-app,kb-gateway -am test '-Dtest=ProductionSettingsTest,BusinessDateTest,ApplicabilityStateTest,StewardshipScheduleTest,ApplicabilityMappingTest,ApplicabilityInputTest,ApplicabilityWriteServiceTest,ApplicabilityReadServiceTest,ApplicabilityBatchQueryTest,ApplicabilityControllerTest,ValidityTransactionTest,StewardshipMappingTest,StewardshipCandidateTest,StewardshipAssignmentTest,StewardshipConfirmTest,StewardshipQueryTest,StewardshipControllerTest,AuditScopeTest,AuditSummaryProjectorTest,ModuleBoundaryTest,DocumentVersionAccessServiceTest,VersionAccessPurposeTest,AuditQueryServiceTest,ValidityRouteTest,ReviewRouteTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
mvn -o -q test-compile '-DskipTests' '-Dstyle.color=never'
```

从 `kb-web` 执行 `npm test -- --run`、`npm run typecheck`、`npm run build`、`npm run bundle:test` 和 `npm run bundle:report -- --check-isolation --out .bundle-analysis/current-report.json`。入口累计 gzip 132845 字节，本人页面 153545 字节，增量 20700 字节；页面不在入口静态闭包，普通/管理隔离检查通过，不代表实际首屏性能。

临时前端复现配置最初用 `mergeConfig` 合并 include，意外带入全量入口，在 120 秒被终止，不能算通过；已改为覆盖 include 后定向重跑，最终 20:50:48 开始、2.41 秒完成，两项均成功复现。临时复现源文件和配置在核验后删除，不修改正式测试。Byte Buddy/JVM/Spring logging 提示仍保留，没有忽略测试或修改依赖来隐藏提示。

## 实现与证据覆盖

路径简称仅用于下表：

- **A**：`kb-app/src/main/java/com/zhishiku/kb/app/document/applicability/`。
- **S**：`kb-app/src/main/java/com/zhishiku/kb/app/document/stewardship/`。
- **AX/SX**：`kb-app/src/main/resources/mapper/applicability/ApplicabilityMapper.xml`、`kb-app/src/main/resources/mapper/stewardship/StewardshipMapper.xml`。
- 对应后端测试位于 `kb-app/src/test/java/com/zhishiku/kb/app/document/applicability/` 与 `stewardship/`。
- **直接**：测试直接驱动生产纯函数、服务/事务代理、控制器或组件，但依赖仍为替身；**组合**：代码分支和相邻测试共同支持，未独立驱动完整场景；**交付**：仅人工核对交付存在，不断言文档或迁移 SQL 文本。

| 规格（要求/场景） | 各要求的实现定位 | 证据判定 |
|---|---|---|
| document-applicability（11/34） | 独立版本声明：AX:58–85；读取日期状态：A `service/BusinessDate.java:14–38`、`model/ApplicabilityState.java:13–30`；当前版本访问：`service/ValidityAccess.java:33–60`；候选冻结、发布更正：`service/ApplicabilityWriteService.java:48–87`；不可改历史：AX:93–105 与 V39 交付；严格输入：`support/ValidityInput.java:40–120`；绑定冲突：`service/ValidityCommands.java:37–63`；原子提交：WriteService:42–97；逐项批量授权：`service/ApplicabilityReadService.java:78–108`；有界安全失败：`web/ValidityAdvice.java:25–47`、`web/ValidityResponseFilter.java:14–27` | 日期、输入、访问/重放、乐观冲突、编排和故障回滚有直接离线证据；上传不继承、撤回重送审、真实发布/召回为组合；历史触发器仅交付，实际拒绝修改/删除 **Not verified** |
| document-stewardship（14/36） | 单责任人、不扩大授权：SX:74–94、117–125；每次资格：SX:4–17、100–108；管理员提交：S `service/StewardshipAccess.java:26–48`、`StewardshipWriteService.java:45–88`；最小管理投影：`StewardshipQueryService.java:45–60`；最小候选与游标：`StewardshipCandidateService.java:27–44`；周期日期：`model/StewardshipSchedule.java:10–39`；读取状态：`model/StewardshipState.java:13–28`；本人确认：`StewardshipConfirmService.java:42–83`；本人清单：QueryService:63–87；精确计数：QueryService:90–99、SX:117–138；空间清单：QueryService:102–129、SX:140–163；删除/归档/搬迁保留：Access、SX 当前文档/空间条件；严格绑定原子提交：三服务事务与 A ValidityCommands；有界失败：StewardshipInput、ValidityAdvice/ResponseFilter | 周期、指定/取消、身份拒绝、清单投影、游标、事务故障有直接离线证据；资格 SQL、归档恢复/搬迁、计数减少由组合/BoundSql 支持，真实效果 **Not verified**；竞争反向顺序见 W2 |
| audit-query（2/4） | 操作域与既有范围：`kb-app/src/main/java/com/zhishiku/kb/app/auth/service/AuditScope.java:116–118`、`IdentityAuditService.java:54–69`；固定投影：`kb-app/src/main/java/com/zhishiku/kb/app/audit/service/AuditSummaryProjector.java:141–147` | 摘要、无自由文本与重放不重复有直接测试；新事件在既有固定窗口/权限查询中的接入为组合，不是实际持久审计查询实验 |
| user-web-documents（4/10） | 详情/版本历史标识：`kb-web/src/pages/DocumentDetail.vue:66,200–209`、`src/validity/ApplicabilityPanel.vue:25–36`；独立编辑与回执：`src/validity/ApplicabilityEditor.vue:16–38`、`src/validity/api.ts:149–163`；操作协调：`src/documents/operations.ts:25–53`；搜索批量标识：`src/search/SearchWorkspace.vue:34–35,119–126` | 标识故障、迟到隔离、删除互斥有直接/组合证据；前端历史完整分页为组合。结果未确认的完整重读路径有 W1，不算该场景通过 |
| user-web-conversations（2/5） | 固定版本批量引用标识：`kb-web/src/assistant/ConversationContent.vue:39–41,229–235`、`src/validity/useValidityBatch.ts:7–15`；模型不感知与隔离：`src/ui/References.vue:19–21`、ConversationContent 的 quarantine/卸载、会话父级 key | 引用组件、原版本/未知身份/故障与代次有直接/组合证据；真实 SSE 和模型生成未执行，不以原助手全量回归代替动态 SSE 场景 |
| user-web-document-review（1/3） | `kb-web/src/reviews/ReviewContext.vue:65–69` 只读适用期、冻结提示、审核按钮独立 | 审核页面故障不阻断操作与无写入口有直接测试；真实审核人权限上下文为组合 |
| user-web-document-stewardship（4/8） | 服务端分页：`kb-web/src/stewardship/StewardshipPage.vue:18–41`；共享数量：`src/stewardship/count.ts:6–9`、`poller.ts:19–43`；显式确认及未确认：`ConfirmationDialog.vue:10–20`；身份/视图迟到隔离：StewardshipPage:23–27、共用 useCursorList | 空/失败、原标识重试、切视图及数量暂停有直接离线证据；改派后续页、完整身份生命周期和浏览器事件为组合，未单独端到端验收 |
| admin-web-documents（3/7） | 管理清单开放：`kb-web/src/admin/DocumentPanel.vue:30–32,91–96`；候选、显式写入：`StewardshipBox.vue:20–45`、`StewardPicker.vue:12–19`；删除互斥：`DeletionBox.vue:14,22–24` 与 DocumentOperations | 最小显示、归档、四类写入及删除互斥有直接/组合测试；关闭未知对话框恢复原重试存在 S1；管理超时、候选失格的完整页面实验为组合 |
| admin-web-spaces（1/3） | `kb-web/src/admin/SpaceDetail.vue:33,42`、`SpaceStewardship.vue:15–25`，ID 级清单、四标签、404 清除重核 | 切标签/游标、失败不空、404 清除及不补标题有直接测试；父级管理资格重核由相邻页面测试组合支持 |

对 `traceability.md` 的 110 个映射均已核对其实现和证据口径；映射不是核验结论。组合场景没有被提升为独立动态通过；V39 与文档只作为交付物，未增加文本断言。

## 问题清单

### CRITICAL：0

未发现未完成任务或整体缺失要求实现。不把未执行真实数据库/浏览器验收升级为本轮禁止范围内的实施任务。

### WARNING：2

#### W1：未确认的适用期保存经“重新读取核对”后丢失原绑定，可未经放弃提交新标识

- 依据：`openspec/changes/archive/2026-11-15-knowledge-validity-and-review/specs/user-web-documents/spec.md:34–36` 只允许原标识重试、重读或放弃。
- 根因：`kb-web/src/validity/ApplicabilityPanel.vue:29–33` 把编辑器放在 `query.data` 分支内；`@verify="query.reload"` 触发 `kb-web/src/useResource.ts:11` 清空数据，卸载编辑器。`kb-web/src/validity/ApplicabilityEditor.vue:12` 未提供父级 `WriteBindings`，`kb-web/src/admin/hooks.ts:67–74` 清掉本地绑定；重读返回后 editing 仍为 true，重新挂载的是 idle 编辑器。Editor:15、21 只检查自身 pending/删除独占，不检查仍存于 DocumentOperations 的旧未知写入。
- **直接离线复现**：初始 OPEN_ENDED/v1 → 编辑为 DATE/2026-12-31 并填写原因 → transport 返回网络未知 → 点击“重新读取核对”，服务器仍返回旧声明 → 原重试入口消失、保存按钮重新可用、operations 仍为 uncertain → 再提交同一日期/原因，第二次请求 ID 与首次不同。没有点击“放弃核对”，也没有任何确认回执。
- 影响：重读并非放弃，也并非写入回执，却丢失幂等恢复路径，允许把同一未决意图当成新操作；同样在重读失败时丢失原重试入口。
- **建议修复**：在 Panel 或文档上下文按 epoch/固定版本保留原 `WriteBindings`，传入 Editor；同目标重读不删除未决绑定，也不以版本变化/读取结果推断写成功。只有确认、明确拒绝或显式放弃才释放，身份/目标离开按规格清理。补充 `kb-web/src/validity/detail.test.ts` 的“unknown→重读旧值/新值/503→仍仅原标识重试或放弃”整区块测试，不只 Mock 写 hook。

#### W2：确认与改派竞争的错误码要求，与当前鉴权优先顺序不一致

- 依据：`openspec/changes/archive/2026-11-15-knowledge-validity-and-review/specs/document-stewardship/spec.md:195–197` 要求同版本竞争失败方为 409 STEWARDSHIP_VERSION_CONFLICT。
- 实现：`kb-app/src/main/java/com/zhishiku/kb/app/document/stewardship/service/StewardshipConfirmService.java:56–65` 先检查当前责任人/资格，再比较版本。如果改派先成功，原责任人的竞争确认在版本检查之前返回 403 FORBIDDEN。
- **直接离线复现**：先指定 A（v1），管理员以 v1 改派 B 成功（v2），再执行等待后以 v1 继续的 A 确认；生产服务返回 FORBIDDEN/403，关系保持 B/v2 且无确认日期。仅复现合法串行化顺序，不证明真实数据库竞争。
- `kb-app/src/test/java/com/zhishiku/kb/app/document/stewardship/StewardshipConfirmTest.java:82–87` 只覆盖确认先成功的顺序。现有测试通过不能证明两种顺序均符合 409 要求。
- **建议协调契约**：保留“先当前资格鉴权、再返回回执/版本”的安全规则，明确规格允许失去责任人资格时先返回 403/404，仅仍合格但版本陈旧时返回 409；同步核对 `design.md:99–102` 与相关场景，并为两种先后顺序分别增加断言。如必须坚持总是 409，应先明确不会泄露不可访问文档状态的实现方案，不能简单把版本检查移到鉴权之前。

### SUGGESTION：1

#### S1：管理对话框关闭后的未知写入只保留阻塞事实，无法恢复原标识重试

- `kb-web/src/admin/StewardshipBox.vue:42` 关闭即丢弃 action，未传 `bindings`；`kb-web/src/admin/AdminModal.vue:7–10` 只阻止 busy，允许 uncertain 时关闭。卸载清掉本地绑定，但 DocumentOperations 保留未知状态，StewardshipBox:20 又因此禁止重新打开。
- **直接离线复现**：修改周期发生网络未知 → 关闭对话框 → 原重试入口消失，修改周期仍禁用，operations 仍 unknown。整页 `kb-web/src/admin/DocumentPanel.vue:90` 提供显式放弃，故未认定为完全无法恢复或数据错误。
- **建议**：采用 `kb-web/src/admin/hooks.test.ts:157–175` 已验证的父级 WriteBindings 保留模式，允许同身份/同目标重新打开“待核对”对话框；或在关闭前明确要求用户选择放弃，避免把普通关闭隐含成只能放弃的恢复路径。补充 `kb-web/src/admin/stewardship-box.test.ts` 的 unknown→关闭→重开→原 ID 重试测试，并保持身份变化清理及删除互斥。

## Not verified 与最终判断

本报告的适用但部分未验证检查为：

1. **Requirement Implementation Mapping（真实数据库效果）**：没有执行 V39、JDBC 日期映射、外键/检查约束、不可修改触发器、真实隔离/锁/回滚、资格 SQL、查询计划或索引效果。BoundSql 只证明映射展开与绑定，不证明 SQL 可执行或 Java/SQL 等价。
2. **Scenario Coverage（完整动态覆盖）**：组合证据未覆盖的完整跨层链路仍未独立验收；上传/发布、撤回重送审、搬迁/归档恢复、资格生命周期、实际 count 减少、真实 SSE/模型、真实浏览器和多实例竞争均未执行。W1、W2 是现存偏差，不因模拟测试能复现而算要求通过。

部署顺序、旧二进制混部、资产原子发布及回退也未演练；这些作为设计方案阅读，不是部署成功证据。没有对 README、Markdown 或迁移 SQL 文本做测试断言。

**最终判断：已执行检查未发现 CRITICAL，2 项 WARNING 建议先处理，另有 1 项 SUGGESTION。Requirement Implementation Mapping 与 Scenario Coverage 的上述部分 Not verified；不宣称“全部检查通过”或“可归档”。本轮仅核验与报告，业务修复、规格协调、同步和归档均未执行。**
