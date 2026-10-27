# 版本化文档审核

> 实施与定向离线检查已完成，任务记录见 `openspec/changes/versioned-document-review/tasks.md`。
> 本文件不代表已部署或生产可用；真实迁移、事务与并发边界见下文验证记录。

## 升级与回退

### 升级边界

- 先备份，再停止接收旧协议写入，并排空全部旧 app/Worker 及在途任务；不得让新旧 app/Worker 混部写入。
- 本次追加 `kb-app/src/main/resources/db/migration/V23__versioned_document_review.sql`，不修改 V1–V22，不执行 Flyway repair。
- 迁移只作为脚本交付，本次开发不连接数据库、不运行迁移。真实迁移及数据备份恢复须由部署流程在维护窗口另行验证。
- 部署须覆盖 app、Worker 的写入和发布路径，以及文件、引用、收藏、历史回答等全部读取门禁。不能仅发布前端或仅替换 Worker。
- 初始空间策略为 AUTO。新版本在数据库插入时从文档实际空间读取策略，固定真实上传者；上传到对象存储的起始时间不是策略快照时点。
- 已创建版本的策略不因空间策略切换、解析完成或文档搬迁改变。空间关闭审核只影响之后插入的版本，不会自动放行旧待审稿。
- 审核事实 coverage 起点须在旧生产者排空、全部新协议生产者启用的边界建立；它不代表覆盖升级前的历史。新通知消费者可晚于审核上线。
- 检查历史手工写入的 review ACL 来源后，才可逐空间启用 REVIEW_REQUIRED；manage 不自动产生审核或阅读资格。
- 后端及 Gateway 接口可用后再发布前端。接口不可用时应显示失败，不得解释为免审或发布成功。

### 旧版本映射

所有迁移前版本显式标记 `LEGACY_COMPAT`，固定策略 AUTO，真实上传者保持未知。
只有升级时父文档 `status=published` 且 `current_version_id` 实际指向该版本，才记录 `OBSERVED_CURRENT`。
未知发布时间保持 null；不得以版本创建时间、owner 或 indexed 状态推测发布时间、上传者或审批历史。

旧非当前 indexed 版本仍保留旧固定版本引用和来源兼容性，但没有真实发布证据，不因此获得评论资格。
迁移不补造审核、发布事件或通知。新行必须明确使用 `VERSIONED_REVIEW` 并携带真实上传者；无 legacy 默认值，插入保护也拒绝显式 legacy 新行。

### 回退原则

- 优先关闭新增页面和写入口，保留新版安全后端及全部读取、发布门禁。
- 关闭空间审核开关不是待审稿放行操作；已有版本仍按其固定策略处理。
- 不得直接恢复会绕过审核和候选隔离的旧 app/Worker，不得删除既有审批或发布事实来迁就旧版本。
- 灾难回退须停写、排空实例，在经确认的维护窗口使用已核验备份恢复；这不属于本次实现或离线验证范围。

## 空间策略 API 与授权

`GET /api/admin/spaces/{id}/publication-policy` 读取策略与空间并发版本；
`PATCH` 同一路径以 JSON 提交 `policy`（AUTO／REVIEW_REQUIRED）和 `expectedVersion`，并提供单个 `X-Request-Id` 请求头。
标识与并发版本以十进制字符串返回以保持大整数精度；预期版本接受精确整数或规范十进制字符串。
未知／重复字段、未知查询参数、负版本和非法标识均拒绝；响应统一 R 封装及 `Cache-Control: no-store`。

策略读写要求当前有效身份，并具有该空间 `manage` 或功能权限 `space:manage-all`，归档空间仍可管理。
修改检查当前空间版本，成功推进版本。状态、无正文审计及精确回执在同一短事务完成；匹配重放须重新鉴权，返回首次回执而非当前快照。
不同操作者、目标、预期版本或载荷复用同操作域请求标识会冲突，不返回其他请求的回执。

默认策略为 AUTO。策略固定时点是版本插入语句读取文档实际空间之时，不是开始上传、对象存储完成或送审时；搬迁与开关切换不追溯已有版本。

| 资格 | 允许范围 | 不会自动授予 |
| --- | --- | --- |
| 空间 manage／space:manage-all | 管理空间及策略、委派允许的 ACL | view、download、edit、review、足够密级 |
| edit | 上传、合法版本送审、本人本轮撤回、候选刷新 | view、正文、文件名、私有意见、下载、审批 |
| view ＋足够密级 | 当前可见版本的正文与受支持预览 | 新候选参与身份、download、审核 |
| download | 通过版本访问及独立 view 检查后的下载 | view、候选访问、审核 |
| 空间 review | 空间审核人池成员资格 | view、download、edit、足够密级、自审 |
| 上传者／已记录送审者 | 候选参与身份；可取得本人最小状态 | 当前 view、密级、下载或审批 |

空间 review 可授予／撤销用户、角色、部门，部门继续按祖先链向下继承；不依赖角色名称。
实际合格审核人须当前用户启用、主部门有效，满足文档当前 view（可来自文档例外）、实际空间 review 及密级。
同时排除真实上传者与本轮送审者，所有者不是上传者的替代值。空间管理资格不构成强制代审入口。
送审必须有至少一名其他合格审核人，否则返回固定 `NO_ELIGIBLE_REVIEWER` 冲突，绝不降级 AUTO；不向提交者开放人员目录。
池名单是当时去重后的提醒意图，不是授权快照，后续处理始终重新核对。

文档例外只接受 view、download、edit；文档 review、manage 以及本阶段所有 comment 授予／撤销均拒绝。
归档空间暂停阅读与审核，但保留管理可达性，不隐式决定旧轮次。

## 命令、查询和回执协议

所有审核写操作只接受当前服务端会话身份，使用 JSON 与单个 `X-Request-Id`（1–128 个非控制 Unicode code point）。
不得传入上传者、策略、审批者或批准时间；未知及重复字段拒绝，不自动补为当前版本。

| 方法／路径 | 输入与语义 |
| --- | --- |
| GET `/api/reviews` | 必须指定 kind=mine／pending；limit 默认 20、最大 100，cursor 可选；无隐藏总数 |
| GET `/api/documents/{d}/versions/{v}/review` | 最小状态与版本能力；opinions=true 才加载授权的私有轮次，limit／cursor 仅随显式意见查询 |
| POST `/api/documents/{d}/versions/{v}/reviews` | expectedStateVersion，手动送审 |
| POST `.../reviews/{r}/approve` | expectedStateVersion，通过与发布必须同事务完成，不存在已批准待发布状态 |
| POST `.../reviews/{r}/reject` | expectedStateVersion、reason |
| POST `.../reviews/{r}/withdraw` | expectedStateVersion，仅本轮送审者且仍有 edit |
| POST `/api/documents/{d}/versions/{v}/index-refresh` | expectedStateVersion，结束旧活动轮次并开启新解析周期 |

以上为接口契约；实现及读取门禁、前端的整体进度以任务文件为准，离线检查不能替代部署验收。
送审只允许已索引、从未发布的需审版本；旧活动轮次拒绝重复送审。驳回、撤回后需显式创建递增新轮次，旧意见、送审空间和时间保留。
本人列表固定使用无标题、文件名、意见、存储信息和人员名单的最小投影；拥有 edit 而无 view 也不能借状态入口读取敏感内容。
pending 在当前身份、ACL、密级、空间、非自审过滤之后按稳定提交位置分页。游标绑定主体与查询条件，不代表持续授权。

### 固定错误码

错误仍使用 R 封装，`data.errorCode` 是固定协议码，客户端不得匹配原始异常文案。
非法参数／ID／JSON 为 400 `INVALID_REQUEST`；会话失效为 401 `UNAUTHENTICATED`；强制改密或身份门禁拒绝为 403 `FORBIDDEN`；资源不存在、不可见或无参与资格统一 404 `NOT_FOUND`。
409 分别为 `STATE_CONFLICT`、`NO_ELIGIBLE_REVIEWER`、`EMBEDDING_MAINTENANCE`、`INDEX_REFRESH_REQUIRED`；依赖不能可靠读取或写入为安全 503 `SERVICE_UNAVAILABLE`，不伪装为空待办或成功。
所有审核响应均 no-store，包括提前认证拒绝；响应、日志及事件不输出底层异常或原因原文。

### 严格幂等

唯一绑定键是操作域＋请求标识，绑定操作者、空间或文档／版本／轮次、原预期并发版本及规范化载荷摘要。
原因统一换行、NFC 和首尾空白后按 UTF-8 长度前缀编码参与 SHA-256 摘要；原因原文不进入回执。
匹配重放先重新鉴权，再返回首次成功回执；不因首次预期版本已旧而重复执行，也不把当前状态冒充首次回执。
同标识不同操作者、目标、原因或预期版本一律冲突。唯一冲突使用 ON CONFLICT 后独立语句读已提交绑定，不在 aborted 事务内继续查询。
新请求须绑定完成后再按控制锁及资源锁序执行。业务状态、事件、去重收件人与成功回执同事务；任何保存失败不得确认业务成功。

### 私有意见

驳回原因是规范化后 1–2000 个 Unicode code point 的非空纯文本，按文本呈现而不执行 HTML 或请求外部资源。
只有当前有 view 且具备上传者、已记录送审者或当前合格审核人资格才可读取；普通读者在发布后仍不能读取意见。
普通版本摘要和最小状态不加载原因。关闭意见、切换目标、隐藏页面或身份变化必须清除页面内存中的意见与输入，不持久化。

## 无正文协作事实 v1

固定种类为 REVIEW_SUBMITTED、REVIEW_APPROVED、REVIEW_REJECTED、REVIEW_WITHDRAWN、REVIEW_CANCELLED、VERSION_PUBLISHED。
事件只有稳定 event_id、protocol_version、kind、唯一 business_key、doc/version、必要 review/round、actor、occurred_at 及 submitted_at／decided_at。
没有正文、标题、文件名、存储键、意见、原始 hash 或令牌。批准生成批准及发布两个不同事实；匹配重试不会多计。
事件及收件人意图追加后不修改，首期不自动清理，也不从管理审计、indexed 或通知送达条数反推历史事实。

送审保存当时合格审核池的去重收件人；批准／驳回针对本轮送审者而非 owner，排除自身。
收件人只保存 ID，名单不是授权快照；后来加入者通过实时待办发现，不补发旧池提醒。
通知消费者尚未上线也不影响业务提交，不向用户声称通知已送达。下游按稳定事件身份去重，不能消费事件来重新发布。
app／Worker 新协议就绪时幂等创建 REVIEW coverage 起点；此契约必须以旧写入实例已全部排空为前提，不证明升级前覆盖。

## 索引、发布、刷新与锁顺序

普通 Worker 保留生成时 profile／fingerprint／epoch、执行周期及 claim token 校验，先控制共享锁、任务锁，再用独立语句锁文档、锁版本。
合法分块与元数据、索引身份、分块数和版本并发值同事务保存；REVIEW_REQUIRED 只 indexed，不推进当前指针。
AUTO 仍须文档及空间可发布、目标较新，才原子记录真实 PUBLISHED 证据、时间与 current 指针；首版此前保持 draft。
已发布或 OBSERVED_CURRENT 版本不能被普通任务重写；批次插入不完整不得确认 indexed。

审批使用 **control → document → version → review → event/receipt**，不在持有文档锁后反向锁任务。
它复核当前 ACTIVE、实际索引 profile 与 fingerprint、全部分块数量／唯一连续序号／归属／空间／密级／向量身份和目标版本序号。
相同身份的旧生成 epoch 可在当前控制锁保护下发布；不同身份必须真实刷新，不能只改向量标签。
决定、证据、时间、当前指针、并发值、批准与发布两个事实及回执一起提交，不提供已批准待发布状态，也不在锁内调用网络。

刷新使用 **请求绑定 → control → task（及其旧死信锁）→ document → version → review → event/receipt**。
独立文档锁语句明确先后，不以联合 FOR UPDATE 的书写顺序宣称已保证实际加锁顺序。
刷新只针对从未发布且需恢复合法索引的候选，保留原件和固定策略；推进 execution_generation 与 claim token，清除旧租约身份，把任务置为 pending。
旧失败历史保留，结束旧活动审核为 CANCELLED；维护期间不启动解析，只在 ACTIVE 后由现有任务领取。
完成合法新索引后需重新手动送审；死信重放不能重置 indexed 或已有发布证据的版本来绕过该入口。

搬迁和删除在各自原事务的文档锁内调用取消服务，再持有版本及轮次锁追加取消事实。
搬迁清空全部文档例外并同步分块归属；固定版本策略不变，目标空间只能新建轮次。删除继续 task → document 顺序并阻止迟到解析复活。
这两条路径不反向取得 control；归档只暂停当前读取和操作，不改旧轮次决定。

AUTO 巡检每次最多 100 个候选，调度默认每 30 秒处理 50 个，循环扫描位置不作为发布许可。
每个版本独立短事务重新取得 control 与文档／版本锁，重新核对空间、序号、profile 和完整性；不放行需审稿或过期索引。
调度间隔可通过 `kb.document-review.auto-publication-delay-ms` 设置。扫描及单测均不证明生产吞吐或真实锁互斥。

## 验证边界

### 用户使用与网关

用户端 `/reviews` 提供本人版本与实时合格审核待办；本人列表只显示最小状态，正文、预览、下载及私有意见分别按服务端当次能力开放。
在文档详情明确选择候选版本后可送审、通过并发布、驳回、本人撤回或刷新索引；默认文件始终使用当前发布版本。
响应不确定时仅显式按原内容及原标识重试，或重新读取核对；核对当前状态不等于取得原命令成功回执。
意见按需读取，关闭、切换身份／目标、隐藏页面时清理；恢复前重新确认身份，原因不写入浏览器持久存储。

prod/dev 均将 `/api/reviews/**` 路由至 kb-app，沿用 `/api/**` 会话门禁；审核业务资格仍由 app 判断。
Gateway 在认证之前和响应提交之前为 reviews、文档读取及空间策略设置 `Cache-Control: no-store`，覆盖成功、拒绝与故障。
该过滤器不读取或聚合响应体，AI 路由、无限流式响应超时和流式协议维持原有配置。

### 端点访问矩阵

所有资源判断均重新核对当前主体、密级、空间归档及删除事实；猜测不可见目标统一 404。
`INTERACTIVE` 允许真实发布历史、显式旧兼容版本，以及另有 view 的候选参与者。
`MODEL_SOURCE` 只允许真实发布／旧兼容来源；`COMMENT_TARGET` 只允许 PUBLISHED／OBSERVED_CURRENT。

| 端点／调用方 | 版本门禁与独立权限 | 默认版本／投影 |
| --- | --- | --- |
| 文档列表、详情、capabilities | 当前 view 且至少一个 INTERACTIVE 可见版本 | 无可见版本不返回元数据或隐藏数量；能力另查 download、edit |
| 版本列表、progress | 指定版本 INTERACTIVE | SQL 过滤后投影；默认 progress 使用 current_version_id |
| preview、显式版本 preview | INTERACTIVE ＋ view | 默认文件严格使用 current_version_id，候选须显式选择 |
| download、显式版本 download | INTERACTIVE ＋ view ＋独立 download | 无权时不产生对象定位信息／直链 |
| 固定版本 citations | INTERACTIVE ＋ view，完整 doc/version/chunk 归属 | 保留原三元组，缺分块不改指新版本；下载入口独立检查 |
| 收藏 PUT、列表、批量状态 | 父文档存在 INTERACTIVE 可见版本 ＋当前 view | 关系不授权；权限过滤先于分页，V2 待审不隐藏已发布 V1 |
| 历史回答、prepareContext、内部 source seal | 全部来源 MODEL_SOURCE | 任一候选来源使整条回答屏蔽并排除模型上下文 |
| 本人审核最小状态 | 当前有效身份 ＋本人上传／已记录送审关系 | 无 view 时不返回标题、文件、意见或当前发布版本资料 |
| 私有审核意见 | 当前 view ＋候选参与资格 | 仅显式 opinions=true 读取，发布后仍保持私有 |

其他模块经 document/service 执行版本访问，不直调 document Mapper；主体句柄仅在一次调用栈内复用，不保存为字段或泛型容器。

本次只允许独立内存单测、Mock/stub 依赖、无连接映射、架构检查、前端单测、类型检查及静态构建。
不启动完整应用或外部组件，不运行集成测试、跨服务联调、浏览器端到端测试、Testcontainers 或内嵌服务。
不对本说明或 SQL 文本编写断言；模型测试不等于数据库约束已经执行。
真实迁移、事务提交与回滚、锁互斥、并发吞吐和生产环境安全性均未验证。

### 2026-10-25 定向离线检查记录

在仓库根目录使用 PowerShell 执行以下 Maven 选择器；未运行包含集成测试或外部组件依赖的全量套件。

```powershell
$reviewTests = @(
    'VersionPublicationTest', 'VersionAccessRulesTest', 'Review*Test',
    'PublicationGateTest', 'LegacyPublicationUpgradeTest', 'CollaborationEventTest',
    'CandidateRefreshCycleTest', 'DocumentPublicationUploadTest',
    'SpacePublicationPolicy*Test', 'ReviewPermissionScopeTest',
    'DocumentReview*Test', 'DocumentVersion*Test', 'VersionAccessPurposeTest',
    'DocumentManagementServiceTest', 'DocumentCapabilitiesServiceTest',
    'DocumentFavorite*Test', 'DocumentCitation*Test', 'CandidateIndexRefreshServiceTest',
    'AutoPublicationServiceTest', 'ConversationServiceTest', 'TurnExecutionServiceTest',
    'DocumentRelocationServiceTest', 'SpaceAclServiceTest', 'ModuleBoundaryTest',
    'VersionedChunkPublicationTest', 'ChunkPublisherTest'
) -join ','
mvn -pl kb-app,kb-worker -am "-Dtest=$reviewTests" '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -pl kb-gateway -am '-Dtest=ReviewRouteTest,ReviewResponsePolicyTest,ConversationRouteTest,SaTokenGatewayConfigTest,AiQueryCacheFilterTest,FavoriteResponsePolicyTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

第一条通过 232 项（common 20、app 204、Worker 8），第二条通过 10 项网关测试，均无失败、错误或跳过。
覆盖状态与权限矩阵、命令幂等、来源隔离、无连接 MyBatis 映射、模块边界、Worker 发布，以及 prod/dev 路由、认证提前拒绝和 no-store；所有外部依赖均为替身。

在 `kb-web` 目录执行：

```powershell
npx vitest run src/reviews src/documents.test.tsx src/api.test.ts src/routing.test.tsx src/navigation.test.tsx src/logic.test.ts src/lifecycle.test.tsx src/components.test.tsx src/favorites src/admin/space-pages.test.tsx src/admin/spaceApi.test.ts src/admin/hooks.test.tsx
npm run typecheck
npm run build
```

Vitest 的 21 个文件、162 项测试全部通过；TypeScript 类型检查与 Vite 静态构建成功。
回归测试夹具已补齐 SSO 初始化、按路径区分搜索与收藏请求、冲突重读后重新确认，以及只允许安全导航提示的存储断言。
未启动应用或浏览器端到端测试，未连接数据库、Redis、对象存储或第三方 API，也未执行迁移。

根目录执行 `openspec validate versioned-document-review --type change --strict --no-interactive` 通过。
当时的工作区构建日志（`target/document-review-*.log`）和各模块 `target/surefire-reports` 只是本地构建产物，已于 2026-10-27 建立版本库前清理；本节记录的命令与结果即为留存结论。

### 已知源码规范基线

2026-10-25 运行 `JavaSourceConventionTest` 时，全库检查在本次未修改的既有文件中发现通配符导入、全限定类名、缺失 Javadoc／Schema 描述等问题。
涉及 aiusage、身份反馈、既有文档能力、Gateway 和 AI 等代码；该次检查失败，不能记录为通过。
当时用户确认保留这些既有文件和检查规则，后续命令不选择该全库源码规范测试。2026-10-27 起项目不再保留源文件格式类检查，该测试已删除，见 `docs/java-code-conventions.md`。
审核变更自身的模型、权限、控制器、无连接映射及模块边界检查仍须单独执行，不以此基线跳过业务验证。

### 2026-10-25 验证问题修复与复核

首次验证发现的 C1、W1、W2、W3 已修复，并增加正常测试套件中的回归覆盖：

- C1：首次上传和上传新版统一只返回 `documentId`、`versionId`、`taskId`、`indexStatus`。上传权限不授予读取资格；标题、文件名、大小和 hash 需通过独立读取接口获取。`DocumentPublicationUploadTest` 使用真实上传服务和写服务、内存 Mapper 与对象存储替身，检查两个 edit-only 入口的序列化响应只含上述四个字段。
- W1：文档详情整体使用审核页面的可见性和身份作用域。隐藏立即卸载正文、文件预览与能力状态，释放 PDF Blob URL、中止读取；恢复先核对身份，再重新读取，旧候选选择和预览不自动恢复。`reviews/file-lifecycle.test.tsx` 覆盖文本、PDF、身份核对未完成和迟到正文。预览清理 effect 只在读取资格失效时关闭预览，避免能力刚确认时覆盖用户刚打开的预览。
- W2：工作台文件预览遇到 401/403/404 时关闭预览与私有意见，并立即重新读取版本状态；重读期间清除旧操作资格，完成后只使用新能力。上述测试对三个状态码分别覆盖。
- W3：审核命令校验回执必填字段、文档／版本、原轮次、命令结果、固定策略、发布证据和精确并发推进；送审核对新轮次，刷新区分取消活动审核与无活动审核。策略命令核对空间、所选策略及精确版本。字段缺失或不匹配保持结果不确定；组件测试验证只显式按原内容和原 `X-Request-Id` 重试，不自动重发。

在上文 `$reviewTests` 选择器基础上执行：

```powershell
$fixReviewTests = "$reviewTests,DocumentUploadServiceTest,DocumentAclServiceTest,ParsePublishIdentityTest"
mvn -pl kb-app,kb-worker -am "-Dtest=$fixReviewTests" '-Dsurefire.failIfNoSpecifiedTests=false' test
```

通过 262 项（common 20、app 228、Worker 14），无失败、错误或跳过。修复前先运行的上传定向检查 21 项也通过，不重复累计。
在 `kb-web` 执行上文相同的 Vitest 选择器，22 个文件、215 项全部通过；`npm run typecheck`、`npm run build` 成功。扩展 Vitest 首次运行发现预览清理时序问题，修正后完整复跑通过；此处计数为最后一次结果。

当时的日志和诊断复核报告保存在根目录 `target/`，已于 2026-10-27 建立版本库前清理；结论以本节为准。
仍遵守上述离线边界和既有源码规范基线；未执行主规格同步或归档。

### 2026-10-25 W4 确认窗口能力失效修复

`VersionReviewPanel` 的确认窗口现在同时核对当前命令能力、文档／版本、原审核轮次及精确并发版本。
下载或私有意见被拒并触发重读时，尚未完成核对的窗口立即暂停；若新状态明确撤销能力或改变原绑定，旧窗口保持停用，需关闭后重新选择操作。
驳回原因输入同步禁用；已发送但结果不确定的请求仍可按原请求体和原 `X-Request-Id` 显式重试，不改用新状态，也不自动重发。

新增 `kb-web/src/reviews/command-lifecycle.test.tsx` 的 11 项行为回归，覆盖送审、批准、驳回、撤回和刷新撤权，轮次／大整数并发版本变化，下载与意见拒绝，原意图仍有效时恢复确认，以及不确定请求匹配重试。
修复前上述测试有 9 项失败，修复后相关 3 文件／24 项通过；随后使用上文相同 Vitest 选择器运行受影响前端套件，23 个文件、226 项全部通过。
`npm run typecheck` 和 `npm run build` 均成功。

当时的日志、W4 复核报告和诊断探针保存在根目录 `target/`，已于 2026-10-27 建立版本库前清理；正常测试使用上述回归文件，结论以本节为准。
本次修改及验证限于前端，继续保留既有离线验证边界。
