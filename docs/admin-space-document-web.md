# 知识空间与文档管理

管理区复用现有登录、强制改密及退出流程。空间和文档管理使用当前资源资格，入口只提供操作提示，每次请求仍由服务端授权。

## 写入错误与兼容

指定管理写入口支持可选顶层 `reasonCode`，HTTP 状态及 `code/message/data` 保持原语义。失败 data 为 null，投影后的 message 使用固定提示，避免泄露空间 ID、文档数量和异常正文。

| 分类 | 含义与处理 |
| --- | --- |
| SPACE_NOT_EMPTY | 空间仍有文档，重新核对，建议归档 |
| SOURCE_MISMATCH | 文档归属变化，重新读取归属后确认 |
| TARGET_ARCHIVED | 目标已归档，重新核对候选 |
| ALREADY_IN_TARGET | 已在目标空间，重新读取 |
| IDENTIFIER_CONFLICT | 用户名或编码重复，更换后提交 |
| INVALID_VERSION | 版本变化，重读后按新版本重新确认 |
| CONFLICT | 不能可靠细分的状态冲突，重读后确认 |

投影只覆盖既有 15 个身份管理写入口及新增的 12 个空间/文档写入口。读取、旧 `/api/identity` 别名、外部身份和运维接口保持原错误契约。分类来自实际检查点，不能从 message 或客户端输入猜测。

管理端删除 `DELETE /api/admin/documents/{documentId}` 与其余管理写入口共用同一投影：失败响应附顶层 `reasonCode`，HTTP 状态与 `code/message/data` 保持原语义，message 为固定提示，不泄露目标是否存在。前端仍可按状态码分流：400 为缺少 `X-Request-Id` 或文档 ID 不是正整数（`INVALID_REQUEST`）；404 统一表示文档不存在、已删除或当前用户无权管理其所属空间，三者不可区分（`NOT_FOUND`）；409 表示资格判定后文档归属已变化，未写入删除，需重新读取归属后再决定（`CONFLICT`）。资格只看文档当前所属空间的 manage 或 `space:manage-all`，edit、view、文档例外与上传者身份都不能替代；已归档空间中的文档仍可删除。

仅错误原因投影可以独立发布与回退。资料与分类功能包含新迁移、标签权限和严格搬迁协议，须同步更新前后端；部署与回退边界见 [文档资料运维](document-metadata-operations.md)。

## 请求与结果核对

`POST /api/admin/spaces/{spaceId}/acl/batch` 接受 `principals: [{principalType, principalId}]` 与 `permissions: [...]`，
请求标识只取 `X-Request-Id`。缺失标识、非正整数空间 ID、主体列表不在 1–50 范围、权限列表不在 1–6 范围、
重复主体/权限位、无效主体形状或不支持的权限位均返回 400；有资格后发现角色不存在同样返回 400。
未通过空间管理资格、含 manage 却无 `space:grant-manage`、操作者或主部门停用均返回 403。
请求标识已绑定其他操作者或空间、审计绑定缺失，或快照空间与目标空间不一致时返回通用 409，不返回原批次明细。
资格先于角色存在性检查；整批全部校验通过后在一个事务中写入，校验失败不写任何条目或成功审计。

批量回执包含 `spaceId`、`items[]`（principalType、principalId、permission、alreadyGranted）、`grantedCount`、
`alreadyGrantedCount`、`clearanceBlockedPrincipalCount` 与 `replayed`。已存在条目跳过，仍返回成功；受阻数按主体而非
权限条目计算，仅统计本批至少有一条新授权的主体：该主体有有效成员，且空间内存在密级高于成员最高密级的已发布文档时计为受阻；无有效成员不计入。这表示至少有一篇文档因密级不可访问，不表示所有文档均不可访问，不修改任何密级。
同一操作者、同一空间以相同标识重试，从首次成功的审计快照恢复逐条结果与全部计数，`replayed=true`，不重新查询 ACL 或计算受阻数，也不新增审计。
既有单条授予与撤销接口的请求、回执及错误契约保持不变。

空间摘要携带 code、name、description、archived、version 及独立的 viewable/manageable；管理文档清单只含 documentId、spaceId、spaceArchived，采用游标而非总数。授权回执区分 alreadyGranted 与 clearanceBlockedPrincipalCount；搬迁回执包含 clearedExceptionCount、clearedExceptionsTruncated 与目标空间密级受阻数。管理端删除回执只含 documentId、spaceId（删除时所属空间）与 replayed，不含标题、文件名或正文；同一 `X-Request-Id` 重复提交返回首次回执且 replayed 为 true，不再写入。

ID、主体 ID、空间版本和 securityVersion 按精确整数回传，保持 JSON 数值类型。所有写入请求标识只在 X-Request-Id 请求头携带；删除空间与删除文档都不发送请求体，撤销授权发送主体及权限位。

网络结果丢失或成功回执无法读取时，操作可能已执行。可按原内容及原请求标识显式重试、重新读取核对或放弃本地核对；不会自动重传。目标、内容或版本变化视为新意图，重新生成标识。关闭页面或放弃核对不能撤回已发送写入。

## 入口与资格

知识空间入口 `/admin/spaces` 要求存在可管理空间，或具有 `space:create`、`space:manage-all`。文档管理面板入口 `/admin/documents` 在上述任一条件成立或持有 `document:security-level:write` 时显示，索引页只提供文档 ID 输入（正整数且不超过 64 位有符号整数范围）并跳转到 `/admin/documents/{id}`，非法值页内提示且不跳转；页面不发出管理请求、不枚举文档、不保存历史或最近打开记录，可直接访问、刷新及作为登录后的站内返回地址，返回后重新核对资格才展示输入框。入口显示不代表任何文档可管理：空间详情 `/admin/spaces/:spaceId` 与文档面板 `/admin/documents/:documentId` 按各自读取结果判断资源资格，面板的例外授权、搬迁与删除区块按管理清单是否返回该文档开放，密级区块按功能权限独立开放。只有 `document:security-level:write` 不会出现空间目录入口，但可经索引页按 ID 到达面板，也可以从可阅读文档的详情进入。

本人权限与空间目录并行核对，分别区分加载、可用与故障；单侧失败不伪装空集合。管理导航、候选及写操作不使用未确认的旧事实。进入管理页、会话恢复、403，以及空间写入、搬迁、主部门调整、角色绑定或权限修改成功后重新核对。身份切换丢弃旧响应与管理资料。持续的读取 403 只触发一次自动资格核对，随后保留拒绝状态，用户可显式重读；确认的资格事实变化后也允许重新读取，避免自动循环和旧拒绝阻止权限恢复。

## 空间生命周期

目录只列 manageable 空间，包含已归档空间；create-only 用户看到空目录与新建入口。新建需要 `space:create`，编码、名称、描述长度上限分别为 64、200、2000；编码创建后只读。名称、描述、归档与取消归档携带当前空间版本，过期后必须重读并重新确认。

归档使文档退出检索、列表、详情、下载与预览，保留管理资格并可取消归档；取消归档按原授权与密级恢复可见。删除前以管理文档清单 `spaceId`、`limit=1` 核对是否为空，有文档时建议归档，核对失败不能删除。删除不可恢复，授权随之移除。服务端仍可能因新上传返回 SPACE_NOT_EMPTY，必须重读；不确定后核对为 404 只表示空间已不可访问，不证明由本次请求删除。

文档删除是软删除，不释放空间删除条件：服务端的 `countReferencedDocuments` 按 `kb_document` 行计数，已软删除的文档行仍计入，因此删完文档的空间依旧会返回 SPACE_NOT_EMPTY。管理文档清单会排除已删除文档，`limit=1` 核对为空不代表服务端允许删除空间；需要让空间退出使用时仍应归档。

## 授权与管理文档清单

空间与文档授权候选只来自对应资源的 `/acl/principals`，可按 USER/ROLE/DEPT 搜索、游标续页；不读取或回退至身份目录，也不提供手填内部 ID。部门授权对子部门生效。

空间面板支持多主体与多权限位：已选清单跨类型和搜索条件保留，同一主体不能重复加入，可逐个移除；候选读取失败时保留已选，但不能从故障候选新增。权限复选提供 view、download、edit、review、comment，manage 授出与撤销另需 `space:grant-manage`，否则已有 manage 行只读。确认前展示主体数、权限位数与乘积总条目数；超过 50 个主体或总条目为 0 禁用提交，不自动拆批。整批只使用一次请求和一个请求标识，400/403 整体拒绝后保留选择；403 资格重核期间草稿仍保存在当前身份的内存，切换身份清空。成功按逐条回执显示新增/已存在并分别汇总计数，密级受阻提示按主体计数。

待核对的批次在当前会话内按身份与空间保留原内容、请求标识；关闭弹窗或资格重核导致页面重挂载后，重开仍待核对，不能直接确认成新批次。仅原内容重试确认成功、明确拒绝或显式放弃核对后清除该绑定；身份变化清空旧身份绑定，不同空间互不混用。

批量路由返回 404/405 时显示“当前服务不支持批量授予”，保留已选内容并禁用授予；即使只选一个主体和一个权限位，也不回退单条调用。授权列表与单条撤销继续可用。部署时先发布后端再发布前端；回退前端可恢复原单条授予交互，不需要数据库迁移。

文档例外继续使用单选主体、单权限位与单条接口；类型或搜索条件变化、候选读取失败都会清空旧选择，不展示批量确认摘要，也不调用空间批量接口。

授权回执的 alreadyGranted=true 表示已有授权，不应显示新增。密级受阻数非零表示当前部分主体不生效，不代表请求失败，授权不修改主体密级。撤销不存在的授权同样幂等成功，回执不证明授权此前一定存在。本人失去唯一管理资格后，重核会清除相应详情。

空间内文档按游标显示 ID 与空间归档状态，没有总数或标题批量补查；尚未发布首版、无正文阅读资格的文档仍可以 ID 呈现。清单不授予阅读、预览或下载资格。

## 文档面板、搬迁与密级

文档面板独立读取管理清单确定归属、按密级写权限读取密级状态，以及用户端详情补充标题。管理清单空页只关闭例外及搬迁区块，不阻止独立密级资格；标题被拒绝时只显示 ID。单区块故障不影响其他区块。

例外权限只提供 view、download、edit，只扩权而不收紧空间授权；已归档空间中仍可管理例外，但取消归档前不因此恢复阅读。搬迁目标限于当前可管理、未归档且不同于来源的空间；目录不可用、没有目标或例外数量尚未确认时不能提交。确认说明会展示将清除的全部例外条数，清除不可恢复，需由目标空间管理员重新授出。请求声明当前来源，不夹带密级。

搬迁确认前读取 `/api/admin/documents/{id}/relocation-context` 的来源空间和 metadataVersion，并明确选择目标分类或未分类。请求必须携带 targetCategoryId（null 表示未分类）及 expectedMetadataVersion；旧请求缺字段返回 400。该最小上下文不要求正文 view，不返回标题或摘要。

搬迁成功展示清除总数、明细是否截断、目标分类、资料版本及目标密级受阻数，受阻非零仍为成功；随后重读全部区块与可管理空间。来源、资料版本或目标分类变化时重读，不自动以新事实或未分类重新提交。全局标签保留；旧审计标识无新绑定时返回 409。成功回执在同一身份下的重核期间保留，刷新失败不能把业务成功改写为失败。

密级选项来自实际字典，只提供不超过本人 me 密级且不同于当前值的选项，不硬编码 0–3。原因必填，去首尾空白后最多 500 个 Unicode 字符，并携带当前独立 securityVersion。调高密级可能使现有读者失去访问。成功显示前后值并重读状态；版本冲突后重新确认，403 时核对本人权限，仍持有权限则显示当前无法调整，不猜测具体拒绝原因。

删除区块与例外授权、搬迁同用管理清单的 owner 开关，仅密级权限不显示；标题不可读时以文档 ID 标识目标，所属空间已归档仍可删除。确认说明删除整份文档及全部版本、取消未完成处理与审核、例外授权随之失效、不可恢复。进入确认即禁用例外授予/撤销、搬迁与密级写入，其他写入在途时不能提交删除，取消确认恢复。成功只显示"文档已删除"并重读各区块；404 时重读管理清单，仍含该文档提示当前服务不支持管理端删除、不再含按当前不可访问处理，两者都不声称由本次删除；409 提示归属已变化并重读；结果不确定只提供按原标识重试、重新读取核对或放弃核对，重新读取得到的 404 不被解释为删除回执。

管理资料、搜索词、原因和表单仅保存在当前内存，页面地址只保留目标 ID；名称与标题按安全文本展示。用户端详情的“管理此文档”只消费已有上下文，不额外查询管理清单或密级，进入面板后重新按各区块读取资格判定。

## 首期限制

只有密级权限却无文档 view 的操作者，以及已归档空间中的文档，难以从用户端详情发现管理入口；可经 `/admin/documents` 索引页手填文档 ID 直达面板。批量授予仅支持同一空间，不提供批量撤销、跨空间授权或文档例外批量；不提供批量删除、单版本删除、回收站或恢复。文档授权及搬迁的 404/403 先后语义保持现状。

## 验证记录

2026-11-04（核验修复）：审计重放增加操作者、目标空间与快照空间校验，不匹配返回通用 409；批量未决绑定在弹窗关闭及资格重核后保留，按身份与空间隔离；密级受阻的规格和注释统一为存在高于有效成员最高密级的已发布文档。

后端按白名单执行 `SpaceAclServiceTest`（22）、`SpaceControllerTest`（13）、`AuditSummaryProjectorTest`（8）、`RecordOpenApiSchemaTest`（2）与 `ModuleBoundaryTest`（9），54 项全部通过。前端 `space-pages.test.tsx`（76）、`spaceApi.test.ts`（6）、`MultiPrincipalPicker.test.tsx`（4）与 `hooks.test.tsx`（14）共 100 项最终全部通过。新增回归覆盖跨操作者/空间的重放拒绝、快照空间不一致、弹窗关闭重开、候选 403 触发真实资格重核、空间及身份隔离，另验证确认、拒绝及显式放弃后的绑定清除。

`npm run typecheck`、`npm run build` 与跳过测试的后端打包通过，前端仍有主包超过 1200 kB 的既有提示；两个主规格 `space-authorization`、`admin-web-spaces` 的严格校验通过。验证仍限本地隔离单元测试，真实事务与并发行为未验证。

2026-11-04（空间批量授予首次实施）：按白名单执行 `SpaceAclServiceTest`（20）、`SpaceControllerTest`（13）、
`AuditSummaryProjectorTest`（8）与 `ModuleBoundaryTest`（9），共 50 项通过，失败、错误、跳过均为 0；
另已定向执行 `RecordOpenApiSchemaTest`（2 项通过），覆盖新增请求、主体与逐条回执的字段说明。跳过测试的后端打包通过。

```powershell
mvn -o -pl kb-app -am test '-Dtest=SpaceAclServiceTest,SpaceControllerTest,AuditSummaryProjectorTest,ModuleBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false'
mvn -o -pl kb-app -am package '-DskipTests'
```

前端按文件执行 `src/admin/spaceApi.test.ts`（6）、`src/admin/space-pages.test.tsx`（73）与
`src/admin/MultiPrincipalPicker.test.tsx`（4），共 83 项通过；`npm run typecheck` 与 `npm run build` 通过。
构建仍提示主包超过 1200 kB 的体积阈值。覆盖多选跨类型保留、故障保留、精确整数、50/6 上限、整批提交、逐条回执、
403 重核后草稿恢复、404/405 不回退、结果不确定的原标识重试，以及文档例外单选回归。
`git diff --check` 与 `openspec validate admin-space-batch-acl-grant --strict` 通过。
未新增迁移、权限位、批量撤销、跨空间批量或文档例外批量；未运行集成测试、真实数据库或外部服务。
事务原子性与并发写入仅依赖实现约束，未由本轮隔离单元测试验证。

2026-10-21：后端以明确白名单执行 `IdentityManagementFeedbackTest`、`SpaceWriteServiceTest`、`DocumentRelocationServiceTest`、`DocumentSecurityLevelServiceTest` 与 `ModuleBoundaryTest`，158 项通过，失败、错误与跳过均为 0；跳过测试的打包通过。命令：

```powershell
mvn -o -pl kb-app -am test '-Dtest=IdentityManagementFeedbackTest,SpaceWriteServiceTest,DocumentRelocationServiceTest,DocumentSecurityLevelServiceTest,ModuleBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false'
mvn -o -pl kb-app -am package '-DskipTests'
```

仅验证 Mock 业务分支、独立控制器、错误响应及架构边界；未验证真实数据库事务、锁、持久化、Redis、对象存储、跨服务链路或真实浏览器效果。文档只作人工核对，不运行文档内容测试或示例请求。

前端执行 `npm run typecheck`、`npm test -- --run` 与 `npm run build`：19 个单元测试文件、216 项通过，类型检查与静态构建通过。新增验证涵盖双事实来源、精确请求、内存路由、空间生命周期、上下文候选、ACL 回执、搬迁、密级范围、403 重核及详情入口；全部使用进程内替身与模拟 DOM。`openspec validate admin-space-document-web --strict` 通过。

2026-11-03（管理端文档删除与索引页）：后端以白名单执行 `DocumentManagementServiceTest`（16）、`AuditSummaryProjectorTest`（7）、`RecordOpenApiSchemaTest`（2）、`ModuleBoundaryTest`（9）、`DocumentAdminDeletionServiceTest`（15）、`DocumentAdminDeletionControllerTest`（9）、`IdentityManagementFeedbackTest`（95），共 153 项通过，失败、错误与跳过均为 0；跳过测试的打包通过。命令：

```powershell
mvn -o -pl kb-app -am test '-Dtest=DocumentManagementServiceTest,AuditSummaryProjectorTest,RecordOpenApiSchemaTest,ModuleBoundaryTest,DocumentAdminDeletionServiceTest,DocumentAdminDeletionControllerTest,IdentityManagementFeedbackTest' '-Dsurefire.failIfNoSpecifiedTests=false'
mvn -o -pl kb-app -am package '-DskipTests'
```

前端按文件执行 `src/admin/spaceApi.test.ts`、`src/admin/space-pages.test.tsx`、`src/admin/access.test.tsx`、`src/admin/documents-index.test.tsx`，4 个文件 94 项通过；`npm run typecheck` 与 `npm run build` 通过。全量 `npx vitest run` 为 78 个文件 937 项，其中 934 项通过，3 项失败位于 `src/reviews/file-lifecycle.test.tsx`（会话替身缺少 `subscribe`），在未应用本次改动的工作树上同样失败，与本次变更无关。新增验证覆盖删除区块显示条件、确认文案、与例外/搬迁/密级的互斥及取消恢复、在途写入标记为未确认后放行提交并提示、成功重读、404 两种分流、409、结果不确定的重试/核对/放弃、不确定后核对为空清单不解释为删除回执、索引页校验与跳转、导航项显示资格、`/admin/documents` 返回地址；后端另覆盖管理端删除路由进入 reasonCode 投影白名单（400 `INVALID_REQUEST`、404 `NOT_FOUND`、409 `CONFLICT`）及共用主体 404/409 改写为身份领域异常；全部使用进程内替身、内存路由与模拟 DOM，未启动任何服务。
