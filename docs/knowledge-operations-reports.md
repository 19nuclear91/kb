# 知识库运营报表

## 授权和部署

`GET /api/admin/reports/operations` 与 `/admin/reports/operations` 只按当前有效主体所有角色中的
`operations:reports:read` 精确授权，每次请求重新确认用户、主部门和改密状态。该资格读取固定全局汇总，
不逐文档求交 ACL，也不授予文档 view、资源明细、私有审核意见、评论正文或 AI 用量／审计读取资格。
管理权限、ROLE_ADMIN 名称和 AI 权限均不能代替它。AI 用量继续使用既有页面和独立 `ai:usage:read`。

部署前确认审核、通知、评论前置变更已实施并同步，源业务事实及连续覆盖元数据可靠。
排空旧 app 后才应用 V26 权限及索引迁移：只向当时存在的 ROLE_ADMIN 一次性绑定新 code，其他角色不隐式获得。
空库正式引导创建新管理员角色时使用完整目录；重复启动或引导不恢复既有角色撤权，不改资源 ACL。
先部署后端查询，再开放前端入口。本次仅交付脚本与说明，未执行迁移或部署。

不认识新 code 的旧 app 仍必须拒绝启动，禁止新旧 app 混部或直接换回旧二进制。
回退先撤下入口或禁用新读取部署，保留权限绑定、业务事实和索引；二进制回退需维护窗口和确认过的兼容版本，
不能靠删除权限绑定或忽略未知 code 绕开兼容约束。

## OPERATIONS_V1 指标

响应只含 `definitionVersion/asOf/from/to/dayTimeZone/inventory/review/comments/days`。
所有整数、样本数及总毫秒为规范十进制字符串，平均毫秒为两位小数字符串。不得转成浮点数展示。

| 指标 | 口径 |
| --- | --- |
| documentCount | 当前快照中非 deleted 文档，一篇一次，不按版本或 chunks 放大 |
| archivedDocumentCount | 上述文档中空间 archived 或文档 archived 的并集，一篇一次 |
| versionCount | 非 deleted 文档的全部版本，包括候选、失败及非当前版本 |
| formats | 以版本持久 file_name 的末尾扩展名忽略大小写，固定 pdf/txt/md/doc/docx/xls/xlsx/ppt/pptx/UNKNOWN 桶 |
| parsingStates | 每版本 pending/parsing/indexed/failed/UNKNOWN；不是审核或发布状态 |
| pendingReviewCount | 当前 IN_REVIEW 轮次，排除已删除、归档文档和归档空间，与事件窗口无关 |
| approvedCount / rejectedCount | 窗口内 REVIEW_APPROVED / REVIEW_REJECTED 唯一轮次事实；送审可在窗口外 |
| durationSampleCount / durationUnknownCount | 已决定轮次中完整、非负、有限的送审到决定时间为有效，其余为未知 |
| decisionElapsedMsTotal | 各有效样本自然耗时先向下取整至毫秒，再精确相加 |
| meanDecisionMs | 总毫秒 / 有效样本数，HALF_UP 两位；没有有效样本为 null |
| rootCommentCount / replyCount | COMMENT_CREATED / COMMENT_REPLIED 唯一创建事实，不是当前剩余正文数量 |

两个版本分布各自之和等于 versionCount。格式只是声明扩展名，不验证真实 MIME、内容或格式质量。
同文档／版本的不同审核轮次分别计数；发布、撤回、取消不充当通过或驳回。评论删除不冲减创建量，
匹配重放、通知未派生、重复投递或已读不增量。私有审核意见不混入评论。历史事件不按当前文档可读性、
删除、归档或搬迁状态再次过滤；正文、名称及身份字段不进入响应、日志或遥测。

## 时间、覆盖与故障

仅接受成对的带偏移 ISO `from/to`，统一截断到数据库微秒，转换 UTC 后按 `[from,to)` 查询。
缺省为数据库固定 asOf 前 7×24 小时，最长 31×24 小时，要求 from < to <= asOf。
重复、未知、单边、空值、未来终点及越界请求返回 400。筛选只影响事件，不改变当前库存口径。
客户端仅检查格式、成对关系与 31 天跨度；未来终点由服务端固定的数据库 asOf 校验，不使用浏览器机器时钟拒绝窗口。

REVIEW 和 COMMENT 各自读取不可变覆盖起点，从起点到 asOf 连续覆盖。COMPLETE 表示全部请求窗口可观察，
无事实才返回 "0"；PARTIAL 只报告 effectiveFrom/effectiveTo 内已观察的值；NOT_STARTED 表示没有覆盖记录或
窗口全在起点前，窗口指标及有效区间为 null。当前库存和待审量仍是当前值。
不能以最早事件、旧 indexed、观察到的发布指针或通知补造历史。矛盾覆盖、缺表或查询故障整体返回 503。

日桶固定 UTC，最多 32 个，首尾按实际窗口截取并标记 partialDay；审核／评论各有覆盖状态。
日桶之和与同次已观察窗口总量一致，未知日不补零，不绘制历史库存曲线。

每次读取在同一连接的只读 REPEATABLE_READ 事务中同步组装，事务超时 6 秒、各语句超时 5 秒。
asOf 是本次时间锚点，不能用于任意历史回放，也不承诺窗口永久封存。读取快照之后才提交的事实本次不可见，
刷新可纳入 occurred_at 落在相同历史窗口内的晚提交；不使用序列高水位推断提交顺序。
任何必需查询失败都整体 503，无半套面板、旧缓存或虚假零；无会话 401，无有效身份或资格 403。
成功、失败及网关提前拒绝均 no-store。查询不生成成功业务事件或身份审计，不采集阅读／下载／搜索，
不记录筛选或统计内容及原始异常；遥测仅允许固定阶段与低基数耗时。没有导出、推送或自动轮询。

库存需要读取全局元数据，真实规模超过短事务预算时安全失败并评估索引／查询优化；异步物化需要另行规划覆盖和新鲜度，
不能以离线检查宣称实际查询性能或事务隔离已验证。

## 页面阅读和生命周期

报表只在当前独立资格确认后读取，不依赖空间目录或 AI 开关，也不读取身份、部门、角色、空间或文档目录补数。
默认进入读取最近 7 天；时间框只编辑草稿，点击“查询”才提交，点击“刷新已提交条件”使用上次提交条件，重置重新读取默认窗口。
所有统计和筛选只在内存中，不进入地址、登录返回、持久存储或遥测；固定登录返回仅接受 `/admin/reports/operations`。

当前库存、审核、评论和 UTC 日趋势分别展示。平均值明确为毫秒且附有效／未知样本数；无有效样本显示“无有效样本”，
未覆盖显示空缺，PARTIAL 只解释为已观察部分。页面不把大整数转为 JavaScript number，也不插值未知日或虚构库存曲线。
AI 用量链接只在另有 `ai:usage:read` 时出现，链接到原页面，不预取 AI 或复制其采集、审计实现。

隐藏、离页、强制改密、退出、切换身份、权限事实改变和 403 都清空统计及筛选并取消读取。
恢复先重新核对当前资格，再按默认窗口读取；请求代次和 Session epoch 拦截迟到响应。
新请求加载或失败时遮蔽旧统计，503 提供显式重试，不把旧 asOf 展示成当前成功结果。没有定时轮询。

## 离线验证记录（2026-10-27）

在仓库根目录使用 PowerShell 执行：

```powershell
mvn -pl kb-app,kb-gateway -am '-Dtest=OperationsWindowTest,OperationsReportTest,OperationsFactsTest,OperationsMappingTest,OperationsControllerTest,ModuleBoundaryTest,AdminQueryAccessTest,AdminBootstrapRunnerTest,PermissionCatalogTest,RolePermissionValidatorTest,PermissionServiceTest,ReviewCollaborationFactTest,DocumentReviewDecisionTest,DocumentReviewSubmitTest,DocumentCommentCreateTest,DocumentCommentDeleteTest,AiQueryCacheFilterTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：kb-app 89 项、kb-gateway 2 项通过，失败／错误／跳过均为 0。涵盖新报表、授权／引导、架构边界及前置源事实契约。
验证反馈修复后，库存、版本、待审和窗口事件由固定 `OperationsReportDefinition` 描述连接、筛选、分组与聚合。
生产 MyBatis 从该定义生成 SELECT，仍在数据库端聚合；时间值保持 JDBC 参数绑定，未引入原始事实在线读取。
`OperationsFactsTest` 的 7 项测试将原始内存表和协作事实交给同一定义求值，再通过真实读取服务生成响应，覆盖：

- 多版本／100 个分块不放大文档数，删除文档及版本排除，归档并集只计一次，候选／失败版本与 UNKNOWN 固定桶。
- 当前 IN_REVIEW 的删除／归档排除、不按文档状态推断轮次，跨窗送审、多轮决定、发布／撤回／取消排除及后续删除不抹去历史。
- 真实协作事实服务配合内存唯一业务键仓储验证匹配重放；评论正文清除、文档失效及通知重复投递不改变历史创建量，审核决定不混入评论。
- 对 1.999、1.999、0.999 毫秒三个样本逐一取整得到总量 2 毫秒、平均 0.67 毫秒；缺失、负数与无限时间计未知，无有效样本平均 null。
- 微秒级左闭右开、非 UTC 输入、跨 UTC 零点及独立覆盖起点，日桶与总量一致。

`OperationsMappingTest` 检查实际 MyBatis 映射、语句超时及全部时间参数（包括未知覆盖的 null）的 JDBC 绑定。
数据库时钟、授权和故障注入仍使用 Mock；源事实的唯一键约束由内存仓储模拟。
内存表达式求值和无连接映射不能证明 PostgreSQL 的实际执行语义、唯一约束、快照隔离或性能；没有 SQL 文本断言。

在 `kb-web` 目录执行 `npm ci --ignore-scripts` 按既有锁文件安装依赖，随后：

```powershell
npx vitest run src/admin/operationsReportsApi.test.ts src/admin/operationsReportsQuery.test.tsx src/admin/operationsReportsPages.test.tsx src/admin/operationsReportsAccess.test.tsx src/admin/operationsReportsRoutes.test.tsx src/admin/access.test.tsx src/admin/access-spaces.test.tsx src/admin/aiUsageAccess.test.tsx src/admin/aiUsageRoutes.test.tsx src/navigation.test.tsx
npm run typecheck
npm run build
```

结果：10 个测试文件、75 项测试通过，类型检查与静态构建通过。新增浏览器时钟落后仍发送合法窗口、未来终点保留服务端 400 的回归测试。
构建提示主包约 1,246.58 kB，超过既有 1,200 kB 提示阈值，不影响构建成功。未增加图表依赖或修改锁文件。

本次没有启动完整应用或网关，没有连接数据库、Redis、对象存储、消息组件或第三方业务 API，
没有执行迁移、集成测试、浏览器端到端、实际查询计划或负载测试，也未使用 Testcontainers／内嵌服务替代。
真实迁移、SQL 执行、快照隔离、提交一致性、查询性能及生产可用性均未验证；它们不是本变更离线完成的前置条件。
