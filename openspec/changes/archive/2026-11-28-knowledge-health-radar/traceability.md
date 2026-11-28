# 场景与实施证据

本表人工整理五份增量规格的场景证据，不是文档内容测试。直接表示针对场景的离线断言；组合表示多个离线测试与实现检查共同覆盖；真实环境限制见 [verification.md](verification.md)。所有路径相对仓库根目录。

## 代码入口

- 后端以 `kb-app/src/main/java/com/zhishiku/kb/app` 为根，分别位于 `controller/health`、`service/health`、`service/impl/health`、`mapper/health`、`enums/health` 和 `domain/{req,dto,vo}/health`；实际查询 `kb-app/src/main/resources/mapper/health/HealthMapper.xml`。
- 复用：`AdminQueryAccess`、`SpaceQueryService`、`StewardshipState`、`ApplicabilityState`、`AuthorizationRules.xml`、`StewardshipMapper.xml` 及 `BusinessClockMapper`。
- 前端：`kb-web/src/admin/healthApi.ts`、`healthQuery.ts`、`KnowledgeHealthPage.vue`、`SpaceDetail.vue`；路由位于 `routing/routes.ts`、`routing/lazyPages.ts`，返回路径在 `navigation.ts`。
- 缓存与预算：app 的 `HealthResponseFilter`、`HealthStatementBudget`，gateway 的 `AiQueryCacheFilter`。

## knowledge-health-radar

| 规格场景 | 离线证据与实现 | 范围 |
|---|---|---|
| 仅有空间管理资格 | HealthAccessTest.exactPermissionAndCurrentPasswordStatePrecedeTargetRead | 直接 |
| 仅有雷达读取资格 | HealthAccessTest.managedSpaceAndArchiveAreIndependentFromContentScope | 直接 |
| 下一页前撤权 | HealthReadTest.cursorRefreshesFactsAndRechecksAccessInsteadOfFreezingOldPage | 直接 |
| 未发布首版与归档文档 | HealthFactsTest.rawScopeExcludesArchivedDeletedMovedAndPreservesUnpublishedUnassigned | 内存直接；实际 SQL 未验证 |
| 空间在打开页面后归档 | HealthAccessTest、healthQuery.test.ts 归档恢复 | 组合 |
| 文档搬迁或删除 | HealthFactsTest 原始范围事实；HealthMapper 目标/状态条件 | 内存直接；实际 SQL 未验证 |
| 取消责任人后原到期日已过 | HealthRulesTest.priorityRetainsOldDatesWithoutInventingOverdueOrPrivateReasons | 直接 |
| 原责任人失去维护资格 | HealthRulesTest 同上、现有 StewardshipQueryTest | 组合 |
| 责任人恢复合格 | HealthRulesTest.priorityRetainsOldDatesWithoutInventingOverdueOrPrivateReasons 恢复后重新命中复审逾期 | 直接 |
| 可管理但不能读取 | HealthAccessTest 空 view 范围；HealthFactsTest 非可读事实；healthApi.test.ts null 日期分支 | 组合；实际 ACL SQL 未验证 |
| 历史版本已届满但新版未声明 | HealthFactsTest.confirmationDoesNotRemoveExpiryAndNewVersionDoesNotInheritOldDates | 内存直接 |
| 候选版本可被管理员本人审核 | HealthFactsTest.currentPublishedReadableVersionIsRequiredAndAclMultiplicityDoesNotMultiplyDocuments；发布共享谓词 | 内存直接；实际发布 SQL 未验证 |
| 未来才生效或明确长期有效 | HealthRulesTest.undeclaredOpenEndedAndFutureEffectiveAreNotProblems | 直接 |
| 当天、次日及三十天边界 | HealthRulesTest.reviewAndApplicabilityUseInclusiveThirtyDayBoundary；ApplicabilityStateTest/StewardshipScheduleTest | 直接 |
| 北京时间跨日 | HealthReadTest.unionCountsAndFilteredSparsePageUseOneBusinessDate | 直接 |
| 一份资料命中两类 | HealthReadTest 同上及 HealthRulesTest.countPrecisionAndUnionAreNotAFloatOrSimpleSum | 直接 |
| 按单类查看第二页 | HealthReadTest 筛选和游标两项测试；汇总和分页共用 HealthSelection | 组合 |
| 大整数及真实零 | HealthRulesTest 数量精度、HealthReadTest 空结果、healthApi.test.ts | 直接 |
| 单类筛选中的多原因文档 | HealthReadTest.unionCountsAndFilteredSparsePageUseOneBusinessDate | 直接 |
| 只有适用期信号 | HealthFactsTest 确认后仍届满 | 直接 |
| 管理信息不能补全标题 | HealthRulesTest JSON 投影、healthPages.test.ts 管理入口 | 直接 |
| 适用期可见集合稀疏 | HealthReadTest、HealthFactsTest 含前置不合格事实的单项页 | 组合；实际 SQL 分页未验证 |
| 游标复用于其他范围 | HealthRulesTest.cursorBindsEveryContextAndDateWithoutExtendingAuthorization | 直接 |
| 日期已经变化 | HealthRulesTest 游标跨日、healthQuery.test.ts 跨日恢复 | 直接 |
| 查询期间复审确认提交 | HealthBudgetTest 真实事务代理配置；HealthReadServiceImpl 只读一致事务 | 配置/编排证据；真实并发隔离未验证 |
| 两次请求间改变当前版本 | HealthFactsTest 新版替换旧日期 | 内存直接 |
| 管理员查看逾期项 | healthPages.test.ts 原管理入口；既有复审确认资格不变 | 组合；未执行联调 |
| 处理后再次查询 | HealthFactsTest 确认保留适用期原因 | 内存直接 |
| 任一必要查询失败 | HealthReadTest.failureNeverBecomesEmptyAndDoesNotReadClockBeforeAuthorization；HealthAccessTest 阅读范围依赖异常；HealthBudgetTest 失败回滚及最终预算到期 | 直接；真实数据库回滚未验证 |
| 客户端传入全局或归档参数 | HealthQueries 白名单；HealthRulesTest.parametersAndIdsAreStrict | 组合 |
| 网关提前拒绝 | AiQueryCacheFilterTest.healthRadarSuccessAndEveryFailureRemainPrivate | 直接 |
| 响应结构与身份精度 | HealthRulesTest JSON、HealthMappingTest 实际映射、HealthControllerTest 成功包装与完整错误码、healthApi.test.ts 首页与分页一致性 | 直接 |
| 最后一页 | HealthReadTest 筛选单项及空页；healthApi.test.ts 游标结构 | 直接 |

## admin-web-knowledge-health

| 规格场景 | 离线证据与实现 | 范围 |
|---|---|---|
| 只有查看资格的空间 | healthPages.test.ts 空间选项过滤 | 直接 |
| 直接访问目标 | healthQuery.test.ts 准入；lazy-routes.test.ts 独立门禁 | 组合 |
| 切换器目录暂时失败 | healthPages.test.ts 目录失败隔离 | 直接 |
| 同一文档两项原因 | healthPages.test.ts 并集展示；healthApi.test.ts | 直接 |
| 适用期数量为零 | 页面两种范围提示；严格 counts 解码与零结果测试 | 组合 |
| 日期含义 | 页面独立标签与说明、healthApi.test.ts 日期语义 | 组合 |
| 切换筛选 | healthQuery.test.ts 筛选首页与故障重试 | 直接 |
| 加载下一页 | healthQuery.test.ts 下一页替换及中止 | 直接 |
| 从处理页面返回 | hook 卸载取消及新挂载默认首页，原处理入口组件测试 | 组合 |
| 可管理但不可读 | healthPages.test.ts 仅管理入口 | 直接 |
| 固定版本入口 | healthPages.test.ts 固定预览路径与拒绝处理 | 直接；真实文件预览未验证 |
| 固定预览期间失去阅读资格 | healthPages.test.ts 模拟撤权；healthQuery.previewDenied | 组合 |
| 责任人不合格 | HealthRulesTest 最小身份投影及页面只读显示 | 组合 |
| 旧服务不支持 | healthApi.test.ts 旧端点404/405与协议内404区分、未知协议/缺字段/HTML 错误；healthQuery 无旧接口回退 | 直接/组合 |
| 后端返回 503 | healthQuery.test.ts 故障遮蔽及显式重试 | 直接 |
| 成功空页与空空间 | HealthReadTest 空结果、healthPages.test.ts 翻页空态保留汇总及返回首页、所选类别无项目与范围无信号分别展示 | 直接 |
| 跨午夜继续翻页 | healthQuery.test.ts 跨日后首页 | 直接 |
| 管理资格撤销 | healthQuery.test.ts 404 重核及 403 重挂载拒绝 | 组合 |
| 请求未完成时切换账号 | healthQuery.test.ts 换号后迟到拒绝、换目标迟到成功及换号/改密清理 | 直接 |
| 页面隐藏后恢复 | healthQuery.test.ts 隐藏/恢复先重核 | 直接 |

## functional-authorization

| 规格场景 | 离线证据与实现 | 范围 |
|---|---|---|
| 查询及授予新权限 | PermissionCatalogTest、HealthAccessTest、现有角色权限目录 | 组合 |
| 原权限不能替代 | HealthAccessTest 精确资格测试 | 直接 |
| 多角色撤权 | HealthAccessTest.roleUnionIsRebuiltAndDoesNotRestoreRevocation | 直接 |
| 有功能资格但无目标管理资格 | HealthAccessTest 目标范围测试 | 直接 |
| 可管理而无内容阅读权 | HealthAccessTest 空阅读范围、HealthFactsTest 授权日期过滤 | 组合 |
| 雷达资格不能代替复审确认 | 雷达无写入接口、原确认流程保留、页面无确认操作 | 实现/组合；真实调用链未联调 |
| 存量首次迁移 | V46 交付并人工审阅一次性绑定范围 | 仅脚本；未执行迁移 |
| 空库引导及撤权后重启 | AdminBootstrapRunnerTest、HealthAccessTest 重建权限服务 | 进程内直接；未启动应用 |
| 不识别目录的旧版本 | RolePermissionValidatorTest 未知 code 拒绝；运维兼容说明 | 组合；未运行旧二进制 |

## admin-web-access

| 规格场景 | 离线证据与实现 | 范围 |
|---|---|---|
| 只有空间管理权限 | lazy-routes.test.ts 雷达专用懒加载门禁 | 直接 |
| 有功能资格而目标不可管理 | healthQuery.test.ts 目标拒绝，不发诊断 | 直接 |
| 页面下载期间撤权 | lazy-routes.test.ts 下载期间撤权，迟到模块不挂载 | 直接 |
| 合法雷达登录返回 | healthApi.test.ts safeReturnPath；既有 ProtectedLayout 登录重定向 | 组合 |
| 带筛选或非法目标 | healthApi.test.ts 目标范围与查询/片段拒绝 | 直接 |
| 只撤销雷达权限 | lazy-routes.test.ts；space-pages.test.ts 原复审入口保留 | 组合 |
| 从空间 A 切换到 B | healthQuery.test.ts 换目标取消及迟到拦截 | 直接 |

## admin-web-spaces

| 规格场景 | 离线证据与实现 | 范围 |
|---|---|---|
| 合格管理员进入雷达 | space-pages.test.ts 健康雷达入口 | 直接 |
| 缺少独立雷达资格 | 同一测试撤销功能权限后清除入口，原复审概况保留 | 直接 |
| 归档与取消归档 | 新入口归档分支及既有空间取消归档重核回归 | 组合 |
| 管理资格被撤销 | 原空间详情不可管理分支回归，入口嵌于 allowed 分支 | 组合 |
| 从雷达返回后处理 | 原页面链接、现有 SpaceDetail/DocumentPanel 重新读取规则 | 实现/组合；未执行真实联调 |
