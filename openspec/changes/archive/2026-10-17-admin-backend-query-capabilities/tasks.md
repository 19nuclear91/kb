# 后端管理查询开发任务

执行顺序：公共契约与权限基础 → 最小文档查询 → 身份授权查询 → 上下文主体检索 → 全局审计查询 →
离线构建与规划校验。完成状态以下方勾选为准；离线验收记录见 `docs/admin-query-offline-evidence.md`。

验证仅使用纯 JDK、Mockito、依赖全部替换为 Mock 的 standalone MockMvc、MyBatis 内存映射构建及 ArchUnit 等进程内检查。controller
与 service 分别做单元测试，不组装真实业务链路。移除全部集成测试、真实 HTTP/网关联调、应用启动、数据库迁移执行、执行计划和外部组件验证任务；不启动或连接
DB、Redis、MinIO、Docker、模型或嵌入式替代服务。

不新增针对 README、Markdown、OpenAPI 文案、注释或文档示例的文字/格式断言，也不执行文档示例作为测试。文档编写任务只做人工核对；文档管理业务的权限、分页和响应投影单元测试保留。迁移及查询
SQL 仅编写、内存映射检查和人工审阅，不执行 SQL；离线结果不代表真实 SQL、迁移、索引或跨服务行为已验证。

## 1. 公共查询契约与权限基础

- [x] 1.1 在 Permission 目录增加 `audit:read`、`identity:external:read`
  及用途，复用现有角色权限精确匹配与主体启用校验；验证：权限目录、角色并集、读写资格相互独立、ROLE_ADMIN 无绑定时拒绝的行为测试通过，不引入
  Sa-Token permission API。
- [x] 1.2 编写下一版本 Flyway 迁移文件（当前预计 V19），定义仅向既有 ROLE_ADMIN 一次性绑定新权限的
  SQL，以及设计中的文档、用户和审计查询索引；验证：人工审阅迁移号、权限授予范围、现有等价索引、历史数据保护及安全 username
  表达式，不连接数据库、不执行 Flyway 或 SQL，不编写迁移文件文字断言测试。
- [x] 1.3 实现 `kb-app` 内部分页响应、limit+1 截取和有版本的 Base64URL
  游标，绑定查询种类、资源/目标、过滤摘要及排序位置；验证：损坏/超长/错类型游标、条件或目标更换、合法 limit 调整、UTC
  微秒时间和审计默认窗口续页测试通过，游标不含原过滤字符串或授权快照。
- [x] 1.4 为新增查询实现参数白名单、重复参数拒绝、Unicode 长度与字面 LIKE 转义；验证：纯函数单元测试与 standalone MockMvc
  参数绑定测试覆盖未知/重复参数、空值、非法 ID/布尔值、limit 越界及特殊字符转义，不发送真实 HTTP 请求。
- [x] 1.5 为新 controller 建立限定范围的异常与 no-store 响应处理，保持现有登录、停用及强制改密门禁；验证：分别通过 Mock
  依赖的单元测试检查 400/401/403/404/503/500、敏感原值隐藏和故障不变为空集，沿用已有可离线执行的异常处理单元测试。
- [x] 1.6 补充新权限初始化、迁移顺序、混部/回退限制及查询公共协议说明；验证：人工核对权限、迁移号和接口约定，不读取或运行说明文档及示例做自动测试。

## 2. 最小文档管理查询

- [x] 2.1 新增文档管理查询 record DTO、controller、service 与 mapper，挂载 `GET /api/admin/documents`，仅投影
  documentId/spaceId/spaceArchived；验证：manage-only、全局管理、view-only、edit-only、owner、文档例外和零范围的服务权限矩阵通过，返回载荷严格只有三个管理字段。
- [x] 2.2 编写 SQL 中的管理范围、spaceId/documentId/spaceArchived、未删除谓词和 ID 倒序游标条件；验证：用 MyBatis
  内存构建器检查映射与参数绑定，通过 Mock mapper 的服务单元测试验证空管理范围不调用无界查询及 limit+1 截取，人工审阅归档、软删除和首版
  pending 条件，不执行 SQL。
- [x] 2.3 分别添加 controller 与 service 的单元测试，用顺序 Stub
  模拟后续请求权限及文档归属变化；验证：旧游标不保留授权、停用与故障被正确处理、隐藏字段参数被拒，不实际执行撤权/搬迁、文件读取或调用其他业务接口。
- [x] 2.4 更新 OpenAPI 与管理查询说明，给出三字段响应、游标用法及管理/阅读资格分离示例；验证：人工核对接口声明与说明，明确无正文和隐藏字段搜索能力，不针对说明文字或示例运行测试。

## 3. 身份与授权查询补齐

- [x] 3.1 实现 `/api/admin/identity/users/search` 的有界身份查询服务与 SQL，支持 q、departmentId 精确主部门及 enabled
  条件；验证：用 Mock mapper、纯参数处理和投影单元测试检查组合条件传递、空页、Unicode/特殊字符转义及 ID
  游标，内存构建映射并人工审阅查询不读取密码字段，不执行 SQL 验证匹配效果。
- [x] 3.2 实现 `/api/admin/identity/users/{userId}/roles`，使用持久绑定查询目标用户的
  roleId/code/name；验证：停用目标仍显示绑定、无绑定空页、不存在目标 404、无资格先拒绝、user:read 不获得角色权限详情或写资格，分页不跨目标复用。
- [x] 3.3 实现 `/api/admin/identity/users/{userId}/external-identities`，使用不含 subject 的 SQL 与独立 DTO，支持 provider
  精确过滤；验证：新读取权限独立、无目标/无绑定状态正确，响应和日志不含 subject 原文/片段/摘要，内部字段新增不会扩大载荷。
- [x] 3.4 实现 `/api/auth/permissions`，从当前启用主体返回角色权限并集；验证：普通用户成功空数组、多角色去重排序、撤权下次请求立即生效、伪造身份头不起作用、userId
  参数被拒，强制改密仍为 403。
- [x] 3.5 实现 `/api/auth/security-levels`，读取实际密级字典并返回数值升序数组；验证：空字典、新增非 0–3 取值、依赖故障
  503、普通启用用户可读和强制改密拒绝的行为测试通过，不增加字典写接口。
- [x] 3.6 添加新查询 controller 的兼容与资格组合单元测试，并更新 OpenAPI/身份查询文档；验证：在依赖全部 Mock 的 standalone
  MockMvc 中检查 `/users/search` 绑定、旧列表数组与 `/auth/me` 结构、新路径别名及 no-store，说明文档只人工核对，不启动应用或访问
  Redis 会话。

## 4. 资源上下文授权主体检索

- [x] 4.1 在 space 服务增加资源上下文校验，文档入口通过领域 service 取得当前真实所属空间，挂载空间与文档两个
  `/acl/principals` 入口；验证：manage-only 可用、view/edit-only 拒绝、无权与不存在目标统一 404、归档空间可达、删除或搬迁后资格按当前事实变化。
- [x] 4.2 在 auth 服务内提供 USER/ROLE/DEPT 最小候选读取与参数化查询，仅投影 principalType/principalId/label；验证：通过
  Mock mapper 的单元测试检查候选投影、类型与分页，通过内存映射构建和人工审阅检查启用过滤，ArchUnit 检查 space 不直接访问
  auth mapper，不执行数据库查询。
- [x] 4.3 实现候选 q 字面匹配、ID 升序分页和资源/类型绑定游标，分别增加候选服务及既有授权服务的单元测试；验证：跨资源/类型游标拒绝、下一请求重新取资格、候选查询不调用授权写入，manage
  授出仍由其独立权限控制，不组装候选查询到授权写入的联合链路。
- [x] 4.4 更新授权主体查询说明与 OpenAPI，解释最小候选和完整身份目录的资格差异；验证：人工核对说明与已有单元测试覆盖的接口契约，不执行说明示例、真实
  HTTP 或跨服务验收。

## 5. 全局管理与登录审计查询

- [x] 5.1 在 auth 服务封装现有审计表的只读查询来源，支持精确条件 AND 组合、eventId 详情和 `(created_at,id)`
  倒序游标，不依赖目标实体内连接；验证：用 Mock mapper 与内存数据夹具检查 null actor/target
  保留、相同时间记录的分页位置和只读调用关系，内存构建映射并人工审阅 SQL，不启动或连接数据库。
- [x] 5.2 实现审计时间与筛选契约，包括带时区 `[from,to)`、默认 7 天/最大 31 天、requestId、targetType/targetId
  和登录用户名精确查询；验证：默认续页窗口不漂移、时区等价和微秒精度、非法窗口、未知合法值空页、大小写目标类型和未知用户名登录失败均有场景覆盖。
- [x] 5.3 新增操作域白名单摘要投影器，定义 metadata/changes/summaryAvailable
  DTO；验证：使用根据现有生产代码定义的内存夹具覆盖各操作域前后值映射，恶意/未知/不合类型载荷仅保留元数据，不输出原 JSON、自由
  reason、名称、subject、凭据、标题/正文或对象键，不读取生产数据。
- [x] 5.4 新增 audit controller/service，挂载 `/api/admin/audits` 及 `/{eventId}`，严格要求 audit:read，调用 auth
  服务取得数据而非跨领域 mapper；验证：旧审计权限和其他管理资格均不隐含新权限，无资格猜测事件存在性统一 403，合格不存在事件
  404、错误 UUID 400、撤权下次请求拒绝。
- [x] 5.5 添加审计查询的故障、日志与副作用单元测试；验证：用 Mock 抛出依赖异常检查安全 503
  与日志脱敏，用交互断言检查未调用审计写入/删除/修补，列表与详情采用同一投影规则，不制造真实数据库或 Redis 故障。
- [x] 5.6 更新审计 API 文档与源覆盖清单，给出精确过滤、分页、权限迁移和摘要降级示例；验证：人工核对覆盖范围、未记录尝试及独立审计源说明，不对文档文字、格式或示例编写/运行测试。

## 6. 离线构建与规划校验

- [x] 6.1 汇总本次及相关既有离线单元测试的明确类名白名单，运行白名单与跳过测试的打包；验证：采用
  `mvn -o -pl kb-app,kb-gateway -am test '-Dtest=<明确列出的离线单元测试类名>' '-Dsurefire.failIfNoSpecifiedTests=false'`
  和 `mvn -o -pl kb-app,kb-gateway -am package '-DskipTests'`，白名单包含适用的
  ModuleBoundaryTest 等纯架构检查，排除 JavaSourceConventionTest 等含 Javadoc/Schema 文档断言的测试，以及集成测试、外部组件烟测和完整应用上下文，记录实际执行结果。
- [x] 6.2 逐项人工核对任务、规格与离线证据范围，运行 `openspec validate admin-backend-query-capabilities --strict`
  ；验证：规划校验通过，验收记录明确只覆盖离线行为，不宣称 SQL 执行、迁移、索引性能或真实跨服务链路已验证，不新增文档文字断言测试。

## Workflow follow-up

- 实现与上述离线检查完成后，由用户明确要求再进入 OpenSpec 归档；本变更不安排集成测试、外部组件运行或文档内容测试，也不执行部署、生产迁移或归档。
