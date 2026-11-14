# 七份增量规格的离线证据映射

对应本变更 `specs/` 下全部 44 项 Requirement、104 个 Scenario。这里是人工核对的证据索引，不是文档内容断言测试；映射不表示真实数据库或部署行为已验证。

## 路径与证据类型

- `app/` = `kb-app/src/main/java/com/zhishiku/kb/app/`；后端测试位于对应的 `kb-app/src/test/java/com/zhishiku/kb/app/`。
- `common/` = `kb-common/src/main/java/com/zhishiku/kb/common/`；`gateway/` = `kb-gateway/src/main/java/com/zhishiku/kb/gateway/`，测试各在对应 `src/test/java`。
- `web/` = `kb-web/src/`；XML/SQL 路径以 `kb-app/src/main/resources/` 为根。
- 表中 `Class#method` 为实际 Java 用例；前端以实际测试文件及可定位的用例主题标识。下面引用的后端类均在 9.1 显式白名单内，前端文件均在 9.2 全量 jsdom 回归内，命令与结果见 [verification.md](verification.md)。
- **动态**：领域/服务、真实事务代理配合内存快照管理器、仓储/身份替身、MockMvc/内存 exchange、jsdom/受控模块与 Promise。
- **静态**：实际 MyBatis 配置/BoundSql/ResultSet 替身、迁移声明、编译后模块依赖、JDK AST、实际 YAML 绑定/内存路由谓词。没有 SQL 执行或应用启动。
- 涉及并发/容量/幂等的用例只验证状态序列、调用顺序、当前资格和声明；真实唯一约束、触发器、行锁、隔离级别、回滚、执行计划、迁移、旧二进制启动及部署均未验证，也不重新设为本变更隐藏待办。

## knowledge-gaps（15 项需求，33 个场景）

### 问题单必须由有效用户主动且明确分享

代码：`app/knowledgegap/web/{KnowledgeGapController,KnowledgeGapInput}.java`、`model/KnowledgeGapInputs.java`、`service/KnowledgeGapCreateService.java`。

| 场景 | 离线证据 |
| --- | --- |
| 独立提交 | `KnowledgeGapCreateTest#firstCreateReturns201AndMatchingReplayReturns200WithoutNewQuota`；`KnowledgeGapResolutionTest#explanationSubmissionWaitsForExplicitReporterConfirmation`，无模型/检索调用 |
| 隐式采集或夹带原文 | `KnowledgeGapCreateTest#creationRequiresConsentAndRejectsPrivateOrForgedPayloadFields`；`KnowledgeGapInputTest#everyParserRejectsUnknownDuplicateTrailingAndWrongRoot` |
| 用户报告零结果或故障 | `KnowledgeGapCreateTest#sourceIsOnlyInputMetadataAndEventReceiptHaveNoBody`；`web/knowledgeGaps/feedback.test.ts` 的检索故障保留、明确分享才创建 |

### 问题单必须按当前最小参与范围读取

代码：`app/knowledgegap/service/KnowledgeGapAccess.java`、`mapper/KnowledgeGapMapper.xml` 的同源 visible/validActor 片段。

| 场景 | 离线证据 |
| --- | --- |
| 改派后旧负责人访问 | `KnowledgeGapAccessTest#currentReporterHandlerAndReaderCanReadButOldAssignmentCannot`；`KnowledgeGapReceiptTest#reassignedOrReturnedHandlerCannotUseOldSuccessfulReceipt` |
| 多种身份及撤权 | `KnowledgeGapAccessTest#revocationAndReassignmentUseNewRequestWithoutHistoricalAccess`、`#onlyReporterConfirmsAndReadQualificationDoesNotSubstituteHandling`、`#identityAndPermissionFailuresRemainSafeErrors` |

### 问题列表与历史必须有界并先鉴权后分页

代码：`app/knowledgegap/service/KnowledgeGapQueryService.java`、`app/query/{QueryCursor,QueryParameters}.java`、`mapper/KnowledgeGapMapper.xml`。

| 场景 | 离线证据 |
| --- | --- |
| 稀疏可见范围 | `KnowledgeGapQueryTest#mineListUsesServerFilteredDescendingPositionAndHasAccurateLastPage`、`#actualSqlFiltersCurrentScopeBeforeFixedOrderAndLimit`；BoundSql 结构，不执行 SQL |
| 游标或筛选非法 | `KnowledgeGapQueryTest#crossActorViewAndFilterCursorsNeverReachListRepository`、`#invalidFiltersAreNotEmptyOrUnrestrictedQueries`；`QueryCursorTest` / `QueryParametersTest` |

### 分派必须是单一当前负责人且不延续历史授权

代码：`app/knowledgegap/service/KnowledgeGapAssignmentService.java`、`model/KnowledgeGapWorkflow.java`、`app/auth/service/KnowledgeGapAssigneeService.java`。

| 场景 | 离线证据 |
| --- | --- |
| 候选读取后失效 | `KnowledgeGapAssigneeTest#candidateSnapshotDoesNotAuthorizeLaterAssignment`；`KnowledgeGapAssignmentTest#invalidCandidateStaleVersionAndSameAssigneeCannotChangeBusinessFacts` |
| 待确认期间改派 | `KnowledgeGapAssignmentTest#awaitingReassignmentInvalidatesSolutionAndOldHandlersReadAccess`；`KnowledgeGapResolutionTest#reassigningAwaitingWorkMakesOriginalConfirmationInvalid` |

### 补充说明与退回受理必须受当前参与资格约束

代码：`app/knowledgegap/service/KnowledgeGapAssignmentService.java`、`model/KnowledgeGapWorkflow.java`、`web/KnowledgeGapAssignmentController.java`。

| 场景 | 离线证据 |
| --- | --- |
| 提问者补充背景 | `KnowledgeGapAssignmentTest#sharedNotesPreserveAllWorkIdentitiesWithoutNotificationIntents`；`KnowledgeGapStateTest#notesPreserveWorkIdentitiesAndReassignmentInvalidatesOldSolution` |
| 负责人退回 | `KnowledgeGapAssignmentTest#returnKeepsHistoryButClearsAssignmentAndRefusesOldHandlersReplay`；`KnowledgeGapReceiptTest#reassignedOrReturnedHandlerCannotUseOldSuccessfulReceipt` |

### 解决方案必须由当前合格负责人明确提交

代码：`app/knowledgegap/service/KnowledgeGapSolutionService.java`、`model/KnowledgeGapInputs.java`、`web/KnowledgeGapSolutionController.java`。

| 场景 | 离线证据 |
| --- | --- |
| 说明型方案 | `KnowledgeGapResolutionTest#explanationSubmissionWaitsForExplicitReporterConfirmation`，不调用文档/上传/模型 |
| 不完整或越权方案 | `KnowledgeGapInputTest#incompleteReferenceAndNonCanonicalUuidAreRejected`；`KnowledgeGapTransactionTest#lockRechecksCurrentHandlerAfterPreflightInsteadOfTrustingOldRead` |

### 文档型方案必须使用双方当前可读的固定发布版本

代码：`app/document/service/PublishedDocumentQueryService.java`、`model/PublishedDocumentReference.java`、`mapper/PublishedDocumentMapper.xml`、`app/knowledgegap/service/KnowledgeGapSolutionService.java`。

| 场景 | 离线证据 |
| --- | --- |
| 新版尚未发布 | `KnowledgeGapDocumentAccessTest#indexOrLegacyVisibilityCannotReplacePublicationEvidence`、`#mapperReusesLiveAclClearanceAndPublishedPredicatesBeforePaging`；NONE/PUBLISHED/OBSERVED_CURRENT 与 SQL 结构 |
| 提问者不能读取或已停用 | `KnowledgeGapDocumentSolutionTest#submitNeedsBothCurrentReadersAndPersistsOnlyFixedDocumentIdentity`、`#reporterInactivityPreventsDocumentSubmissionButDoesNotRewriteState` |

### 关联资料读取必须独立于问题单资格

代码：`app/knowledgegap/service/KnowledgeGapReferenceService.java`、`app/document/service/PublishedDocumentQueryService.java`、`model/KnowledgeGapViews.java`。

| 场景 | 离线证据 |
| --- | --- |
| 受理人员无文档阅读权 | `KnowledgeGapReferenceTest#operatorReadDoesNotRevealHiddenReferenceIdentityTitleOrCount`；`KnowledgeGapQueryTest#historyAndCurrentSolutionBothUseIndependentReferenceProjection` |
| 资料撤权或更新 | `KnowledgeGapReferenceTest#newPublicationDoesNotReplaceOldIdentityAndRevocationClearsProjection`、`#resolvedFactSurvivesLaterReferenceRevocation`、`#sourceFailureOrUnexpectedVersionDoesNotReturnOldSnapshot` |

### 方案资料候选必须受当前处理和文档资格约束

代码：`app/knowledgegap/service/KnowledgeGapSolutionQueryService.java`、`web/KnowledgeGapSolutionController.java`、`mapper/PublishedDocumentMapper.xml`。

| 场景 | 离线证据 |
| --- | --- |
| 处理人选择资料 | `KnowledgeGapDocumentSolutionTest#candidateCursorIsScopedAndOnlyExposesAuthorizedFixedIdentityAndTitle`；`KnowledgeGapDocumentAccessTest#unpublishedOrInconsistentCandidatePageFailsInsteadOfPostPageFiltering` |
| 旧负责人或未知游标 | 同一 candidate cursor 用例及 `KnowledgeGapAccessTest#actionPermissionsAreIndependentAndCheckedBeforeTargetReads`；前端 `web/knowledgeGaps/api.test.ts` 的固定候选 POST 路径/非法身份拒绝 |

### 只有提问者才能确认当前方案或退回继续处理

代码：`app/knowledgegap/service/KnowledgeGapSolutionService.java`、`KnowledgeGapAccess.java`、`model/KnowledgeGapWorkflow.java`。

| 场景 | 离线证据 |
| --- | --- |
| 处理资格不能代替本人确认 | `KnowledgeGapResolutionTest#otherParticipantsCannotSubstituteReporterEvenWithReadAssignOrHandling`；`KnowledgeGapAccessTest#onlyReporterConfirmsAndReadQualificationDoesNotSubstituteHandling` |
| 确认过期方案 | `KnowledgeGapResolutionTest#reassigningAwaitingWorkMakesOriginalConfirmationInvalid`、`#rejectionPreservesOriginalHandlerAndNextSubmissionGetsNewIdentity` |
| 文档型确认时来源不可读 | `KnowledgeGapDocumentSolutionTest#confirmingAfterRevocationFailsButExplicitRejectionRemainsAvailable` |

### 关闭撤回与解决必须是不同且不可重开的终态

代码：`app/knowledgegap/service/KnowledgeGapClosureService.java`、`model/KnowledgeGapWorkflow.java`、`web/KnowledgeGapClosureController.java`。

| 场景 | 离线证据 |
| --- | --- |
| 关闭无人确认的问题 | `KnowledgeGapClosureTest#qualifiedOperatorClosesWithoutReporterActivityOrPretendResolution`；`KnowledgeGapStateTest#submissionAndSolutionAreNeverAutomaticallyResolved` |
| 终态操作 | `KnowledgeGapClosureTest#allTerminalStatesRejectNewNotesAssignmentsSolutionsAndClosures`；`KnowledgeGapStateTest#completeTransitionMatrixHasOnlyExplicitLegalActions` |

### 业务记录保留必须明确告知且不扩权

代码：V37、`app/knowledgegap/model/KnowledgeGapInputs.java`、问题/auth/会话之间的生产依赖边界、`web/knowledgeGaps/{GapCreateForm,GapLifecycleActions}.vue`。

| 场景 | 离线证据 |
| --- | --- |
| 撤回与原会话删除 | `KnowledgeGapClosureTest#reporterWithdrawsWithoutErasingSharedDescriptionOrHistory`；`KnowledgeGapSchemaTest#factsAndReceiptsDoNotDuplicateBodiesOrPrivateSourceIdentities`；`ModuleBoundaryTest` 禁止问题依赖私人会话。保留/无引用为模型及静态证据，未执行真实会话删除 |
| 未知删除或编辑入口 | `KnowledgeGapControllerTest#unsupportedEditDeleteReopenMediaTypeAndQueryNeverDispatchBusinessWrites`；`KnowledgeGapSchemaTest#originHistoryBindingsAndTerminalStatesAreProtected` |

### 问题写入必须绑定载荷并重新鉴权后幂等

代码：`app/knowledgegap/service/KnowledgeGapCommandService.java`、全部命令服务、`mapper/KnowledgeGapMapper.xml` 与 V37 命令唯一键声明。

| 场景 | 离线证据 |
| --- | --- |
| 响应丢失并发重试 | `KnowledgeGapReceiptTest#normalizedCreationBindsCollectionNotGeneratedIdentityAndReplayDoesNotCompleteAgain`、`#changedActorActionTargetExpectedVersionOrPayloadConflictsWithoutReturningReceipt`；`KnowledgeGapAssignmentTest#assignmentRechecksCandidateAndProducesOneFactForMatchingReplay`。重放动态/唯一键静态，不证明数据库并发 |
| 旧负责人重放 | `KnowledgeGapReceiptTest#currentActionQualificationIsCheckedBeforeClaimAndAgainBeforeReplayProjection`、`#reassignedOrReturnedHandlerCannotUseOldSuccessfulReceipt` |
| 退回受理导致本人失去访问 | `KnowledgeGapAssignmentTest#returnKeepsHistoryButClearsAssignmentAndRefusesOldHandlersReplay`；`web/knowledgeGaps/recovery.test.ts` 的退回未知结果/404 清理 |

### 状态事实与回执必须原子且竞争只允许一个合法结果

代码：全部问题命令服务的 5 秒事务、命令/event MANDATORY 助手、`mapper/KnowledgeGapMapper.xml` 的预期版本/锁序。

| 场景 | 离线证据 |
| --- | --- |
| 确认与撤回关闭竞争 | `KnowledgeGapTransactionTest#eachSerializationOrderHasOnlyOneLegalWinner`，覆盖确认/改派/撤回/关闭两两串行顺序；不是数据库并行锁测试 |
| 事件或回执失败 | `KnowledgeGapTransactionTest#everyFailureRollsBackAllSourceRecordsAndAllowsSameRequestRetry`、`#missingWriteResultDoesNotCommit`、`#mandatoryHelpersCannotCreateIndependentTransactions`；真实 Spring 代理+快照替身，不证明数据库回滚 |

### 输入响应与容量必须保持有界及真实错误

代码：`app/knowledgegap/web/{KnowledgeGapInput,KnowledgeGapAdvice}.java`、`service/KnowledgeGapCreateService.java`、`app/config/{KnowledgeGapResponseFilter,PrivateApiResponse,GlobalExceptionHandler}.java`、`gateway/filter/KnowledgeGapResponsePolicy.java`、`common/config/ProductionSettings.java`。

| 场景 | 离线证据 |
| --- | --- |
| 并发创建达到容量上限 | `KnowledgeGapCreateTest#quotaUsesCommandThenActorLockAndRejectsAtOneHundred`；`KnowledgeGapTransactionTest#createAndOtherWritesUseCommandFirstAndOneOuterShortTransaction`；`KnowledgeGapMappingTest#bindingsTimeoutsLocksAndPaginationRemainBoundedAndUncached`。99/100、guard 锁序及声明，不证明真实并发容量 |
| 错误含敏感内容 | `KnowledgeGapInputTest` 的 64 KiB/Unicode/整数/重复键用例；`KnowledgeGapControllerTest#errorsAreFixedForEveryContractStatusWithoutRawBodyOrSql`；`KnowledgeGapLoggingTest` 全部三方法；app/gateway `KnowledgeGapResponsePolicyTest`；`ProductionSettingsTest` knowledgegap logger/group 方法 |

## functional-authorization（4 项需求，11 个场景）

### 知识问题单必须具有独立精确功能资格

代码：`app/auth/model/Permission.java`、`service/{PermissionService,RoleManagementService}.java`、`app/knowledgegap/service/KnowledgeGapAccess.java`。

| 场景 | 离线证据 |
| --- | --- |
| 目录与显式授予 | `PermissionCatalogTest`；`RoleManagementServiceTest` 的普通角色显式授予；`KnowledgeGapAssigneeTest#candidateOnlyContainsExactIdentityAndLabelWithoutDirectoryPermission` |
| 只有读取权限 | `KnowledgeGapAccessTest#actionPermissionsAreIndependentAndCheckedBeforeTargetReads`；`web/admin/knowledge-gaps-commands.test.ts` 的只有 read 不显示写操作 |
| 角色名称或其他管理资格 | `KnowledgeGapAccessTest#roleNamesWildcardsAndOtherManagementCodesDoNotAuthorize`；`web/admin/knowledge-gaps-access.test.ts` 的五种无 read 资格 |

### 问题单功能资格与当前参与访问必须同时满足

代码：`app/knowledgegap/service/KnowledgeGapAccess.java`、全部读写服务、`mapper/KnowledgeGapMapper.xml`。

| 场景 | 离线证据 |
| --- | --- |
| 写权限不授予队列 | `KnowledgeGapAccessTest#actionPermissionsAreIndependentAndCheckedBeforeTargetReads`；`KnowledgeGapQueryTest#assignedAndQueueRequireIndependentPermissionBeforeReadingList` |
| 处理资格下一次请求撤销 | `KnowledgeGapAccessTest#revocationAndReassignmentUseNewRequestWithoutHistoricalAccess`；`KnowledgeGapReceiptTest#reassignedOrReturnedHandlerCannotUseOldSuccessfulReceipt` |
| 分派与本人确认分离 | `KnowledgeGapResolutionTest#otherParticipantsCannotSubstituteReporterEvenWithReadAssignOrHandling` |

### 问题分派候选必须为受控最小身份投影

代码：`app/auth/service/KnowledgeGapAssigneeService.java`、`model/KnowledgeGapAssigneeViews.java`、`mapper/KnowledgeGapAssigneeMapper.xml`、`app/knowledgegap/service/KnowledgeGapAssigneeQueryService.java`。

| 场景 | 离线证据 |
| --- | --- |
| 人员池搜索 | `KnowledgeGapAssigneeControllerTest#postBodySearchWorksWithoutIdentityDirectoryAndUsesScopedCursor`、`#crossActorGapOrNameCursorFailsBeforePersonnelQuery`；`KnowledgeGapAssigneeTest#mybatisUsesRoleUnionAndMinimumProjectionBeforePagination` |
| 候选资格变化 | `KnowledgeGapAssigneeTest#everyEligibilityFactIsRequired`、`#candidateSnapshotDoesNotAuthorizeLaterAssignment` |

### 问题单权限初始化必须一次性且保留撤权及兼容边界

代码：V37 权限加法、`app/auth/service/{AdminBootstrapRunner,RolePermissionValidator}.java`。

| 场景 | 离线证据 |
| --- | --- |
| 首次升级与空库引导 | `KnowledgeGapSchemaTest#migrationGrantsOnlyExistingAdminOnceWithoutChangingResourceAcl`（迁移声明）；`AdminBootstrapRunnerTest#emptyDatabaseCreatesRoleDepartmentUserAndBinding`（存储替身） |
| 撤销并重启 | `AdminBootstrapRunnerTest#repeatedRunMakesNoFurtherWrites`、`#existingIncompleteAdminRoleWarnsWithoutRepairingPermissions`；`RolePermissionValidatorTest#explicitGapCodesAreAcceptedWithoutRepairingMissingAdminBindings` |
| 旧实例启动 | `RolePermissionValidatorTest#unknownCodeFailsStartupAndIsReported`、`#unknownGapCodeStillFailsStartup` 保留未知 code 拒绝机制；`docs/knowledge-gap-rollout.md` 交付旧 app 不混部/不删绑定边界。未启动旧二进制，不将机制证据当作实际旧实例验证 |

## admin-web-access（2 项需求，5 个场景）

### 知识问题受理入口必须按独立读取资格开放

代码：`web/admin/access.ts`、`routing/{routes,lazyPages,ProtectedLayout}.ts/.vue`、`admin/{KnowledgeGapQueuePage,KnowledgeGapReceptionPage}.vue`。

| 场景 | 离线证据 |
| --- | --- |
| 有独立资格而空间目录不可用 | `web/admin/knowledge-gaps-access.test.ts`：“只有 read 可加载…不依赖空间/身份目录或其他页面” |
| 只持写权限或模块已缓存 | 同文件：“只有 %s 不下载受理页面或读取队列”；`web/admin/access.test.ts` / `routing/lazy-routes.test.ts` 的缓存模块与当前资格门禁回归 |
| 页面下载期间撤权 | `web/admin/knowledge-gaps-access.test.ts`：“下载期间 read 撤销后迟到模块不能挂载或发旧目标请求” |

### 知识问题受理登录返回必须拒绝内容与未注册路径

代码：`web/navigation.ts`、`knowledgeGaps/routes.ts`、受保护路由/身份门禁。

| 场景 | 离线证据 |
| --- | --- |
| 合法详情返回 | `web/admin/knowledge-gaps-access.test.ts`：“匿名合法详情保留安全返回，身份未确认和改密时不下载或读取受理内容”及 read/无 read 直接进入用例；内存分层验证，不是完整登录联调 |
| 非法受理返回 | 同文件：“受理登录返回仅固定根及精确正整数详情，不接受名称/正文/筛选或未注册路径”；`web/navigation.test.ts` 保持旧落点 |

## admin-web-knowledge-gaps（5 项需求，10 个场景）

### 受理工作台必须使用独立读取资格和固定路由

代码：`web/admin/{access,KnowledgeGapQueuePage,KnowledgeGapReceptionPage}.ts/.vue`、`routing/ProtectedLayout.vue`。

| 场景 | 离线证据 |
| --- | --- |
| 只有受理读取资格 | `web/admin/knowledge-gaps-access.test.ts` read-only；`knowledge-gaps-commands.test.ts`：“只有 read 不能分派/关闭…” |
| 只持处理或写权限 | `knowledge-gaps-access.test.ts` 五种无 read 资格；`web/knowledgeGaps/routes.test.ts` handle-only 普通 assigned 入口 |

### 统一队列必须准确表达处理阶段与人工接管需要

代码：`web/knowledgeGaps/GapListWorkspace.vue`、`GapDetailWorkspace.vue`、`types.ts`。

| 场景 | 离线证据 |
| --- | --- |
| 负责人不再合格 | `web/admin/knowledge-gaps-pages.test.ts`：“统一队列区分全部六态，失效负责人只显示需人工接管，不泄露生命周期原因” |
| 读取故障与空队列 | 同文件：“队列 503 不是空队列，详情 read 不补充文档/目录权限”；“状态筛选由服务端执行且改变条件从首页开始…” |

### 处理人选择必须限于当前合格最小候选

代码：`web/knowledgeGaps/{GapAssigneePicker,GapReceptionActions}.vue`、`api.ts`。

| 场景 | 离线证据 |
| --- | --- |
| 无身份目录权限的分派 | `web/admin/knowledge-gaps-commands.test.ts`：“人员选择只调用问题候选 POST，改派使旧方案失效但不删除历史” |
| 候选变化及失败 | 同文件：“候选失效 409 清理选择并重读…”、“只有 read 不能分派/关闭，资格变化清理在途候选和旧对话框”；`web/knowledgeGaps/recovery.test.ts` 切单/隐藏读取隔离 |

### 分派与关闭交互必须保留版本和未知结果边界

代码：`web/knowledgeGaps/{GapReceptionActions,GapDetailWorkspace}.vue`、`commands.ts`。

| 场景 | 离线证据 |
| --- | --- |
| 待确认改派 | `web/admin/knowledge-gaps-commands.test.ts` 的人员选择/旧方案清理；“未知结果不自动重发，显式重试保留同一版本、原载荷和请求标识” |
| 提问者停用后关闭 | 同文件：“带原因关闭明确不是本人解决，关闭终态不提供处理或确认”；停用主体业务事实由 `KnowledgeGapClosureTest#qualifiedOperatorClosesWithoutReporterActivityOrPretendResolution` 单独覆盖 |

### 工作台必须隔离问题资格与资料权限及页面生命周期

代码：`web/admin/KnowledgeGapReceptionPage.vue`、共享 `GapDetailWorkspace.vue` / `GapEntryView.vue`、`reviews/lifecycle.ts`。

| 场景 | 离线证据 |
| --- | --- |
| 读问题不等于读文档 | `KnowledgeGapReferenceTest#operatorReadDoesNotRevealHiddenReferenceIdentityTitleOrCount`；`web/admin/knowledge-gaps-pages.test.ts` 只读详情不补目录；`web/knowledgeGaps/pages.test.ts` 不可用资料清理 |
| 加载中撤权或换号 | `web/admin/knowledge-gaps-access.test.ts` 迟到模块；`knowledge-gaps-commands.test.ts` 在途候选撤权；共享 `web/knowledgeGaps/recovery.test.ts` Session/epoch/切单/隐藏/迟到响应 |

## user-web-knowledge-gaps（8 项需求，18 个场景）

### 问题单入口必须独立可用并受身份保护

代码：`web/pages/KnowledgeGapsPage.vue`、`KnowledgeGapNewPage.vue`、`KnowledgeGapDetailPage.vue`、`routing/`、`navigation.ts`。

| 场景 | 离线证据 |
| --- | --- |
| AI 功能关闭 | `web/knowledgeGaps/routes.test.ts`：“AI 关闭且无管理权限仍可直达本人列表…”；非法 ID、未确认身份、改密拒绝加载用例 |
| 合格处理人访问 | 同文件：“分派给我须当前 handle…只有 handle 无 read 也可用”；`pages.test.ts` assigned 撤权清理 |

### 源页面反馈必须先预览再明确分享

代码：`web/search/SearchWorkspace.vue`、`assistant/ConversationContent.vue`、`knowledgeGaps/{GapFeedbackDialog,GapCreateForm}.vue`、`feedback.ts`。

| 场景 | 离线证据 |
| --- | --- |
| 取消反馈 | `web/knowledgeGaps/feedback.test.ts`：“搜索仅预填实际已提交的本人词…取消零创建、重开不恢复草稿” |
| 私人问答反馈 | 同文件：“已保存轮次只分享本人原问题，回答/引用/完整会话/轮次身份不进入载荷”、“来源被屏蔽也不搬运回答…”；RUNNING/临时轮次无入口及超限不裁剪 |
| 只有服务故障 | 同文件：“检索故障仍保持真实错误，用户明确分享后才创建…”；相邻 `web/search.test.ts` / `assistant.test.ts` |

### 列表详情与历史必须按服务端当前范围展示

代码：`web/knowledgeGaps/{GapListWorkspace,GapDetailWorkspace,GapHistoryList}.vue`、`hooks.ts`、`api.ts`。

| 场景 | 离线证据 |
| --- | --- |
| 分页时改派 | `web/knowledgeGaps/pages.test.ts` 的服务端游标/assigned 资格清理、“历史 404 清理整个问题…”；`recovery.test.ts` 目标/代次隔离 |
| 历史读取失败 | `web/knowledgeGaps/pages.test.ts`：“详情与独立历史有界正序分页，历史失败不伪装为空记录” |

### 处理交互必须以当前资格与当前问题状态为准

代码：`web/knowledgeGaps/{GapProcessingForm,GapDocumentPicker}.vue`、`api.ts`、`intents.ts`。

| 场景 | 离线证据 |
| --- | --- |
| 说明型方案 | `web/knowledgeGaps/pages.test.ts`：“说明型方案无需上传/模型，提交后只显示待确认，不能自动解决”；当前能力门禁/共享说明确认用例 |
| 资料候选或权限失败 | 同文件：“候选条件改变或读取失败清除旧选择…”、“资料只能从当前受控 POST 候选选择…”；`lifecycle.test.ts` 资料不可用仍可拒绝 |

### 确认解决必须是提问者针对当前方案的显式动作

代码：`web/knowledgeGaps/GapLifecycleActions.vue`、`commands.ts`、`intents.ts`。

| 场景 | 离线证据 |
| --- | --- |
| 方案被改派失效 | `web/knowledgeGaps/lifecycle.test.ts`：“旧方案确认 409 后重读新事实，清理旧对话框，不自动确认新方案”；加载/资料/打开弹窗不自动确认 |
| 用户拒绝方案 | 同文件：“资料不可用不能宣称确认成功，但仍可带原因退回方案并保留历史”；`api.test.ts` 确认/拒绝载荷与回执精确方案绑定 |

### 撤回和终态展示必须诚实说明保留边界

代码：`web/knowledgeGaps/{GapLifecycleActions,GapDetailWorkspace}.vue`、`types.ts`。

| 场景 | 离线证据 |
| --- | --- |
| 撤回成功 | `web/knowledgeGaps/lifecycle.test.ts`：“撤回需原因及明确保留确认，显示已撤回而非已删除/解决”；取消未发送操作零请求 |
| 新需求 | 同文件：“终态 %s 只读、不可重开或追加，新需求不自动复制描述”，覆盖三类终态 |

### 写入交互必须绑定未知结果并隔离迟到响应

代码：`web/knowledgeGaps/commands.ts`、`intents.ts`、`hooks.ts`、共享 workspace 与 `reviews/lifecycle.ts`。

| 场景 | 离线证据 |
| --- | --- |
| 创建响应丢失 | `web/knowledgeGaps/commands.test.ts` 原标识/冻结载荷/改变输入新绑定/同步互斥用例；`api.test.ts` 非法成功回执保持未知结果 |
| 退回后失去访问 | `web/knowledgeGaps/recovery.test.ts`：“退回结果未知只允许显式核对，404 清理原内容且不自动重发或扩权读队列” |
| 换号及后台恢复 | 同文件切目标、Session 对象、epoch、隐藏恢复、handle 撤销、迟到成功及刷新不重发用例；`feedback.test.ts` 草稿隐藏/离页清理 |

### 文本资料与问题草稿不得扩大内容传播

代码：`web/knowledgeGaps/{protocol,input,commands}.ts`、`GapEntryView.vue`、`GapCreateForm.vue`、共享作用域。

| 场景 | 离线证据 |
| --- | --- |
| 共享记录包含脚本 | `web/knowledgeGaps/recovery.test.ts`：“描述、说明和标签仅纯文本显示，不执行脚本、HTML 或加载远程图片”；`api.test.ts` 超安全整数精确解码/Unicode；刷新用例核对存储/history 无草稿 |
| 关联资料失去资格 | `web/knowledgeGaps/pages.test.ts`：“资料后续不可用清空旧标题/身份/入口，保留本人确认事实及共享人工文本”；固定版本预览替身，不实际下载 |

## user-notifications（7 项需求，20 个场景）

### 通知必须来自已提交业务事件并按收件人去重

代码：`app/knowledgegap/service/KnowledgeGapEventService.java`、`app/notification/service/{NotificationDerivationTransaction,GapNotificationDerivationTransaction}.java`、V38 与两个派生 Mapper。

| 场景 | 离线证据 |
| --- | --- |
| 重复派生与消费者重启 | `GapNotificationDerivationTest#duplicateDeliveryKeepsNotificationIdentityTimeAndFirstRead`；`ReviewNotificationContractTest#repeatedDerivationAndRestartKeepAtMostOneNotificationPerRecipient` |
| 所有者不是送审者 | `ReviewNotificationContractTest#approvalNotifiesThisRoundSubmitterNotOwnerAndPublicationIsNotAnotherReminder` |
| 回复本人或新加入审核池 | `CommentNotificationContractTest#onlyRepliesFromOthersBecomeRemindersAndCreateOrDeleteFactsCompleteSilently`；`ReviewNotificationContractTest#laterRoundIsANewFactAndLateJoinerOnlyReceivesTheNewRoundsRecordedIntent` |
| 问题单操作重放 | `KnowledgeGapAssignmentTest#assignmentRechecksCandidateAndProducesOneFactForMatchingReplay`；`KnowledgeGapReceiptTest` 匹配重放；派生重复交付用例不重置 readAt |

### 通知派生故障不得改变已提交业务

代码：`app/notification/service/{NotificationDerivationService,GapNotificationDerivationService,NotificationDerivationScheduler}.java` 及来源短事务。

| 场景 | 离线证据 |
| --- | --- |
| 部分收件人后崩溃 | `GapNotificationDerivationTest#committedPrefixSurvivesBatchFailureAndRemainingIntentCanBeTakenOver`、`#expiredExecutorCannotWriteAfterFreshClaimAndPartialProgressIsPreserved`；旧 `NotificationLeaseTest` |
| 暂时依赖故障 | `NotificationRetryTest#missingCommentResolverWaitsWithBackoffInsteadOfHotLoopingAndResumesWhenInstalled`；`GapNotificationDerivationTest#sourceNotReadyWaitsWithBackoffAndNotesCompleteWithoutNotification` |
| 问题单通知暂不可用 | `GapNotificationDerivationTest#schedulerDoesNotSkipEitherSourceWhenOtherSourceThrows`、`#mapperIsIndependentBoundedAndCannotWriteSourceStateOrReadBody`；`ModuleBoundaryTest` 禁止消费者调用问题写服务；源事务与派生隔离，不模拟真实进程崩溃 |

### 通知读取与未读数必须使用相同当前可见范围

代码：`mapper/UserNotificationMapper.xml`、`app/notification/service/{UserNotificationService,GapNotificationTargets,ReviewNotificationTargets,CommentNotificationTargets}.java`。

| 场景 | 离线证据 |
| --- | --- |
| 撤权、升密或归档 | `ReviewNotificationVisibilityTest#pendingReviewIsVisibleOnlyWhileTheSameRoundIsActionableByAnEligibleNonSelfReviewer`、`#resultIsVisibleOnlyToThisRoundSubmitterWithCurrentInteractiveRead`；`CommentNotificationContractTest#legalReplyLocatesTheFixedVersionReplyAndHiddenFactsHideIt` |
| 列表与计数依赖失败 | `UserNotificationServiceTest#dependencyFailuresAreUnavailableNotEmptyPagesOrFalseZero`；`GapNotificationVisibilityTest#dependencyFailureIsNotAFalseZeroOrEmptyMixedPage` |
| 原负责人失去问题访问 | `GapNotificationVisibilityTest#oldSolutionAndLostParticipationAreHiddenFromListAndCountTogether`、`#listAndCountIncludeTheExactSameGapAccessAndWorkRulesBeforePagination`；UNION 后过滤/分页的结构检查 |
| 关联文档不可读但问题仍可读 | `GapNotificationVisibilityTest#noDocumentViewDoesNotHideCurrentlyAuthorizedGapNotification`；`KnowledgeGapReferenceTest#operatorReadDoesNotRevealHiddenReferenceIdentityTitleOrCount` |

### 通知目标打开必须再次执行源业务授权

代码：`app/notification/model/{NotificationTarget,NotificationTargetJson}.java`、`service/*NotificationTargets.java`、`web/notifications/NotificationTargetPanel.vue`。

| 场景 | 离线证据 |
| --- | --- |
| 点击前失效或发布新版本 | `web/notifications/page.test.ts` 固定原版本/轮次/撤权清理及评论固定版本用例；后端 `CommentNotificationContractTest` 和 `ReviewNotificationVisibilityTest` |
| 点击后问题状态已变化 | `web/notifications/gap-target.test.ts` 同 gapId 404/当前 CLOSED 两分支，不自动已读/确认；共享详情当前事实说明 |

### 问题单通知必须采用确定的事件与收件人规则

代码：`app/knowledgegap/service/KnowledgeGapEventService.java`、`mapper/KnowledgeGapMapper.xml` recipient 插入、`app/notification/model/NotificationType.java`。

| 场景 | 离线证据 |
| --- | --- |
| 分派与提交方案 | `KnowledgeGapEventTest#eightKindsFixRecipientsAndExcludeActor`（八类参数化）、`#closedWithOverlappingParticipantsHasOneRecipientAndSelfOnlyIsEmpty` |
| 受理池变化 | `KnowledgeGapEventTest#laterPermissionDoesNotBackfillOldIntentAndOverlappingRolesAreDeduplicated`、`#sharedNoteRetainsFactButNeverCreatesNotificationIntent`；read 池 SQL 无 Java 无界收集 |

### 问题单待办提醒必须绑定当前有效工作而非历史参与

代码：`app/notification/service/GapNotificationTargets.java`、`mapper/KnowledgeGapMapper.xml` 的 `notificationWorkCurrent` 片段、事件分派代次/方案/队列字段。

| 场景 | 离线证据 |
| --- | --- |
| 改派及重复方案 | `GapNotificationTargetTest#queueSolutionAndTerminalTargetsNeverRepresentAnOldWorkIdentity`；`GapNotificationVisibilityTest#oldSolutionAndLostParticipationAreHiddenFromListAndCountTogether` |
| 追加说明 | `GapNotificationTargetTest#appendedNoteDoesNotInvalidateCurrentAssignmentAndReassignmentDoes`；`GapNotificationVisibilityTest#noteDoesNotInvalidateAssignmentButEpochAndOwnerChangeDo` |

### 新通知类型必须通过显式客户端能力保持旧客户端兼容

代码：`common/security/NotificationContract.java`、`app/notification/web/NotificationInput.java`、`service/UserNotificationService.java`、`gateway/filter/NotificationResponsePolicy.java`。

| 场景 | 离线证据 |
| --- | --- |
| 旧页面继续读取 | `NotificationContractTest#defaultContractKeepsLegacyTypesCountsAndOriginalCursorDigest`；`GapNotificationTargetTest#gapTargetSerializesOnlyKindAndExactGapIdWhileLegacyFiveFieldsStayIdentical` |
| 新页面混合收件箱 | `NotificationContractTest#declaredListAndCountBothReturnSameMixedContractAndExactMinimalTarget`；`GapNotificationVisibilityTest#sparseMixedPageAndUnreadUseTheSameCurrentlyVisibleUnion` |
| 契约不匹配 | `NotificationContractTest#crossContractAndCrossUserCursorsFailWithoutQueryingOrExpandingAccess`、`#unknownRepeatedCombinedOrWhitespaceHeadersAre400ForBothReads`、`#declaredReadFailureNeverRetriesAsLegacyOrReturnsFakeZero`；gateway 能力头拒绝/透传用例 |

## user-web-notifications（3 项需求，7 个场景）

### 通知跳转必须保留固定业务目标

代码：`web/notifications/NotificationTargetPanel.vue`、`NotificationList.vue`、受保护问题路由。

| 场景 | 离线证据 |
| --- | --- |
| 打开旧版回复通知 | `web/notifications/page.test.ts`：“回复通知打开固定版本的评论目标，不调用审核面板，也不改指最新版本” |
| 打开问题通知 | `web/notifications/gap-target.test.ts`：“问题通知打开相同 ID，重新鉴权/核对当前状态…无自动已读或确认” |
| 点击前被改派 | 同文件 missing=true 返回 404 清理旧正文分支；`web/knowledgeGaps/recovery.test.ts` 当前参与撤销清理 |

### 新通知界面必须一致声明支持问题单通知

代码：`web/notifications/{api,unreadPoller,unread}.ts`、`NotificationWorkspace.vue`。

| 场景 | 离线证据 |
| --- | --- |
| 新列表与未读数 | `web/notifications/api.test.ts`：“问题目标精确两字段，八类类型严格匹配，列表与计数统一能力头”；`unread.test.ts` 同 Session 共享单在途/30 秒底线/能力头 |
| 读取失败或重新登录 | `api.test.ts`：“能力头请求失败或契约游标被拒绝不去头降级…”；`page.test.ts` 账号切换迟到列表；`unread.test.ts` 隐藏/离线/身份清理及失败待确认 |

### 问题单通知必须严格解析最小目标与固定提示

代码：`web/notifications/api.ts` 的 exactKeys、判别联合和固定标签，`NotificationList.vue`。

| 场景 | 离线证据 |
| --- | --- |
| 问题通知含额外正文或外链 | `web/notifications/api.test.ts` 精确两字段/额外字段/不精确 ID/类型不一致拒绝；`gap-target.test.ts` 不渲染来源 prompt |
| 既有通知混合显示 | `web/notifications/page.test.ts`：“同页混合审核、回复和问题通知时分别匹配目标，显式已读不改变业务”；`api.test.ts` 新旧判别联合解析；`gap-target.test.ts` 新目标鉴权拒绝。后端混合页另由 NotificationContract/GapNotificationVisibility 覆盖，不称作全链路联调 |

## 横切交付与一致性复核

- 提案四项决定、八项补充/首期边界与设计 D1–D11 保持：统一人工受理、明确最小分享、独立四资格、单一当前负责人、固定已发布版本双边鉴权、只有提问者显式确认、三终态不可重开、业务记录保留、独立源共享收件箱、旧客户端能力协商。
- V1–V36 未改（KnowledgeGapSchemaTest 的既有迁移 SHA-256 基线）；新增 V37/V38。没有 Worker/AI 新业务数据访问或执行迁移职责；ModuleBoundaryTest 对问题/auth/document/notification 边界通过。
- 网关基础/dev 路由与相邻搜索/会话/审核/通知/导入路由离线通过；app/gateway no-store、严格 JSON、400/401/403/404/409/413/503、安全异常/日志/遥测证据见任务 8.1–8.3。
- 发布清单、API、使用与排障说明是正常交付，不用说明文档内容/存在性测试证明业务。app→gateway→web、旧 app 不混部、旧静态前端缺省合同兼容、只回退兼容二进制、不删数据/绑定/权限或反迁移均已写明；没有实际部署动作。
- 最终后端 640 tests、前端 1291 tests、类型检查、app/gateway 打包、web 生产构建、10 项包体脚本与实际隔离均通过；前端首次 120 秒时限终止和成功重跑分别记录，不隐去失败。
- 实施映射阶段未归档或同步主规格；2026-11-14 核验后已按用户另行授权同步七份主规格并归档，流程记录见 verification.md。不补回外部验证待办，未提交或推送代码；结构校验本身不证明运行时行为。
