# 身份管理 Web 与错误反馈

本文说明 `kb-web` 管理区和身份管理写接口的增量错误投影，不执行部署、服务启动或外部链路验证。普通用户端托管约定见 [用户端静态托管与回退](user-web-operations.md)。

## 错误投影白名单

只有下列 15 个 `/api/admin/identity` 写入口（方法与路由模板同时精确匹配）在已识别领域失败时增加顶层 `reasonCode`：

| 方法 | 路由模板 |
| --- | --- |
| POST | `/users` |
| PATCH | `/users/{userId}/department`、`/users/{userId}/enabled`、`/users/{userId}/password`、`/users/{userId}/clearance` |
| POST / DELETE | `/users/{userId}/roles`、`/users/{userId}/roles/{roleId}` |
| POST | `/departments` |
| PATCH | `/departments/{departmentId}/name`、`/departments/{departmentId}/parent`、`/departments/{departmentId}/enabled` |
| POST / PATCH / DELETE | `/roles`、`/roles/{roleId}`、`/roles/{roleId}` |
| PUT | `/roles/{roleId}/permissions` |

以下入口保持原错误响应：两个身份管理控制器的全部读取、`/users/{userId}/external-identities` 写入、整个旧 `/api/identity` 别名，以及其他控制器（即使路径前缀相同）。实现位于 `IdentityManagementFeedbackAdvice`，只作用于 `IdentityManagementController` 与 `RoleManagementController`，未命中时交回 `GlobalExceptionHandler.handleIdentity`。

响应示例：

```json
{"code":500,"message":"角色仍有引用，请先处理绑定或授权。","data":null,"reasonCode":"ROLE_IN_USE"}
```

`code`、`data=null` 与原 `R` 失败语义一致，HTTP 状态保持异常原状态。`message` 由分类映射固定文本，不携带请求值、对象字段、引用数量、SQL、堆栈或异常正文。

## 原因与 HTTP 对照

| reasonCode | HTTP | 来源 | 页面处理 |
| --- | --- | --- | --- |
| `INVALID_VERSION` | 409 | 用户/部门版本不匹配 | 重新读取并确认新版本 |
| `DEPARTMENT_NOT_EMPTY` | 409 | 停用部门时仍有启用子部门或用户 | 先处理启用成员或子部门 |
| `ROLE_IN_USE` | 409 | 删除角色时仍有用户绑定或空间/文档授权 | 先处理引用，不提供强制删除 |
| `LAST_MANAGER_REQUIRED` | 409 | 解绑角色、停用用户或撤销角色权限会失去最后一名合格管理员 | 保留其他合格管理员 |
| `IDENTIFIER_CONFLICT` | 409 | 创建时用户名、部门编码或角色 code 预检查命中 | 在创建表单提示已存在 |
| `INVALID_HIERARCHY` / `INVALID_PARENT` | 409 | 部门层级检查 | 读取后重新确认层级 |
| `CONFLICT` | 409 | 其余无法可靠细分的冲突 | 读取后重新确认 |
| `INVALID_REQUEST` | 400 | 服务端业务校验（含缺少请求标识） | 提示输入不符合要求 |
| `FORBIDDEN` / `DISABLED` / `PASSWORD_CHANGE_REQUIRED` | 403 | 权限门禁、停用对象、强制改密 | 沿用 Session 的 me 核对 |
| `NOT_FOUND` | 404 | 目标不存在 | 清理失效目标并允许重新读取 |
| `UNAUTHORIZED` | 401 | 身份不可用 | 由 Session 清理本地会话 |

细分原因只在对应检查点通过 `IdentityException.conflict(IdentityConflictReason, message)` 显式附加，errorCode 仍为 `CONFLICT`、HTTP 仍为 409。数据库唯一约束兜底（`DataIntegrityViolationException`）可能来自其他约束，因此保持通用 `CONFLICT`；适配器不从 message、SQL 或自由文本提取分类。错误码与原状态不一致的异常视为未识别，交回原响应。

请求体 Bean Validation、类型转换等框架异常仍返回原 `请求参数或请求体无效`；数据库、事务与未知异常仍走原 500/503 处理，不冒充业务冲突。权限门禁先于业务判断执行，补充原因不追加目标存在性、引用或审计查询。适配器只记录固定分类与状态的 debug 日志，不记录异常正文或堆栈。

## 兼容与回退

- 新前端连接旧后端：错误体没有 `reasonCode`，前端按原 HTTP 状态显示通用提示，查询与提交协议不变。
- 旧前端连接新后端：成功响应、HTTP 状态及 `code/message/data` 字段保留；旧前端忽略新增字段。仅白名单入口的 `message` 改为固定安全文本。
- 调用方继续使用 `/api/identity` 别名时响应与之前完全一致。
- 后端可单独回退：删除 `IdentityManagementFeedbackAdvice` 即恢复原响应；`IdentityConflictReason` 元数据不影响其他消费方。无 DDL、权限目录或角色归属变更，回退不需要处理数据库或权限绑定。

## 前端提交规则

管理写入统一经 `src/admin/hooks.ts` 的 `useAdminWrite` 与 `src/admin/api.ts` 的 `adminWrite`：

- 请求标识：一次逻辑写入生成一个 `crypto.randomUUID()`，只放在 `X-Request-Id` 请求头，不写入请求体。绑定内容为方法、目标路径与精确请求体（含 version）。提交中按钮与弹窗关闭被锁定，双击或重复触发不会再次发送。
- 结果不确定：网络失败、网关或服务端 5xx、成功响应无法读出回执时，界面提示“提交结果尚未确认”，在内存中保留原绑定，不自动重发。可选“按原内容重试”（原标识、原请求体）、“重新读取核对”或“放弃核对”；结果不确定期间再次提交相同内容同样复用原标识。
- 新标识：内容、目标或 version 任一变化，以及上一次已确认或已被明确拒绝后，再次提交都使用新标识。“放弃核对”只丢弃本地绑定，旧提交仍可能已经执行。关闭弹窗、离开页面、退出登录或切换账号只会停止等待并丢弃绑定与晚到回执，不能撤回已经发出的写入。
- 版本冲突：409（含 `INVALID_VERSION`、层级冲突）或 404 拒绝后保留表单中的非敏感输入，并重新读取目标；重读期间禁止提交。确认最新事实后再提交，会携带新的 version 和新标识。
- 成功与刷新分离：写入确认后关闭弹窗并显示成功回执，然后重读列表、详情或部门树；重读失败只显示读取错误与“重新读取”，不会再次发送写入。
- 错误提示：只对上述白名单写入的失败响应读取最多 8 KiB 的 JSON，`code=500`、`data=null` 且 `reasonCode` 与 HTTP 状态匹配时才显示分类固定文本；其他情况显示当前 HTTP 的通用提示。前端不展示服务端 message 原文。403 拒绝会触发一次本人权限核对。
- 密码清理：初始密码与重置密码只出现在写请求体中，不进入回执、地址、日志或复制内容。提交得到任何结果后都会清空密码输入框；结果不确定时，原密码只保留在内存绑定中供“按原内容重试”使用，放弃核对、关闭弹窗或身份变化后即被丢弃。

## 管理入口与权限

管理区位于现有已认证布局内，只有三个精确路由：

| 路由 | 页面 | 读取资格 |
| --- | --- | --- |
| `/admin/users` | 账号管理 | `identity:user:read` |
| `/admin/departments` | 部门管理 | `identity:dept:read` |
| `/admin/roles` | 角色管理 | `identity:role:read` |

- 侧栏“身份管理”分组只显示当前账号有读取资格的入口；登录后的站内返回地址只增补这三个路径，`/admin`、`/admin/users/1` 等其他地址一律回到文档中心。
- 本人权限来自 `/api/auth/permissions`，按完整 code 精确判断，不依据角色名称（如 `ROLE_ADMIN`）、前缀或通配推断。只有写权限、没有读取资格时不进入页面，也不显示入口。
- 权限状态与会话 epoch 对齐，分为确认中、已确认、不可确认。查询失败只让管理区显示“重新确认”，文档、搜索等普通页面和当前身份不受影响；强制改密阶段不预取管理权限或数据。
- 进入管理路由、页面恢复（重新聚焦或从后台返回）、管理请求 403，以及用户角色绑定或角色权限变更成功后，都会重新核对本人权限。失去读取资格时立即关闭表单并清空页面数据；同一个 403 最多触发一次核对，不轮询、不循环登录。界面不承诺实时同步他人修改，最终以服务端判定为准。

| 行为 | 所需权限 | 候选读取（缺少时禁用并说明，不提供裸 ID 输入） |
| --- | --- | --- |
| 账号检索、详情、绑定角色摘要 | `identity:user:read` | 部门名称与部门筛选需 `identity:dept:read`，否则只显示部门 ID |
| 新建账号、调整主部门 | `identity:user:write` | `identity:dept:read` |
| 账号启停 | `identity:user:write` | — |
| 重置密码 | `identity:user:password` | — |
| 调整他人密级 | `identity:clearance:write`；目标不是本人 | — |
| 分配用户角色 | `identity:role:assign` | `identity:role:read` |
| 解除已读取的用户角色绑定 | `identity:role:assign` | — |
| 部门新建、改名、移动、启停 | `identity:dept:write` | 页面本身需 `identity:dept:read` |
| 角色新建、改名、删除、单项授权/撤权 | `identity:role:write` | 页面本身需 `identity:role:read` |

上述写操作都还需要当前页面的读取资格；这是可视界面的前提，不改变后端写接口可单独授权的事实。此处原有身份管理页面不另设权限；员工生命周期新增的后端权限见下文补充。

## 账号管理

- 检索：关键词匹配用户名或显示名，最多 128 个字符，不能含控制字符，不写入地址栏。主部门条件只精确匹配账号的主部门，不包含子部门；状态可选启用或停用。改变条件会清空已读游标，按“继续读取”追加后续页，不显示总数。
- 详情：显示用户名、显示名、主部门、密级和状态，停用账号同样可查看。绑定角色只显示角色名称与 code，可分页继续读取，不展开角色权限详情、外部身份或审计。
- 新建账号：主部门只列启用部门，密级来自实际密级字典；初始密码输入两次，强度由服务器判定。新账号首次登录必须修改密码，角色需在创建后的详情中单独分配。
- 主部门调整和启停：分别提交，各自携带当前 version，不夹带密级；版本冲突后重新读取再确认。停用最后一名合格管理员会被拒绝，不显示成功。
- 重置密码：独立于账号 version，重置后该账号下次登录需修改密码。调整密级：选项来自实际字典，不能调整本人密级。
- 角色：一次分配或解除一个角色。解除已不存在的绑定（404）或会失去最后一名合格管理员（409）时显示拒绝原因并重新读取绑定，不伪装成功。
- 不提供通用资料编辑、批量保存或密码回显；密码不会出现在回执、列表或详情中。

## 部门管理

- 部门树：按 `parentId` 构建，`parentId=null` 为根部门，可以有多个根；上级不在目录中的部门也作为根显示。停用部门保留在树中并标注“已停用”。`path` 只用于服务端，界面不据此推导层级或权限。名称按普通文本显示。
- 节点详情显示名称、编码、上级部门和状态；部门编码创建后只读。
- 新建：页头“新建根部门”提交 `parentId=null`；“新建子部门”以当前节点为上级。停用部门下不能新建子部门。重复编码会提示已存在。
- 改名：只提交 `name` 与当前 version，不改上级；版本冲突后重新读取再确认。
- 移动：在独立弹窗中选择目标上级并确认，选择本身不会提交，也没有拖拽移动。候选排除本节点、其全部后代、停用部门和当前上级；“设为根部门”提交 `parentId=null`。本节点的全部后代随之移动，层级路径同步变化，最终层级由服务端校验（并发造成的层级冲突会被拒绝并重新读取）。
- 启停：每次只作用于当前部门，不递归处理后代。停用前需先处理其下启用的子部门和用户，否则拒绝为“部门仍有启用的子部门或用户”；启用前上级部门必须处于启用状态，否则提示“目标或关联对象已停用”。界面不显示依赖数量。
- 任何部门写入成功或被冲突拒绝后都会重新读取整棵树和当前节点；刷新失败只提供重新读取。目标已不存在时显示提示并允许清除选择。
- 首期不提供部门删除。

## 角色管理

- 角色目录只显示名称与 code，不显示成员数（没有对应查询契约）。选中角色后读取其详情与服务端完整权限目录（`/permissions`，覆盖身份、审计、解析、空间、文档、向量等全部业务）。
- 权限按 code 首段分组展示：`identity` 身份管理、`audit` 审计、`parse` 解析处理、`space` 知识空间、`document` 文档、`embedding` 向量与检索；未知前缀原样作为组名。分组只影响展示，勾选状态只来自角色详情的 `permissions`，`ROLE_ADMIN` 也不会被自动勾选。详情中存在目录外的已授予权限时单独列出。
- 授权与撤权逐项提交：勾选或取消勾选后先确认，确认后发送一个 `PUT /roles/{roleId}/permissions`（`permission`/`granted`），每项一个请求标识，没有批量保存或原子提交。等待确认、提交中或结果不确定时，锁定该角色的全部勾选、改名、删除和角色切换；不确定时只能按原内容重试、重新读取核对或放弃核对，不形成后台队列。成功后重新读取角色详情并核对本人权限；被拒绝时重新读取详情，勾选回到服务端事实。撤销会使系统失去最后一名合格管理员时被拒绝。
- 新建角色：code 创建后只读，新角色没有任何功能权限，需逐项授予；不提供权限模板或默认授权。改名只提交 `name`，角色接口没有 version。
- 删除：确认后发送 `DELETE /roles/{roleId}`。仍有用户绑定或空间/文档授权引用时服务端拒绝（`ROLE_IN_USE`），界面只说明需先处理引用，不自动解除用户绑定或资源授权，也不显示引用数量。删除成功后清空详情并重新读取目录。
- 功能权限只控制管理与操作入口，不等于空间或文档正文的阅读资格；正文可见性仍由空间/文档授权和密级决定，本页不读取资源正文，也不管理 ACL。
- 本人失去 `identity:role:write` 时关闭待确认项与表单，勾选不可操作；失去 `identity:role:read` 时整个页面清空。角色被他人删除后（读取或写入返回 404），清除旧勾选与操作并重新读取目录。界面串行只约束本页，角色接口没有跨管理员的版本检测。

## 本次实施检查记录

2026-10-18 在 Windows PowerShell 7、Node.js 24.21.0、npm 11.19.0、Java 21.0.7、Maven 3.9.9（离线）环境完成：

| 工作目录 | 实际命令 | 结果 |
| --- | --- | --- |
| `kb-web` | `npm run typecheck` | 通过 |
| `kb-web` | `npm test -- --run` | 16 个单元测试文件、162 项测试全部通过；其中新增管理测试 6 个文件 78 项（`admin/api` 23、`admin/hooks` 9、`admin/access` 8、`admin/users` 19、`admin/departments` 9、`admin/roles` 10），路由与导航测试新增 4 项 |
| `kb-web` | `npm run build` | 通过，生成 `dist` 静态产物 |
| 项目根目录 | 下列 Maven 白名单命令 | 14 个测试类、159 项测试通过，失败/错误/跳过均为 0 |

```powershell
mvn -o -q -pl kb-app -am test '-Dtest=IdentityManagementFeedbackTest,GlobalExceptionHandlerTest,IdentityManagementControllerTest,IdentityClearanceControllerTest,IdentityServiceTest,IdentityServiceCreateUserTest,IdentityServicePasswordResetTest,IdentityServiceClearanceTest,IdentityServiceRoleTest,IdentityServiceLastAdminTest,DepartmentServiceTest,RoleManagementServiceTest,ModuleBoundaryTest,RoleManagementControllerTest#permissionsEndpointRequiresRoleWriteAndRejectsUnknownCode+unrelatedPermissionCannotAccessRoleEndpoints+roleReadReturnsLiveBindingsAndCatalogFromEnum+roleWriteCreatesEmptyRoleAndRejectsCodeChange' '-Dsurefire.failIfNoSpecifiedTests=false'
```

后端各类计数：IdentityManagementFeedbackTest 70、IdentityClearanceControllerTest 12、IdentityServiceClearanceTest 10、RoleManagementServiceTest 10、DepartmentServiceTest 8、IdentityServiceCreateUserTest 8、RoleManagementControllerTest 8（4 个业务方法，其中一个参数化 5 组）、IdentityServiceTest 7、IdentityServiceRoleTest 6、IdentityServiceLastAdminTest 5、IdentityServicePasswordResetTest 5、ModuleBoundaryTest 5、IdentityManagementControllerTest 3、GlobalExceptionHandlerTest 2。运行日志中的两条 `GlobalExceptionHandler -- 未处理异常` 来自验证未识别异常保持原处理的用例，属预期输出。

前端测试使用内存响应、受控 Promise/流和模拟 DOM；后端仅运行服务/Mapper 替身、standaloneSetup MockMvc 与 ArchUnit。未执行 `RoleManagementControllerTest#everyRoleEndpointHasOperationAndResponses` 文档注解断言、全量 Maven 测试、集成测试或文档文案测试；未启动开发服务器、应用、网关、浏览器、数据库、Redis、MinIO、容器或真实外部服务，也未监听端口。

## 员工生命周期管理补充

`/admin/identity/lifecycle` 为独立懒加载入口，要求 `identity:lifecycle:read`。账号详情的生命周期面板也独立检查该权限；普通账号读取资格不会触发生命周期摘要请求。本次后端新增的四项权限分别为读取、映射/事件管理、复职和应急登记，互不蕴含，前端消费完整 code，不按角色名称推断资格。

受管账号的摘要展示“受管来源：钉钉”及允许展示的连接标识，只需生命周期读取资格，不额外请求映射写接口，不展示员工标识。受管记录缺失有效连接标识时返回不可确认状态。

账号详情分别展示人工启停、生命周期限制和最终启停。纳管支持本地登录员工，但必须输入权威目录中的准确员工标识，经服务端核验当前成员并明确确认；没有姓名或手机号关联入口，也不修改 SSO 绑定。退出纳管不能解除离职限制，服务端还会拒绝未解决事件或在途复职。

普通账号启停、主部门、密级、密码和角色绑定操作成功，以及相关 404/409 重读时，会同步重新查询生命周期摘要，不从普通账号回执推断限制或最终状态。刷新会清除旧事实对应的生命周期确认表单及证明，读取期间不提供基于旧摘要的操作；没有生命周期读取资格时仍不发摘要请求。生命周期操作对普通详情的反向刷新不会形成循环。

复职先读取当前成员、部门、角色、密级和人工状态，五分钟证明有效期间填写原因并确认；确认时服务端再次核验。人工停用继续生效，普通启用遇到生命周期限制返回 409 并引导人工复职。已解除限制的账号仍须重新登录。

事件页每次请求最多 20 条，游标继续读取；已读条数不是全局总数。全局摘要来自独立服务端聚合。未匹配、待核对、失败与已停权分别呈现，重试只提交内部事件编号、原因和请求标识，不能替换事件载荷。员工项不返回原始员工 ID；历史停权结果不代表账号的当前状态。

应急页只允许当前候选本人输入本地密码；SSO/开发会话由服务端拒绝。选择既有登记账号进行轮换时先登记本人、再退役旧保障，原子执行；不收集他人密码。密码在发起核验时清空，确认请求只含短时证明。成功后重读当前保障资格，资格不足或最后保障冲突不展示成功。

敏感输入、密码、证明与未决写入绑定仅在组件内存中保存，不进入 URL、浏览器持久存储或日志。身份/目标变化、权限事实变化或页面隐藏会卸载操作域、中止请求并隔离迟到结果。核验失败不会自动提交；写入响应未知时保留原原因、版本、证明和请求标识，只允许显式“按原内容重试”、重读事实或放弃核对。过期证明仍可用于原请求回执核对，不能用于新的确认。关闭页面或放弃核对不能撤回已经发送的写入。

生命周期流程点击“放弃核对”会撤销旧证明、退出确认并重新读取当前事实。原请求仍可能已经执行，必须核对重读结果；只有再次进入操作并完成新的显式核验、填写原因和确认后，才会生成新的请求标识，不能复用旧证明提交新原因。该规则不改变其他管理操作的共用绑定重置行为。

验证采用 jsdom、API mock 与可控时间，不启动前端服务或外部组件；另运行 TypeScript 检查及生产构建。相关测试位于 `kb-web/src/admin/lifecycle/lifecycle.test.ts`，使用 `@testing-library/vue`。
