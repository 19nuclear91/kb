# Tasks

本清单跟踪已获用户授权的实施，复选框表示各项当前完成状态。按组依赖推进，行为以七份增量规格为准，技术取舍见 design.md D1–D11；实际测试类/方法、命令和结果见 verification.md，逐场景证据见 traceability.md。

本清单只安排无需启动应用或外部组件的离线单测、模拟 DOM 和代码/配置静态检查，以及类型检查、构建与打包。不安排前后端联调、真实浏览器端到端测试，或需要数据库、Redis、容器及其他外部服务的集成测试；服务、身份、事务、仓储和网络调用均使用内存替身。混合测试类只选择符合此范围的方法，不为执行其他断言夹带外部依赖或文档内容测试。

保留 API、运维和使用说明的编写交付，但不新增或执行针对 Markdown、README、OpenAPI 描述等文档内容/存在性的断言测试。代码、SQL 和配置检查不属于文档内容测试；离线结果不得宣称证明数据库约束、真实锁/回滚、SQL 执行计划或部署联调行为。

## 1. 领域模型与持久化基础

- [x] 1.1 在 `kb-app/knowledgegap` 建立六态、类别、来源、方案身份和输入值对象；验证：`KnowledgeGapStateTest` 覆盖全部合法转移、三类终态、无自动解决、Unicode/长度边界及同负责人新请求冲突。
- [x] 1.2 核对迁移号后新增核心表、外键、唯一键、状态检查及不可变记录约束，包含单内方案/队列事件复合引用与延迟约束；验证：`KnowledgeGapSchemaTest` 离线检查 SQL 结构中的单内引用、事件/回执无正文字段及 V1–V36 未被修改，不连接数据库或执行迁移，不作为真实约束生效证据。
- [x] 1.3 新增双来源通知迁移，保留旧收件箱身份/时间/序列、原复合外键和唯一键，加入新来源 XOR/类型约束、复合外键及不可变列触发器；验证：`KnowledgeGapNotificationSchemaTest` 离线检查双来源约束声明，并以模型单测检查空/双来源和首次已读规则，不宣称验证数据库实际拒绝或触发器行为。
- [x] 1.4 实现问题、entry、event、receipt 和 actor_guard 的本模块 Mapper 及显式映射；验证：`KnowledgeGapMappingTest` 离线加载实际 MyBatis 配置，检查 BoundSql 参数绑定、字段映射、预期版本、超时、先过滤后分页的 SQL 结构及 receipt 无正文投影，不建立数据库连接或执行 SQL。
- [x] 1.5 建立本人/分派/队列/历史及派生领取所需有界索引，补入迁移说明；验证：交付 `docs/knowledge-gap-operations.md` 的数据归属、记录保留、升级前提和索引用途，不宣称已执行迁移或验证计划。

## 2. 身份资格、最小人员池与访问规则

- [x] 2.1 将四项精确权限加入 `Permission`、一次性管理员迁移及正式引导目录；验证：扩展 `PermissionCatalogTest`、`RolePermissionValidatorTest`、`AdminBootstrapRunnerTest`，以引导/角色存储替身覆盖普通角色显式授权、撤权后重复引导不恢复、未知 code 拒绝及资源 ACL 不变，不启动应用或数据库。
- [x] 2.2 实现请求内问题可见性与各动作授权，兼容提问者、当前 handle 负责人、read 三者并集；验证：`KnowledgeGapAccessTest` 覆盖旧负责人改派后失权、兼任身份、角色名无效、强制改密/停用/权限依赖故障及写权限不蕴含全局读取。
- [x] 2.3 在 auth service 层增加处理候选与单人合格性检查，knowledgegap 不直连 auth Mapper；验证：`KnowledgeGapAssigneeTest` 覆盖主部门、启用、改密、handle、多角色并集、最小 label 投影和实际分派重核。
- [x] 2.4 实现问题作用域人员候选 POST 入口及游标绑定；验证：`KnowledgeGapAssigneeControllerTest` 覆盖无 identity:user:read 可用、无 assign 先拒绝、跨人/跨单/跨条件游标拒绝、未知载荷及不回退身份目录。
- [x] 2.5 将人员池、四项资格及“问题可读不等于文档可读”说明加入运维文档；验证：交付权限配置步骤和 actor/action 可见性矩阵，并扩展 `ModuleBoundaryTest` 检查 knowledgegap 与 auth 的服务边界。

## 3. 问题命令、状态与事务回执

- [x] 3.1 实现创建与每人 100 个非终态容量检查，使用命令绑定后 actor_guard 行锁；验证：`KnowledgeGapCreateTest` 覆盖分享确认、来源不证明根因、首次 201/重放 200、99/100 边界、匹配重放不占额度及无会话原文读取。
- [x] 3.2 实现本域命令领取、规范化摘要、一次完成的最小回执和匹配重放；验证：`KnowledgeGapReceiptTest` 覆盖跨用户/动作/目标/载荷冲突、创建目标无预生成 ID、先重鉴权、未知结果重试以及无正文/资料身份回执。
- [x] 3.3 实现分派、改派、共享说明和负责人退回，维护 revision、assignment_epoch、queue_event_id 与当前方案；验证：`KnowledgeGapAssignmentTest` 覆盖候选失效、待确认改派清除当前方案、说明不变更工作身份、退回后旧负责人无法读取或重放。
- [x] 3.4 实现说明型方案、提问者确认及仍未解决，绑定不可变 solutionId 和 expectedVersion；验证：`KnowledgeGapResolutionTest` 覆盖非本人不能代确认、过期方案拒绝、退回重提新身份、说明型无模型/上传依赖和三种状态不混淆。
- [x] 3.5 实现明确撤回与带原因关闭，保留正文和不可变历史，禁止终态重开或新写入；验证：`KnowledgeGapClosureTest` 覆盖提问者停用后人工 CLOSED、无自动 RESOLVED、终态拒绝及不支持的编辑/删除入口。
- [x] 3.6 将状态、entry、event、固定收件人意图和回执放入同一短事务，统一锁序及失败映射；验证：`KnowledgeGapTransactionTest` 用离线事务/仓储替身检查各阶段失败路径、调用顺序、确认/改派/撤回/关闭的竞争状态机及迟到写入拒绝，不作为真实数据库回滚或锁行为证据；将命令/状态与重试示例写入 `docs/knowledge-gap-api.md`。

## 4. 读取、方案资料与 API 载荷

- [x] 4.1 实现本人/assigned/统一队列、详情与独立历史入口，投影当前能力和不含生命周期细节的接管提示；验证：`KnowledgeGapQueryTest` 覆盖先授权后分页、固定排序、空页/末页/503、跨视图游标和人员失效不自动改状态。
- [x] 4.2 在 document service 增加固定已发布版本核验与最小资料候选契约，不调用对方 Mapper；验证：`KnowledgeGapDocumentAccessTest` 覆盖 NONE、PUBLISHED、OBSERVED_CURRENT、历史已发布非当前版本、归档/删除/升密及旧兼容可读不冒充发布。
- [x] 4.3 实现方案资料候选 POST、方案提交双方可读复核和文档型 confirm 重核；验证：`KnowledgeGapDocumentSolutionTest` 覆盖仅处理人可读、提问者停用、候选后撤权、旧方案身份与不自动改用说明型。
- [x] 4.4 对当前及历史方案实施资料独立投影，隐藏不可读身份/标题/数量，不存标题或正文快照；验证：`KnowledgeGapReferenceTest` 覆盖无文档 read 的受理人员、后续撤权/归档/新版本、权限故障 503、已解决事实不被资料变化改写。
- [x] 4.5 为全部新 DTO、输入解析及端点配置严格 JSON、64 KiB、整数/Unicode、参数与安全错误；验证：`KnowledgeGapControllerTest`、`KnowledgeGapInputTest` 以离线控制器及 JSON 单测覆盖载荷合同、所有错误状态、非支持方法及 no-store；补齐 API 文档字段、候选和资料不可用示例，但不为文档内容编写测试。

## 5. 独立问题事件与统一通知

- [x] 5.1 实现源事务的八类 GAP_* 事件/固定收件人规则及 NOTE_ADDED 无提醒规则；验证：`KnowledgeGapEventTest` 覆盖 read 池快照、自身排除、角色重叠去重、后来获权不补发、正文不进入意图及事件保存失败回滚。
- [x] 5.2 在 notification 内实现独立问题来源的有界发现、领取、分批、游标推进与完成，接入现有调度入口；验证：`GapNotificationDerivationTest` 覆盖晚提交、部分提交后接管、重复发现、失租旧写回、退避/WAITING_SOURCE 和源问题状态不受派生失败影响。
- [x] 5.3 扩展收件箱 Mapper 与模型为双来源判别投影，合并后分页及计数，共用对应来源授权规则；验证：`GapNotificationVisibilityTest` 覆盖稀疏可见混合页、同源唯一性、旧分派/旧方案隐藏、note 不使当前提醒失效及文档型通知既有规则不变。
- [x] 5.4 实现 `KNOWLEDGE_GAP` 最小目标及固定类型提示，保留旧两个目标的精确 JSON 结构和已读语义；验证：`NotificationModelTest`、`NotificationControllerTest` 及新目标单测覆盖额外字段拒绝、本人隐藏通知可幂等已读、旧四类契约保持。
- [x] 5.5 在列表与未读数加入单值能力头及契约游标绑定，缺省仅暴露旧类型；验证：`NotificationContractTest` 覆盖缺省旧列表/计数/旧游标、声明新混合结果、跨契约及重复/未知头 400，不通过能力头扩大资源资格。
- [x] 5.6 合并两类来源的有界 backlog 读取与已有低基数 metrics，扩展架构规则禁止通知依赖源业务 Mapper/写服务或读取 entry.body；验证：`GapNotificationMetricsTest`、`ModuleBoundaryTest` 及既有通知回归保证无同名标签冲突、无假零、无新业务依赖。
- [x] 5.7 在通知及问题运维文档记录双来源、能力头、去重、时效、派生失败和旧客户端限制；验证：交付 `docs/in-app-notifications.md`、`docs/knowledge-gap-operations.md` 的新增协议/排障段落，不将数据库意图记为已送达。

## 6. 普通用户与处理人页面

- [x] 6.1 建立 `src/knowledgeGaps` 的严格 API、类型、精确身份和命令绑定逻辑；验证：`knowledgeGaps/api.test.ts`、`commands.test.ts` 覆盖路径/载荷、shareAcknowledged、expectedVersion/solutionId、未知结果和错误封装。
- [x] 6.2 实现普通列表、新建和共享详情的受保护懒加载路由及安全登录返回；验证：`knowledgeGaps/routes.test.ts` 覆盖 AI 关闭、无管理权限、assigned 所需 handle、非法 ID/返回查询及旧默认落点不变。
- [x] 6.3 实现最小反馈弹窗，接入 `SearchWorkspace.vue` 与已保存轮次的 `ConversationContent.vue`；验证：`knowledgeGaps/feedback.test.ts` 覆盖仅预填本人输入、超限不静默裁剪、明确分享/保留提示、取消零请求、临时轮次无入口及回答/引用/会话身份不入请求。
- [x] 6.4 实现本人/被指派列表、详情及历史分页、共享说明、说明型和资料型方案；验证：`knowledgeGaps/pages.test.ts` 覆盖加载/空/失败/末页、当前能力门禁、受控资料选择和固定版本展示、无身份目录请求。
- [x] 6.5 实现本人确认/仍未解决、负责人退回、本人撤回及终态只读；验证：`knowledgeGaps/lifecycle.test.ts` 覆盖旧方案冲突、资料撤权仍可退回、保留而非删除提示、不可重开和无自动确认。
- [x] 6.6 实现 Session epoch/代次、同步提交互斥与隐藏/离页/换号/撤权清理；验证：`knowledgeGaps/recovery.test.ts` 覆盖迟到响应、退回后 404、刷新不重发、安全文本/脚本和内存外无草稿泄露。
- [x] 6.7 将用户和处理人使用说明加入 `kb-web/README.md`，更新包体隔离检查的合理断言；验证：交付入口/分享/保留/未知结果说明，运行该组组件测试、`npm run typecheck` 及包体脚本单测，不使登录或文档列表依赖问题详情。

## 7. 统一受理与前端通知

- [x] 7.1 在管理资格映射、导航、路由和懒加载中加入受理列表/详情；验证：`admin/knowledge-gaps-access.test.ts` 覆盖只有 read、只有 assign/close、空间目录故障独立可用、直接访问和加载中撤权。
- [x] 7.2 实现受理队列、状态筛选、共享详情与需重新分派提示；验证：`admin/knowledge-gaps-pages.test.ts` 覆盖三类非终态/三类终态区分、失权负责人不泄露生命周期原因、稀疏分页和 503 不变成无待办。
- [x] 7.3 实现最小处理人选择、改派及带原因关闭对话框，复用当前版本与命令绑定而不回退身份目录；验证：`admin/knowledge-gaps-commands.test.ts` 覆盖候选失效、改派使旧方案无效、关闭不等于解决、冲突重读及未知结果不自动重发。
- [x] 7.4 扩展 `notifications/api.ts`、固定标签和 `NotificationTargetPanel.vue`，列表/计数统一能力头并严格匹配新旧目标；验证：扩展通知 API、页面、未读轮询测试，覆盖额外字段/目标类型不一致、改派后点击拒绝、原固定版本和已读交互不变。
- [x] 7.5 补充用户端说明和问题运维的受理、接管及新旧通知阅读流程；验证：交付可见范围/操作矩阵和恢复提示，并运行管理、通知及相邻导航回归，不新增外部 IM 推送或隐藏内部评论。

## 8. 网关、日志及协调发布

- [x] 8.1 在 gateway 基础与 dev 配置将新问题 API 根路径及子路径指向 app，保持 admin 路由和 AI/SSE 边界；验证：`KnowledgeGapRouteTest` 及相邻搜索/会话/通知路由测试仅离线绑定 YAML、执行内存路由谓词并检查根路径、子路径、方法和下游配置，不启动网关、下游或网络客户端。
- [x] 8.2 补齐 app 和 gateway 全部成功/失败/提前拒绝的 no-store、能力头透传及严格头值处理；验证：`KnowledgeGapResponsePolicyTest`、认证过滤器和通知契约测试使用内存请求/响应与依赖替身覆盖 400/401/403/404/409/413/503，不将 API 错误回退为静态 HTML，不启动 app/gateway 或连接 Redis。
- [x] 8.3 在公共生产配置的敏感 SQL 日志前缀中加入 knowledgegap，限制异常与遥测输出；验证：扩展 `ProductionSettingsTest` 等实际配置测试，覆盖父级继承、日志组、Mapper 子 logger 与正文/候选名称/凭据不输出，执行 `JavaSourceConventionTest` 不永久排除它。
- [x] 8.4 完成迁移、双来源约束、app→gateway→web 顺序和止损/兼容回退运维说明，更新 README 功能入口；验证：文档明确旧 app 不混部、旧前端缺省契约可用、保留数据和权限、不反迁移或删绑定，并交付发布检查清单。

## 9. 离线回归与证据

- [x] 9.1 运行显式后端离线白名单及 app/gateway 打包，包含新领域、身份/文档、通知、边界和生产配置影响测试，排除外部依赖及文档内容测试；验证：保存实际 Maven 命令、类/方法名、通过/失败/跳过结果到变更 `verification.md`，不以跳过或编译代替离线动态行为，也不宣称真实环境验收通过。
- [x] 9.2 运行前端类型检查、Vitest/jsdom 离线单测、生产构建和包体隔离；验证：记录 `npm run typecheck`、`npm test -- --run`、`npm run build`、`npm run bundle:test` 与既有包体检查的实际结果，核对相邻搜索、助手、文档、审核、通知和管理恢复的离线回归，不启动前后端服务或真实浏览器，不执行文档内容测试。
- [x] 9.3 建立七份规格的需求/场景→代码→离线单测/静态检查证据映射，运行变更严格校验并复核提案/设计/任务一致性；验证：`openspec validate knowledge-gap-feedback-loop --strict` 通过，注明离线证据覆盖范围及无法证明的真实数据库/部署行为，不新增外部验证待办，未完成项不得直接勾选或宣称上线验收完成。

## Workflow follow-up

- 已于 2026-11-14 在实施及证据核验后，按用户另行请求同步七份主规格并归档；流程收尾记录见 verification.md，不计入本清单的 47 项实施任务。
- 外部依赖集成/联调、真实浏览器端到端和文档内容测试已移出本清单，不作为隐藏待办或本变更的完成条件；删除任务不等于这些行为已验证，证据只能声明实际完成的离线检查范围。
