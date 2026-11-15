# Tasks

按组依赖推进。行为以本变更的九份增量规格为准，技术取舍见 design.md D1–D11。

本清单只安排离线验证：内存替身的服务与事务测试；加载 MyBatis 配置检查 BoundSql 参数绑定（不连接数据库）；控制器与 JSON 输入测试；模块边界测试；前端 Vitest 模拟 DOM；类型检查、构建与打包。不安排需要前后端联调，或需要启动数据库、Redis、容器、浏览器才能执行的测试；也不安排检查文件内容的测试，包括 Markdown、README 等文档和迁移 SQL 文本中是否存在某段内容。文档和迁移脚本只作为交付物。离线结果不能作为真实约束、锁顺序、触发器或 SQL 执行计划已生效的证据。

## 1. 持久化与业务日期基础

- [x] 1.1 核对迁移号后新增 V39：适用期、修改历史、复审、命令回执四张表，以及 D2 列出的约束、索引和不可修改触发器，不改动现有表；验证：交付迁移脚本，不执行迁移，也不对 SQL 文本做断言。
- [x] 1.2 新增 `kb.validity.business-time-zone`（默认 `Asia/Shanghai`），启动时校验，只接受地区 ID；实现每请求取一次数据库时钟并换算业务日期；验证：`BusinessDateTest` 用配置校验器和时钟替身的单元测试，覆盖非法值被拒绝、偏移量写法被拒绝、2026-12-31T16:30:00Z 换算为 2027-01-01，不启动应用，也不连接数据库。
- [x] 1.3 实现适用状态、复审状态及到期日的纯函数，建立 Java 与 SQL 共用的边界用例表；验证：`ApplicabilityStateTest`、`StewardshipScheduleTest` 覆盖当天与次日、第 30 和 31 天、只声明一项、明确长期有效、2026-11-30 加 3 个月得 2027-02-28、修改周期重算、首次指定日不变，以及 UNASSIGNED 优先于逾期。
- [x] 1.4 在 `AuditScope` 新增三个操作域，并在 `AuditSummaryProjector` 登记固定摘要字段；验证：扩展审计摘要投影测试，覆盖前后日期、“已提供原因”布尔值、前后责任人 ID，以及原因文本、显示名、标题和未知嵌套字段不进入投影。

## 2. 版本适用期后端

- [x] 2.1 实现适用期行、修改历史和命令表的 Mapper 及显式映射；验证：`ApplicabilityMappingTest` 离线检查 BoundSql 参数、`FOR UPDATE OF v`、3 秒超时、历史投影不含操作者，以及批量读取只用传入组合。
- [x] 2.2 实现严格输入解析：五个必需字段、日期范围、截止类型与日期一致、截止日不早于生效日、原因 1–2000 个 code point、请求体 16 KiB；验证：`ApplicabilityInputTest` 覆盖每类 400 和 413、未知或重复字段、尾随 JSON、NUL 与孤立代理项。
- [x] 2.3 按 D4 实现写入服务：绑定请求、锁版本、重新鉴权、判定版本类别、审核中冻结、REASON_REQUIRED、版本比较与无变化、写入、追加历史、保存回执和审计；验证：`ApplicabilityWriteServiceTest` 用替身覆盖候选访问 404、IN_REVIEW 返回 409、发布后补交原因、需审空间立即生效、更正历史版本、归档返回 404、匹配重放、绑定不符、撤权后重放、并发冲突，以及审计失败时不保存回执的调用顺序。
- [x] 2.4 实现单项读取、修改历史分页和批量标识读取（复用 `DocumentVersionAccessService` 的 INTERACTIVE 规则）；验证：`ApplicabilityReadServiceTest` 覆盖候选不可见和版本归属不符时返回 404；`ApplicabilityBatchQueryTest` 覆盖不可见项省略、current 标记、0 个、101 个或重复组合返回 400、访问检查失败返回 503，以及不写审计。
- [x] 2.5 实现控制器、no-store 响应过滤器和错误投影；验证：`ApplicabilityControllerTest` 覆盖全部状态码与原因码、错误中不回显原因文本、`/api/documents/applicability/query` 不被解析为文档 ID。
- [x] 2.6 编写 `docs/knowledge-validity-review.md` 的适用期部分：接口字段、状态判定表、错误码、原因可见范围、时区配置不得修改；验证：交付文档，不做内容断言。

## 3. 文档复审后端

- [x] 3.1 新增 `stewardEligible` 片段（含 `editGrant`）及复审 Mapper：复审行锁定与插入、候选键集分页、单用户核验、本人清单与计数、空间清单；验证：`StewardshipMappingTest` 离线检查片段被 include、参数绑定、`FOR SHARE` 与 `FOR UPDATE`、3 秒超时、清单与计数共用 WHERE 片段、候选只投影 userId 和显示名。
- [x] 3.2 实现候选服务与 POST 接口；验证：`StewardshipCandidateTest` 覆盖无 `identity:user:read` 也可使用、只有 view 或密级不足的用户被排除、游标跨文档或跨条件返回 400、nameQuery 上限 64 个 code point。
- [x] 3.3 按 D5 实现管理端写入：指定、改派、取消、修改周期；验证：`StewardshipAssignmentTest` 覆盖候选失效返回 409、只有 edit 时返回 404、管理员指定本人、NO_CHANGE、改派不改到期日、取消后再指定首次指定日不变、修改周期重算、空间归档返回 409、文档删除返回 404、匹配重放与绑定不符，以及审计操作名。
- [x] 3.4 实现复审确认；验证：`StewardshipConfirmTest` 覆盖编辑者与非责任人管理员返回 403、提前确认重算到期日、改派后旧责任人被拒绝、发布新版本不算确认、归档期间被拒绝、撤权后重放被拒绝。
- [x] 3.5 实现管理端单项读取、本人清单、计数和空间清单；验证：`StewardshipQueryTest` 覆盖 NEEDS_REASSIGN 不含原因、UNASSIGNED 优先、仅因适用期届满列入、无当前发布版本时标识为 null、改派后清单变化、游标跨用户或跨视图返回 400、失去 manage 后返回 404、归档空间可读但本人清单不列出、依赖失败返回 503 而不是空页。
- [x] 3.6 实现控制器、no-store 和严格参数校验；验证：`StewardshipControllerTest` 覆盖周期为 1 或 9 返回 400、未知或重复参数、413，以及 `/api/documents/stewardship` 不被解析为文档 ID。
- [x] 3.7 扩展 `ModuleBoundaryTest`，限定两个子包只依赖 D1 列出的服务；验证：测试通过。
- [x] 3.8 编写 `docs/knowledge-validity-review.md` 的复审部分：权限矩阵、资格条件、清单规则、归档与搬迁的影响、上线引导步骤、首次真实库核对清单；验证：交付文档，不做内容断言。

## 4. 网关

- [x] 4.1 为新路径补充路由回归测试，确认它们走现有 kb-app 路由且网关配置不变；验证：仿照 `ReviewRouteTest` 的离线路由测试通过。

## 5. 用户端前端

- [x] 5.1 新增 `kb-web/src/validity/`：接口封装、状态文案、带请求代次的批量标识组合函数；验证：`validity/api.test.ts` 覆盖严格解析、ID 精确保留、每批不超过 100 个、迟到响应被丢弃；`validity/labels.test.ts` 覆盖全部状态文案。
- [x] 5.2 文档详情接入选中版本标识、版本表标识列、“尚未生效”提示和修改历史，读取失败显示暂不可确认；验证：`validity/detail.test.ts`。
- [x] 5.3 实现适用期编辑区块：区分候选与已发布、原因可见提示、审核冻结、REASON_REQUIRED 后重新读取、冲突与结果未确认的处理，并加入详情页写入协调；验证：`validity/editor.test.ts`，并扩展 `documents/write-coordination.test.ts` 覆盖与删除互斥、切换版本后响应迟到。
- [x] 5.4 搜索结果接入批量标识；验证：扩展 `search/page.test.ts`，覆盖结果顺序不变、503 不移除结果、新搜索后旧标识被丢弃、缺少版本身份时不发请求。
- [x] 5.5 助手引用接入标识与“模型不感知适用期”提示，并隔离会话与身份；验证：新增 `validity/references.test.ts`，覆盖实时与历史引用、非当前版本标注、版本未知、503，以及切换会话后标识迟到。
- [x] 5.6 审核界面接入只读标识与冻结说明；验证：扩展 `reviews/page.test.ts`，覆盖读取失败不影响审核操作。
- [x] 5.7 新增 `/stewardship` 页面、懒加载路由、导航项、`safeReturnPath` 白名单、导航数量轮询器和确认对话框；验证：`stewardship/page.test.ts`、`stewardship/poller.test.ts`，并扩展 `navigation.test.ts` 与 `routing/lazy-routes.test.ts`，覆盖空清单与失败的区分、99+ 和“?”、后台暂停、切换账号清除、结果未知不自动重发、迟到结果被丢弃。
- [x] 5.8 在 `kb-web/README.md` 补充适用期标识、本人清单和导航数量的说明；验证：交付文档，不做内容断言。

## 6. 管理端前端

- [x] 6.1 在 `DocumentPanel.vue` 新增 `StewardshipBox`：按管理清单开放、区块级故障隔离、需重新指定的显示、候选选择、四类写入的确认说明、结果未确认处理、归档空间只读，并加入删除互斥；验证：新增 `admin/stewardship-box.test.ts`，并扩展 `admin/space-pages.test.ts` 的删除互斥用例。
- [x] 6.2 在 `SpaceDetail.vue` 新增 `SpaceStewardship` 区块：四个标签、游标续读、不额外读取标题、503 与空清单区分、返回 404 后重新核对资格、归档提示；验证：新增 `admin/space-stewardship.test.ts`。
- [x] 6.3 在 `docs/admin-space-document-web.md` 补充责任人区块与复审概况清单；验证：交付文档，不做内容断言。

## 7. 整体核对

- [x] 7.1 运行后端离线测试白名单和全模块 `test-compile`，把命令与结果记录到本变更的 `verification.md`；验证：白名单 0 失败、0 错误，并如实记录跳过数。
- [x] 7.2 运行前端 `npm run typecheck`、`npm test -- --run`、`npm run build` 和包体隔离检查，确认 `/stewardship` 页面按需加载、不进入入口包；验证：命令均成功，结果记入 `verification.md`。
- [x] 7.3 建立规格场景到代码和测试证据的映射，运行 `openspec validate knowledge-validity-and-review --strict`；验证：校验通过，映射中明确列出未经真实数据库、浏览器和部署验证的范围。
- [x] 7.4 更新根 README 功能概览和 `docs/development-roadmap.md` 中 F-02 的状态，注明已实现、未部署；验证：交付文档，不做内容断言。

## 8. 独立核验整改

- [x] 8.1 修复 W1/S1：适用期 Panel 与管理责任人 Box 在父级保留原写状态/绑定，重读旧值、新值或 503、关闭后继续核对均只允许原内容原标识重试或显式放弃；保持目标/身份隔离、迟到回调清理及删除互斥，并同步整页放弃；验证：真实组件与共用写工具的离线回归，不 Mock 写 hook。
- [x] 8.2 按用户确认的先鉴权契约补齐 W2 回归：确认先完成时仍合格管理员收到 409，改派先完成时原责任人收到 403，撤权/目标不可访问及旧请求重放不能返回回执或版本信息；验证：StewardshipConfirmTest 的两种串行化顺序与拒绝测试，不连接数据库。
- [x] 8.3 复跑后端离线白名单、全模块 test-compile、前端全量测试/类型检查/构建、包体隔离与严格规格校验，更新 verification.md、traceability.md 和 verify-report.md 的整改状态；记录最新数量，不累计历史结果，保留真实数据库/浏览器/部署未经验证的边界。

## Workflow follow-up

- 实施和核验完成后，经用户另行确认，再同步主规格并归档本变更。
- 归档后核对主规格同步结果和路线图维护记录。
