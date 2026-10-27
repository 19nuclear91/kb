# 空间授权主体检索规格增量

## ADDED Requirements

### Requirement: 授权主体检索必须绑定当前资源管理资格

系统 SHALL 提供 `GET /api/admin/spaces/{spaceId}/acl/principals` 和
`GET /api/admin/documents/{documentId}/acl/principals`。调用者必须是启用且主部门有效、已完成强制改密的用户，并按当前目标空间或文档实际所属空间拥有
manage 或 `space:manage-all`。不具备完整身份目录读取资格的空间管理员也 SHALL 可使用该最小检索；此授权 MUST 不扩展原身份目录接口。

#### Scenario: 空间管理员没有身份目录读取权限

- **WHEN** 用户能够管理空间 A，但没有 identity:user:read、identity:role:read 或 identity:dept:read
- **THEN** 可在 A 的上下文检索最小授权目标，原全量身份与角色目录仍按原资格拒绝

#### Scenario: 文档已经搬离原空间

- **WHEN** 原空间管理员使用旧文档 ID 请求授权目标，而文档现已迁往其无权管理的空间
- **THEN** 请求不再返回主体候选，不以调用者声明或旧游标中的空间认定资格

### Requirement: 授权候选必须采用固定最小投影

请求 SHALL 必须指定 principalType 为 USER、ROLE 或 DEPT；每项仅返回
principalType、principalId、label。用户候选仅包含用户及其主部门均启用者，部门候选仅包含启用部门，角色候选取仍存在的角色。候选可来自资源之外的部门，以维持既有跨主体授权能力。MUST
NOT 返回用户名、部门祖先链、密级、角色成员/权限、外部身份、凭据或完整主体实体。

#### Scenario: 按用户显示名查找跨部门授权目标

- **WHEN** 合格空间管理员检索另一个启用部门中的启用用户
- **THEN** 可得到其 ID 与显示标签，不返回该用户的用户名、部门关系或密级

#### Scenario: 候选被停用或删除

- **WHEN** 用户或其主部门已停用，部门已停用，或角色已不存在
- **THEN** 相应候选不出现在新检索结果中，既有 ACL 记录不因此被查询接口删除

### Requirement: 主体检索必须有界并且不得扩展写入资格

主体检索 SHALL 支持可选 q 的显示标签字面包含匹配、limit 与 cursor，q 最多 128 个 Unicode code point；按同类型 principalId
升序返回 items、hasMore、nextCursor，limit 默认 20、范围 1–100。游标绑定资源种类、资源 ID、主体类型和过滤条件。查询 MUST 不授予
ACL、不调整密级、不允许空间管理员据此授出 manage，也不承诺候选在后续写入时仍有效。

#### Scenario: 同名主体以及特殊字符

- **WHEN** 多个主体具有相同标签，或 q 包含 SQL 通配字符
- **THEN** 以 ID 保持确定排序，并按字面标签匹配，不把同名主体合并或展开通配符

#### Scenario: 更换资源或类型复用游标

- **WHEN** 将空间 A 的用户游标用于空间 B、文档查询或角色查询
- **THEN** 返回 400，不复用先前资源的管理资格

#### Scenario: 以候选查询结果尝试授出 manage

- **WHEN** 仅持空间 manage 的用户选中候选后提交 manage 授权
- **THEN** 写操作仍按既有独立 space:grant-manage 资格拒绝，不因候选可检索而放行

### Requirement: 主体查询必须维持归档管理可达和读取隐私

归档空间的合格管理员 SHALL 仍能执行最小主体检索。缺失或无权管理的资源在新上下文接口上 MUST 统一返回 404；无会话返回
401，调用者停用或强制改密返回 403。非法参数返回 400，依赖故障返回安全 503，成功响应标记 `Cache-Control: no-store`。MUST
不返回未授权候选计数或资源名称，也不为查询创建成功授权审计。

#### Scenario: 归档空间的授权维护

- **WHEN** 空间已归档但调用者仍有 manage
- **THEN** 主体检索可用，普通正文读取仍遵循归档限制

#### Scenario: 无权访问的资源与不存在的资源

- **WHEN** 合格登录者分别以合法 ID 查询不能管理和不存在的资源
- **THEN** 两者均返回同一安全 404，不执行全局主体枚举

#### Scenario: 查询与授权依赖故障

- **WHEN** 资源资格或主体查询无法可靠完成
- **THEN** 返回 503，不将故障解释为空候选或绕过上下文去查完整身份目录
