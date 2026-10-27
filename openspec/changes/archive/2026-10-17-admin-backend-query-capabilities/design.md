# 后端管理查询设计

## Context

动机及范围见 `proposal.md`；本设计需要处理多个后端领域之间的授权、兼容和查询性能边界，因此保留独立设计文档。

已核对的现状：

- `document.service.DocumentManagementService.list` 固定按 view 与密级查询；`DocumentMapper.xml`
  的读取谓词排除归档空间，摘要含标题、文件名、版本及解析错误，不能复用为最小管理响应。
- `space.service.SpaceAuthorizer` 已提供当前启用主体和空间管理资格判定；`PermissionService` 提供调用栈内主体句柄，manage
  范围包含归档空间，功能权限来自持久角色并集。
- `IdentityManagementController` 的用户列表返回数组；`IdentitySummary` 不含角色绑定。外部绑定已有内部
  mapper，但对外仅有写操作，内部实体虽然用 `JsonIgnore` 隐藏 subject，仍不适合作为新查询 DTO。
- `SessionIdentity` 只含用户、角色 ID 和强制改密标记；`SecurityLevelDictionary` 已从 `sys_security_level` 读取取值，但没有只读
  HTTP 入口。
- `IdentityAuditMapper.list` 必须指定 targetType/targetId，查询没有分页；`LoginAuditService` 将未知用户名登录失败写为
  targetId=null。`AuditScope.targetType()` 的真实值主要为小写，也有 `PARSE_DEAD_LETTER`，不能按文档示例全部改成大写。
- `sys_identity_audit` 已有时间、JSONB 前后摘要、事件 UUID、操作域和请求标识。Embedding 操作位于另一张表，AI 审计位于
  Redis；首期不合并这些来源。
- `SaTokenConfig` 对 `/api/**` 做登录和强制改密拦截；新 `/api/auth/permissions` 和字典接口不能被加入改密豁免。网关现有
  `/api/admin/**` 与 `/api/auth/**` 路由已经覆盖新增入口。
- `ModuleBoundaryTest` 约束 web → service → mapper、跨领域服务边界及主体句柄不存字段；沿用这些约束。

## Goals / Non-Goals

**目标：** 提供可以直接实施和验证的后端查询契约；每个请求重新鉴权，查询前限定管理范围，以专用投影控制数据暴露；通过新增路径和增量迁移保持已有调用方兼容。

**不包含：** 前端工程、现有写操作重构、上传幂等、文档改名、配额策略、结算处置、身份同步和审计生产者补录。最小管理清单只提供资源定位，不代替正文列表或明细接口。

**验证范围：** 按用户要求，只安排离线单元测试、内存映射检查、人工审阅及构建/规划校验；移除集成测试、外部组件运行与文档内容/示例测试任务。文档管理是业务领域，其纯单元测试仍在范围内。

## Decisions

### 1. 增加查询入口，保留已有响应

所有新响应沿用 `R<T>`。新分页对象为 `items / hasMore / nextCursor`，不返回总数；审计页另含实际 `from / to`。集合为空时成功返回空
items 与 null 游标。

| 方法与路径                                                        | 当前请求资格                               | 返回或过滤重点                                                              |
|--------------------------------------------------------------|--------------------------------------|----------------------------------------------------------------------|
| `GET /api/admin/documents`                                   | 启用主体；仅查询 manage 范围或 space:manage-all | documentId、spaceId、spaceArchived；过滤 spaceId/documentId/spaceArchived |
| `GET /api/admin/identity/users/search`                       | identity:user:read                   | 既有 IdentitySummary 的专用查询投影；q/departmentId/enabled                    |
| `GET /api/admin/identity/users/{userId}/roles`               | identity:user:read                   | 已绑定的 roleId/code/name；不返回角色权限                                        |
| `GET /api/admin/identity/users/{userId}/external-identities` | identity:external:read               | identityId/userId/provider/enabled；可按 provider 精确过滤                  |
| `GET /api/auth/permissions`                                  | 当前有效会话与启用主体                          | 排序、去重的当前用户功能权限 code 数组                                               |
| `GET /api/auth/security-levels`                              | 当前有效会话与启用主体                          | 服务端实际 level 数值升序数组                                                   |
| `GET /api/admin/spaces/{spaceId}/acl/principals`             | 当前目标空间 manage 或 space:manage-all     | principalType/principalId/label；按类型与 q 查询                            |
| `GET /api/admin/documents/{documentId}/acl/principals`       | 文档当前所属空间 manage 或 space:manage-all   | 同上，先从服务端确定实际归属                                                       |
| `GET /api/admin/audits`                                      | audit:read                           | 有界的跨目标审计安全元数据                                                        |
| `GET /api/admin/audits/{eventId}`                            | audit:read                           | 同一元数据及白名单结构化前后摘要                                                     |

新接口不新增 `/api/identity` 别名。保留 `/api/admin/identity/users`、旧别名和 `/api/auth/me`
，不通过“是否带分页参数”令同一路径改变返回类型。Spring 的静态 `/users/search` 路径应明确优先于 `/users/{userId}`，用依赖全部为
Mock 的 standalone MockMvc 单元测试检查绑定，不发送真实 HTTP。

替代方案是直接修改旧列表为分页对象、让 `/me` 承载所有管理信息；未采用，因为会造成兼容性变化且混合不同权限的读取。

### 2. 沿用主体句柄，不新增跨请求权限缓存

新查询服务先从已验证会话取得 userId，调用现有主体解析服务，要求用户及主部门启用，再读取当前功能权限或指定 ACL
位；句柄只存在于一次调用栈中。不得使用 Sa-Token 固定返回空集的 permission API，也不得把角色 code 当功能资格。

最小文档查询用 manage 范围；`space:manage-all` 只放开最小元数据范围。未显式授予 manage 但具备 view/edit、文档例外或 owner
身份均不进入管理清单。当前请求无管理范围时返回空页；明确提供无权或不存在的过滤 ID 时也返回空页。

资源上下文主体检索先确认资源存在、未删除以及当前管理资格。对于身份正常但资源不存在或不能管理的调用者，新上下文入口统一
404；不改变已有写接口的状态语义。文档的实际 spaceId 由后端查询，不能使用请求携带的旧归属。

字典和当前权限查询只供本人正常会话使用，仍受强制改密限制。权限查询返回当前权限事实，不能作为后续请求的服务端授权凭据。所有新响应加
`Cache-Control: no-store`。

替代方案是按管理角色显示全部文档或直接复用普通文档清单；均无法满足 manage 与 view 独立的既有约束。

### 3. 最小文档查询单独建模并在 SQL 内过滤

新增 `document` 领域管理查询 controller/service/mapper 与只含三个字段的 record DTO。查询仅读 `kb_document` 与 `kb_space`
，不连接版本表、分块或对象存储。固定排除 `status='deleted'`，不按 current_version_id 是否为空过滤；默认不排除归档空间。

SQL 对无管理范围使用恒假条件，绝不能因列表为空省略授权过滤。全部查询条件、授权范围、排序位置及 limit 在 SQL 中完成，不先全库取回再由
Java 过滤。高于操作者密级的文档仍仅暴露已批准的 ID/空间/归档标记，这是管理定位的刻意边界，不扩大内容或密级调整权限。

无论调用者是否同时可阅读，管理 DTO 都不混入额外字段；需要标题的调用方另行使用原 view 接口。新清单拒绝 title、fileName
等未声明参数，防止通过隐藏字段匹配推断内容。

### 4. 身份绑定和授权候选是不同的读取契约

用户分页在 `auth` 新查询服务与 mapper 中实现，返回身份摘要；departmentId 对应 `sys_user.dept_id`，只匹配主部门。q
先去首尾空白，空值拒绝，按 Unicode code point 限长，使用绑定参数和显式 LIKE 转义；不引入全文检索或新扩展。

角色绑定查询先鉴权再确认目标存在，返回目标持久绑定，因此不能复用会过滤停用目标的当前主体角色解析。无需向 user:read
额外开放完整角色权限目录。

外部绑定使用专用 SQL 投影，SELECT 中不取 subject；返回独立 record DTO，而非直接序列化 ExternalIdentityRecord。provider
是可选的精确过滤，1–128 个字符；subject 及其片段不接受为查询参数。

密级字典返回实际数值，不把实体注解中的 0–3 或名称变成新的权威来源；沿用现有字典服务，不提供写 API。

上下文授权候选由 `space` 查询服务负责资源资格，再经 `auth` 服务接口取得最小候选，不能跨领域直接调用 mapper。USER 使用
display_name、ROLE/DEPT 使用 name 作为 label，q 仅匹配 label；USER 要求用户及主部门启用，DEPT 要求启用，ROLE 要求存在。返回值只有类型、ID
和 label，不返回 username、部门关系、密级或权限。

不限制候选只能来自本空间的部门，因为原授权允许跨部门授予。它是受资源上下文保护的最小目标检索，不是开放原完整目录。既有 ACL
授予、撤销、manage 授出与撤销规则保持原样。

### 5. 统一有界游标结构，保持各查询自己的排序

新增无 Spring 运行时依赖扩散的 `kb-app` 内部分页工具与查询 DTO。limit 默认 20、最大 100，SQL 读取 limit+1 判断
hasMore；游标不含凭据、显示标签、用户名、provider、原过滤字符串或主体范围列表。

| 查询                 | 排序位置                      | 翻页条件          |
|--------------------|---------------------------|---------------|
| 管理文档               | documentId DESC           | id 小于上一页末尾 ID |
| 用户、绑定角色、外部绑定、同类型候选 | 对应 ID ASC                 | id 大于上一页末尾 ID |
| 审计                 | createdAt DESC、内部 id DESC | 以两列组成的键向后查询   |

游标采用有版本的 Base64URL JSON，限定解码后最多 2048 字节，白名单字段为版本、查询种类、绑定资源/目标 ID、末尾位置、规范化条件
SHA-256；审计游标另含有效 from/to。SHA-256
用于绑定当前规范化条件，不是签名或授权机制。游标全部视为不可信输入，重新校验字段类型、正数、时间精度、窗口长度、查询种类与当前过滤条件；无需新增签名密钥或依赖。

未修改过滤条件时可调整合法 limit；资源种类、目标、主体类型、过滤条件改变必须重新从第一页查询。无效游标返回固定
400，不输出游标原值。权限范围不写入游标，每次查询重新计算；本协议只保证稳定位置，不承诺跨请求的冻结快照，新插入、更名、搬迁、删除及权限变化均按当前数据处理。

审计继续请求省略 from/to 时使用游标内窗口，显式提供时必须一致。时间标准化为 UTC 与 PostgreSQL
微秒精度，再参与摘要和比较，避免默认窗口或精度在翻页时漂移。

### 6. 全局审计从现有写模型派生只读查询

新增 `audit.web`、`audit.service`、安全 DTO 和摘要投影器；数据读取由 `auth.service` 的独立只读来源服务封装 `auth.mapper`
新查询，避免 audit 直接访问其他领域 mapper。保留旧 `IdentityAuditService.list` 及全部写入路径。

新增列表使用绑定参数查询现有表，不对存量事件做重写或补录。除查询本表不关联目标实体表，因此 targetId/actorUserId 为 null
或目标被删除时事件仍能返回。eventId 详情与列表使用同一权限和同一安全元数据投影，不返回原持久化实体。

过滤规则：

- actorUserId、targetId 为正整数；targetId 必须伴随 targetType。targetType 保留实际大小写，长度
  1–64；operationScope/operation 长度 1–128，requestId 长度 1–256，均按精确值匹配，值为未知但合法字符串时返回空页。
- status 仅 SUCCESS/FAILED。loginUsername 长度 1–128 个 Unicode code point，仅在 AUTH_LOGIN_SUCCESS/AUTH_LOGIN_FAILURE
  的已记录 username 字段做精确匹配；不能因为 targetId=null 就丢失未知账号事件。
- from/to 为有时区时间，采用左闭右开；缺省窗口 7 天，最长 31 天。请求仅指定一端时用同一规则补另一端并校验跨度。不允许无界总数、自由排序或任意
  JSON 路径检索。
- 审计 q、title、content 等未声明参数拒绝；过滤条件全部按 AND 组合，不能由后续条件覆盖前面的范围。

替代方案是把 Redis AI 列表和 Embedding 操作表即时拼入统一分页；未采用，因为这些来源有不同保留期、去重身份和排序语义。首期
API 文档逐项声明覆盖范围；扩展来源需独立规格。

### 7. 审计摘要先投影再输出

列表仅返回 id、eventId、operationScope、requestId、actorUserId、targetType、targetId、operation、status、createdAt；登录事件可附
attemptedUsername。详情额外返回 `changes` 和 `summaryAvailable`。changes 每项是已批准的
field/before/after，值只能是已校验的标量，不透传任意 JSON 节点。

投影器按操作域登记规则，最少覆盖用户部门/启停/密级/角色绑定、部门层级/启停、角色权限、空间归档与 ACL、文档
ACL/搬迁/密级、登录结果和死信操作。白名单示例：departmentId、parentId、enabled、clearanceLevel、securityLevel、spaceId、fromSpaceId、toSpaceId、roleId、principalType/principalId、permission、archived、version/securityVersion
及必要状态。字段值必须与对应类型和服务端目录兼容。

原数据键名不同则由每个操作域显式映射，不用通用“删除几个敏感键”方式。用户名只从两个登录操作域的已知字段提取，最多 128 个
code point，非字符串或超长历史值标记不可用；不提取用户创建等操作中的任意自由文本。原 reason、名称、provider
的任意载荷、subject、密码/哈希、token、标题、文件名、正文、对象键一律不输出。

未知操作域、非法结构或不合类型的摘要保留事件元数据，summaryAvailable=false、changes 为空；不吞掉整个事件、不输出原异常。外部身份与密码操作用操作域和目标
ID 追踪，不提供可还原内容。自由文本原因查询与导出留待独立需求。

查询本身不向同表追加审计，防止递归放大。参数与服务日志只记查询种类、固定错误类别、耗时等，不能打印用户名、requestId 原值、游标和原始审计
JSON。

### 8. 新权限、索引及模块边界

新增 `audit:read` 和 `identity:external:read`，独立于旧读取及写权限。新增迁移一次性只向当时 ROLE_ADMIN
绑定；其他角色由既有角色权限写接口显式授予。新创建的引导角色使用完整目录，已有角色不在启动时补回。此选择沿用 V14/V16
的一次性初始化习惯，同时避免把旧身份审计读者自动升级为全局审计读者。

迁移当前预期为 V19，实施时先检查最新版本。候选索引范围：

- 文档 `(space_id, id DESC)` 的未删除记录部分索引，用于管理范围和 ID 游标。
- 审计 `(created_at DESC, id DESC)`、`(request_id, created_at DESC, id DESC)`，以及登录操作域内安全 username 提取表达式与时间/ID
  的部分索引；已有 actor/target 时间索引保持不变。
- 用户按实际 `dept_id, id` 组合补足筛选索引，实施时检查已有等价索引后避免重复。

安全 username 索引表达式与查询表达式同源，设计为对非字符串或超长值产生 null，降低异常值风险。首期不新增
pg_trgm；用户/标签包含搜索采用有界结果和查询超时控制。仅编写 SQL 并人工审阅表达式及索引定义，不执行
EXPLAIN，不将离线检查表述为实际查询成本或索引行为的证明。

新 controller 只调用 service；跨领域经 service 取得安全投影，`kb-common` 不增加查询专用 DTO、Spring/JDBC
或运行时依赖。新增索引和权限迁移不修改已归档迁移校验和。

### 9. 参数、异常及验证

新接口对允许的查询参数实行白名单，拒绝未知参数和同名参数重复提供，不能因为 Spring 默认忽略未知参数而留下探测通道。对每个入口区分无会话
401、停用/强制改密/缺少功能权限 403、授权上下文隐藏 404、参数错误 400、依赖故障 503。

为新 controller 提供限定范围的异常映射，沿用 R 的 HTTP 状态分离约定，不改变旧全局错误处理行为。DataAccessException、查询超时和权限事实读取异常映射安全
503；不可预期的程序错误为安全 500，不能一律当作空集或重试型故障。错误消息不拼接原查询值、SQL 或底层异常文本。

新只读查询使用短事务、明确超时，建议 5 秒；不持有长期行锁。单请求允许已开始处理的内容按当时事实完成，规格保证的是权限变更后下一次请求重新判定，不宣称能撤回既发出的结果。

验证只使用纯 JDK、Mockito、依赖全部为 Mock 的 standalone MockMvc、MyBatis 内存映射构建、ArchUnit 与既有源代码规范检查。controller
与 service 分别测试，用顺序 Stub 表达下一请求权限变化，不组装真实业务链路，不启动应用、监听端口、发送真实 HTTP
或连接数据库、Redis、MinIO、Docker、模型。H2、嵌入式 Redis、Testcontainers 或完整 SpringBootTest 上下文也不作为替代方案。

迁移与查询 SQL 仅作为源码文件交付，可做内存映射及参数绑定检查和人工审阅；不执行 Flyway、SQL、索引创建、EXPLAIN
或故障注入，不新增读取迁移/查询文件文字的脆弱断言。SQL 实际执行语义、事务提交、索引性能和网关联调均没有运行验证证据，离线结果不能据此声称这些能力已实测。

README、Markdown、OpenAPI 文案、注释和文档示例只做人工核对，不编写读取其文字或格式的自动断言，也不执行示例作为验收。代码中的响应投影、参数和业务权限测试保留。最终构建显式选择离线单元测试类白名单并使用
Maven 离线模式，打包跳过测试，避免默认测试发现触发既存集成测试或外部组件烟测。

## Risks / Trade-offs

- 最小清单允许管理者得知可管理空间中文档 ID 的存在，包括高于自身密级和归档内容 → 这是已确认的独立管理边界，严格只返回三个字段，其他字段继续走原权限接口。
- 上下文主体搜索可跨部门发现显示标签 → 限定资源 manage、最小字段、查询规模与实时撤权，不开放完整身份目录或成员关系。
- 当前旧用户列表仍无界 → 为保持兼容保留，新管理调用使用分页入口；旧入口移除另行规划。
- 全局审计覆盖并非全系统 → 明示只查询现有持久管理/登录事件，不补造 Redis/Embedding 或未记录的事件。
- 不返回原审计 reason 会减少自由文本排障信息 → 使用元数据与结构化变更值定位；不为便利破坏内容和凭据隔离。
- 包含匹配在大型身份目录可能扫描较多记录 → 设计绑定参数、可利用索引的精确条件、有界页和超时；本次没有性能运行任务，不承诺未经测量的吞吐。
- 索引创建可能造成锁等待 → 迁移文件与部署说明保留锁影响和兼容边界，本变更不执行迁移或安排建索引任务，不宣称实际锁等待已验证。
- 旧 app 不认识新增权限会拒绝启动 → 先排空旧 app，再由新版本迁移，禁止在新绑定存在时直接回滚到旧目录。

## Migration Compatibility

- 本变更交付增量迁移 SQL 文件、新权限目录和查询代码；通过源码检查确定下一迁移号，不连接数据库确认状态、不执行迁移或备份。
- SQL 定义为只向当时既有 ROLE_ADMIN 一次性授权；首次引导纳入新目录、撤权后不自动补回的代码行为通过 Mock
  依赖的单元测试检查，实际迁移和重启效果不作已验证结论。
- 部署说明保留新权限入库后旧 app 不可混部/直接回退的兼容约束；这是运行环境使用说明，不是本变更中启动服务、切换实例或访问外部组件的执行任务。Worker/AI
  仍不承担本迁移。
- 回退说明保留加法迁移与未知权限校验约束，不自动删除权限绑定或改写 Flyway 历史。本次不执行回退、生产数据操作或跨服务验收。

本规划没有需要延期决定才能实施的接口契约问题；运行环境、实际 SQL 和性能验证已从任务范围移除，不列为本变更完成条件。
