# 文档密级管理

## 权限与迁移

文档密级管理使用独立权限 `document:security-level:write`。空间 manage、edit、`space:manage-all` 和用户密级权限
`identity:clearance:write` 均不能替代它。通过现有角色权限管理接口显式授予该权限；运行时只认权限绑定，不认管理员角色名称。

操作者和主部门必须启用，文档原密级与目标密级均不得超过操作者当前密级。密级管理资格不授予正文阅读或下载权限；正文仍按
ACL、密级与归档状态判定。

V14 迁移为 `kb_document` 增加默认 0、非空且非负的 `security_version`，并一次性为当时已有的 `ROLE_ADMIN`
绑定新权限。无管理员角色时由正式引导创建，新角色获得权限目录全集；既有角色撤销权限后，重启或重新引导不会自动补回。V1 至 V13
的迁移文件保持原内容。

部署需使用包含新权限目录的应用版本，旧版本重启时可能因不认识新权限 code
而拒绝启动。优先通过修复新版或关闭入口处理故障。确需回退二进制时，应先停止新请求、保存并移除所有角色上的该新权限绑定；保留新增列、已调整的密级和审计。恢复新版时按保存的授权恢复，Flyway
不会重跑已执行迁移，启动引导也不会补齐既有角色。

本变更的验证仅包含单元测试、静态检查和文档核对，不执行数据库迁移或部署。

## 查询与调整接口

`GET /api/admin/documents/{documentId}/security-level` 返回当前 `documentId`、`securityLevel`、`securityVersion`，使用现有
`R` 响应包装。不要求正文 view 或空间 manage，也不返回标题、正文或文件地址。所有未删除文档均可管理，包括草稿、审核中、已发布、已归档和归档空间内文档；读取权限另行判断。

`PATCH` 同一路径调整密级。请求字段如下：

| 字段                | 规则                                                    |
|-------------------|-------------------------------------------------------|
| `securityLevel`   | 字典中的整数级别，且原密级和目标级别均不超过操作者密级                           |
| `securityVersion` | 查询返回的非负整数密级版本；与正文版本无关                                 |
| `reason`          | 必填，去除首尾空白后 1–500 个 Unicode 字符                         |
| `requestId`       | 可在请求体提供，或使用 `X-Request-Id`；去除首尾空白后 1–128 个 Unicode 字符 |

两个位置都提供请求标识时必须一致。未知业务字段、字符串形式的数值和小数均被拒绝，不允许夹带操作者、空间或 ACL 变更。

以下为 PowerShell 调用示例，需在业务服务可用时由调用方执行；它不是本次编码任务的验证步骤。`$env:KB_ADMIN_TOKEN`
为已登录且具有上述资格的用户会话。

```powershell
$documentId = 7
$uri = "http://localhost:8081/api/admin/documents/$documentId/security-level"
$headers = @{ satoken = $env:KB_ADMIN_TOKEN }
$current = Invoke-RestMethod -Method Get -Uri $uri -Headers $headers
$requestId = [guid]::NewGuid().ToString()
$body = @{
    securityLevel = 2
    securityVersion = $current.data.securityVersion
    reason = '定级复核'
    requestId = $requestId
} | ConvertTo-Json
$receipt = Invoke-RestMethod -Method Patch -Uri $uri -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $body
$receipt.data
```

首次成功回执示例：

```json
{
  "documentId": 7,
  "previousSecurityLevel": 1,
  "securityLevel": 2,
  "previousSecurityVersion": 0,
  "securityVersion": 1,
  "requestId": "client-generated-id",
  "changedAt": "2026-10-05T00:00:00Z",
  "replayed": false
}
```

降密使用同一接口，重新查询后提交较低目标及新请求标识。原密级超出操作者密级时，即使目标级别很低也返回 `403`。

超时重试须使用相同操作者、文档、目标、预期版本、规范化原因及请求标识。成功重试返回首次的密级、版本和时间，`replayed=true`
；不会使用文档后来变化的状态替代首次回执。重试仍校验当前权限、文档存在性，以及当前文档和首次前后密级是否在操作者当前上限内。

| 状态    | 含义及处理                                    |
|-------|------------------------------------------|
| `400` | 缺失或无效字段、字典外密级、双标识冲突、夹带字段；修正请求            |
| `401` | 会话缺失或失效；重新登录                             |
| `403` | 权限不足、身份或主部门停用、原/目标/重放历史密级越界              |
| `404` | 文档不存在或已删除，包含匹配重试                         |
| `409` | 版本过期、目标与当前相同，或标识被用于不同内容；过期版本需重新查询并用新标识提交 |
| `500` | 持久化或审计失败；不返回成功回执，事务异常交由 Spring 回滚        |

## 事务与生效边界

调整服务在同一 `READ_COMMITTED` 事务内依次取得操作域/请求标识的事务锁、文档行锁，再修改文档密级和 `security_version`
、同步全部内容版本分块、追加成功审计。锁等待后清理 MyBatis 一级缓存并重新解析权限、启用状态和密级。已命中的请求先精确比对绑定，合法重放不再写入。

已有 V2 触发器也会同步分块；服务的同步 SQL 只更新密级仍不一致的分块，避免重复更新检索索引。零分块为合法状态。任一步异常直接抛出，审计新入口要求已有事务，不在唯一约束错误后尝试继续查询已失败的
PostgreSQL 事务。

`security_version` 从 0 开始，每次实际密级变化加一。正文发布和空间搬迁不推进它，因此密级 A→B→A
后的旧请求仍会因版本过期而冲突。调整不改变正文、向量、文档状态、内容版本指针、空间和 ACL。

Worker 发布保持先锁文档再写分块，并从文档当前行取得 `security_level`。调整服务不在文档锁后再锁解析任务或内容版本，保持锁顺序兼容。提交后的新读取按新密级和既有
ACL/归档约束执行；降密不会解归档或给用户新增 ACL。提交前已开始的下载和 AI 响应不在撤回范围内。

审计操作域为 `DOCUMENT_SECURITY_LEVEL_CHANGE`，目标类型为 `document`。记录真实操作者、目标、请求标识、原因、时间和前后密级/版本，不含正文或凭据；通过现有审计查询接口和
`identity:audit:read` 资格读取。

## 无外部依赖的验证

在项目根目录运行：

```powershell
mvn -pl kb-app -am test '-Dtest=DocumentSecurityLevel*Test,IdentityAuditServiceTest,AuditScopeTest,PermissionCatalogTest,DocumentSecurityPermissionTest,AdminBootstrapRunnerTest,ModuleBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false'
mvn -pl kb-worker -am test '-Dtest=ChunkPublisherTest' '-Dsurefire.failIfNoSpecifiedTests=false'
openspec validate document-security-level-change --strict
```

服务单元测试使用模拟 Mapper、身份服务和 MyBatis 会话，验证规则 A、状态冲突、重试绑定、失败传播及等待锁后的重新授权；控制器测试使用
MockMvc standalone，校验实际 JSON 输入和 HTTP 响应。审计兼容测试保留原调用入口，新增入口单独验证原因及写入失败传播。

Worker 测试与 SQL 静态复核仅证明调用顺序和写入契约；迁移文件仅检查内容及旧文件一致性。本次没有运行真实数据库迁移、事务回滚、锁竞争、跨服务检索或外部组件集成测试，不能把上述单元测试结果当成这些行为已实测的证据。

2026-10-05 验证记录：以上选定测试共 72 项通过（`kb-app` 69 项、Worker 3 项），失败、错误与跳过均为 0。11
份既有正式迁移与本次编译前的副本哈希一致；静态复核确认查询排除 deleted、更新按密级版本判断、分块同步覆盖全部版本、正文访问仍走原有密级与
ACL 过滤。
