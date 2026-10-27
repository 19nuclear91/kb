# Design

## Context

动机与查询计数见 `proposal.md` 的 Why 与 Impact，不在此重复。

设计上只需要三条现状约束：

1. **`ResolvedPrincipal` 已经足够。** 它携带 `clearanceLevel`、`roleIds`、`departmentChainIds`，正是
   `PermissionService.resolve` 重新查询的那三件事实。`resolve` 里不需要任何新数据，只需要不丢弃已有的返回值。
2. **两条解析路径的失败语义刻意不同。** `PermissionService.resolve` 把 `IdentityException` 降级为 `denyAll`
   （拿不到身份时返回安全默认值，而不是抛异常后被上层吞掉当成放行）。而 `SpaceAuthorizer.requireEnabledUser` 直接调
   `identityService.resolvePrincipal` 并让异常传播，其注释写明理由：「resolve 会把停用错误降级为
   denyAll，故这里必须直接解析主体」。任何复用机制都必须同时保留这两种语义，不能把其中一条拉平成另一条。
3. **跨请求存活就是本变更不敢做的那件事。** 一旦复用状态活过一次请求，它就成了缓存，立刻与 `functional-authorization`、
   `identity-department` 的即时生效要求冲突（见 `proposal.md`）。而这类错误的失败模式是静默的——`PermissionScope`
   的类注释已经写明：「这类 bug 不会报错，只会让人看到不该看的东西」。

第 3 条决定了整个设计的形状：**复用的边界必须由类型结构保证，而不是由清理纪律保证。**

## Goals / Non-Goals

**Goals:**

- 让「同一次请求内同一件身份事实只查一次」成为结构上的默认，而不是靠调用方自觉。
- 让复用状态**无法**跨请求存活——不是"约定不要"，而是"写不出来"。
- 保留两条失败语义（`denyAll` 降级与异常传播）各自的调用点不变。
- 保留 `identityService == null` 简化构造器路径的现有行为，使既有单测不必整体改写。

**Non-Goals:**

- 不定义任何缓存、TTL、失效协议或 Redis 结构。
- 不追求「最少查询数」。合并 `resolvePrincipal` 内部的 4 次查询属于改 SQL，不在本次边界内。
- 不消除权限解析的跨查询一致性窗口（见 Risks 最后一条）。
- 不改变 `PermissionScope` 的形状、不可变性或语义。

## Decisions

### D1：不引入 ThreadLocal，也不引入请求作用域 Bean；复用句柄走显式参数

三种候选：

| 方案                   | 复用边界由什么保证           | 漏到跨请求的后果                       |
|----------------------|---------------------|--------------------------------|
| `ThreadLocal` + 清理   | 清理纪律（`finally`、过滤器） | 漏一次清理 = 线程池复用时静默越权             |
| `@RequestScope` Bean | Spring 请求上下文        | 在无请求上下文处（启动引导、调度任务）调用即失败或静默退化  |
| **显式句柄参数**           | **调用栈**             | **写不出来：句柄出不了方法作用域，除非有人把它存成字段** |

选显式参数。理由是失败模式的代价不对称：前两种方案的事故形态都是"静默越权"，而这正是本项目反复防的那一类——`PermissionScope`
的存在理由就是「不可能构造出'无过滤'的实例」。把复用做成参数，复用期就等于调用栈的生命周期，天然等于一次请求之内。

代价是签名改动。但需要多权限位的调用点只有三个（`SpaceQueryService.resolve`、`DocumentCitationService.find`、
`SpaceAuthorizer.requireSpaceManager`），churn 是有界的。

另外，`resolve()` **内部**的去重（8 → 5，占收益的大头）根本不需要任何机制：`resolvePrincipal` 的返回值已经在局部作用域里，用它即可。D1
只服务于跨方法的那部分。

### D2：句柄是只能由 `PermissionService` 铸造的不可伪造类型

显式参数有个直接的安全退化风险：如果新增 `resolve(ResolvedPrincipal, String)` 这种重载，调用方就可以手工
`new ResolvedPrincipal(userId, deptId, (short) 9, ...)` 塞一个虚高密级进去。现有的 `resolve(long userId, String)`
保证了主体必然来自服务端事实，这个重载会把保证打开一个口子。

因此句柄不是 `ResolvedPrincipal`，而是 `PermissionService` 的静态嵌套 final 类型，构造器私有，唯一来源是
`PermissionService` 自己的工厂方法：

```
  +-----------------------------------------------------------+
  |  PermissionService                                        |
  |                                                           |
  |  + resolve(long userId, String perm) : PermissionScope    |
  |        内部：principalOf(userId) --> resolve(handle, perm) |
  |        既有签名与既有语义，调用方无需改动                  |
  |                                                           |
  |  + principalOf(long userId) : PrincipalHandle   <-- 唯一铸造入口
  |                                                           |
  |  + resolve(PrincipalHandle h, String perm) : PermissionScope
  |        每个权限位仅发出 findVisibleSpaceIds 一次           |
  |                                                           |
  |  +-- static final class PrincipalHandle -----------------+ |
  |  |   private PrincipalHandle(...)   <-- 外部无法 new     | |
  |  |   持有 ResolvedPrincipal 或 IdentityException 之一    | |
  |  |   + requireEnabled()  : 重新抛出已捕获的异常          | |
  |  |   + functionalPermissions() : 懒查一次，句柄内复用    | |
  |  +--------------------------------------------------------+ |
  +-----------------------------------------------------------+
```

`ResolvedPrincipal` 本身不从句柄暴露出去，避免调用方取出内容再自行拼权限条件——那是 `PermissionService`
类注释明令禁止的（「不允许任何路径自行拼权限条件」）。

**备选：** 把重载设为包级可见。否决——`SpaceQueryService`、`DocumentCitationService`
在别的包里，包级可见度不覆盖它们；且包级可见只拦外部模块，拦不住同包内的误用。私有构造器 + 唯一工厂是更强且更简单的约束。

### D3：句柄同时承载"解析成功"与"解析失败"，让两条失败语义各取所需

句柄内部是二择一的状态（主体 or 异常），而不是"解析失败就抛"。这样同一个句柄能喂给两种调用点：

```
  失败降级路径（检索、文档读取、空间范围）
    handle = principalOf(userId)          // 不抛
    resolve(handle, VIEW)                 // 失败 --> PermissionScope.denyAll(userId)
    resolve(handle, DOWNLOAD)             // 同一句柄，同一降级

  异常传播路径（空间管理资格）
    handle = principalOf(userId)
    handle.requireEnabled()               // 失败 --> 重新抛出 IdentityException
    resolve(handle, MANAGE)               // 到这里主体必然有效
```

`SpaceAuthorizer.requireEnabledUser` 由此从「直接调 `identityService.resolvePrincipal`」改为「`principalOf` +
`requireEnabled`」，拿到的句柄可以直接交给同一方法里后续的 `resolve(handle, MANAGE)`
——这正是它当前解析两次主体的原因被消除的地方。注释里那句理由（必须直接解析主体以拿到异常）依然成立，只是实现它的手段从"绕过
`PermissionService`"变成"向句柄索取异常"。

**备选：** 保留 `requireEnabledUser` 直接调 `identityService`，再额外给 `PermissionService` 一个接受 `ResolvedPrincipal`
的入口。否决——那就是 D2 否决掉的可伪造重载。

### D4：功能权限码集合挂在句柄上懒查，不单独做缓存

`IdentityManagementAuthorizer.holds` 每次判定都发一次 `findUserPermissions`。同一请求内判定多个权限（如 `SpaceAuthorizer`
先 `has(SPACE_MANAGE_ALL)`、失败后再走别的判定）就会重复查询。

句柄提供 `functionalPermissions()`，首次访问时查一次并在句柄内保留，`IdentityManagementAuthorizer` 增加接受句柄的重载。既有的无句柄
`has(Permission)` / `require(Permission)` 保持原样（每次实时查），因为它们服务的是只判定一次的控制器入口，没有可复用对象。

这满足 `functional-authorization`「MUST 与判定时使用的事实同源，不得来自缓存快照」：句柄的寿命是一次调用栈，不是快照。

### D5：`identityService == null` 路径保留，不借本次变更清理

该路径只被简化构造器 `PermissionService(mapper)` 使用（测试装配）。保留它，并让两条路径**汇聚到同一个 `PermissionScope` 构造位置
**，避免语义漂移：

```
  principalOf(userId)
    |
    +-- identityService != null --> resolvePrincipal()
    |                               clearance/roles/chain 全部取自返回值
    |
    +-- identityService == null --> findClearanceLevel()
                                    null --> 句柄进入失败态
                                    非 null --> findRoleIds() + findDeptChainIds()
    |
    v
  两条路径产出同形的句柄 --> resolve(handle, perm) 只有一条实现
```

`PermissionMapper.findClearanceLevel` 因此保留，退化为只服务第二条路径。删除简化构造器需要改写一批既有单测，属独立清理，不在本次范围。

### D6：测试断言"每件事实只查一次"，不断言绝对查询条数

用 mock 的 `verify(mapper, times(1)).findRoleIds(userId)` 这类调用次数断言，而不是"总查询数等于 5"。理由：绝对条数会把无关改动（比如将来合并身份
SQL）变成测试失败，而"同一事实不重复查"才是本变更真正要锁住的性质。

同时补一条断言句柄不跨请求的测试：两次独立调用必须各自重新查询，不得第二次命中第一次的结果。

### D7：用 ArchUnit 守住句柄不被存成字段

项目已有 ArchUnit（`ModuleBoundaryTest`，3 条规则），沿用该机制新增一条：禁止任何类把 `PrincipalHandle` 声明为字段或静态字段。

私有构造器保证了句柄不可伪造，但不能阻止有人把拿到的句柄存进 Bean 的字段——那就把它变成了跨请求缓存。这条规则把 D1 的"
写不出来"补全：句柄既不能被铸造，也不能被存放。

## Risks / Trade-offs

- **[句柄被存成字段，复用悄悄变成跨请求缓存]** → D7 的 ArchUnit 规则在构建期拦截；D6 的跨调用测试在行为层再拦一次。两道都不依赖代码评审时有人注意到。

- **[`resolve(handle, perm)` 被当成"免身份校验"的快捷入口]** → D2 的私有构造器使句柄只能来自 `principalOf`，而
  `principalOf` 只接受 `long userId`；没有任何路径能用客户端提供的数据拼出句柄。

- **[两条装配路径（D5）语义漂移]** → 两条路径汇聚到同一 `PermissionScope` 构造点，`resolve(handle, perm)`
  只有一份实现；单测分别覆盖两条路径的 `denyAll` 行为。

- **[去重消除了撕裂读窗口，被误读成"修好了并发一致性"]** → 必须明确：**没有**。`findVisibleSpaceIds` 与主体查询之间依然没有事务保护（检索路径是
  `NOT_SUPPORTED`，这是为避免在 embedding HTTP 调用期间占用连接而刻意选择的）。并发的 ACL 变更仍可能产出"旧主体事实 +
  新空间事实"的组合。本变更只是把窗口数量从多个降到一个，不改变其存在。任何声称本变更解决了权限并发一致性的表述都是错的。

- **[静态查询计数被当作性能承诺]** → `proposal.md` 已声明计数为读代码所得、本机无 PostgreSQL、不以性能改善为完成判据。本设计不新增任何性能主张。

- **[收益可能小于预期]** → 若真实硬件上单次点查是亚毫秒级，消除 3~11
  次点查对端到端延迟的影响可能不可观测。该风险被接受：本变更的理由是消除无谓重复与撕裂读窗口，不是兑现某个延迟目标。真正的量化要等集成测试底座就位后才可能。

## Migration Plan

无迁移。不改数据库 schema、不需要 Flyway、不改 SQL 语句、不改 HTTP 接口、不新增配置项或环境变量。

- **部署**：无顺序要求。新旧实例可混部——不存在共享状态、协议或 schema 的版本差异。
- **回滚**：回退提交即可，无需数据修复或配置回滚。
- **开关**：不加特性开关。纯重构加开关会产生两条需要同时维护的权限解析路径，而两条路径不一致恰好是权限代码最危险的形态。

## Open Questions

无。D5 里"是否删除简化构造器"已判定为超出范围；缓存方案的取舍取决于「可接受的撤销延迟」，属独立变更，不影响本设计的实现路径或任务拆分。
