# Spec Delta

## MODIFIED Requirements

### Requirement: 管理页面必须复用既有会话和受保护路由

用户端 SHALL 提供 `/admin/users`、`/admin/departments`、`/admin/roles`、`/admin/spaces`、`/admin/spaces/:spaceId`、`/admin/documents`、`/admin/documents/:documentId`、`/admin/embedding`、`/admin/parse/dead-letters`、`/admin/audits`，沿用当前登录、强制改密及退出流程。合法管理路径 SHALL 可直接访问、刷新及作为登录后的站内返回地址，且返回后重新核对资格；默认登录落点保持文档中心。路径中的空间或文档 ID 不是合法正整数时 SHALL 显示目标不可访问，且不发出管理请求。

#### Scenario: 未登录访问管理页面
- **WHEN** 用户直接打开账号管理地址但没有有效会话
- **THEN** 进入现有登录页，登录并完成必要改密后仅在当前资格允许时恢复该页面
- **AND** 不因返回地址而获得管理资格，也不接受站外返回地址

#### Scenario: 普通页面继续可用
- **WHEN** 正常用户没有任何管理权限
- **THEN** 原文档、搜索、助手和个人账户入口保持原行为，管理区不展示可用入口

#### Scenario: 非法目标地址
- **WHEN** 用户访问 `/admin/spaces/abc`
- **THEN** 页面显示目标不可访问，不发出任何空间管理请求

#### Scenario: 登录后返回运维页面
- **WHEN** 用户未登录时访问 `/admin/embedding` 或 `/admin/parse/dead-letters`，随后完成现有登录和必要改密
- **THEN** 可返回原站内路径并重新核对对应资格，不能因返回地址加载未获授权的运维资料

#### Scenario: 登录后返回审计查询
- **WHEN** 用户未登录时直接访问 `/admin/audits`，随后完成现有登录和必要改密
- **THEN** 返回 `/admin/audits` 并重新核对 `audit:read` 后才读取审计资料
- **AND** 身份确认及强制改密期间不预取列表或详情

#### Scenario: 审计返回路径不携带查询
- **WHEN** 外部输入把站外地址、`/admin/audits?loginUsername=someone` 或 `/admin/audits/other` 作为登录返回地址
- **THEN** 不把该值作为合法审计返回路径，沿用文档中心的安全回退

#### Scenario: 登录后返回文档面板索引
- **WHEN** 用户未登录时访问 `/admin/documents`，随后完成现有登录和必要改密
- **THEN** 返回 `/admin/documents` 并重新核对资格后才展示输入框
- **AND** 返回地址不携带文档 ID 以外的路径或查询

### Requirement: 管理菜单与页面必须按独立读取资格开放

用户端 MUST 以 `/api/auth/permissions` 的当前有效权限控制身份管理入口；账号、部门、角色页面分别要求 `identity:user:read`、`identity:dept:read`、`identity:role:read`。知识空间入口 SHALL 在当前空间目录存在可管理空间，或持有 `space:create`、`space:manage-all` 时显示；文档管理面板入口 SHALL 在上述任一条件成立或持有 `document:security-level:write` 时显示；空间详情与文档管理面板按各自规格的资源级读取结果开放。向量重建、解析死信和审计页面分别要求 `embedding:read`、`parse:deadletter:read`、`audit:read`，对应写权限及旧 `identity:audit:read` 不代替相应读取资格。角色名称、角色 ID、权限前缀及任意写权限 MUST 不代替读取资格，知识空间入口与文档管理面板入口的显示 MUST 不被解释为任一具体空间或文档的管理资格。直接访问无读取资格的页面 SHALL 显示无访问资格且不加载该实体数据。

#### Scenario: 只有账号读取资格
- **WHEN** 当前用户只有 `identity:user:read`
- **THEN** 账号页面可读，部门和角色页面均不加载，账号写操作不可用

#### Scenario: 只有部门写入资格
- **WHEN** 当前用户有 `identity:dept:write` 但没有 `identity:dept:read`
- **THEN** 部门浏览页面不开放，界面不把写权限解释为读取资格
- **AND** 后端已有写入资格及其语义保持不变

#### Scenario: 只有单个空间的 manage 授权
- **WHEN** 用户没有任何管理功能权限，但空间目录中有一个 manageable 空间
- **THEN** 知识空间入口显示且管理目录只包含该空间，身份管理入口保持不可用

#### Scenario: 只有密级调整权限
- **WHEN** 用户只持有 `document:security-level:write`，空间目录没有 manageable 条目
- **THEN** 知识空间入口不显示，文档管理面板入口显示
- **AND** 经索引页按 ID 到达的面板只开放密级区块，不开放例外授权、搬迁或删除

#### Scenario: 空间目录读取失败
- **WHEN** 权限已确认而空间目录读取失败，且用户未持有 `space:create` 或 `space:manage-all`
- **THEN** 知识空间入口不按旧目录显示，直接访问空间管理页显示可重试故障

#### Scenario: 运维读取不依赖空间目录
- **WHEN** 功能权限已确认包含某运维页面读取权限，而可管理空间目录读取失败
- **THEN** 该运维入口仍按自身读取权限开放，不要求空间管理或身份目录资格

#### Scenario: 仅持有运维写权限
- **WHEN** 当前权限只有 `embedding:rebuild` 或 `parse:deadletter:replay` 而不含对应读取权限
- **THEN** 相应管理入口不显示，直接访问不读取运维数据
- **AND** 不变更后端既有独立写权限的判定语义

#### Scenario: 只有全局审计读取资格
- **WHEN** 当前用户持有 `audit:read`，没有身份目录、空间管理资格，且空间目录读取失败
- **THEN** 审计导航与页面仍可使用，不要求读取用户、角色、部门、空间或文档目录

#### Scenario: 旧审计资格与角色名不能开放全局页面
- **WHEN** 当前用户仅持有 `identity:audit:read`、`ROLE_ADMIN`、`audit:*` 或其他写权限，未持有精确的 `audit:read`
- **THEN** 审计导航不显示，直接访问不请求审计列表或详情

#### Scenario: 文档面板入口不代表任何文档可管理
- **WHEN** 用户持有 `space:create` 但空间目录没有 manageable 条目，也没有密级调整权限
- **THEN** 文档管理面板入口显示，按 ID 到达的面板显示无可用管理操作
- **AND** 入口显示不使任何文档进入管理清单
