# Proposal

## Why

知识空间是系统的主授权单元，但没有任何接口能创建空间或维护空间 ACL。`kb_space` 与 `kb_space_acl` 的全部数据只来自 V3/V6
开发种子迁移，因此生产部署后无法为任何人授予对任何内容的访问权——整套权限模型只在种子数据上可用。

与此同时，`kb_space.archived` 字段和 ACL 的 `manage` 权限位都已在 V1 定义，却没有任何代码读取或赋予语义：归档一个空间不会影响检索结果，
`manage` 则是一个空槽。本变更在补齐授权管理接口的同时给这两者落地语义，避免交付"点了没反应"的开关。

## What Changes

- 引入**空间级委派授权模型**：`manage(S)` 的持有者管理空间 S 的 ACL；`ROLE_ADMIN` 负责创建空间、授出 `manage`
  、调整用户密级，并对全部空间兜底。
- 新增**空间管理接口**：创建、修改名称与描述、归档与取消归档、查询。空间不支持硬删除（`kb_document.space_id` 外键无
  `ON DELETE` 子句，有文档的空间无法删除），仅空空间可真删，其余一律归档。
- 新增**空间 ACL 接口**：授予、撤销、列出。可授予的权限位限定为 `view` / `download` / `edit`；`manage` 仅 `ROLE_ADMIN` 可授出；
  `review` / `comment` 暂不开放授予，因为没有任何功能执行它们。
- 新增**文档例外 ACL 接口**：授予、撤销、列出。鉴权依据是"文档当前所属空间的 `manage`"。沿用 V1 既定语义——例外只能扩权，不能收紧。
- 创建空间时在**同一事务内**授予创建者 `manage`，避免产生无人可管理的孤儿空间。
- `archived` 接入权限解析：`view` / `download` / `edit` 的可见空间集合排除已归档空间，`manage` **不**
  排除（否则被委派的管理员将无法取消归档）。所有读路径经由同一处解析继承该过滤。
- 空间 ACL 授予响应中回报**被密级拦截的主体数量**。跨密级授权被显式接受（见 design.md），因此管理员需要知道一次授权实际是否生效。
- **BREAKING** 部门审计的 `operation_scope` 由裸值 `CREATE` / `RENAME` / `MOVE` / `ENABLE` / `DISABLE` 改为
  `DEPARTMENT_*` 前缀。`sys_identity_audit` 的幂等键是 `UNIQUE (operation_scope, request_id)`，而 `request_id`
  由客户端提供；裸值会让一次空间创建被误判为某次部门创建的重试并返回错误实体。历史审计行保留原值，旧 `request_id`
  的重放去重随之失效。
- `kb_space` 新增 `version` 列，使空间写操作与既有身份/部门接口共用同一套乐观锁约定。

不在本变更范围内：角色与用户-角色管理、用户密级调整（另立变更，且密级管理不走委派模型）、deny 语义、空间密级上限、权限解析缓存。

## Capabilities

### New Capabilities

- `space-authorization`: 知识空间的生命周期、空间 ACL 与文档例外 ACL 的授权管理契约，含谁有资格授权、授权的有效性边界、归档对可见性的影响，以及管理操作的审计与幂等要求。

### Modified Capabilities

- `identity-department`: 审计幂等要求需补充——`operation_scope` 必须对实体类型唯一，使不同实体上复用同一 `request_id`
  不被误判为重试；现有部门操作的 scope 取值随之变更。

## Impact

- **数据库**：新增 V11 迁移为 `kb_space` 添加 `version` 列。`sys_identity_audit` 结构不变，新增 `SPACE_*` 等 scope 取值。
- **kb-app**：新增空间授权模块（controller / service / mapper / DTO）；`PermissionService` 新增 `PERM_MANAGE`；
  `PermissionMapper.findVisibleSpaceIds` 增加归档过滤并按权限位区分；新增空间级鉴权器；`DepartmentService` 审计 scope 改名；
  `PermissionScope` 的 `visibleSpaceIds` 文档措辞需去掉"view"假设。
- **读路径**：检索、文档列表、详情、下载、预览均通过 `PermissionScope` 取得可见空间集合，因此自动继承归档过滤，无需逐处改动。
- **kb-gateway**：kb-app 路由当前只覆盖 `/api/search/**,/api/documents/**,/api/files/**,/api/auth/**`，新接口位于
  `/api/admin/**`，不加入路由则无法经网关访问。
- **错误语义**：复用 `IdentityException` 与 `IdentityErrorCode`，使新接口直接返回 403/404/409，不依赖全局错误码改造。
- **跨变更约束**：文档修改所属空间的能力属于另一变更，但其实现必须清理该文档的遗留例外 ACL——原空间管理员的授权依据在搬迁后已失效。
