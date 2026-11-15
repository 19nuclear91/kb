# Design

## Context

动机和已确认的十项决定见 [proposal.md](proposal.md)，行为契约见本变更下的九份增量规格。以下只列出影响实现方式的现状：

- **访问规则已有可复用的统一实现。**`DocumentVersionAccessService.visible(handle, keys, INTERACTIVE)` 能批量判定固定版本的当前交互访问，其中已包含“未发布版本只向上传者、送审者和合格审核人开放”的规则（`DocumentMapper.xml` 中的 `versionInteractivePredicate`）。资料编辑通过 `MetadataAccess` 按 view、edit 范围鉴权；空间管理资格由 `SpaceQueryService.findManaged` 判定。
- **按用户批量判定资格已有先例。**审核人池 `DocumentReviewEligibilityMapper.xml` 用 `userKeys`（用户、角色、部门祖先）和 `viewGrant` 片段，在 SQL 中判定任意用户对文档的资格；问题单候选人 `KnowledgeGapAssigneeMapper.xml` 用“启用、主部门启用、无需强制改密”过滤，只投影 userId 和显示名。
- **写入模式已经稳定。**`MetadataWriteService` 的顺序是：绑定请求、锁命令行、锁业务行、重新鉴权、匹配重放、版本比较、写入、保存回执、写审计，全部在 READ_COMMITTED 单事务内完成。回执存放在追加写的命令表 `kb_document_metadata_command`，游标采用 `MetadataCursor` 的“Base64 JSON + 条件摘要”方式。
- **审核相关的锁。**送审时对版本行加锁（`lockVersion`，`FOR UPDATE OF v`），审批时按 `FOR UPDATE OF d, v` 同时锁文档和版本。
- **数据库时钟。**已有用法 `SELECT clock_timestamp()`（`ImportMapper`）。项目中没有业务时区配置。
- **网关路由。**网关已把 `/api/documents/**` 和 `/api/admin/**` 整段转发给 kb-app；资料接口的 no-store 由 app 端过滤器设置（`MetadataResponseFilter`），网关的认证拒绝响应已统一设置 no-store。
- **迁移版本。**当前最高为 V38。

## Goals / Non-Goals

**Goals:**

- 适用期和复审都只新增表与接口，不改动现有表结构、发布流程、检索 SQL 或 AI 协议。
- 状态判定规则只有一份：Java 纯函数负责单项读取，SQL 谓词与之等价，两者使用同一个业务日期参数。
- 资格、可读性和管理范围全部复用现有片段与服务，不另写一套授权语义。

**Non-Goals:**

- 不做定时任务、事件派生或通知投影，状态全部在读取时计算。
- 不做跨请求缓存，不预计算各文档的状态。
- 不提供修改历史的删除或清理接口，也不定义保留期。

## Decisions

### D1 放在 document 模块下的两个子包

在 `kb-app/document` 下新增 `applicability` 与 `stewardship` 子包，与 `metadata` 并列；各自包含 web、service、mapper、model。

- **只依赖本模块已公开的服务：**`DocumentVersionAccessService`、`PermissionService`、`SpaceQueryService`、`IdentityAuditService`。
- **资格 SQL 在 Mapper XML 中 include 现有片段**（`userKeys`、`viewGrant`），不复制规则文本。

**备选：**独立的顶层 `validity` 模块。没有采用，因为两项能力都紧密依赖文档访问片段，放在顶层会增加跨模块 include，也会让 `ModuleBoundaryTest` 的约束变复杂。

### D2 V39 只新增四张表，存量不回填

| 表 | 主键 | 要点 |
|---|---|---|
| `kb_document_version_applicability` | `version_id` | 字段：`doc_id`、`effective_from DATE NULL`、`until_kind`（UNDECLARED、OPEN_ENDED、DATE）、`effective_until DATE NULL`、`applicability_version`、`corrected`、`updated_at`。约束：`until_kind = 'DATE'` 与 `effective_until` 非空等价；截止日不早于生效日；日期在 1900–2999 年之间 |
| `kb_document_applicability_change` | 自增 ID | 字段：`version_id`、`doc_id`、`changed_at`、前后声明、`visible_to_readers`、`reason`。约束：`visible_to_readers` 为真时 `reason` 必须非空；触发器禁止 UPDATE 和 DELETE；索引 `(version_id, id DESC)`。不存操作者，操作者只进入审计 |
| `kb_document_stewardship` | `doc_id` | 字段：`responsible_user_id NULL`、`cycle_months`（3、6、12、24）、`first_assigned_on`、`last_confirmed_on`、`last_confirmed_by`、`due_date`、`stewardship_version`、`updated_at`。约束：最近确认日与确认人同时为空或同时非空。索引：`(responsible_user_id, due_date, doc_id)`，条件为 `responsible_user_id` 非空 |
| `kb_document_validity_command` | `(operation_scope, request_id)` | 结构仿照 `kb_document_metadata_command`；`operation_scope` 限三类新操作域；追加写，不可修改 |

各行的含义：

- 适用期没有对应行，等同于“两项均未声明、版本 0”。第一次写入时插入，版本记为 1。复审没有对应行，等同于 UNASSIGNED、版本 0。这样存量数据不需要回写，也符合“存量为未声明、未指定”。
- `due_date` 是派生值，在 Java 中按 `LocalDate.plusMonths` 计算后保存，月末自动截取，与规格一致。它只在加锁写入时变化，因此可以保存；状态本身不保存。
- 首次指定日在第一次插入时写入，之后任何写入都不修改。“取消后再次指定”不能借此推迟到期，正是靠这一点保证。

**备选：**复用 `kb_document_metadata_command`，修改它的 CHECK 约束。没有采用：修改一张已上线的追加写表的约束，会扩大旧二进制版本的兼容风险。

### D3 业务日期 T 每个请求只取一次

- **配置。**新增 `kb.validity.business-time-zone`，默认 `Asia/Shanghai`。启动时用 `ZoneId.of` 校验，只接受地区 ID，非法值阻止启动。运维说明标注：上线后不得修改。
- **取值。**每个请求执行一次 `SELECT clock_timestamp()`，在 Java 中换算成 `LocalDate` 得到 T。同一请求内的单项读取、清单、计数和响应中的 `asOfDate` 全部使用这个 T。
- **判定。**单项读取用纯函数 `ApplicabilityState.of(declaration, today)` 和 `StewardshipState.of(...)`。清单 SQL 只把已存储的日期和同一个 `#{today}` 参数比较，例如“即将届满”写成 `effective_until <= #{today} + 30`。
- **等价性。**两种实现共用一张边界用例表：当天、次日、第 30 和 31 天、月末、跨 UTC 日界。

**备选：**在 SQL 中写 `now() AT TIME ZONE`。没有采用：Java 和 SQL 会各自取时钟，同一请求可能跨过午夜，得到两个不同的“今天”。

### D4 适用期写入沿用资料编辑的流程，锁版本行

写入顺序：

1. 绑定请求标识，锁命令行；
2. 对版本行加锁 `FOR UPDATE OF v`，与送审使用同一把锁，也与审批锁定的版本行冲突；
3. 重新鉴权：文档 view 和 edit，再用 `DocumentVersionAccessService`（INTERACTIVE）确认能访问该版本；
4. 如果是匹配重放，返回首次回执；
5. 判断版本类别：发布证据为 PUBLISHED 或 OBSERVED_CURRENT，或协议为 LEGACY_COMPAT，算“对读者可见”，否则算候选；
6. 候选版本有 IN_REVIEW 轮次时返回 REVIEW_IN_PROGRESS；对读者可见的版本缺少原因时返回 REASON_REQUIRED；
7. 比较预期版本，检查是否无变化；
8. 插入或更新适用期行（版本加 1，`corrected = corrected OR 本次版本对读者可见`）；
9. 追加修改记录，先写审计，再保存回执。审计失败时不调用回执保存；回执失败时仍整体回滚。

全部在一个 READ_COMMITTED 事务内完成。

**为什么只锁版本行就够了：**送审先锁版本，再检查和写入轮次，所以“是否在审核中”在同一把锁下判定。审批按“先文档、后版本”的顺序加锁，适用期写入只锁版本，且之后不再锁文档，不会形成环形等待。

**原因的去向：**原因只写入修改记录。审计中只记录“已提供原因”这个布尔值，符合 `audit-query` 禁止输出自由文本原因的要求。

### D5 复审写入：先共享锁文档，再锁复审行

- **管理端写入**（指定、改派、取消、修改周期）：
  1. 绑定请求标识；
  2. 对文档行加共享锁 `FOR SHARE`，与搬迁、删除对文档行加的排他锁互斥；
  3. 按锁定时文档的当前空间检查 `findManaged`；空间已归档时返回 SPACE_ARCHIVED；
  4. 对复审行加锁 `FOR UPDATE`；行不存在时走插入路径，由主键防止重复插入；
  5. 在同一事务内用单用户资格 SQL 核验目标用户；
  6. 计算首次指定日和到期日；按前后状态确定审计操作名（assign、reassign、unassign、cycle）；
   7. 先写审计，再保存回执，失败整体回滚。
- **确认**：
  1. 绑定请求标识；
  2. 对文档行加共享锁 `FOR SHARE`；
  3. 对复审行加锁 `FOR UPDATE`；
  4. 检查调用者是否为当前责任人，并用资格片段确认其合格；
  5. 按调用者自己的 view 范围确认文档可读：复用 `MetadataAccess.document` 的语义，文档归档视为不可读；
   6. 当前资格与可读性检查通过后，匹配重放返回首次回执，否则比较预期版本；确认与改派竞争时仍先鉴权，改派先成功使原责任人失格时返回 403，目标不可访问时按现有规则返回 404，仍合格但版本过期才返回 409；不把版本检查提前来统一错误码；
  7. 写入最近确认日 T、确认人，到期日改为 T 加周期；
   8. 先写审计，再保存回执，失败整体回滚。

### D6 一个资格片段，四处共用

新增 `stewardEligible` 片段：

```text
u.enabled AND dept.enabled AND NOT u.must_change_password
AND u.clearance_level >= d.security_level
AND viewGrant(u, d) AND editGrant(u, d)
```

- `editGrant` 与 `viewGrant` 写法相同：权限位为 edit，来源包括空间授权和文档例外授权。
- 片段不含空间归档条件，归档由 D5 和清单查询单独处理。
- 使用片段的四处：候选检索（`CROSS JOIN sys_user`，按名称过滤，以 userId 作键集分页）、提交时的单用户核验、清单中的资格判断、计数。

### D7 清单与计数查询

- **本人清单与计数**：
  - 从 `(responsible_user_id, due_date, doc_id)` 索引出发，只扫描调用者负责的行；
  - 组合三个条件：调用者的资格片段；用调用者 view 范围的 `documentAccessPredicate` 加 `parentVersionVisibility` 判定可读，保证返回的标题已授权；`LEFT JOIN` 当前发布版本的适用期行；
  - attention 条件：`due_date <= T + 30`，或当前版本 `until_kind = 'DATE' AND (effective_from IS NULL OR effective_from <= T) AND effective_until <= T + 30`；后者排除尚未生效版本，与适用状态判定顺序一致；
  - 键集分页使用 `(due_date, doc_id)`，游标绑定用户和视图；
  - 计数与清单共用同一个 WHERE 片段。
- **空间清单**：
  - 先检查管理资格，再查询 `kb_document`（同一空间、未删除），`LEFT JOIN` 复审行和责任人显示名；
  - 四种状态各有谓词。NEEDS_REASSIGN 和 DUE_SOON、OVERDUE 用资格片段的 `NOT EXISTS` 或 `EXISTS` 区分；
  - 取 limit + 1 行判断是否有下一页。
- **超时**：所有查询都设 3 秒语句超时，超时返回 503。

### D8 批量标识读取不新增授权逻辑

`POST /api/documents/applicability/query` 的处理：

1. 校验 1–100 个不重复的组合；
2. 直接调用 `DocumentVersionAccessService.visible(handle, keys, INTERACTIVE)`，现有实现已能从组合中筛出可访问项，失败时抛出 503；
3. 对可访问项读取适用期行和文档的 `current_version_id`，在 Java 中按同一个 T 计算状态。

接口不写审计，也不缓存结果。

前端的调用时机：

- **搜索页：**每次搜索最多 20 条结果，请求一次。
- **助手：**按已加载的轮次合并引用，超过 100 个时分批，用请求代次丢弃迟到响应。

### D9 前端模块划分

- **新增 `kb-web/src/validity/`：**
  - 接口封装：适用期读取、历史、写入、批量读取；本人清单、计数、确认；
  - 状态文案映射；
  - 组件：`ApplicabilityBadge`、`ApplicabilityEditor`、`ApplicabilityHistory`；
  - 带请求代次保护的批量标识组合函数。
- **接入现有页面：**
  - `DocumentDetail.vue`：选中版本的标识、版本表新增一列、编辑区块；写入加入现有详情页写入协调；
  - `SearchWorkspace.vue`：结果标识；
  - `ui/References.vue`：新增可选的标识映射参数，助手页通过 `TurnCard` 传入；
  - `reviews/ReviewContext.vue`：只读标识和冻结说明。
- **本人清单：**新增懒加载路由 `/stewardship` 和页面。`navigation.ts` 增加编号 10 的“我负责的资料”，并加入 `safeReturnPath` 白名单。导航数量仿照 `UnreadPoller` 另建一个轮询器：`VITE_STEWARDSHIP_POLL_MS` 默认 300000，最小 60000，后台时暂停，同时最多一个在途请求。
- **管理端：**
  - `DocumentPanel.vue` 新增 `StewardshipBox`，开放条件与 `DeletionBox` 相同（以管理清单是否返回该文档为准），并加入删除互斥；
  - 候选选择器仿照 `GapAssigneePicker`；
   - `SpaceDetail.vue` 新增 `SpaceStewardship` 区块。

- **未决写入生命周期：**适用期 Panel 与责任人 Box 在区块父级持有共用写状态及原请求绑定，表单重读或关闭不销毁它；同一身份/目标内可恢复原内容重试。原始草稿与新读取事实分开，不以新版本或读取失败解除未确认状态。确认、明确拒绝或显式放弃后释放；区块离开、身份/目标变化清理旧草稿、绑定与回调。显式放弃须同步释放 DocumentOperations 的对应未知事实，整页放弃也须同步清理区块绑定，不影响在途写入的真实结算。

### D10 新接口放在现有路由前缀下，网关配置不变

新接口的路径：

- **适用期：**`/api/documents/{id}/versions/{versionId}/applicability`，以及其下的 `/history`；批量读取 `/api/documents/applicability/query`。
- **本人清单：**`/api/documents/stewardship` 和 `/count`；确认 `/api/documents/{id}/stewardship/confirm`。
- **管理端：**`/api/admin/documents/{id}/stewardship`、`/candidates`，以及 `/api/admin/spaces/{spaceId}/stewardship`。

这些路径都在网关现有的 kb-app 路由内。app 端新增响应过滤器设置 no-store。

Spring 会优先匹配字面路径，所以 `/api/documents/stewardship` 不会被当成文档 ID 解析。这一点用控制器测试固定下来；网关只补路由回归测试。

**备选：**新建顶层前缀 `/api/stewardship/**`。没有采用：需要新增网关路由和响应策略，而收益只是路径更短。

### D11 审计

- **新操作域：**在 `AuditScope` 中新增 `DOCUMENT_APPLICABILITY_UPDATE`（目标类型 document-version）、`DOCUMENT_STEWARDSHIP_UPDATE` 和 `DOCUMENT_STEWARDSHIP_CONFIRM`（目标类型均为 document）。
- **摘要字段：**`AuditSummaryProjector` 按 `audit-query` 增量规格为它们登记固定字段。
- **兼容：**旧版 app 读取到这些未知操作域时，按现有“操作域未识别”规则标记摘要不可用。

## Risks / Trade-offs

- **[Java 与 SQL 的状态判定不一致]** → 两者共用一张边界用例表，SQL 只比较已存储的日期与同一个 T。离线测试无法执行 SQL，首次接入真实数据库时需按运维说明核对清单与单项读取是否一致。
- **[资格片段与 `PermissionService` 的语义出现偏差]** → 与审核人池共用 `userKeys` 和 `viewGrant`，不另写规则；确认和本人清单额外按调用者自己的 view 范围检查可读性，偏差只会导致少列，不会多给访问。
- **[大空间的“需重新指定”清单逐行判定资格，成本较高]** → 键集分页、limit + 1、3 秒超时，超时返回 503 而不是空页；真实执行计划未验证。
- **[已发布版本的更正原因对所有读者可见，可能写入内部信息]** → 编辑表单明确提示可见范围；审计只记录是否提供了原因。
- **[新版本发布后适用期为“未声明”，没有提醒责任人补填]** → 首期依靠详情页标识和编辑入口；是否把“当前版本未声明”纳入本人清单，留给后续变更评估。
- **[上线初期所有文档都是“未指定”，清单为空]** → 运维说明引导空间管理员从 UNASSIGNED 清单开始逐步指定责任人。
- **[时区配置被修改，所有日期边界一起移动]** → 只接受地区 ID，运维说明标注上线后不得修改。
- **[导航数量的轮询增加请求量]** → 默认 5 分钟、最短 60 秒，后台暂停，同时最多一个在途请求。
- **[模型回答仍可能把已届满的资料当作现行规定]** → 引用区域提示标识只反映资料声明；让模型感知适用期属于首期非目标。

## Migration Plan

1. **app。**排空旧写入实例后升级，执行 V39（只新增表、索引和触发器，不改动现有数据），并提供新接口。旧版 app 不读取新表，混部期间新接口可能返回 404；新前端在全部 app 升级后才发布，所以不会受影响。
2. **gateway。**配置不变。如果只是补充了路由回归测试，可以随常规版本发布。
3. **web。**发布新页面和入口，并按现有前端运维规则先上传哈希资产、再原子切换 HTML。
4. **回退。**先回退 web，隐藏入口；再回退 app。新表保留，不执行反向迁移；旧 app 忽略新表，旧审计查询把新操作域显示为未识别。重新升级后，原有声明、历史和复审设置继续生效。
5. **交付文档。**新增 `docs/knowledge-validity-review.md`，包含 API、权限矩阵、时区配置、上线引导和首次真实库核对清单；实施完成后再更新 README 功能表和路线图 F-02 的状态。

## Validation Strategy

沿用项目惯例，只安排离线验证：

- **范围内：**内存替身的服务与事务测试、加载 MyBatis 配置检查 BoundSql 参数绑定（不连接数据库）、控制器与 JSON 输入测试、模块边界测试、前端 Vitest 模拟 DOM、类型检查与生产构建。
- **范围外：**不安排需要数据库、Redis、容器或浏览器的集成测试和联调，也不对 Markdown 等文档内容做断言。

离线结果不能证明真实约束、锁顺序、触发器或 SQL 执行计划。这些作为已知未验证项记录在交付文档中。
