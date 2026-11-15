# 规格场景、实现与离线证据映射

日期：2026-11-15。覆盖本变更九份增量规格的 **110 个场景**。实际命令、最终数量和修复过程见 [verification.md](verification.md)。表格是追踪映射，不表示 110 个场景都已在真实环境动态验收。

## 证据口径与路径

- **直接**：对应测试直接驱动生产纯函数、服务/事务代理、控制器或组件分支；依赖仍为内存/Mock/模拟 DOM。
- **组合**：对应分支由所列实现及相邻测试共同支持，没有独立完整场景实验；不能写成真实端到端通过。
- **交付**：迁移或非目标/未改动边界，仅交付和人工核对；不测试文档或迁移 SQL 文本。
- MyBatis **BoundSql** 检查加载生产 XML、展开片段并检查参数，不连接数据库、不执行 SQL。

后端实现路径：

- **A**：`kb-app/src/main/java/com/zhishiku/kb/app/document/applicability/`；**S**：同级 `stewardship/`。下文服务名均位于各自 `service/`，模型位于 `model/`，控制器位于 `web/`。
- **A-XML / S-XML**：`kb-app/src/main/resources/mapper/applicability/ApplicabilityMapper.xml`、`mapper/stewardship/StewardshipMapper.xml`；命令 Mapper 在 `mapper/applicability/ValidityCommandMapper.xml`。
- **A 测试**：`kb-app/src/test/java/com/zhishiku/kb/app/document/applicability/`；**S 测试**：同级 `stewardship/`，包括各表所列 `*Test.java`。
- **A-read / A-write**：ApplicabilityReadService / ApplicabilityWriteService；**S-query / S-write / S-confirm / S-access / S-candidate**：StewardshipQueryService / StewardshipWriteService / StewardshipConfirmService / StewardshipAccess / StewardshipCandidateService。
- 审计：`kb-app/src/main/java/com/zhishiku/kb/app/auth/service/AuditScope.java`、`audit/service/AuditSummaryProjector.java`、`AuditQueryService.java`；测试在对应 `kb-app/src/test/java/` 包下。
- 日志：`kb-common/src/main/java/com/zhishiku/kb/common/config/ProductionSettings.java` 和对应 `ProductionSettingsTest.java`。
- 路由：`kb-gateway/src/test/java/com/zhishiku/kb/gateway/config/ValidityRouteTest.java`、`ReviewRouteTest.java`；网关生产配置未改。
- 前端实现与测试以下均从 **`kb-web/`** 起写出路径。

## document-applicability（34）

| 规格场景 | 实现 | 证据与边界 |
|---|---|---|
| 升级前已有的版本 | A-XML `rowSelect` 无行默认值；A-read | 直接：ApplicabilityReadServiceTest `missingDeclarationReturnsVersionZeroAndReviewFreezeWithoutInventedDates`；迁移不回填为交付 |
| 新版本不继承旧日期 | A-XML 按精确版本 LEFT JOIN；上传/发布未写新表 | 组合：ApplicabilityMappingTest `batchOnlyExpandsSuppliedPairsAndEmptyBatchFailsClosed`、版本无声明读取；真实上传/发布未执行 |
| 修改不影响发布与召回 | ApplicabilityWriteService 仅写声明、历史、审计/回执 | 交付/组合：写入调用顺序测试、ModuleBoundaryTest；发布、搜索和 AI 协议未改，无真实召回实验 |
| 截止日当天与次日 | A-model `ApplicabilityState` | 直接：ApplicabilityStateTest 共用 CSV，当天 EXPIRING_SOON、次日 EXPIRED |
| 即将届满的 30 天边界 | 同上 | 直接：同测试，第 30/31 天边界 |
| 已发布但尚未生效 | 同上 | 直接：同测试，NOT_YET_EFFECTIVE 优先 |
| 只声明其中一项 | `ApplicabilityDeclaration`、`ApplicabilityState` | 直接：同测试，生效/截止单项声明 |
| 明确长期有效 | 同上 | 直接：同测试，OPEN_ENDED 不届满 |
| 业务日期跨越 UTC 日界 | A `BusinessDate`、`ValidityProperties` | 直接：BusinessDateTest `databaseInstantIsConvertedOncePerRequestEvenAcrossMidnight`，请求一次取钟 |
| 无候选访问资格 | A `ValidityAccess.version` → DocumentVersionAccessService INTERACTIVE | 直接：ApplicabilityReadServiceTest 隐藏候选；组合：DocumentVersionAccessServiceTest、VersionAccessPurposeTest |
| 版本不属于路径中的文档 | A-XML 绑定 documentId/versionId；A-read | 直接：ApplicabilityReadServiceTest 目标缺失 404；BoundSql：ApplicabilityMappingTest 精确组合，不执行跨文档 SQL |
| 审核中冻结 | A-write 锁版本后再读 `target` | 直接：ApplicabilityWriteServiceTest `hiddenCandidateFrozenReviewAndArchivedTargetsNeverWrite` |
| 撤回后修改再送审 | A-write 当前 IN_REVIEW 判断；审核流程未改 | 组合：候选非冻结写入测试和冻结拒绝；未实际执行撤回→重送审流程 |
| 有 edit 但无候选访问 | `ValidityAccess.editable` 先核验精确版本访问 | 直接：A-write 隐藏候选 404；组合：A-read 和 VersionAccessPurposeTest |
| 提交前版本已经发布 | A-write 锁等待后重新读取发布证据 | 直接：ApplicabilityWriteServiceTest `publicationRaceRequiresReasonAndAllPublishedEvidenceAllowsImmediateHistoricalCorrection`；真实锁等待竞争未验证 |
| 缺少原因 | A-write REASON_REQUIRED | 直接：同测试，PUBLISHED、OBSERVED_CURRENT、LEGACY_COMPAT |
| 原因只有空白或超长 | A-support `ValidityInput.text` | 直接：ApplicabilityInputTest `reasonIsTrimmedButNeverTruncatedAndCountsUnicodeCodePoints` |
| 需审空间中更正 | A-write 不调用审核/空间发布策略写入 | 组合：发布证据更正及审计顺序测试；AUTO/REVIEW_REQUIRED 真实空间未配置 |
| 更正历史版本 | A-write 按目标版本，corrected 永久 OR | 直接：发布证据更正测试使用 current=false；BoundSql 仅更新目标版本 |
| 所属空间已归档 | A-write target.spaceArchived → 404 | 直接：归档目标拒绝测试 |
| 读者查看更正记录 | A-read `history`；A-XML `changeRow` 不含操作者 | 直接：ApplicabilityReadServiceTest `historyUsesBoundedCursorAndRechecksAccessOnEveryPage`；BoundSql：ApplicabilityMappingTest 历史显式投影 |
| 尝试修改或删除历史 | V39 不可修改触发器；无历史写改/删除接口 | **交付**：迁移未执行、触发器拒绝效果未验证，没有 SQL 文本断言 |
| 截止日早于生效日 | A-support ValidityInput、ApplicabilityDeclaration | 直接：ApplicabilityInputTest `enforcesDateRangeKindAgreementOrderAndIntegerVersion` |
| 截止类型与日期不一致 | 同上 | 直接：同测试，DATE/null 和 OPEN_ENDED/日期 |
| 响应丢失后重试 | A `ValidityCommands`；A-write 当前资格后重放 | 直接：ApplicabilityWriteServiceTest `matchingReplayDoesNotWriteAgainAndRevocationOrChangedBindingRejectsReplay`、ValidityTransactionTest |
| 同一标识用于不同载荷 | `ValidityCommands.bind/lock` | 直接：A-write 匹配重放/不同绑定测试 |
| 撤权后重放 | A-write 重放前 `access.editable` | 直接：同测试，撤销 edit 后拒绝，不返回回执 |
| 并发修改 | 版本行锁、乐观版本和受影响行数检查 | 直接：A-write `competingRequestBasedOnSameVersionCannotCommitAfterFirst`；内存序列化竞争，非真实并发锁证据 |
| 审计写入失败 | READ_COMMITTED 注解、先审计后回执 | 直接：A-write 审计失败不调用回执；ValidityTransactionTest 故障后快照恢复和原请求重试；非数据库回滚 |
| 混合可见性 | A-read batch 逐项访问后只查询 visible | 直接：ApplicabilityBatchQueryTest `invisibleItemsAreOmittedAndCurrentIsBoundToExactVersionWithoutAnyWrite` |
| 已被取代的版本 | A-XML current 与精确版本匹配；Badge.of | 直接：同测试，旧版本 current=false、自身 EXPIRED |
| 超限或重复 | A-support sources；A-read batch 前置校验 | 直接：ApplicabilityBatchQueryTest `emptyOversizedRepeatedAndInvalidPairsAreRejectedBeforeAnyReads`；前端 api.test.ts 0/重复及分批 |
| 依赖故障 | A-read、BusinessDate、ValidityAdvice 固定 503 | 直接：ApplicabilityBatchQueryTest、BusinessDateTest、ApplicabilityControllerTest 全原因码；单项底层故障通过 Advice 组合验证 |
| 原因包含敏感文本 | ValidityAdvice 固定消息；审计固定字段；生产 SQL 日志禁用 | 直接：ApplicabilityControllerTest 不回显原因、AuditSummaryProjectorTest、ProductionSettingsTest；真实日志平台未接入 |

## document-stewardship（36）

| 规格场景 | 实现 | 证据与边界 |
|---|---|---|
| 上线后的存量文档 | S-query 无行返回 UNASSIGNED/0/null | 直接：StewardshipQueryTest `unassignedWinsOverOverdueAndNeedsReassignNeverContainsReason`；无 owner 回填为交付 |
| 责任人身份不扩大访问 | S-XML stewardEligible + 当前调用者 view；不修改授权 | 组合：StewardshipCandidateTest 资格片段、StewardshipQueryTest、StewardshipConfirmTest 撤权；非实际 ACL 改写 |
| 责任人离职或停用 | S-XML 当前启用/主部门/改密/view/edit；S-query 不合格状态 | 组合：候选 BoundSql、NEEDS_REASSIGN 无原因投影、确认资格失效；没有真实生命周期联调 |
| 资格恢复 | S-query 每次 eligible；无永久拒绝缓存 | 组合：StewardshipQueryTest 不合格读取、StewardshipConfirmTest eligible 从 false 恢复；完整恢复清单未独立实验 |
| 候选在提交前失去资格 | S-write 锁后 `eligible` | 直接：StewardshipAssignmentTest `candidateLossAndMissingManagementOrArchivedAndDeletedTargetsNeverChangeAssignment` |
| 只有 edit 没有 manage | S-access `manager` → findManaged 当前空间 | 直接：同测试管理资格缺失 404 替身；真实 edit/manage 组合未执行 |
| 管理员指定本人 | S-write 候选不排除调用者 | 直接：StewardshipAssignmentTest `managerCanAssignSelfAndNoChangeOrStaleVersionHasNoSuccessEvent` |
| 提交内容与当前一致 | S-write NO_CHANGE | 直接：同测试，无第二次成功写入 |
| 需重新指定的文档 | S-query 最小 Detail，保留 responsible | 直接：StewardshipQueryTest 原显示名、无原因字段投影 |
| 无身份目录读取资格 | S-access 只核验 manage；S-candidate 最小候选 | 直接：StewardshipCandidateTest `managerWithoutIdentityDirectoryPermissionCanReadMinimalEligibleCandidates` |
| 不合格用户 | S-XML stewardEligible 共用 viewGrant/editGrant | BoundSql：StewardshipCandidateTest `candidateQueryRequiresEditInAdditionToViewAndEnoughClearance`；不执行 SQL 用户过滤 |
| 游标用于其他文档 | S-candidate `ValidityCursor.context` 绑定用户/文档/名称/limit | 直接：StewardshipCandidateTest `cursorsCannotCrossActorDocumentQueryOrPageSize` |
| 月末日期 | S-model StewardshipSchedule.plusMonths | 直接：StewardshipScheduleTest、StewardshipAssignmentTest 2026-11-30→2027-02-28 |
| 改派不重新计时 | S-write schedule.assign 保留基准 | 直接：StewardshipAssignmentTest 指定/改派/取消/再指定链路 |
| 取消后再次指定 | S-XML update 不写 first_assigned_on | 直接：同链路；真实不可变约束未执行 |
| 修改周期 | StewardshipSchedule.assign 按最近确认基准重算 | 直接：StewardshipAssignmentTest `cycleChangeUsesLastConfirmationAndMatchingReplayReauthorizesButNeverWritesAgain` |
| 到期日当天与次日 | S-model StewardshipState | 直接：StewardshipScheduleTest 共用 CSV，当天 DUE_SOON、次日 OVERDUE |
| 未指定优先于逾期 | 同上；S-query 保留旧日期 | 直接：StewardshipScheduleTest、StewardshipQueryTest 取消后原到期日 |
| 编辑者代为确认 | S-confirm 仅当前合格 responsible | 直接：StewardshipConfirmTest `onlyCurrentResponsibleCanConfirmAndManagementOrEditDoesNotSubstitute` |
| 提前确认 | S-confirm today、schedule.confirm | 直接：StewardshipConfirmTest `earlyConfirmationRecalculatesDueDateAndPreservesFirstAssignment` |
| 改派后旧页面确认 | S-confirm 锁后 current responsible | 直接：StewardshipConfirmTest `reassignmentAndRevocationRejectOldResponsibleEvenForMatchingReplay` |
| 发布新版本 | 发布流程未调用复审服务 | 组合：StewardshipConfirmTest 文档事实变化不写确认；真实发布未执行 |
| 只因适用期届满而列出 | S-XML attention + S-query mineItem 提醒原因 | 直接：StewardshipQueryTest `applicabilityOnlyAttentionAndNoCurrentVersionAreDistinctAndReassignmentChangesNextPage`；SQL 条件只 BoundSql 验证 |
| 没有当前发布版本 | S-XML LEFT JOIN；S-query currentVersion=null | 直接：同测试，无当前标识只按复审提醒 |
| 改派后 | S-XML f.responsible_user_id=actor 当前过滤 | 组合：同测试下一次返回空页及 AssignmentTest 改派；真实数据库联动未执行 |
| 游标属于其他用户或视图 | S-query mine cursor context | 直接：StewardshipQueryTest `cursorsBindUserAndViewAndManagementMustBeRecheckedOnEverySpacePage` |
| 确认后数量减少 | S-confirm 重算 due；S-XML mine/count 共用 WHERE | 组合：提前确认测试 + StewardshipMappingTest 共用 WHERE；未实际执行 SQL count 减一 |
| 从未设置的文档 | S-XML LEFT JOIN + UNASSIGNED；S-query SpaceItem | 直接/组合：StewardshipQueryTest 未指定空间页；BoundSql：StewardshipMappingTest |
| 读取过程中失去管理资格 | S-query 每次 management.space | 直接：StewardshipQueryTest 次页管理拒绝 404 |
| 搬迁后责任人缺少 edit | 搬迁未写复审表；资格片段使用当前 d.space_id | 组合：资格 SQL 绑定与 NEEDS_REASSIGN 查询；真实搬迁和日期保留未动态实验 |
| 取消归档后已逾期 | 不重写 due；S-XML 本人过滤归档、管理可读 | 组合：StewardshipQueryTest 归档管理读取与本人 SQL 排除、纯函数逾期；真实取消归档未执行 |
| 归档空间中指定责任人 | S-access write → SPACE_ARCHIVED | 直接：StewardshipAssignmentTest 空间归档拒绝；确认归档拒绝见 ConfirmTest |
| 旧责任人重放确认 | S-confirm 重放前重新检查 responsible/eligible | 直接：StewardshipConfirmTest 改派/撤权后原回执不返回 |
| 确认与改派并发 | 共享文档锁、复审锁与乐观版本；锁等待后先当前资格，再版本比较 | 直接：StewardshipConfirmTest `confirmationAndAssignmentBasedOnSameVersionCannotBothSucceed`、`assignmentWinningBeforeConfirmationReauthorizesBeforeCheckingStaleVersion`、`stillEligibleResponsibleGetsConflictForAStaleNewRequestButMatchingReplayKeepsItsReceipt`、`revocationAndInaccessibleTargetRejectBeforeVersionOrReceiptDisclosure`；按用户确认的先鉴权契约覆盖确认先完成时 409、改派先完成时 403、不可访问时 404；内存串行化顺序，非真实锁验证 |
| 清单依赖故障 | S-query null/非法投影拒绝；ValidityAdvice 503 | 直接：StewardshipQueryTest 失败不为空、精确计数；StewardshipControllerTest、ProductionSettingsTest；无真实故障平台 |
| 周期不在档位内 | S-support StewardshipInput + Schedule.requireCycle | 直接：StewardshipControllerTest 周期 1/9、未知/重复参数/413；StewardshipScheduleTest |

复审全部写入的事务补充证据：ValidityTransactionTest 覆盖首次指定与确认在审计/回执失败时恢复全部快照，原请求可再次成功且匹配重试不新增事件。Mapper 使用三秒超时、FOR SHARE/FOR UPDATE 及候选最小列投影由 StewardshipMappingTest 检查，不代表实际数据库超时和锁已经生效。

## audit-query（4）

| 规格场景 | 实现 | 证据与边界 |
|---|---|---|
| 按操作域查询复审确认 | AuditScope targetType、AuditQueryService 精确过滤与 AUDIT_READ | 组合：AuditScopeTest、AuditQueryServiceTest 精确过滤/固定窗口/当前资格；仅审计资格，不访问文档或空间，未单独查询真实新事件 |
| 重试不产生重复事件 | A/S-write 重放在审计之前返回 | 直接：写入匹配重放测试、ValidityTransactionTest 事件/回执数量 |
| 已发布版本更正 | AuditSummaryProjector 固定 DATE/BOOL 字段 | 直接：AuditSummaryProjectorTest 适用期前后日期及 reasonProvided，无原因正文 |
| 载荷混入名称 | 固定摘要白名单、异常降级 | 直接：AuditSummaryProjectorTest 显示名/标题/嵌套字段不进入投影；ProductionSettingsTest 防 SQL 日志绕过 |

## user-web-documents（10）

| 规格场景 | 实现路径 | 证据与边界 |
|---|---|---|
| 当前发布版本尚未生效 | `src/pages/DocumentDetail.vue`、`src/validity/ApplicabilityPanel.vue` | 直接：`src/validity/detail.test.ts` 起始日、旧版提示；不自动切换 |
| 查看已更正版本的历史 | `src/validity/ApplicabilityHistory.vue`、`api.ts.history` | 组合：detail.test.ts 历史入口；后端历史分页和无操作者测试，完整前端分页交互未独立实验 |
| 标识读取失败 | ApplicabilityPanel、版本表批量标识 | 直接：detail.test.ts 故障与版本迟到隔离；recovery.test.ts 文件操作在标识故障下保持原规则 |
| 版本在编辑时已经发布 | ApplicabilityEditor → Panel rejected 重读 | 直接：`src/validity/editor.test.ts` REASON_REQUIRED 发拒绝/无成功；组合：Panel 重新读取逻辑 |
| 并发冲突 | Panel rejected 清空编辑并重读 | 组合：editor.test.ts 拒绝分支、后端乐观冲突测试；无真实两个浏览器竞争 |
| 结果未确认 | `src/validity/ApplicabilityPanel.vue` 父级写状态/原草稿、`useValidityWrite.ts`、WriteNotice | 直接：`src/validity/detail.test.ts` 使用真实组件及共用写工具，重读旧声明/新声明/503 后仍以原精确载荷与原标识重试；关闭恢复、区块/整页显式放弃、删除独占和取消恢复；不 Mock 写 hook，不自动重发 |
| 切换版本后响应迟到 | DocumentDetail key、Panel 父级 useValidityWrite、useAdminWrite discard/代次 | 直接：write-coordination.test.ts 卸载忽略写回调；detail.test.ts 版本、身份、强制改密、页面隐藏与离开时清草稿并忽略迟到写回调；另保留迟到读取测试 |
| 结果包含已届满资料 | `src/search/SearchWorkspace.vue`、ApplicabilityBadge | 组合：`src/search/page.test.ts` 顺序不变、`src/validity/labels.test.ts` EXPIRED 截止日、api.test.ts 批量精确版本 |
| 标识读取失败 | SearchWorkspace 单独 unavailable | 直接：search/page.test.ts 503 不移除结果、不重发搜索 |
| 新搜索后旧标识迟到 | `src/validity/useValidityBatch.ts`、ValidityBatch generation | 直接：search/page.test.ts 新搜索及未知版本不查询；api.test.ts 迟到/身份代次 |

## user-web-conversations（5）

| 规格场景 | 实现路径 | 证据与边界 |
|---|---|---|
| 实时回答引用已届满版本 | `src/assistant/ConversationContent.vue` → `src/ui/References.vue` | 直接/组合：`src/validity/references.test.ts` 引用 EXPIRED；实际 SSE 未执行，流协议未改 |
| 历史回答引用已被取代的版本 | `src/ui/TurnCard.vue` 传原引用标识映射 | 直接：references.test.ts current=false 标注、原版本日期 |
| 引用版本未知 | `src/validity/api.ts.sourcePair`、References | 直接：references.test.ts 版本未知、不推断；api.test.ts 精确 ID |
| 标识读取失败 | 独立 ValidityBatch、无标识仍渲染正文/引用 | 直接：references.test.ts 503 保留引用；组合：原 `src/assistant.test.ts` 全量回归，无真实模型调用 |
| 切换会话后标识迟到 | ConversationPanel 身份/会话 key、ValidityBatch.clear | 直接：references.test.ts 迟到标识丢弃；组合：ConversationContent 状态清理。引用旁模型不感知说明同测试直接覆盖 |

## user-web-document-review（3）

| 规格场景 | 实现路径 | 证据与边界 |
|---|---|---|
| 审核人查看待审版本 | `src/reviews/ReviewContext.vue` → readonly ApplicabilityPanel | 组合：`src/reviews/page.test.ts` 活动审核上下文、detail.test.ts 原日期/状态；不替代真实审核人权限实验 |
| 送审者想修改日期 | ReviewContext readonly + 冻结说明 | 直接：reviews/page.test.ts 不出现编辑入口，不发送适用期写入 |
| 适用期读取失败 | ReviewContext 标识故障独立 | 直接：reviews/page.test.ts 503 不禁用通过发布；command-lifecycle.test.ts 和 office/legacy-preview.test.ts 在标识故障下保留原审核/预览规则 |

## user-web-document-stewardship（8）

| 规格场景 | 实现路径 | 证据与边界 |
|---|---|---|
| 没有需要处理的资料 | `src/stewardship/StewardshipPage.vue` attention/all | 直接：`src/stewardship/page.test.ts` 成功空清单；`src/navigation.test.ts`、`src/routing/lazy-routes.test.ts` 直接路由/合法返回/身份门禁 |
| 读取失败 | StewardshipPage、`src/admin/hooks.ts.useCursorList` | 直接：page.test.ts 空/503 区分；旧身份清理由 useCursorList 及页面代次组合支持 |
| 续页时文档已不再归属本人 | useCursorList 只追加返回项 | 组合：共享游标工具回归、后端 StewardshipQueryTest 改派后新读取；没有独立该页改派续页实验 |
| 切换账号 | `src/stewardship/count.ts`、poller.ts | 直接：`src/stewardship/poller.test.ts` 身份变化清旧数；99+/零/? 直接覆盖 |
| 页面进入后台 | poller running/pause/resume | 直接：poller.test.ts 后台清除、迟到丢弃、恢复先 refresh；实际浏览器事件未执行 |
| 响应超时 | `src/stewardship/ConfirmationDialog.vue` + useAdminWrite | 直接：page.test.ts 未确认不自动重发、原标识重试 |
| 提交前已被改派 | ConfirmationDialog 403/404/409 → rejected→重读 | 组合：后端 ConfirmTest 改派拒绝、页面 rejected 处理及共享写工具拒绝测试；没有独立全页改派提交实验 |
| 切换视图时旧请求返回 | StewardshipPage key + useCursorList 取消 | 直接：page.test.ts 旧视图迟到不能覆盖新视图；确认 key 清理为组合证据 |

## admin-web-documents（7）

| 规格场景 | 实现路径 | 证据与边界 |
|---|---|---|
| 只有密级权限 | `src/admin/DocumentPanel.vue` owner 控制 StewardshipBox | 组合：`src/admin/space-pages.test.ts` 管理清单空不开放、`src/admin/access.test.ts` 密级入口不读取文档；代码分支控制复审请求 |
| 责任人已不合格 | `src/admin/StewardshipBox.vue` | 直接：`src/admin/stewardship-box.test.ts` 原显示名、需重新指定、无停用原因 |
| 区块读取失败 | StewardshipBox 独立 useAdminResource | 直接：stewardship-box.test.ts 503 不触发其他重核；space-pages.test.ts 管理面板回归 |
| 候选在提交前失去资格 | ResourceWriteDialog → rejected 重读、StewardPicker | 组合：四类写入显式确认测试、后端 AssignmentTest STEWARD_INELIGIBLE；错误固定提示见 validity/api.ts |
| 结果未确认 | `src/admin/StewardshipBox.vue` 父级写状态与原 action、ResourceWriteDialog、useValidityWrite | 直接：`src/admin/stewardship-box.test.ts` 使用真实组件及共用写工具，网络未知后关闭/恢复、重读旧信息/新信息/503、原内容原标识重试、区块/整页显式放弃、删除互斥，以及文档/身份/强制改密/隐藏/离开后忽略迟到回调；没有真实浏览器实验 |
| 所属空间已归档 | StewardshipBox archived 只读 | 直接：stewardship-box.test.ts 只读、不查询候选、不显示写入口 |
| 责任人写入在途时删除 | `src/documents/operations.ts`、`src/admin/DeletionBox.vue` | 直接：space-pages.test.ts 在途/未确认禁用删除、显式放弃释放；stewardship-box.test.ts 删除独占禁改派、取消恢复 |

## admin-web-spaces（3）

| 规格场景 | 实现路径 | 证据与边界 |
|---|---|---|
| 切换标签 | `src/admin/SpaceStewardship.vue` 状态 key | 直接：`src/admin/space-stewardship.test.ts` 四标签、新首页、丢弃旧 ID/游标；不补标题，归档只读 |
| 读取失败 | SpaceStewardship 独立 Feedback | 直接：space-stewardship.test.ts 503 不显示空清单 |
| 失去管理资格 | SpaceStewardship revoked 清理 + SpaceDetail 重核 | 直接：space-stewardship.test.ts 续页404清旧清单、发 recheck；组合：space-pages.test.ts 既有空间管理重核 |

## 跨规格补充与未验证清单

- 2026-11-15 核验整改：W1、S1 的原绑定生命周期与 W2 的鉴权优先契约已补齐直接离线证据；规格仍为九份/42 项要求/110 个场景。最新执行为后端 218 项、前端 1343 项；原核验记录与关闭状态见 `verify-report.md`，完整命令和失败修复过程见 `verification.md`。

- `src/validity/api.test.ts`：严格日期/类型、精确大整数、每批不超过 100、去重、身份与请求代次；`src/validity/labels.test.ts` 覆盖全部状态文案。
- `kb-web/scripts/bundle-report.test.mjs` 与生产 manifest/模块映射：stewardship 页面独立统计、不进入入口静态闭包，普通/管理双向交叉内联检查；不证明实际网络首屏性能。
- 新 HTTP 路径和 no-store：ApplicabilityControllerTest、StewardshipControllerTest 和 ValidityRouteTest；未启动 app 或 gateway。主体状态/强制改密沿用既有全站拦截器，控制器测试的会话为替身，不是真实 Redis 会话验收。
- 三秒查询、资格 SQL、日期 CSV、锁范围等静态配置与绑定证据，不证明 SQL 可执行性、Java/SQL 真等价、真实锁顺序、约束、触发器或执行计划。
- **数据库迁移与并发、Redis/对象存储、真实浏览器、前后端联调、SSE/真实模型、发布与回退全部未执行**。迁移及说明文档仅交付，不做文本断言。上线前检查见 `docs/knowledge-validity-review.md`；不因完成或归档而视为这些检查已通过。
