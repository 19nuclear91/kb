# 身份与部门查询规格增量

## ADDED Requirements

### Requirement: 用户检索必须新增兼容的有界查询入口

系统 SHALL 提供 `GET /api/admin/identity/users/search`，要求当前启用主体持有 `identity:user:read`，支持
q、departmentId、enabled、limit、cursor。q 是用户名或显示名的大小写不敏感字面包含匹配，长度最多 128 个 Unicode code
point；departmentId 为精确主部门过滤，不隐式包含子树。响应 SHALL 为稳定分页的身份摘要，不包含凭据、外部 subject 或角色权限集合。

#### Scenario: 多条件用户查询

- **WHEN** 合格用户同时提供 q、departmentId 和 enabled
- **THEN** 按条件交集返回用户；停用用户可作为被管理目标返回，但调用者必须保持启用

#### Scenario: 关键词包含通配符

- **WHEN** q 包含 `%`、`_` 或反斜杠
- **THEN** 按普通字符搜索，不扩展 SQL 通配或改变查询结构

#### Scenario: 查询不存在的部门

- **WHEN** 合格调用者按不存在的 departmentId 查询
- **THEN** 返回空页，不忽略部门条件而返回全部用户

### Requirement: 用户角色绑定必须可按目标安全读取

系统 SHALL 提供 `GET /api/admin/identity/users/{userId}/roles`，要求 `identity:user:read`，返回目标用户已绑定角色的
roleId、code、name 有界分页。该接口 MUST 不返回角色的功能权限、不隐含完整角色目录读取或角色分配资格。目标可以停用；合法目标无绑定返回空页，有资格调用者查询不存在目标返回
404，未授权请求在查询目标前返回 403。

#### Scenario: 查询已停用用户的角色

- **WHEN** 合格操作者查询已停用但仍存在的用户
- **THEN** 返回其持久角色绑定，不因目标停用将其伪装成无角色

#### Scenario: 只有用户读取资格

- **WHEN** 操作者持有 identity:user:read，但没有 identity:role:read 或 identity:role:assign
- **THEN** 可读取目标的最小绑定摘要，不能通过本接口读取完整权限目录或修改绑定

### Requirement: 外部身份绑定读取必须最小化且使用独立资格

系统 SHALL 提供 `GET /api/admin/identity/users/{userId}/external-identities`，要求 `identity:external:read`，支持可选
provider 精确过滤和有界分页；每项只包含 identityId、userId、provider、enabled。任何 subject 原值、片段、可逆编码、摘要或凭据
MUST 不被返回或作为检索条件。合法目标无绑定返回空页，不存在目标仅在鉴权成功后返回 404。

#### Scenario: 按提供方查询绑定

- **WHEN** 合格用户按 provider 查询指定用户的外部身份
- **THEN** 仅返回该目标和提供方的绑定记录 ID、状态及提供方标识
- **AND** 列表项仍可供现有解绑接口使用，不需要回显 subject

#### Scenario: 仅持有绑定写权限

- **WHEN** 操作者持有 identity:external:write，但未被授予 identity:external:read
- **THEN** 读取返回 403；既有绑定和解绑资格不因本查询变更

### Requirement: 密级字典必须具有只读查询契约

系统 SHALL 提供 `GET /api/auth/security-levels`，供已认证、用户及主部门启用且已完成强制改密的调用者读取服务端实际字典。返回升序的
level 列表，不硬编码级数或显示名称；空字典返回空列表。此查询 MUST 不提供字典写入、不改变操作者密级，也不授予用户或文档密级调整资格。

#### Scenario: 字典增加新级别

- **WHEN** 字典合法增加一个取值后发起下一次查询
- **THEN** 新列表包含该取值，无需修改客户端常量或等待跨请求缓存到期

#### Scenario: 字典读取故障

- **WHEN** 无法可靠读取字典
- **THEN** 返回安全的 503，不以空列表或默认 0–3 代替真实字典

### Requirement: 新身份查询必须使用稳定有界分页和当前资格

用户检索、用户角色及外部绑定查询 SHALL 使用 limit 默认 20、范围 1–100，分别按 userId、roleId、identityId 升序返回
items、hasMore、nextCursor。游标 MUST 绑定查询路径、目标、过滤条件和上页末尾 ID；非法 ID、空过滤值、未知参数、越界 limit 或无效游标返回
400。每次请求 MUST 重新鉴权，主体或主部门停用、强制改密返回 403，无会话返回 401。

#### Scenario: 更换目标复用绑定游标

- **WHEN** 调用者将用户 A 的角色或外部绑定游标用于用户 B
- **THEN** 返回 400，游标不改变当前请求的目标或权限

#### Scenario: 分页期间撤销身份读取资格

- **WHEN** 第一页之后当前调用者的相应读取资格被撤销
- **THEN** 下一次列表或绑定查询返回 403，不依赖会话过期

### Requirement: 新身份查询不得破坏原接口或泄露失败数据

新增接口 SHALL 使用专门的最小响应投影并标记 `Cache-Control: no-store`。既有 `GET /api/admin/identity/users` 及
`/api/identity/users` MUST 继续返回原有数组，`GET /api/auth/me` MUST 保持既有结构；新路径不新增 `/api/identity`
兼容别名。依赖故障返回安全 503，参数错误不回显用户名、provider、subject 或底层异常。

#### Scenario: 旧调用方继续读取用户列表

- **WHEN** 既有调用方在升级后调用原用户列表或会话主体接口
- **THEN** 响应结构及原有权限要求保持不变，不把数组静默改成分页对象

#### Scenario: 构造带凭据字段的内部绑定对象

- **WHEN** 内部数据库记录含完整 subject 或其他凭据字段
- **THEN** 对外查询仍只返回批准字段，不因为新增实体字段而扩大响应
