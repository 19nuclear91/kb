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

投影只覆盖既有 15 个身份管理写入口及新增的 11 个空间/文档写入口。读取、旧 `/api/identity` 别名、外部身份和运维接口保持原错误契约。分类来自实际检查点，不能从 message 或客户端输入猜测。

前后端可以独立发布与回退：旧前端忽略新增原因；新前端连接旧后端时显示通用状态提示。回退前端移除新增页面；回退后端恢复原错误 message。无需数据库迁移、新接口或新权限。

## 请求与结果核对

空间摘要携带 code、name、description、archived、version 及独立的 viewable/manageable；管理文档清单只含 documentId、spaceId、spaceArchived，采用游标而非总数。授权回执区分 alreadyGranted 与 clearanceBlockedPrincipalCount；搬迁回执包含 clearedExceptionCount、clearedExceptionsTruncated 与目标空间密级受阻数。

ID、主体 ID、空间版本和 securityVersion 按精确整数回传，保持 JSON 数值类型。所有写入请求标识只在 X-Request-Id 请求头携带；删除空间不发送请求体，撤销授权发送主体及权限位。

网络结果丢失或成功回执无法读取时，操作可能已执行。可按原内容及原请求标识显式重试、重新读取核对或放弃本地核对；不会自动重传。目标、内容或版本变化视为新意图，重新生成标识。关闭页面或放弃核对不能撤回已发送写入。

## 入口与资格

知识空间入口 `/admin/spaces` 要求存在可管理空间，或具有 `space:create`、`space:manage-all`。空间详情 `/admin/spaces/:spaceId` 与文档面板 `/admin/documents/:documentId` 按各自读取结果判断资源资格。只有 `document:security-level:write` 不会出现空间目录入口，但可以从可阅读文档的详情进入管理面板。

本人权限与空间目录并行核对，分别区分加载、可用与故障；单侧失败不伪装空集合。管理导航、候选及写操作不使用未确认的旧事实。进入管理页、会话恢复、403，以及空间写入、搬迁、主部门调整、角色绑定或权限修改成功后重新核对。身份切换丢弃旧响应与管理资料。持续的读取 403 只触发一次自动资格核对，随后保留拒绝状态，用户可显式重读；确认的资格事实变化后也允许重新读取，避免自动循环和旧拒绝阻止权限恢复。

## 空间生命周期

目录只列 manageable 空间，包含已归档空间；create-only 用户看到空目录与新建入口。新建需要 `space:create`，编码、名称、描述长度上限分别为 64、200、2000；编码创建后只读。名称、描述、归档与取消归档携带当前空间版本，过期后必须重读并重新确认。

归档使文档退出检索、列表、详情、下载与预览，保留管理资格并可取消归档；取消归档按原授权与密级恢复可见。删除前以管理文档清单 `spaceId`、`limit=1` 核对是否为空，有文档时建议归档，核对失败不能删除。删除不可恢复，授权随之移除。服务端仍可能因新上传返回 SPACE_NOT_EMPTY，必须重读；不确定后核对为 404 只表示空间已不可访问，不证明由本次请求删除。

## 授权与管理文档清单

空间与文档授权候选只来自对应资源的 `/acl/principals`，可按 USER/ROLE/DEPT 搜索、游标续页。资源、类型或搜索条件变化后清除旧选择，失败时不使用旧候选；不读取或回退至身份目录，也不提供手填内部 ID。部门授权对子部门生效。空间权限提供 view、download、edit；manage 授出与撤销另需 `space:grant-manage`，否则已有 manage 行只读。

授权回执的 alreadyGranted=true 表示已有授权，不应显示新增。密级受阻数非零表示当前部分主体不生效，不代表请求失败，授权不修改主体密级。撤销不存在的授权同样幂等成功，回执不证明授权此前一定存在。本人失去唯一管理资格后，重核会清除相应详情。

空间内文档按游标显示 ID 与空间归档状态，没有总数或标题批量补查；尚未发布首版、无正文阅读资格的文档仍可以 ID 呈现。清单不授予阅读、预览或下载资格。

## 文档面板、搬迁与密级

文档面板独立读取管理清单确定归属、按密级写权限读取密级状态，以及用户端详情补充标题。管理清单空页只关闭例外及搬迁区块，不阻止独立密级资格；标题被拒绝时只显示 ID。单区块故障不影响其他区块。

例外权限只提供 view、download、edit，只扩权而不收紧空间授权；已归档空间中仍可管理例外，但取消归档前不因此恢复阅读。搬迁目标限于当前可管理、未归档且不同于来源的空间；目录不可用、没有目标或例外数量尚未确认时不能提交。确认说明会展示将清除的全部例外条数，清除不可恢复，需由目标空间管理员重新授出。请求声明当前来源，不夹带密级。

搬迁成功展示清除总数、明细是否截断及目标密级受阻数，受阻非零仍为成功；随后重读全部区块与可管理空间。来源变化、已在目标或目标归档时重读，不自动以新事实重新提交。成功回执在同一身份下的重核期间保留，刷新失败不能把业务成功改写为失败。

密级选项来自实际字典，只提供不超过本人 me 密级且不同于当前值的选项，不硬编码 0–3。原因必填，去首尾空白后最多 500 个 Unicode 字符，并携带当前独立 securityVersion。调高密级可能使现有读者失去访问。成功显示前后值并重读状态；版本冲突后重新确认，403 时核对本人权限，仍持有权限则显示当前无法调整，不猜测具体拒绝原因。

管理资料、搜索词、原因和表单仅保存在当前内存，页面地址只保留目标 ID；名称与标题按安全文本展示。用户端详情的“管理此文档”只消费已有上下文，不额外查询管理清单或密级，进入面板后重新按各区块读取资格判定。

## 首期限制

只有密级权限却无文档 view 的操作者，以及已归档空间中的文档，难以从用户端详情发现管理入口；已知面板地址仍可直接访问，首期不提供手填文档 ID。没有批量授权、文档删除、审计、外部身份或 Embedding/死信运维页。文档授权及搬迁的 404/403 先后语义保持现状。

## 验证记录

2026-10-21：后端以明确白名单执行 `IdentityManagementFeedbackTest`、`SpaceWriteServiceTest`、`DocumentRelocationServiceTest`、`DocumentSecurityLevelServiceTest` 与 `ModuleBoundaryTest`，158 项通过，失败、错误与跳过均为 0；跳过测试的打包通过。命令：

```powershell
mvn -o -pl kb-app -am test '-Dtest=IdentityManagementFeedbackTest,SpaceWriteServiceTest,DocumentRelocationServiceTest,DocumentSecurityLevelServiceTest,ModuleBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false'
mvn -o -pl kb-app -am package '-DskipTests'
```

仅验证 Mock 业务分支、独立控制器、错误响应及架构边界；未验证真实数据库事务、锁、持久化、Redis、对象存储、跨服务链路或真实浏览器效果。文档只作人工核对，不运行文档内容测试或示例请求。

前端执行 `npm run typecheck`、`npm test -- --run` 与 `npm run build`：19 个单元测试文件、216 项通过，类型检查与静态构建通过。新增验证涵盖双事实来源、精确请求、内存路由、空间生命周期、上下文候选、ACL 回执、搬迁、密级范围、403 重核及详情入口；全部使用进程内替身与模拟 DOM。`openspec validate admin-space-document-web --strict` 通过。
