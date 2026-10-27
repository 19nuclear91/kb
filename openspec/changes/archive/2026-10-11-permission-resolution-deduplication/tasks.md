# Tasks

验证仅限进程内单元测试与静态检查，依赖使用 Mock/Stub，不组装跨模块业务链路，不启动应用或外部服务。文档仅交付并人工核对，不编写或执行 README、Markdown、OpenAPI/Javadoc 内容及示例测试。测试按明确的单元测试类/方法白名单运行，打包跳过测试。

## 1. PermissionService 内部去重

- [x] 1.1 修改 `PermissionService.resolve`，用 `IdentityService.resolvePrincipal` 返回的 `ResolvedPrincipal` 构造密级与主体键（
  `clearanceLevel`、`roleIds`、`departmentChainIds`），不再调用 `findClearanceLevel`、`findRoleIds`、`findDeptChainIds`
  ——验证：单测以 mock 断言 `identityService != null` 时 `findClearanceLevel`、`findRoleIds`、`findDeptChainIds` 的调用次数为
  0，`findVisibleSpaceIds` 为 1
- [x] 1.2 按 design D5 让 `identityService == null` 路径与非 null 路径汇聚到同一个 `PermissionScope` 构造位置，
  `PermissionMapper.findClearanceLevel` 保留并只服务于 null 路径——验证：单测分别用两种构造器断言同一组输入产出相等的
  `PermissionScope`（userId、clearanceLevel、visibleSpaceIds、principalKeys 四项逐一比对）
- [x] 1.3 为 `denyAll` 降级补齐两条路径的单测：`identityService` 抛 `IdentityException` 时、以及 null 路径下
  `findClearanceLevel` 返回 null 时，均返回 `denyAll` 且 `seesNothing()` 为真，且不再发出 `findVisibleSpaceIds`
  ——验证：两个测试用例通过，并断言 `findVisibleSpaceIds` 调用次数为 0

## 2. 不可伪造的主体句柄

- [x] 2.1 在 `PermissionService` 内新增静态嵌套 final 类 `PrincipalHandle`，构造器私有，内部二择一持有 `ResolvedPrincipal`
  或已捕获的 `IdentityException`，不对外暴露 `ResolvedPrincipal`——验证：单测断言该类型在 `PermissionService`
  之外无可用构造器或工厂（反射检查构造器为 private 且无其他公开铸造入口）
- [x] 2.2 新增 `PermissionService.principalOf(long userId)` 作为句柄唯一铸造入口，并新增
  `resolve(PrincipalHandle, String permission)`；把既有 `resolve(long userId, String permission)` 改为委派给这两者，签名与语义不变——验证：既有
  `PermissionService` 相关单测在不修改断言的前提下全部通过
- [x] 2.3 为 `PrincipalHandle` 增加 `requireEnabled()`，在失败态下重新抛出原 `IdentityException`，成功态下为无操作——验证：单测断言停用用户的句柄
  `requireEnabled()` 抛出的异常类型与消息同改动前 `identityService.resolvePrincipal` 的行为一致
- [x] 2.4 补句柄行为单测：同一句柄解析多个权限位时主体查询只发生一次；两次独立 `principalOf`
  调用各自重新查询，第二次不得复用第一次结果——验证：前者断言身份类查询各 `times(1)` 而 `findVisibleSpaceIds`
  按权限位个数计次，后者断言身份类查询 `times(2)`
- [x] 2.5 按 design D7 在 ArchUnit 测试中新增一条规则，禁止任何类把 `PrincipalHandle` 声明为实例字段或静态字段——验证：新规则随既有
  `ModuleBoundaryTest` 规则一并通过；并以一个故意违例的测试夹具确认该规则确实会失败（确认后移除夹具）

## 3. 多权限位调用点改用句柄

- [x] 3.1 改造 `SpaceAuthorizer`：`requireEnabledUser` 改为 `principalOf` + `requireEnabled` 并返回句柄，
  `requireSpaceManager` 复用该句柄执行 `resolve(handle, PERM_MANAGE)`，不再二次解析主体——验证：单测断言一次
  `requireSpaceManager` 调用中身份类查询各 `times(1)`，且停用用户仍抛出 `IdentityException` 而非降级为 `denyAll`
- [x] 3.2 改造 `SpaceQueryService.resolve`：解析主体一次后对 view 与 manage 各调用一次 `resolve(handle, ...)`，
  `space:manage-all` 短路分支行为不变——验证：单测断言身份类查询各 `times(1)`、`findVisibleSpaceIds` `times(2)`，且
  `manage-all` 持有者仍走短路分支不发出 `findVisibleSpaceIds`
- [x] 3.3 改造 `DocumentCitationService.find`：解析主体一次后对 view 与 download 各调用一次 `resolve(handle, ...)`，
  `seesNothing()` 短路与 `downloadable` 判定逻辑不变——验证：既有 `DocumentCitationAuthorizationTest`
  不修改断言即通过，并新增一例断言身份类查询各 `times(1)`
- [x] 3.4 核对 `DocumentManagementService`、`DocumentUploadService`、`SearchService` 等单权限位调用点无需改动，确认它们经由
  2.2 的委派自动获得去重收益——验证：这些服务的既有单测不修改即通过，并对 `SearchService.search` 新增一例断言身份类查询各
  `times(1)`

## 4. 功能权限码请求内复用

- [x] 4.1 为 `PrincipalHandle` 增加 `functionalPermissions()`，首次访问时经 `RolePermissionMapper.findUserPermissions`
  查询一次并在句柄内保留——验证：单测断言同一句柄上连续多次访问只触发一次 `findUserPermissions`
- [x] 4.2 为 `IdentityManagementAuthorizer` 增加接受句柄的判定重载，供 `SpaceAuthorizer` 等同请求内多次判定的路径使用；既有无句柄的
  `has(Permission)` / `require(Permission)` 保持每次实时查询不变——验证：单测断言句柄重载在多次判定下
  `findUserPermissions` 为 `times(1)`，而无句柄版本在多次判定下按次计数
- [x] 4.3 补一例断言功能权限复用不跨调用：两次独立 `principalOf` 后各自判定，`findUserPermissions` 为 `times(2)`
  ——验证：该测试通过，确认符合 `functional-authorization`「不得来自缓存快照」

## 5. 注释与文档更正

- [x] 5.1 更新 `PermissionService` 与 `PermissionScope` 的类注释中关于缓存的段落：当前写的是「P0
  暂不加缓存，先保证正确；加缓存时务必同步补失效逻辑」，改为准确描述——请求内复用已实现且由类型结构限定在调用栈内，跨请求缓存仍未实现，且引入前必须先修订
  `functional-authorization` 与 `identity-department` 的即时生效要求——交付注释并人工核对前置约束
- [x] 5.2 更正 README 第六节检查项「权限缓存在部门/角色/密级变更、空间 ACL
  变更时必须失效」：该条预设了并不存在的缓存，改为说明当前无跨请求权限缓存故该项不适用，以及将来引入缓存的前置条件（先修订上述规格要求、先确定可接受的撤销延迟）——交付 README 更新并人工核对缓存状态与验证边界
- [x] 5.3 在 README 能力表或相应位置记录本变更的真实验证边界：仅进程内单测，未在 PostgreSQL
  上执行过任何一条权限查询，查询计数为读代码所得而非实测——交付说明并人工核对，不添加文档内容测试

## 6. 离线构建与静态检查

- [x] 6.1 按明确的进程内单元测试类/方法白名单运行权限与纯 ArchUnit 回归，排除数据库烟测、集成及文档内容测试；执行 `mvn -q install '-DskipTests'`。验证：构建与白名单检查通过，记录实际执行范围，不依靠未配置数据库或条件跳过来筛选测试
- [x] 6.2 确认本变更未触及声明为范围外的面：`kb-app/src/main/resources/db/migration/` 与 `db/dev/` 无增删改、
  `mapper/*.xml` 中 SQL 未变、各 Controller 的 HTTP 方法与路径签名未变、`application*.yml` 无新增或改名的配置键——验证：逐项核对并记录结论
- [x] 6.3 确认复用状态不跨请求：全仓库检索 `PrincipalHandle` 的使用处，确认不存在字段、静态字段、集合或 `ThreadLocal`
  持有——验证：检索结果仅出现局部变量、方法参数与返回值，且 2.5 的 ArchUnit 规则通过

## Workflow follow-up

- 按项目评审要求满足后归档本变更。
- 真实 PostgreSQL 上的查询计数、延迟与连接池占用验证依赖集成测试底座（README 第五节与「下一步」第 1
  项），不属于本变更的任务，也不得据本变更的单测宣称已完成。
- 跨请求权限缓存（探索中的方案 B/C/D）取决于尚未定论的「可接受的撤销延迟」，须作为独立变更提出，并在其中显式修订
  `functional-authorization` 与 `identity-department` 的即时生效要求。
