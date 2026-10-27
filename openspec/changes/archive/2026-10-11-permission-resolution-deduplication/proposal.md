# Proposal

## Why

`PermissionService.resolve` 先调用 `IdentityService.resolvePrincipal` 并**丢弃其返回值**，随后把该返回值里已经包含的三件事实——密级、角色
ID、部门祖先链——各自重新查一遍。一次权限解析因此发出 8 次数据库查询，其中 4 次与前 4 次完全重复，只有 `findVisibleSpaceIds`
是真正新增的。`SpaceAuthorizer.requireSpaceManager` 更进一步：它自己先解析一次主体，再调 `resolve`，后者在内部又解析一次同一用户的主体。

代价集中在连接池上。`SearchService.search` 标注 `@Transactional(propagation = Propagation.NOT_SUPPORTED)`——这个标注是对的，它避免在
embedding 的 HTTP 调用期间占用连接——但副作用是这些查询无法共享连接，每次都是独立的池取还，而 `hikari.maximum-pool-size` 是
10。结果是每请求的数据库往返次数由权限解析而非检索主导。

第六节检查项「权限缓存在部门/角色/密级变更、空间 ACL 变更时必须失效」预设了一个并不存在的缓存。而缓存也不是这里可选的解法：现有规格已经禁止陈旧的权限事实——
`functional-authorization` 要求角色权限视图「MUST 与判定时使用的事实同源，不得来自缓存快照」且变更「MUST
在下一次请求即生效，不得依赖会话过期」，`identity-department` 对角色资格、主部门归属和密级调整有同样的即时生效要求。因此可做的是
**去掉本不该发生的查询**，而不是给它们加速。

## What Changes

- `PermissionService.resolve` 复用 `resolvePrincipal` 返回的 `ResolvedPrincipal`（它已携带 `clearanceLevel`、`roleIds`、
  `departmentChainIds`）构造主体键与密级，不再重复查询这三件事实；`findVisibleSpaceIds` 保持为该方法唯一的新增查询。
- 保留 `PermissionService(mapper)` 这个 `identityService == null` 的简化构造器路径及其既有语义：该路径下仍由
  `findClearanceLevel` 承担用户存在与启用判定，并在返回 null 时降级为 `denyAll`。
- `SpaceAuthorizer.requireSpaceManager` 不再对同一用户解析两次主体。
- 同一请求内需要**多个权限位**的调用点改为「解析主体一次 + 每个权限位一次 `findVisibleSpaceIds`」：
  `SpaceQueryService.resolve`（view + manage）与 `DocumentCitationService.find`（view + download）。`SpaceQueryService` 里
  `ScopePair` 的注释「一次解析出的访问范围，避免同一用户重复查询权限」描述的正是本变更要真正实现的行为。
- 同一请求内重复判定功能权限的调用点复用一次 `findUserPermissions` 结果（如 `SpaceAuthorizer` 先 `has(SPACE_MANAGE_ALL)`
  再判定其他权限的路径）。
- 复用的生命周期**严格限定在一次解析调用或一次请求处理之内**，不得跨请求存活：没有 TTL、没有
  Redis、没有本地缓存、没有任何跨请求状态。请求结束即消失是本变更可以不改任何规格的前提。
- 对外可观测行为不变：权限判定结果、拒绝语义、`denyAll` 降级、审计与错误码全部保持现状。停用用户、停用部门、无有效主部门仍在
  `resolvePrincipal` 处被拒。
- 附带收益（非目标）：当前 `resolvePrincipal` 与 `findClearanceLevel`
  之间存在一个撕裂读窗口——用户在两次查询之间被停用时，两者会给出不一致的事实。去重后该窗口随重复查询一起消失。
- 范围不包含：
    - **任何形式的权限缓存**（本机讨论中的方案 B/C/D）。缓存与否取决于「可接受的撤销延迟」这一尚未定论的问题，且方案 B
      的有界陈旧会与上述至少三条既有规格要求冲突，必须以独立变更显式修订规格后才能进行。
    - 把 `resolvePrincipal` 内部的 4 次查询合并为更少的 SQL。那需要改动身份查询语句，与本次「不改 SQL、只去重」的边界不同，留作独立评估。
    - `SearchService` 的事务传播设置、`hikari` 连接池参数、`EmbeddingControlService.current()` 的每请求读取。
    - 检索过滤能力、功能权限目录、空间归档语义等任何行为变更。

## Capabilities

### New Capabilities

无。本变更不引入任何新的系统行为。

### Modified Capabilities

无。本变更是纯重构：相同的权限判定结果、相同的拒绝语义、相同的即时生效保证，只是不再对同一事实重复查询。权限解析必须反映当前数据库事实、变更必须在下一次请求即生效这一不变量
**已经**由 `functional-authorization`（「角色的权限集必须可查询」、「角色权限集变更必须受保护、幂等且可审计」）与
`identity-department`（「身份主体解析必须只信任服务端事实」、「功能角色资格必须从服务端持久的角色归属解析」、「用户必须有明确且有效的主部门归属」、「用户密级调整必须是独立且受保护的操作」）承载，本变更保持而不修改它。

因此 `.openspec.yaml` 设置 `skip_specs: true`。不为满足校验而虚构要求。

## Impact

- `kb-app` `auth`：`PermissionService.resolve` 的解析流程；`IdentityManagementAuthorizer` 的功能权限判定需要一个请求内复用点。
  `PermissionMapper.findClearanceLevel` 退化为只服务 `identityService == null` 路径，保留不删。
- `kb-app` `space`：`SpaceAuthorizer.requireSpaceManager`、`SpaceQueryService.resolve`。
- `kb-app` `document`：`DocumentCitationService.find`。
- `kb-common`：无改动。`ResolvedPrincipal` 与 `PermissionScope` 已提供所需的全部字段，且 `PermissionScope` 仍是不可变值对象。
- 存储与迁移：无。不新增表、不需要 Flyway 迁移、不改任何 SQL 语句。
- API 与配置：无。不新增端点、权限码、配置项或环境变量。
- 预期静态查询计数（**读代码数出，非实测**；本机无 PostgreSQL，这些查询从未真正执行过）：

  | 路径 | 当前 | 之后 |
    |---|---|---|
  | `PermissionService.resolve`（单权限位） | 8 | 5 |
  | `SpaceAuthorizer.requireSpaceManager` | 13 | 6 |
  | `SpaceQueryService.resolve`（view + manage） | 17 | 7 |
  | `DocumentCitationService.find`（view + download） | 16 | 6 |

  这些数字量化的是**被消除的重复**，不构成任何延迟或吞吐承诺。单次点查在真实硬件上的实际成本未知，本变更也不以性能改善作为完成判据。

- 验证：仅进程内单元测试与静态检查，以 mock/stub 断言查询次数与调用序列、`denyAll` 降级路径、`identityService == null`
  回退路径，以及复用状态不跨请求存活。不运行 PostgreSQL/ParadeDB、Redis、MinIO，不做压测。不得据单元测试宣称性能已改善或已完成生产验证。
