# 权限诊断 API

六个端点均为 `POST`、`Content-Type: application/json`，成功和失败均 `Cache-Control: no-store`。不接受 URL 查询参数、重复字段、未知字段、身份事实或版本覆盖。正文 ID 是 1–9223372036854775807 的精确 JSON 整数；响应 ID 是十进制字符串。

| 端点 | 正文 |
| --- | --- |
| `/api/admin/spaces/{spaceId}/authorization-diagnostics/users/search` | `q`、`limit`、可选 `cursor` |
| `/api/admin/spaces/{spaceId}/authorization-diagnostics/explain` | `userId`、可选同空间 `documentId` |
| `/api/admin/spaces/{spaceId}/authorization-diagnostics/preview` | `userId`、可选同空间 `documentId`、`intent` |
| `/api/admin/documents/{documentId}/authorization-diagnostics/users/search` | `q`、`limit`、可选 `cursor` |
| `/api/admin/documents/{documentId}/authorization-diagnostics/explain` | `userId` |
| `/api/admin/documents/{documentId}/authorization-diagnostics/preview` | `userId`、`intent` |

操作者需有效会话、身份／主部门及改密条件、`authorization:diagnose`，再核对路径空间 manage 或 `space:manage-all`。文档以当前所属空间鉴权；附选文档不属于路径空间为安全 404。含 manage 的预演还需 `space:grant-manage`。目标用户不能替换操作者身份或为其自授准入。

搜索 q 按显示名字面匹配，最多 128 个 code point；limit 缺省 20、范围 1–100。按 userId 升序，仅返回 `userId/label`；游标绑定操作者、认证代次、资源、q 和 limit，每页重新鉴权。成功 `data={contract:"AUTHORIZATION_DIAGNOSTIC_USERS_V1",resource,items,hasMore,nextCursor}`，末页 nextCursor 为 null，没有总数。

## 意图

| type | 必填字段与约束 |
| --- | --- |
| SPACE_GRANT | principals：1–50 个不同 `{principalType,principalId}`；permissions：1–6 个不同权限位，完整笛卡尔积 |
| SPACE_REVOKE | items：1–300 个不同 `{principalType,principalId,permission}`，只撤销显式组合 |
| DOCUMENT_GRANT | principalType、principalId、permission，一条例外 |
| DOCUMENT_REVOKE | principalType、principalId、permission，一条例外 |

主体类型仅 USER／ROLE／DEPT。空间权限为 view／download／edit／review／comment／manage；文档只允许 view／download／edit／comment。类型必须匹配路径资源。授予角色引用须存在；撤销残留行不要求角色仍存在。USER／DEPT 引用沿用原写接口，不增加成员枚举。重复授予、缺失撤销为 UNCHANGED。

示例：观察用户 8 在空间 10 的文档 20 上，拟新增部门 5 的 view：

```json
{"userId":8,"documentId":20,"intent":{"type":"SPACE_GRANT","principals":[{"principalType":"DEPT","principalId":5}],"permissions":["view"]}}
```

## 响应

外层为 `R<T>`：`code=200,message="ok",data=...`。解释和预演的 data 字段如下；所有字段必填，未适用字段明确为 null。

| 字段 | 含义 |
| --- | --- |
| contract | 固定 AUTHORIZATION_DIAGNOSTICS_V1 |
| mode | EXPLAIN 或 PREVIEW |
| user | 本次目标的 userId、label |
| resource | kind=SPACE／DOCUMENT、spaceId、documentId（未选文档为 null） |
| evaluatedAt | 同次数据库事务时间，带偏移 ISO-8601 |
| assumptions | 固定 SELECTED_USER_AND_RESOURCE_ONLY、FACTS_AT_EVALUATION、LISTED_ACL_ONLY、FILE_AVAILABILITY_NOT_CHECKED |
| before | 当前事实的完整层 |
| after | 预演完整假设层；解释为 null |
| changes | 全部规范化拟议条目；解释为空 |

每层包含 `subjectCondition=PASSED/BLOCKED`、固定六项 `spacePermissions`、固定五项 `documentPermissions`（无文档为 null）、`currentVersionAccess`、去重 `sources`。权限项含 permission、grantMatch（MATCHED／NOT_MATCHED／NOT_EVALUATED）、scopeCondition（PASSED／BLOCKED／NOT_EVALUATED）、reasonCodes、sourceKeys。

来源含 key、sourceKind、principalType、principalId、label、permission、origin。SPACE／DOCUMENT 来源键为 `范围:类型数字:主体ID:权限位`，类型数字 1 用户、2 角色、3 部门；GLOBAL_MANAGEMENT 是固定键与类型，主体类型和 ID 为 null。origin 为 EXISTING 或 PROPOSED。新行不生成数据库 ACL id。sources 无重复，sourceKeys 无悬空引用；同一空间来源可被空间层和文档层复用。

当前版本状态优先级：未选文档 NOT_APPLICABLE；身份受阻 NOT_EVALUATED；身份有效而指针为空 NO_CURRENT_PUBLISHED_VERSION；其余 ASSESSED。已选文档 scope=CURRENT_PUBLISHED，否则 null。只有 ASSESSED 返回 view／download 的 ALLOWED 或 DENIED，其他状态均 null；另有 viewReasonCodes／downloadReasonCodes。不包含版本 ID、号、时间或原件地址。

原因仅 SUBJECT_BLOCKED、SPACE_ARCHIVED、CLEARANCE_BLOCKED、AUTHORIZATION_NOT_GRANTED、VIEW_NOT_GRANTED、DOWNLOAD_NOT_GRANTED。changes 含 sourceKind、principalType、principalId、permission、action（GRANT／REVOKE）及 outcome（ADDED／REMOVED／UNCHANGED），不得作为正式回执；前端核对与输入精确条目集合相等，并双向核对前后来源及 outcome。USER 来源必须属于本次目标；对本人及已披露的角色／部门命中关系检查应有来源，不推断未披露的成员关系。矛盾响应整体拒绝。

## 限额、错误与证据

默认请求 64 KiB、目标主体键 4096、before／after 来源并集 1000、完整响应 512 KiB、总预算 5 秒。`kb.authorization-diagnostics` 可收紧这些正值上限。只读 REQUIRES_NEW／REPEATABLE_READ 事务包住鉴权、事实和前后比较，每条 SQL 按剩余预算设置超时；编码预检超限或中断均整体拒绝。

失败外层 `code=500,data=null,message=固定中文提示,reasonCode=固定类别`：400 INVALID_INPUT 或 DIAGNOSTIC_LIMIT_EXCEEDED；413 PAYLOAD_TOO_LARGE；401 UNAUTHORIZED；403 FORBIDDEN；404 NOT_FOUND；503 DIAGNOSTIC_UNAVAILABLE。依赖故障不伪装成无授权。原正式写入口不接受预演作为凭据，仍独立鉴权。

接口使用严格流解码，OpenAPI 的请求模型描述形状，控制器返回准确的 `R<DiagnosticViews.Result>`／`R<DiagnosticViews.Users>` 泛型。独立测试直接调用服务、过滤器、路由、事务与 JDBC 替身；未启动 HTTP 服务或执行真实 SQL。V43 只交付未执行，数据库隔离、网络、浏览器与联调均未验证。
