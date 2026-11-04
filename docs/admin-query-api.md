# 后端管理查询接口

企业浏览器认证事件同样来自 `sys_identity_audit`：`SSO_LOGIN` / `SSO_BIND`，目标类型 user，操作 login / bind，请求标识由服务端
flowId 生成。未知内部用户的失败 actor/target 为 null；不把平台授权成功记成会话成功。全局审计仍要求当前 `audit:read`
，使用现有时间窗口、游标与详情投影，原管理员外部绑定事件和旧接口保持不变。

新增摘要仅包含固定
platform（dingtalk/wecom/feishu）、resultCode（OK、ALREADY_BOUND、BINDING_REQUIRED、NOT_MEMBER、USER_DISABLED、PASSWORD_CHANGE_REQUIRED、CREDENTIAL_REJECTED、BINDING_CONFLICT、INVALID_FLOW、UPSTREAM_UNAVAILABLE、STORAGE_UNAVAILABLE、CONFIG_CHANGED），绑定另有
bound 布尔值。非法枚举降级 `summaryAvailable=false`，不输出 subject、密码、哈希、授权码、state、Cookie 或令牌。`loginUsername`
仍只筛选 AUTH_LOGIN_SUCCESS/FAILURE，SSO 不补录用户名。进程崩溃或审计依赖不可用可能没有事件，不能把未查到事件解释为操作未发生。

所有入口均为 GET，沿用服务端验证的会话与 `R<T>`
包装；公共参数、状态码和升级约束见 [管理查询公共协议](admin-query-operations.md)。本文件只作人工核对，不执行示例或文案测试。

| 路径                                                       | 当前请求资格                               | 查询参数                                                                                                         |
|----------------------------------------------------------|--------------------------------------|--------------------------------------------------------------------------------------------------------------|
| `/api/admin/documents`                                   | 所属空间 manage 或 `space:manage-all`     | spaceId、documentId、spaceArchived、limit、cursor                                                                |
| `/api/admin/identity/users/search`                       | `identity:user:read`                 | q、departmentId、enabled、limit、cursor                                                                          |
| `/api/admin/identity/users/{userId}/roles`               | `identity:user:read`                 | limit、cursor                                                                                                 |
| `/api/admin/identity/users/{userId}/external-identities` | `identity:external:read`             | provider、limit、cursor                                                                                        |
| `/api/auth/permissions`                                  | 当前启用用户与有效主部门，已完成强制改密                 | 无                                                                                                            |
| `/api/auth/security-levels`                              | 当前启用用户与有效主部门，已完成强制改密                 | 无                                                                                                            |
| `/api/admin/spaces/{spaceId}/acl/principals`             | 指定空间 manage 或 `space:manage-all`     | principalType、q、limit、cursor                                                                                 |
| `/api/admin/documents/{documentId}/acl/principals`       | 文档当前所属空间 manage 或 `space:manage-all` | principalType、q、limit、cursor                                                                                 |
| `/api/admin/audits`                                      | `audit:read`                         | actorUserId、operationScope、operation、targetType、targetId、status、requestId、loginUsername、from、to、limit、cursor |
| `/api/admin/audits/{eventId}`                            | `audit:read`                         | 无                                                                                                            |

## 最小文档清单

分页 data 示例：

```json
{"items":[{"documentId":9,"spaceId":3,"spaceArchived":true}],"hasMore":false,"nextCursor":null}
```

每项固定三个字段，即使同时有 view 也不扩展。空间 manage
足以定位已归档、密级高于自身或首个版本尚未发布的文档；软删除文档排除。标题、文件名、密级、版本、正文、解析错误、对象键和外部地址均不返回，也不能作为过滤条件。普通阅读、预览、下载、授权和搬迁继续由各自原有接口判定。

spaceId/documentId 为正整数；spaceArchived 严格为 true/false。按文档 ID 倒序，无当前管理范围、指定无权目标和不存在目标均返回空页。继续翻页使用
nextCursor；改变过滤条件需重新查询。文档搬迁或管理资格撤销后的下一次请求按最新事实过滤，游标不保留旧资格。

## 身份与自助授权事实

用户搜索 q 去首尾空白后，以用户名或显示名做大小写不敏感的字面包含匹配，最多 128 个 Unicode code
point；百分号、下划线和反斜杠均按普通字符匹配。departmentId 仅匹配主部门，不展开子树；enabled 严格为 true/false。用户摘要包含
userId、username、displayName、departmentId、clearanceLevel、enabled、version，不包含凭据或角色权限。按 userId 升序。

用户角色页按 roleId 升序，每项仅 roleId/code/name；停用目标仍查询持久绑定，有资格时不存在目标返回 404，无绑定返回空页。user:
read 不开放角色权限详情或任何角色写入。

外部绑定页按 identityId 升序，每项仅 identityId/userId/provider/enabled。provider 精确匹配，最多 128 个 Unicode code
point。subject 原值、片段和摘要均不返回，也不接受 subject 搜索。写资格不隐含新读取资格。

permissions 返回当前持久角色权限并集的去重、按 code 排序数组，无功能权限时返回空数组。security-levels
返回实际字典的数值升序数组，包括合法新增级别，空字典返回空数组。两者都不接受 userId 或角色选择参数，不授予后续请求资格，不加入强制改密豁免。

原 `/api/admin/identity/users`、`/api/identity/users` 数组及 `/api/auth/me` 结构保持原样；本次新查询不增加 `/api/identity`
别名。旧路径上已有 POST/DELETE 的位置收到 GET 时可能返回 405，这不代表新增读取别名。

## 资源上下文候选

principalType 必填，严格为 USER、ROLE、DEPT。每项固定 principalType/principalId/label；q 仅对 label 字面包含匹配，最多 128 个
Unicode code point，按同类型 ID 升序分页。用户及其主部门都必须启用，部门必须启用，角色必须仍存在；可跨部门检索。

只有空间管理资格且没有完整身份目录读取权限的用户可使用最小候选。接口不返回用户名、部门关系、密级、成员、权限或外部身份；资源无权与不存在统一
404，归档空间仍可管理。文档入口按当前实际所属空间鉴权，不能提交 spaceId 替代该事实。游标绑定资源种类、资源 ID、主体类型和
q。候选不代表后续写入时仍有效，授出 manage 仍要求独立 `space:grant-manage`。

## 全局审计

列表精确条件按 AND 组合，大小写保留；targetId 必须伴随 targetType，targetType 单独合法。actorUserId/targetId 为正整数，status
严格为 SUCCESS/FAILED。operationScope/operation 最多 128、targetType 最多 64、requestId 最多 256、loginUsername 最多 128 个
Unicode code point。合法未知值返回空页；loginUsername 只在 AUTH_LOGIN_SUCCESS/AUTH_LOGIN_FAILURE 中精确匹配已记录
username，与其他操作域同时提供不会忽略任一条件。

from/to 必须带时区，规范化为 UTC 微秒精度，范围为 `[from,to)`。默认 to 是首次请求时间，from 是 to 前 7 天；最大跨度 31
天。仅给一端时按相同缺省规则补齐另一端并检查跨度。审计 data 除 items/hasMore/nextCursor，还返回实际
from/to；空页也返回窗口。继续请求省略时间沿用游标窗口，显式提供的时间必须与其一致，等价时区按同一 UTC 时间处理。按
createdAt、id 双字段倒序分页。

列表字段为 id/eventId/operationScope/requestId/actorUserId/targetType/targetId/operation/status/createdAt，登录事件另有有界
attemptedUsername；未知账号失败可以保留 null actorUserId/targetId。详情 data 为 metadata（同一安全元数据）、changes 和
summaryAvailable。changes 为 field/before/after 标量项，按已注册操作域校验 ID、布尔、密级、权限、版本和死信状态；不加载目标当前名称。

例如归档摘要只可出现 archived/version，文档搬迁只可出现空间 ID 与清理/阻止计数，角色授权只输出目录中的
permission。外部绑定及密码事件不提供可还原内容。原
beforeState/afterState、reason、自由名称、provider、subject、密码、哈希、token、标题、文件名、正文、对象键和远程错误体均不返回。未知操作域、非法
JSON 或已注册字段类型错误时，保留 metadata，changes 为空且 summaryAvailable=false，不回显原字符串。查询不写审计，不修补和删除历史事件。

管理端整文档删除写入操作域 `DOCUMENT_ADMIN_DELETE`：targetType 为 `document`，targetId 为被删文档 ID，operation 为
`delete`，requestId 即客户端提交的 `X-Request-Id`；可按 operationScope、targetType+targetId 或 requestId 检索，相同标识重放不产生第二条事件。详情
changes 只可出现 spaceId（删除时所属空间）与 spaceArchived（该空间当时是否已归档），均为变更前值、变更后为 null；标题、文件名、版本、对象键与操作者以外的身份信息均不进入摘要。用户端
`DELETE /api/documents/{id}` 不写审计，查不到该操作域事件不代表文档未被删除。

### 数据源覆盖

空间批量授予使用操作域 code `SPACE_ACL_BATCH_GRANT`，targetType 为 `space-acl`，targetId 为目标空间 ID，
operation 为 `batch-grant`。可按 operationScope、targetType+targetId、requestId 与时间窗口检索；一批只写一条成功事件，
相同 `X-Request-Id` 重放不增加事件。详情仅投影 `spaceId`、`grantedCount`、`alreadyGrantedCount`、
`clearanceBlockedPrincipalCount`，最后一项按至少新增一条授权的主体计数。逐条主体、权限位及 `alreadyGranted`
只保存在审计 `after_state.items[]` 快照中，不在审计查询摘要展开。

首期只查询 `sys_identity_audit` 中生产者已经写入的管理及登录事件，包括成功、失败、目标为 null 和目标后来被删除的事件。

- 覆盖用户生命周期、部门、角色权限、外部身份绑定、空间/文档管理与 ACL、管理端文档删除、文档密级、登录和解析死信等已记录事件。
- 不包含 Redis AI 审计、独立 Embedding 操作表，也不拼接不同来源的保留期或排序。
- 不补录生产者未留下记录的请求，包括登录节流期间未记录的尝试。没有查到记录不能证明操作从未发生。

新 `audit:read` 独立于旧 `identity:audit:read`；空间管理、文档密级、外部身份或 Embedding
管理资格均不隐含全局审计读取。旧按目标审计接口的路径、资格和返回结构不变。
