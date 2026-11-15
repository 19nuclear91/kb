# 知识适用期与复审

适用期跟随固定版本，表示资料正文所声明的日期；复审跟随整份文档，表示责任人确认内容仍然准确。复审逾期不表示内容失效，适用期届满不改变发布、当前版本、检索或问答召回。首期已实现、任务 37/37 完成，2026-11-15 已同步九份主规格并[归档](../openspec/changes/archive/2026-11-15-knowledge-validity-and-review/archive-record.md)，未部署；[第二轮独立核验](../openspec/changes/archive/2026-11-15-knowledge-validity-and-review/verify-report-2.md)保留真实数据库、完整动态场景、浏览器与部署的未验证范围。

行为契约见[版本适用期主规格](../openspec/specs/document-applicability/spec.md)、[文档复审主规格](../openspec/specs/document-stewardship/spec.md)和[本人负责资料界面主规格](../openspec/specs/user-web-document-stewardship/spec.md)。

## 全站业务日期

`kb.validity.business-time-zone` 默认 `Asia/Shanghai`，可通过 `KB_VALIDITY_BUSINESS_TIME_ZONE` 配置。仅接受 JDK 已登记的地区 ID，不接受 `+08:00`、`GMT+08:00` 等偏移量写法；非法配置在绑定时阻止启动。**上线后不得修改该配置**。

每个 HTTP 请求读取一次数据库 `clock_timestamp()`，换算成业务日期 T。同一请求的状态、列表、计数和 `asOfDate` 使用同一个 T；不使用应用主机日期。日期状态不持久化，不由定时任务改写。

## 版本适用期接口

响应使用现有 `code/message/data` 包装。身份、版本、计数和乐观版本号须按精确整数处理，前端不能转为不安全的 JavaScript Number。所有成功、拒绝及故障响应均为 `Cache-Control: no-store`。

| 方法与路径 | 说明 |
|---|---|
| `GET /api/documents/{documentId}/versions/{versionId}/applicability` | 读取选中版本的原始声明、状态及编辑资格 |
| `PUT` 同一路径 | 独立替换该版本声明，不新建文件版本或审核轮次 |
| `GET .../applicability/history?limit=20&cursor=...` | 按修改时间、ID 倒序分页；limit 1–100，不含操作者 |
| `POST /api/documents/applicability/query` | 按固定版本组合批量读取标识，不写审计 |

读取字段：`documentId`、`versionId`、`effectiveFrom`、`untilKind`、`effectiveUntil`、`state`、`corrected`、`applicabilityVersion`、`asOfDate`、`canEdit`、`reviewFrozen`、`visibleToReaders`。无声明行为是两项未声明、版本 0；不从正文、创建时间或旧版本推断日期。新版本不继承旧声明。

写入必须提供 `X-Request-Id`，首尾去空白后 1–128 个非控制 Unicode code point。JSON **只包含且必须包含**以下五项：

```json
{
  "effectiveFrom": null,
  "untilKind": "DATE",
  "effectiveUntil": "2026-12-31",
  "reason": "按正文更正截止日期",
  "expectedApplicabilityVersion": 0
}
```

- `effectiveFrom`：null 或 `1900-01-01` 至 `2999-12-31` 的严格 ISO 日期。
- `untilKind`：`UNDECLARED`（未声明）、`OPEN_ENDED`（明确长期有效）或 `DATE`。
- `effectiveUntil`：取 DATE 时必须为日期，其他类型必须为 null；不得早于已声明的生效日。
- `reason`：null 或首尾去空白后 1–2000 个 Unicode code point；空白原因无效，不截断。候选不要求原因，已对读者可见版本必须提供。
- `expectedApplicabilityVersion`：非负精确整数。陈旧版本及无变化均返回 409。

请求体最多 16 KiB。未知或重复字段、尾随 JSON、非法 UTF-8/Unicode、NUL、浮点身份或非法日期返回 400，超限返回 413。写入成功回执包含 documentId、versionId、applicabilityVersion、corrected、updatedAt、asOfDate 和 replayed。

批量请求为 `{"items":[{"documentId":10,"versionId":20}]}`，1–100 个不重复的正整数组合。响应 data 为 `{items, asOfDate}`，每项仅返回该版本标识及 `current`。不存在、归属不符或不可访问项直接省略，不返回占位、原因或省略数。

### 适用状态判定（按下表顺序）

| 条件 | 状态 |
|---|---|
| 生效日未声明且截止类型 UNDECLARED | UNDECLARED |
| 生效日 > T | NOT_YET_EFFECTIVE |
| 截止日 < T | EXPIRED |
| 有截止日且截止日 ≤ T + 30 天 | EXPIRING_SOON |
| 其他 | EFFECTIVE |

生效日与截止日都包含当天。明确长期有效不进入即将届满或已届满；只声明一项时按该项判定，未声明截止日不等于明确长期有效。

### 访问、更正与历史

单项、历史与批量读取复用版本 INTERACTIVE 规则：父文档 view 不能单独打开未发布候选，仍须上传者、送审者或合格审核人资格。不存在、归属不符与不可访问统一为 404。

写入在锁定版本后重新检查当前 view、edit 和候选访问资格。候选的 IN_REVIEW 期间冻结；具有 PUBLISHED、OBSERVED_CURRENT 证据或 LEGACY_COMPAT 访问的版本按已对读者可见处理。AUTO 与 REVIEW_REQUIRED 空间都允许编辑者附原因立即更正，历史发布版本也按相同规则处理，不改变当前版本或审核结论。已删除、文档归档或空间归档时禁止写入。

**原因对所有当前可访问该版本的用户可见**，请勿填写仅管理人员可见的信息。每次成功修改追加不可编辑、不可删除的历史，包含时间、前后声明、修改时是否对读者可见及原因，不含操作者；操作者只进入审计。已发布版本更正后 corrected 永久为 true。审计只保存原因存在布尔值，不保存原因正文、标题或显示名。

请求标识绑定操作域、操作者、目标、预期版本及规范化载荷。相同标识和载荷重试，须按当前资格重新鉴权，才返回首次回执并标记 replayed=true；不重复写历史或审计。失败写入、读取和批量查询不产生成功审计。声明、历史、成功审计与回执同事务提交，先审计后回执，任一失败整体回滚。

### 错误投影

错误固定为 `{code:500, message:固定安全文本, data:null, reasonCode}`，不回显请求原因或底层异常。

| HTTP | reasonCode |
|---|---|
| 400 | INVALID_INPUT |
| 401 | UNAUTHORIZED |
| 403 | FORBIDDEN、PASSWORD_CHANGE_REQUIRED |
| 404 | NOT_FOUND |
| 409 | REVIEW_IN_PROGRESS、REASON_REQUIRED、REQUEST_MISMATCH、APPLICABILITY_VERSION_CONFLICT、NO_CHANGE |
| 413 | PAYLOAD_TOO_LARGE |
| 503 | UNAVAILABLE |

REASON_REQUIRED 表示提交前版本可能已发布，需要重新读取、由用户补充原因并明确重新提交。版本冲突需要重读再编辑；网络中断或超时属于结果未确认，只能以原标识和原载荷显式重试、重读核对或放弃，不自动重发。同一身份与固定版本内重读旧值、新值或失败，以及关闭编辑后继续核对，均保留原标识与精确载荷；读取或关闭不是成功回执，也不是隐式放弃。读取故障不能显示为未声明或空结果。

## 文档责任人与复审接口

| 方法与路径 | 用途 |
|---|---|
| `GET /api/admin/documents/{documentId}/stewardship` | 读取管理端最小复审投影，无标题或正文 |
| `POST .../stewardship/candidates` | `{nameQuery?, limit?, cursor?}`；名字面包含匹配，最多 64 个 code point，limit 默认 20、1–100 |
| `PUT .../stewardship` | `{responsibleUserId, cycleMonths, expectedStewardshipVersion}`，三项必需 |
| `POST /api/documents/{documentId}/stewardship/confirm` | `{expectedStewardshipVersion}`，只有当前合格责任人可确认 |
| `GET /api/documents/stewardship?view=attention&limit=20&cursor=...` | 本人需要处理清单，view 也可为 all |
| `GET /api/documents/stewardship/count` | 无参数；返回 attention 的精确十进制字符串 count 与 asOfDate |
| `GET /api/admin/spaces/{spaceId}/stewardship?state=UNASSIGNED&limit=20&cursor=...` | 空间 ID 级清单，无标题、无总数 |

所有写入要求 X-Request-Id 与当前复审版本。指定/改派要求 responsibleUserId 与 cycleMonths 非空；取消时两者都为 null。周期仅接受 3、6、12、24 个月。未知/重复字段与参数、尾随 JSON、非整数身份、非法 Unicode 或 NUL 返回 400；请求体最多 16 KiB，超限 413。查询默认 limit=20，最大 100，读取 limit+1 判断下一页。

### 权限与资格矩阵

| 操作 | 当前空间 manage 或 space:manage-all | 文档 view/edit | 当前责任人 |
|---|---|---|---|
| 管理读取、空间清单、候选检索 | 必需 | 管理者本人不要求 view | 不要求 |
| 指定、改派、取消、修改周期 | 必需；提交时重新核验 | 目标候选必须同时具备 view/edit | 不要求 |
| 本人清单、提醒计数 | 不要求 | 当前资格合格且文档当前可读 | 必需 |
| 确认内容仍然准确 | 不要求 | 当前资格合格且文档当前可读 | 必需 |

候选和当前责任人资格为：用户启用、主部门启用、无需强制改密、密级不低于文档，并同时有当前 view 与 edit，空间授权和文档例外授权都可作为来源。责任人身份不授予阅读、编辑、下载或审核权限，不从 owner、上传者、角色名称等字段推导。每次读取与确认重新判定；不合格时仅显示 NEEDS_REASSIGN 和原显示名，不输出原因，也不自动清除或改派。不要求 identity:user:read，不返回用户名、部门、角色或密级。

### 日期与状态

首次指定日只在首次指定时记录。到期日为最近确认日（从未确认时为首次指定日）加周期月份，月末自动截取，例如 2026-11-30 加 3 个月为 2027-02-28。改派不重新计时，取消保留周期与日期，再次指定不能通过重置首次指定日推迟到期；修改周期按同一基准重算。当前责任人可提前确认，成功后以今天 T 重算到期日。发布新版本、修改资料或适用期不算确认。

| 顺序条件 | 状态 |
|---|---|
| 无当前责任人 | UNASSIGNED |
| 当前责任人不合格 | NEEDS_REASSIGN |
| 到期日 < T | OVERDUE |
| 到期日 ≤ T + 30 天 | DUE_SOON |
| 其他 | OK |

到期当天为 DUE_SOON，次日为 OVERDUE。曾指定后取消的文档即便到期日已过，仍为 UNASSIGNED，并返回原到期日。

### 清单、计数和游标

本人清单只列出本人当前合格负责且可读、未删除、未归档的文档。attention 包含复审 DUE_SOON/OVERDUE，或当前发布版本适用期 EXPIRING_SOON/EXPIRED；未来才生效的版本不因截止日接近而单独进入该清单。提醒原因代码为 REVIEW_DUE_SOON、REVIEW_OVERDUE、APPLICABILITY_EXPIRING_SOON、APPLICABILITY_EXPIRED。没有当前发布版本时 currentVersion 为 null。每项包含已授权标题、复审状态、日期、周期、stewardshipVersion 和当前版本标识。

本人清单按 `(dueDate, documentId)` 升序，过滤后分页，计数与 attention 使用同一 WHERE 片段。数量表示需要处理的文档数，不是通知数，不含失去资格或不可读文档，不转换成浮点数。改派后下一次读取即反映归属变化。

空间清单状态必填，只允许 UNASSIGNED、NEEDS_REASSIGN、DUE_SOON、OVERDUE。范围为该空间所有未删除文档，含未发布首版文档；每项只有 documentId、state、dueDate、responsible（用户 ID 与显示名）。DUE_SOON/OVERDUE 按到期日、ID 升序，其余按 ID 升序。候选游标绑定操作者、文档、名称条件和 limit；本人游标绑定用户与视图；空间游标绑定操作者、空间与状态。游标每次读取都重新鉴权，不能跨目标或条件使用。

依赖故障和查询超时返回 503，不伪装为空清单、零计数或未指定。非责任人确认为 403，无管理资格的管理请求及已删除目标为 404。新复审冲突码为 STEWARD_INELIGIBLE、STEWARDSHIP_VERSION_CONFLICT、SPACE_ARCHIVED、DOCUMENT_ARCHIVED；另沿用 REQUEST_MISMATCH 和 NO_CHANGE。确认与改派竞争仍先按锁等待后的当前资格鉴权：确认先完成且管理员仍合格时，陈旧改派返回 409；改派先完成时原责任人返回 403，目标不可访问时按既有规则返回 404。匹配重试也不能越过当前资格返回旧回执。409、403、404 后重读核对，结果未确认不自动重发；管理对话框关闭后可继续核对并使用原标识和原精确载荷重试，关闭或重读不解除删除互斥。

### 删除、归档和搬迁

- 删除：保留复审事实，但所有读取、清单、后续修改与确认都不再可用，返回 404。
- 文档或空间归档：本人清单不列出，确认和管理写入拒绝；管理读取及空间概况仍可读。空间归档写入返回 SPACE_ARCHIVED，文档归档返回 DOCUMENT_ARCHIVED；确认归档目标返回 404。取消归档后按原日期继续计算。
- 搬迁：保留责任人、周期和日期；资格按新空间重新核验，失去 edit 等资格时显示需重新指定，不输出原因。
- 仅当前空间管理资格可指定或改派；管理员可以指定本人，但不能因 manage 代为确认他人负责资料。

## 上线与回退

1. 排空旧 app 写入实例，再升级 app 并执行 V39；本次开发没有执行迁移。新表不回填存量，所有版本默认为未声明，所有文档默认为未指定。
2. 核对 Asia/Shanghai 配置并固定；新接口在现有 documents/admin 网关路由下，网关配置不变。
3. 全部 app 提供新接口后才发布 web，先上传哈希资源，再原子切换 HTML。混部期间新接口可能返回 404，不能把它解释为不存在或空清单。
4. 管理者从空间 UNASSIGNED 标签开始逐步指定责任人、设置周期；编辑者另按固定版本补填适用期。未指定阶段本人清单为空是预期状态，不自动从 owner 推断。
5. 回退先 web 后 app。保留新表，不做反向迁移；旧 app 忽略新表，旧审计查询把新操作域作为未知摘要处理。重新升级后原数据继续生效。

### 首次真实数据库核对清单（尚未执行）

- 在独立环境应用 V39，确认复合版本归属外键、日期/类型/顺序约束、确认日与确认人配对约束，以及历史和命令表禁止 UPDATE/DELETE 的触发器。
- 用 `kb-app/src/test/resources/validity/state-boundaries.csv` 的相同输入核对单项 Java 状态、SQL 本人清单、计数和空间标签，尤其当天、次日、第 30/31 天、未指定优先和 UTC 跨日。
- 核对实际数据库 clock_timestamp 到 OffsetDateTime/LocalDate 的 JDBC 映射；修改业务时区配置应在启动校验时被正确拒绝（非法/偏移量写法）。
- 并发执行送审与适用期修改、确认与改派、首次指定与首次指定、搬迁/删除与复审管理写入，核对真实 FOR UPDATE/FOR SHARE、3 秒超时和乐观冲突。
- 故障注入审计及回执写入，确认四类适用期事实、复审设置与审计/回执整体回滚；同一标识失败后重试仍可按未成功命令处理。
- 使用只有 view、只有 edit、密级不足、停用、主部门停用、强制改密、文档例外授权、空间归档及搬迁后的主体，核对候选、提交、读取、清单、计数与重放。
- 对大空间执行 EXPLAIN ANALYZE，核对责任人到期索引、候选键集分页、NEEDS_REASSIGN 子查询与 3 秒失败行为；不把超时显示为空页。
- 在真实浏览器及部署入口核对登录/强制改密、no-store、迟到响应隔离、删除互斥、回退与哈希资源加载；当前模拟 DOM 不能替代这些验证。

## 验证边界

第二轮独立复跑：后端明确白名单 25 类/218 项、前端全量 122 文件/1343 项通过，八模块测试编译、类型检查、生产构建、包体工具 11 项和隔离检查通过。已执行核验中 CRITICAL / WARNING / SUGGESTION 均为 0，W1/W2/S1 关闭；Requirement Implementation Mapping 与 Scenario Coverage 仍部分 Not verified。历史执行见[实施记录](../openspec/changes/archive/2026-11-15-knowledge-validity-and-review/verification.md)，逐场景证据见[追踪映射](../openspec/changes/archive/2026-11-15-knowledge-validity-and-review/traceability.md)，不累计不同轮次测试数量。

当前只安排进程内服务替身、严格 JSON、MyBatis 映射和 BoundSql、模拟 DOM、类型检查及静态构建。迁移脚本与文档作为交付物，不对其文本做断言。没有真实数据库、Redis、容器、浏览器或部署验证；离线测试不能证明真实约束、触发器、锁、回滚或 SQL 执行计划已经生效。
