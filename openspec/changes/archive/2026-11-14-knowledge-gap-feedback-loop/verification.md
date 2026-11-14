# 实施验证记录

仅记录实际执行的离线检查；不是数据库、部署联调或浏览器端到端验收。

最终结果：47/47 实施任务完成；后端白名单 79 类/640 tests、前端 113 文件/1291 tests 全通过，类型检查、app/gateway 打包、web 生产构建、10 项包体脚本、实际模块隔离及 OpenSpec 严格校验通过。七份规格的逐场景证据见 [traceability.md](traceability.md)，发布动作仍未执行。以下按时间保留中间失败与修复记录，不将历次测试数累加。

## 2026-11-14：任务 1.1

- 命令：`mvn -o -pl kb-app -am '-Dtest=KnowledgeGapStateTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
- 结果：BUILD SUCCESS；8 tests，0 failures，0 errors，0 skipped。
- 范围：六态动作矩阵、终态拒绝、方案身份与版本冲突、改派/退回、说明不改变工作身份、Unicode code point 限额、无效 Unicode/NUL、明确分享、固定资料身份及文本诊断脱敏。
- 未验证：持久化、事务、真实身份/文档鉴权、接口、通知及页面；后续任务尚未实施。

## 2026-11-14：任务 1.2

- 命令：`mvn -o -pl kb-app -am '-Dtest=KnowledgeGapStateTest,KnowledgeGapSchemaTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
- 结果：BUILD SUCCESS；12 tests（状态 8、SQL 结构 4），0 failures，0 errors，0 skipped。
- 范围：V37 六张核心表、同单且限定类型的延迟方案/队列引用、状态及不可变记录声明、无正文事件/回执、35 份既有迁移的规范化 SHA-256 基线。
- 未执行迁移或连接数据库；本结果不证明外键、触发器、锁或事务的实际执行行为。

## 2026-11-14：任务 1.3

- 命令：`mvn -o -pl kb-app -am '-Dtest=KnowledgeGapStateTest,KnowledgeGapSchemaTest,KnowledgeGapNotificationSchemaTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
- 结果：BUILD SUCCESS；16 tests，0 failures，0 errors，0 skipped。
- 范围：V38 独立问题来源 projection、共享收件箱加法扩展、来源 XOR/类型及复合收件人引用声明、原身份与序列不回填、不修改原外键/唯一键、双来源和首次已读模型。
- 仅验证 SQL 声明及模型，不证明数据库实际约束/触发器；派生和混合查询仍待实施。

## 2026-11-14：任务 1.4

- 命令：`mvn -o -pl kb-app -am '-Dtest=KnowledgeGapStateTest,KnowledgeGapSchemaTest,KnowledgeGapNotificationSchemaTest,KnowledgeGapMappingTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
- 结果：BUILD SUCCESS；20 tests，0 failures，0 errors，0 skipped。
- 范围：本域 Mapper 显式构造映射、超过 JavaScript 安全整数的身份、可空 UUID/资料/创建目标、无正文回执、3 秒语句超时、1 秒锁等待配置、同源可见谓词先过滤后分页、命令/额度/问题锁语句。
- 使用实际 MyBatis 配置与 JDBC 结果集替身，不建立数据库连接或执行 SQL；业务服务尚未接入。

## 2026-11-14：任务 1.5

- V37 已声明本人、当前分派、状态队列、非终态容量及事件位置索引；单内 entry/event 唯一键支持历史读取，V38 声明未完成派生工作的到期索引。
- 交付 `docs/knowledge-gap-operations.md`：数据归属、保留边界、升级前提及索引用途，明确当前为不可发布的实施中间状态。
- 未增加文档内容/存在性测试，也未执行迁移或 SQL 计划检查。

## 2026-11-14：任务 2.1

- 命令：`mvn -o -pl kb-app -am '-Dtest=KnowledgeGapStateTest,KnowledgeGapSchemaTest,KnowledgeGapNotificationSchemaTest,KnowledgeGapMappingTest,PermissionCatalogTest,RolePermissionValidatorTest,AdminBootstrapRunnerTest,RoleManagementServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
- 结果：BUILD SUCCESS；44 tests，0 failures，0 errors，0 skipped。
- 四项精确权限进入服务端目录；正式引导沿用现有完整目录绑定，V37 一次性授予已有 ROLE_ADMIN。重复引导/目录校验不修复已撤销资格。
- 回归覆盖普通角色显式授予 handle、未知/通配 code 拒绝、缺省资格不扩权与资源 ACL 无新增写入；全部使用存储替身。

## 2026-11-14：任务 2.2（未完成，测试错误暂停）

- 已新增 `KnowledgeGapAccess` 与对应离线测试，但任务保持未勾选。
- 命令：`mvn -o -pl kb-app -am '-Dtest=KnowledgeGapAccessTest,KnowledgeGapStateTest,KnowledgeGapSchemaTest,KnowledgeGapNotificationSchemaTest,KnowledgeGapMappingTest,AdminQueryAccessTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
- 结果：BUILD FAILURE；33 tests，0 failures，1 error，0 skipped。
- 错误位置：`KnowledgeGapAccessTest.identityAndPermissionFailuresRemainSafeErrors` 第 137 行。
- 原因：身份 Mock 已被设置为抛出 `IdentityException`，使用 `when(identity.resolvePrincipal(7)).thenReturn(...)` 重新设置时调用了旧的抛异常行为。需改用 `doReturn(...).when(identity).resolvePrincipal(7)` 后重跑，不能将本次失败记为任务通过。
- 已完成进度仍为 6/47；未启动外部组件或服务。

## 2026-11-14：任务 2.2 修复后通过

- 将测试身份 Mock 的重设改为 `doReturn(...).when(...)`，保留停用、强制改密与依赖故障断言。
- 重跑上述同一命令：BUILD SUCCESS；33 tests，0 failures，0 errors，0 skipped。
- 当前参与三者并集、独立动作资格先于目标读取、旧负责人失权、兼任提问者确认和匹配重放当前资格已覆盖；任务 2.2 完成，进度 7/47。

## 2026-11-14：任务 2.3

- 命令：`mvn -o -pl kb-app -am '-Dtest=KnowledgeGapAssigneeTest,KnowledgeGapAccessTest,KnowledgeGapMappingTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
- 结果：BUILD SUCCESS；17 tests，0 failures，0 errors，0 skipped。
- auth 提供最小处理候选、当次合格性及 `requireEligible` 服务入口；只返回 userId/label，不依赖身份目录读取资格。分页有界、角色用 EXISTS 并集去重，实际分派通过服务重新查询资格，不用候选快照放行。
- 6 个新单测覆盖四项资格事实、Unicode/分页边界、最小 JSON 投影、过期资格拒绝、异常脱敏、真实 MyBatis 映射与 SQL 参数绑定；无数据库连接或 SQL 执行。

## 2026-11-14：任务 2.4

- 命令：`mvn -o -pl kb-app -am '-Dtest=KnowledgeGapAssigneeControllerTest,KnowledgeGapAssigneeTest,KnowledgeGapAccessTest,KnowledgeGapMappingTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
- 结果：BUILD SUCCESS；23 tests，0 failures，0 errors，0 skipped。
- 已提供 `/api/admin/knowledge-gaps/{gapId}/assignees/search`；先检查当前 assign 和问题访问，再解析有界严格 POST JSON。名称不入 URL，游标绑定 actor/gapId/nameQuery，只返回最小分页结果，不生成命令/业务事实。
- 6 个控制器离线测试覆盖无完整身份目录资格可用、无 assign 先拒绝、跨作用域游标、未知/重复/尾随 JSON、非整数 limit、非法 Unicode/ID、64 KiB、固定错误投影和 no-store；未启动 HTTP 服务。

## 2026-11-14：任务 2.5

- 命令：`mvn -o -pl kb-app -am '-Dtest=ModuleBoundaryTest,KnowledgeGapAssigneeControllerTest,KnowledgeGapAssigneeTest,KnowledgeGapAccessTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
- 结果：BUILD SUCCESS；29 tests，0 failures，0 errors，0 skipped。
- 扩展 ArchUnit：knowledgegap 禁止直接依赖其他模块 Mapper、通知/私人会话/模型来源，auth 不反向依赖问题业务；既有分层与句柄无跨请求字段规则继续通过。
- 运维文档已补齐处理人池、四项独立权限的配置步骤及 actor/action 矩阵；未增加文档内容断言测试。进度 10/47。

## 2026-11-14：任务 3.1

- 命令：`mvn -o -pl kb-app -am '-Dtest=KnowledgeGapCreateTest,KnowledgeGapAssigneeControllerTest,KnowledgeGapAccessTest,KnowledgeGapMappingTest,ModuleBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
- 结果：BUILD SUCCESS；33 tests，0 failures，0 errors，0 skipped。
- 已接入主动创建 POST、明确分享输入、每人非终态容量上限和最小回执。命令绑定先于 actor_guard 锁；匹配创建重放重新检查当前问题访问，不再次读取配额或创建事件。
- 6 个创建单测覆盖首次 201/重放 200、99/100 配额、修改载荷冲突、拒绝模型/会话/归属覆盖、来源仅是入口元数据以及事件失败路径；仓储均为替身，不证明真实锁或回滚。泛化命令回执在 3.2 继续专门验证。

## 2026-11-14：任务 3.2

- 命令：`mvn -o -pl kb-app -am '-Dtest=KnowledgeGapReceiptTest,KnowledgeGapCreateTest,KnowledgeGapAccessTest,KnowledgeGapMappingTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
- 结果：BUILD SUCCESS；24 tests，0 failures，0 errors，0 skipped。
- 命令使用固定字段顺序规范化输入并保存 SHA-256，CREATE 绑定集合入口而非生成身份；新增命令与回执使用调用方事务，匹配重放先检查当前访问/动作资格，不再次执行业务。
- 7 个专门单测覆盖绑定维度冲突、未知结果重放、一次完成、过期负责人拒绝、当前资格检查顺序、无正文/文档身份回执及严格输入类型。实际数据库并发/锁仍未验证。进度 12/47。

## 2026-11-14：任务 3.3

- 命令：`mvn -o -pl kb-app -am '-Dtest=KnowledgeGapAssignmentTest,KnowledgeGapReceiptTest,KnowledgeGapCreateTest,KnowledgeGapAccessTest,ModuleBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
- 结果：BUILD SUCCESS；36 tests，0 failures，0 errors，0 skipped。
- 已接入分派/改派、共享说明和退回受理固定 POST 端点；锁内重核当前关系与预期版本，分派再次检查候选，维护分派代次/当前队列信号/待确认方案。业务文本只进入 entry，无正文事件和回执与业务共用事务。
- 6 个单测覆盖候选失效、匹配重放、待确认改派、说明不改变工作身份、不派生说明提醒、退回后旧负责人读取/重放拒绝及严格输入；使用可变仓储替身，不作为真实锁/回滚证据。进度 13/47。

## 2026-11-14：先行完成依赖任务 4.2

- 命令：`mvn -o -pl kb-app -am '-Dtest=KnowledgeGapDocumentAccessTest,KnowledgeGapAssignmentTest,KnowledgeGapMappingTest,ModuleBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
- 结果：BUILD SUCCESS；25 tests，0 failures，0 errors，0 skipped。
- document 提供固定发布资料窄服务；沿用当前 view/密级/空间门禁并明确排除归档、删除及无发布证据版本，固定查询不替换为当前最新版。候选只取当前已发布版本并先过滤后分页。
- 5 个单测覆盖 NONE/PUBLISHED/OBSERVED_CURRENT、固定历史版本、权限撤销无快照回退、依赖故障、候选漂移拒绝和实际 MyBatis/SQL 结构；不连接数据库。为接入方案提交/确认先行完成此依赖，进度 14/47。

## 2026-11-14：任务 3.4

- 命令：`mvn -o -pl kb-app -am '-Dtest=KnowledgeGapResolutionTest,KnowledgeGapDocumentSolutionTest,KnowledgeGapDocumentAccessTest,KnowledgeGapAssignmentTest,KnowledgeGapReceiptTest,ModuleBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
- 结果：BUILD SUCCESS；37 tests，0 failures，0 errors，0 skipped。
- 已提供方案提交、本人确认和仍未解决端点；方案 UUID 与预期版本绑定，说明型不依赖模型、上传或文档查询。确认/拒绝/改派后的旧方案不能解决当前问题。
- 4 个说明型闭环单测覆盖等待本人明确确认、其他身份拒绝、退回后新方案身份和待确认改派。文档型门禁也同时接入并测试，3.4 完成，进度 15/47。

## 2026-11-14：任务 4.3

- 同一 37 个测试的回归命令中，5 个 `KnowledgeGapDocumentSolutionTest` 全部通过。
- 资料候选 POST 只返回处理人自身可读的固定版本身份和标题，游标绑定当前人/问题/名称；不预筛提问者 ACL。文档型方案提交独立核对有效提问者与双方当前可读性，确认时再次核对本人对原版本的访问。
- 不可读、提问者停用/强制改密等情况拒绝方案或确认；仍可明确退回，不自动降级说明型或替换最新版。无文档/目录/上传网络调用，相关依赖均为离线替身。进度 16/47。

## 2026-11-14：任务 3.5

- 命令：`mvn -o -pl kb-app -am '-Dtest=KnowledgeGapClosureTest,KnowledgeGapResolutionTest,KnowledgeGapDocumentSolutionTest,KnowledgeGapAssignmentTest,KnowledgeGapReceiptTest,ModuleBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
- 结果：BUILD SUCCESS；37 tests，0 failures，0 errors，0 skipped。
- 已接入本人明确撤回和独立 close 的带原因关闭；保留描述、历史及最后分派关系，不伪造本人解决确认，终态拒绝新的处理动作。
- 5 个关闭单测覆盖保留与匹配重放、提问者无效仍可人工 CLOSED、三种终态拒绝、动作权限及编辑/删除/重开拒绝。非支持方法使用内存 MockMvc，不启动应用或 HTTP 服务；响应错误合同的完整处理仍由任务 4.5 补齐。进度 17/47。

## 2026-11-14：任务 3.6

- 命令：`mvn -o -pl kb-app -am '-Dtest=KnowledgeGapTransactionTest,KnowledgeGapClosureTest,KnowledgeGapResolutionTest,KnowledgeGapDocumentSolutionTest,KnowledgeGapAssignmentTest,KnowledgeGapReceiptTest,KnowledgeGapCreateTest,KnowledgeGapMappingTest,ModuleBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
- 结果：BUILD SUCCESS；166 tests，0 failures，0 errors，0 skipped，其中事务测试 119 个。
- 实际 Spring 注解事务代理配合内存快照管理器覆盖九类动作的命令/锁/状态/entry/event/收件人/回执失败、异常写入行数、5 秒单一外层事务与 MANDATORY 参与边界。失败后整体恢复业务/意图/绑定并可用同一标识作为新请求重试。
- 确认/改派/撤回/关闭的十二种两两提交顺序仅一个同版本合法结果；锁内负责人变化拒绝迟到方案。该结果证明离线编排和状态机，不证明真实并行数据库锁、隔离、约束或回滚。
- 已交付 `docs/knowledge-gap-api.md` 的命令、状态、保留、回执和未知结果示例，没有文档内容断言。进度 18/47。

## 2026-11-14：任务 4.4

- 命令：`mvn -o -pl kb-app -am '-Dtest=KnowledgeGapQueryTest,KnowledgeGapReferenceTest,KnowledgeGapDocumentAccessTest,KnowledgeGapDocumentSolutionTest,KnowledgeGapAccessTest,KnowledgeGapMappingTest,KnowledgeGapTransactionTest,ModuleBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
- 结果：BUILD SUCCESS；168 tests，0 failures，0 errors，0 skipped，其中资料投影 5 个、读取 13 个。
- 当前/历史方案改用独立安全 DTO，关联逐次经过固定发布资料服务；无关联 NONE、可读 AVAILABLE、不可读 UNAVAILABLE，后者不返回资料身份/标题/数量。权限故障为 503，不用旧快照，原资料不切换最新版。
- 5 个资料单测和读取测试覆盖受理 read 不授予文档 view、撤权/恢复、版本不替換、历史与当前一致隐藏、资料依赖故障及 RESOLVED 不被后续资料变化改写；归档/删除/密级复用已测 MyBatis 结构，不执行 SQL。先行完成读取依赖，进度 19/47。

## 2026-11-14：任务 4.1

- 同一 168 个测试的命令中，`KnowledgeGapQueryTest` 13 个测试全部通过。
- 接入本人/assigned、独立 read 统一队列、共享详情及独立历史 GET；列表固定创建位置倒序，历史包含创建位置 0 并按结果版本正序，游标绑定当前人/视图/筛选或目标。SQL 先授权再 LIMIT，不对原始截页进行过滤。
- 详情投影当前能力，队列对失效负责人只给 needsReassignment；只批量读取实际页的最小标签，最大 100 项/200 身份，不查询完整身份目录。空页/末页与依赖故障区分，人员失效不自动改状态。
- 覆盖跨作用域游标、参数、权限先拒绝、标签调用顺序、历史独立、稀疏结果、资料隔离、最大页及异常无假空。全部为离线仓储/控制器/SQL 结构验证，不证明数据库执行。进度 20/47。

## 2026-11-14：任务 4.5

- 命令：`mvn -o -pl kb-app -am '-Dtest=KnowledgeGapInputTest,KnowledgeGapControllerTest,KnowledgeGapQueryTest,KnowledgeGapReferenceTest,KnowledgeGapAssigneeControllerTest,KnowledgeGapClosureTest,KnowledgeGapTransactionTest,GlobalExceptionHandlerTest,ModuleBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
- 结果：BUILD SUCCESS；187 tests，0 failures，0 errors，0 skipped，其中严格输入 21 个、全控制器合同 6 个。
- 八种解析入口覆盖未知/重复/尾随 JSON、根节点、实际 64 KiB+1 读取、精确大整数、Unicode/code point、UTF-8、方案成对身份/UUID、单值请求标识；九种写动作和只读候选/读取路径覆盖固定回执、首次/重放、所有七类错误及 no-store。
- 增加问题范围的未注册路径/方法拒绝，不进入静态 HTML 或原始 URL 缺失日志；框架/全局兜底对问题请求脱敏固定映射，不改变相邻 API 的 405/415。日志捕获检查无原始异常输出，架构回归通过。
- API 文档已补齐读取 DTO、分页、候选、能力和资料不可用示例，运维交付状态同步；不执行文档内容断言。进度 21/47；通知、网关和前端仍未完整接入。

## 2026-11-14：任务 5.1

- 命令：`mvn -o -pl kb-app -am '-Dtest=KnowledgeGapEventTest,KnowledgeGapTransactionTest,KnowledgeGapReceiptTest,KnowledgeGapClosureTest,KnowledgeGapMappingTest,ModuleBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
- 结果：BUILD SUCCESS；158 tests，0 failures，0 errors，0 skipped，其中源事件规则 13 个。
- 八类 GAP_* 的固定收件人、read 池当次意图、重叠身份去重及自身排除已覆盖；后来获权仅影响新事件，不补发旧意图。NOTE_ADDED 保留事实及共享 entry，不生成通知意图。
- 使用实际事务代理/快照替身检查 event/recipient 保存失败整体撤销，MyBatis 结构检查 SQL 选择有效 read 池且不搬运正文。源意图不是已派生或已送达；独立消费者仍待接入。进度 22/47。

## 2026-11-14：任务 5.2

- 命令：`mvn -o -pl kb-app -am '-Dtest=GapNotificationDerivationTest,NotificationDiscoveryTest,NotificationLeaseTest,NotificationRetryTest,NotificationMappingTest,KnowledgeGapEventTest,ModuleBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
- 结果：BUILD SUCCESS；60 tests，0 failures，0 errors，0 skipped，其中问题派生 9 个。
- notification 内独立 Gap Mapper/短事务适配器和有界轮次已接入原调度；共用已有批次算法，不共享协作 projection，Spring 注入明确选择来源。写入 only gap_event_id，反连接发现无全局高水位，按源唯一键冲突不刷新已读/时间。
- 离线代理/内存时间覆盖晚提交、重复发现、部分提交后回滚/接管、旧 token 拒绝、退避/WAITING_SOURCE、NOTE_ADDED 无提醒、有界释放及调度失败不跳过另一来源；旧发现/租约/重试/映射回归继续通过。
- SQL 结构检查无源业务状态写入或 entry.body 读取；没有实际派生到数据库、数据库锁或网络验证。混合查询/目标/能力协商仍待接入，进度 23/47。

## 2026-11-14：先行完成依赖任务 5.4

- 命令：`mvn -o -pl kb-app -am '-Dtest=GapNotificationTargetTest,NotificationModelTest,NotificationControllerTest,NotificationMappingTest,UserNotificationServiceTest,ReviewNotificationContractTest,CommentNotificationContractTest,GapNotificationDerivationTest,ModuleBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
- 结果：BUILD SUCCESS；67 tests，0 failures，0 errors，0 skipped，其中问题目标 6 个。
- 八种 GAP_* 采用固定提示；问题目标严格只有 kind/gapId，旧审核和评论目标五字段（包含原可空字段）完全不变。自定义精确序列化/解析拒绝混合身份、扩展、重复字段和不精确整数，固定目标不带 URL/标题/正文。
- 问题最小事实/解析器核对当前队列、分派代次、方案与终态，不用普通版本使 note 失效；本人隐藏通知仍可幂等标记首次已读，不需目标/文档资格或能力头。旧服务缺省仍只读四类，旧 JSON/已读回归通过。
- 为混合 SQL 读取先行补齐模型与目标；真实混合查询和新能力声明尚在 5.3/5.5 接入，进度 24/47。

## 2026-11-14：任务 5.3 / 5.5（未完成，测试错误暂停）

- 已写入双来源 UNION 查询、嵌套最小事实映射、公共单值能力头和新旧游标绑定，但两项任务保持未勾选；当前中间状态不可发布。
- 命令：`mvn -o -pl kb-app -am '-Dtest=GapNotificationVisibilityTest,NotificationContractTest,GapNotificationTargetTest,NotificationMappingTest,NotificationModelTest,NotificationControllerTest,UserNotificationServiceTest,ReviewNotificationContractTest,CommentNotificationContractTest,ModuleBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
- 结果：BUILD FAILURE；71 tests，0 failures，11 errors，0 skipped。`NotificationContractTest` 的 6 个离线用例通过，但不能代替实际 Mapper 可加载性或完整混合查询验证。
- 9 个 `NotificationMappingTest` 错误：`KnowledgeGapMapper.visible` 经跨命名空间 include 展开后，其内部相对 `validActor` 被解析为 `UserNotificationMapper.validActor`，报 `Could not find SQL statement to include`。下一步需为可跨模块引用的内部授权片段使用完整限定 refid，并重跑旧/新映射回归，不能只修测试或绕过同源授权。
- 2 个 `GapNotificationVisibilityTest` 错误：新测试配置未注册 UUID TypeHandler，加载 `DocumentCommentMapper.xml` 时报 `No typehandler found for property null`。已确认 `MapperRowStub.configuration` 不自动注册全局类型处理器；需在新仓储/探针配置显式注册既有 `UuidTypeHandler`。
- 未执行上述修复，等待继续指示；总进度仍为 24/47。没有执行迁移、启动外部组件、联调、部署或提交代码。

## 2026-11-14：5.3 / 5.5 原错误修复，结果集替身断言仍未通过

- 已将 `KnowledgeGapMapper.visible` 内部的 validActor/handlePermission/readPermission 引用改为完整限定 refid；新通知测试和探针配置显式注册既有 UUID TypeHandler，不更改或绕过同源授权。
- 命令：`mvn -o -pl kb-app -am '-Dtest=GapNotificationVisibilityTest,NotificationContractTest,GapNotificationTargetTest,NotificationMappingTest,NotificationModelTest,NotificationControllerTest,UserNotificationServiceTest,ReviewNotificationContractTest,CommentNotificationContractTest,ModuleBoundaryTest,KnowledgeGapMappingTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
- 结果：BUILD FAILURE；75 tests，3 failures，0 errors，0 skipped。原先的跨命名空间和 TypeHandler 错误已消除；共享规则探针、旧权限/JSON/已读以及本域 Mapper 回归通过，但两项任务仍保持未勾选。
- 失败 1：`GapNotificationVisibilityTest.actualNestedMappingKeepsGapIdentityExactAndDocumentIdentityNull` 的问题来源结果集缺少 `review_id` 等实际 UNION 投影的可空旧来源列。
- 失败 2–3：`NotificationMappingTest.listRowMapsExactIdsNullableFactsAndUuidTargets`、`commentReplyRowMapsExternalCommentIdAndIndependentDiscussionFacts` 的旧来源结果集缺少实际 UNION 投影的 `gap_id` 等全空问题事实列。
- `MapperRowStub` 正确要求显式提供 Mapper 实际读取的列；下一步应在三个测试中补齐对应可空列并核验非对应来源保持 null，不放宽严格结果集检查或删除断言。
- 按实施流程暂停等待继续指示，进度仍为 24/47；未启动外部组件、执行迁移、部署或提交代码。

## 2026-11-14：任务 5.3 修复后通过

- 为三个映射用例显式补齐实际 UNION 投影的可空列，并断言旧来源 gap 为 null、问题来源无文档/审核/评论身份；没有放宽 `MapperRowStub` 严格列检查或删除断言。
- 命令：`mvn -o -pl kb-app -am '-Dtest=GapNotificationVisibilityTest,NotificationContractTest,GapNotificationTargetTest,NotificationMappingTest,NotificationModelTest,NotificationControllerTest,UserNotificationServiceTest,ReviewNotificationContractTest,CommentNotificationContractTest,ModuleBoundaryTest,KnowledgeGapMappingTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
- 结果：BUILD SUCCESS；75 tests，0 failures，0 errors，0 skipped。
- 双来源事实先 UNION 后按当前范围过滤、排序及分页/计数；问题分支引用完整同源授权与工作身份片段，Java 独立复核队列、分派代次、方案和终态。稀疏混合页、资料资格不扩大问题通知、note 不使当前提醒失效、失权/旧方案隐藏、精确嵌套映射及旧文档通知契约均通过。
- 仅为内存服务、实际 MyBatis 映射/结构证据，不执行 SQL 或连接数据库。任务 5.3 完成，进度 25/47。

## 2026-11-14：任务 5.5

- 同一 75 个测试的回归命令中，6 个 `NotificationContractTest` 全部通过，且新旧实际 Mapper 映射加载已通过。
- 公共单值能力头供 app/gateway 共用；缺省仍只返回旧四类及其计数，并保留原 userId 游标摘要；声明 KNOWLEDGE_GAPS_V1 时列表/计数使用同一混合集合，游标增加契约绑定。
- 跨契约/跨人游标、未知/重复/合并/空白头均拒绝；不失败降级裸请求，不通过声明扩大本人归属或资源资格，所有响应 no-store。任务 5.5 完成，进度 26/47。

## 2026-11-14：任务 5.6

- 命令：`mvn -o -pl kb-app -am '-Dtest=GapNotificationMetricsTest,GapNotificationDerivationTest,GapNotificationVisibilityTest,NotificationMappingTest,NotificationDiscoveryTest,NotificationLeaseTest,NotificationRetryTest,ModuleBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`
- 结果：BUILD SUCCESS；59 tests，0 failures，0 errors，0 skipped（已核对八个 Surefire XML 报告）。
- 调度使用一次完整双来源聚合；两类事件各 LEFT JOIN 独立 projection 后 UNION，未登记意图视为 PENDING、DONE 排除、年龄不为负。不读取 entry.body 或输出事件身份，不新增指标名称或来源标签。
- 两类派生复用同一 metrics 实例及既有固定标签；依赖失败、null 或负数快照保留上次可靠指标并增加 stage=backlog，不导出假零。ArchUnit 禁止 notification 对 knowledgegap Java 模块及跨模块 Mapper 的依赖。
- 内存完整来源汇总、无连接 MyBatis 映射/SQL 结构和既有发现/租约/退避回归通过；不证明真实 SQL 执行计划、性能、行锁或数据库约束。任务 5.6 完成，进度 27/47。

## 2026-11-14：任务 5.7

- 已交付 `docs/in-app-notifications.md` 与 `docs/knowledge-gap-operations.md` 的双来源生命周期、八类固定意图、工作身份时效、来源去重、最小目标、能力头/游标及故障定位说明。
- 修正旧“积压只统计已登记事件”的说明，记录未登记归入 pending、一次双来源汇总及采集失败保留可靠旧值的边界；明确发现数量限制/语句超时不证明扫描成本、积压不是送达或待办数量。
- 同步后端通知已接入而页面/网关尚未完整的中间状态；记录旧客户端只读旧契约、旧 app 不可混部及兼容回退边界，未宣称已部署。
- 本任务按要求只交付说明，不新增或执行文档内容/存在性断言。任务 5.7 完成，进度 28/47。

## 2026-11-14：任务 6.1（单测通过，类型检查失败暂停）

- 已新增 `kb-web/src/knowledgeGaps/{types,input,intents,protocol,api,commands}.ts`：固定 API 路径及严格输入工厂、精确整数/UUID、最小 DTO/独立资料投影、原始 JSON 有界读取及重复键检查、最小回执匹配、内存命令绑定和同步互斥。未接入页面，不应发布此中间状态。
- 命令（`kb-web` 目录）：`npm test -- --run src/knowledgeGaps/api.test.ts src/knowledgeGaps/commands.test.ts`。
- 结果：2 test files、30 tests 全部通过；覆盖创建分享/禁止回答与会话身份夹带、九类命令路径和版本/方案绑定、整数 JSON 精度、严格响应/失败固定提示、未知结果显式原标识重试、内容变化新标识、目标/epoch 清理及迟到响应互斥隔离。使用真实 Session 和内存 transport、受控 Promise，无真实服务或浏览器。
- 并行命令：`npm run typecheck`。
- 结果：失败（exit code 1）；`src/knowledgeGaps/api.ts(30,29): error TS2345: Argument of type '{}' is not assignable to parameter of type '"WITHDRAWN" | "RESOLVED" | "SUBMITTED" | "IN_PROGRESS" | "AWAITING_CONFIRMATION" | "CLOSED"'.`
- 原因：`gapKeys` 的严格对象类型守卫后，列表 query.status 在 includes 调用处未收窄为状态枚举。下一步应显式验证并收窄状态值，保持未知/非法状态拒绝，不放宽类型或移除校验；修正后重跑两项命令。
- 按实施流程遇错暂停；6.1 保持未勾选，进度 28/47。未启动外部组件、执行迁移、联调、部署、提交或推送代码，未新增/执行文档内容测试。

## 2026-11-14：任务 6.1 修复后通过

- 状态筛选先验证字符串，再逐项匹配六态白名单，避免严格对象守卫后调用 includes 的类型错误；新增空字符串、数字和对象状态拒绝用例，不使用宽松断言或删除运行时校验。
- 命令（`kb-web` 目录）：`npm test -- --run src/knowledgeGaps/api.test.ts src/knowledgeGaps/commands.test.ts`；结果：2 test files、30 tests 全部通过。
- 并行命令：`npm run typecheck`；结果：通过（exit code 0）。原 TS2345 已消除。
- 6.1 的严格输入/DTO、精确身份/版本及未知结果内存绑定完成；页面与前后端部署不在本次通过证据内。任务 6.1 完成，进度 29/47。

## 2026-11-14：任务 6.2

- 接入普通知识需求导航、三条受保护路由和三个独立页面加载器；登录返回使用精确根/新建/正整数详情白名单，拒绝正文、筛选、片段、非注册子路径及超 BIGINT ID。旧默认落点保持文档中心。
- 本人入口不依赖 AI 或功能权限；assigned 单独要求当次 handle，不能借此进入受理队列。问题页面不读取空间/完整身份目录；非法地址不发问题请求，身份未确认/匿名/改密时不调用页面加载器。
- 交付基础本人列表、新建分享表单及只读详情外壳，并复用现有会话/隐藏作用域；历史、处理动作及完整生命周期组件仍由 6.3–6.6 继续完成，未勾选这些任务。
- 命令（`kb-web` 目录）：`npm test -- --run src/knowledgeGaps/routes.test.ts src/knowledgeGaps/api.test.ts src/knowledgeGaps/commands.test.ts src/navigation.test.ts src/routing/lazy-routes.test.ts`；结果：5 test files、79 tests 全部通过。
- 并行命令：`npm run typecheck`；结果：通过。全部使用 jsdom、内存路由、Session/transport 替身及受控模块加载，不启动应用或真实浏览器。任务 6.2 完成，进度 30/47。

## 2026-11-14：任务 6.3

- 搜索仅预填实际已提交的本人搜索词，问答仅提供持久终结轮次的原提问（COMPLETED/FAILED/CANCELLED/INTERRUPTED）；RUNNING 和临时流无轮次反馈入口。预填函数不读取答案、引用或来源身份，屏蔽答案也不能被搬入描述。
- 两处均在用户显式打开后才加载 `GapFeedbackDialog`，复用明确分享表单；超限保留全部原输入并禁用提交，须人工缩短及重新确认，不静默裁剪。取消零创建、重开不恢复草稿，不影响原结果或错误。
- 来源页反馈描述仅在当前身份内存，隐藏/离页/换号/目标改变即清理；提交只发送四个创建字段，成功提示不宣称通知送达或问题解决，不改变原搜索/会话地址。
- 命令（`kb-web` 目录）：`npm test -- --run src/knowledgeGaps/feedback.test.ts src/search.test.ts src/assistant.test.ts src/knowledgeGaps/api.test.ts src/knowledgeGaps/commands.test.ts src/knowledgeGaps/routes.test.ts`；结果：6 test files、83 tests 全部通过。
- 并行命令：`npm run typecheck`；结果：通过。使用 jsdom、内存 transport 和流替身，无浏览器端到端或前后端联调。任务 6.3 完成，进度 31/47。

## 2026-11-14：任务 6.4

- 完成本人/assigned 有界列表、当前详情、独立正序历史分页和当前能力门禁；加载/空/失败/末页分开显示，无隐藏总数或身份目录补查。历史 404 清理整个问题且不循环读或回退他人队列。
- 共享说明及说明型/资料型方案要求明确同单分享；共用页面级同步命令互斥，成功仅重读源问题事实，未知结果按原载荷显式重试，409 清理旧输入并重读。
- 资料选择只调用问题作用域 POST 候选，名称/条件改变或候选失败清除选择；提交固定整数身份，展示和主动预览只用原版本。资料不可用清空标题/身份/入口，不自动下载或调用模型/上传/完整目录。
- 命令（`kb-web` 目录）：`npm test -- --run src/knowledgeGaps/pages.test.ts src/knowledgeGaps/routes.test.ts src/knowledgeGaps/feedback.test.ts src/knowledgeGaps/api.test.ts src/knowledgeGaps/commands.test.ts`；结果：5 test files、62 tests 全部通过。
- 并行命令：`npm run typecheck`；结果：通过。预览目标使用组件替身证明固定路径，不宣称真实文件预览、数据库授权或联调已验证。任务 6.4 完成，进度 32/47。

## 2026-11-14：任务 6.5

- 本人确认/仍未解决额外核对精确提问者身份和当前方案；确认只在用户打开并明确确认后发送，绑定原 solutionId 和预期版本，不因通知、资料或等待时间自动执行。资料 UNAVAILABLE 禁止宣称确认成功，仍可退回方案或撤回。
- 当前负责人可带原因退回受理；本人撤回需原因及保留提示，均使用页面同一同步命令互斥。退回成功后 404 清理旧描述/方案，不延续历史访问或读取他人队列。
- RESOLVED/CLOSED/WITHDRAWN 均只读，无追加/编辑/删除/重开；新需求只进入空白新建路由。409 清理原对话框并重读新方案，不自动确认新身份。
- 命令（`kb-web` 目录）：`npm test -- --run src/knowledgeGaps/lifecycle.test.ts src/knowledgeGaps/pages.test.ts src/knowledgeGaps/routes.test.ts src/knowledgeGaps/feedback.test.ts src/knowledgeGaps/api.test.ts src/knowledgeGaps/commands.test.ts`；结果：6 test files、72 tests 全部通过。
- 并行命令：`npm run typecheck`；结果：通过。全部为 jsdom/内存 transport，不宣称真实状态竞争或数据库行为已验证。任务 6.5 完成，进度 33/47。

## 2026-11-14：任务 6.6

- 读取隔离同时绑定 Session 对象、epoch、目标及请求代次；隐藏/离页卸载正文与表单，恢复先核对身份。本人功能资格事实变化同步清理命令绑定/表单并核对当前参与，不依赖空间目录；失去全部参与身份时遮蔽正文和候选。
- 全部详情状态/处理动作共享同步提交互斥；迟到成功不能恢复旧目标、身份或撤权正文，旧请求结束不能解除新上下文互斥。未知结果只驻当前内存；刷新/重挂载无恢复或重发，退回后显式核对 404 不扩权。
- 描述、说明及标签按纯文本显示，脚本/HTML/远程图片不执行；草稿、回执和候选名称不进入 URL/history/浏览器持久存储。
- 命令（`kb-web` 目录）：`npm test -- --run src/knowledgeGaps/recovery.test.ts src/knowledgeGaps/lifecycle.test.ts src/knowledgeGaps/pages.test.ts src/knowledgeGaps/routes.test.ts src/knowledgeGaps/feedback.test.ts src/knowledgeGaps/api.test.ts src/knowledgeGaps/commands.test.ts src/routing/lazy-routes.test.ts src/navigation.test.ts`；结果：9 test files、120 tests 全部通过。
- 并行命令：`npm run typecheck`；结果：通过。受控 Promise、内存路由/身份、模拟隐藏和模块加载证据不代表真实浏览器或后端部署行为。任务 6.6 完成，进度 34/47。

## 2026-11-14：任务 6.7（包体脚本断言失败暂停）

- 已补充 `kb-web/README.md` 的普通入口、分享/保留、受控处理、固定资料、本人确认、未知结果和页面清理说明，明确管理/新通知/网关仍未全部交付，不宣称可发布；未为说明文档新增或执行断言测试。
- 包体报告新增 knowledgeGapList/New/Detail/Feedback 四场景；入口/登录/文档列表只允许极小的 `knowledgeGaps/routes.ts` 地址助手，其余问题业务模块保持异步隔离，既有受保护页面和管理/普通交叉内联规则未放宽。
- 命令（`kb-web` 目录）：`npm test -- --run src/knowledgeGaps/api.test.ts src/knowledgeGaps/commands.test.ts src/knowledgeGaps/routes.test.ts src/knowledgeGaps/feedback.test.ts src/knowledgeGaps/pages.test.ts src/knowledgeGaps/lifecycle.test.ts src/knowledgeGaps/recovery.test.ts src/routing/lazy-routes.test.ts src/navigation.test.ts src/search.test.ts src/assistant.test.ts`；结果：11 test files、151 tests 全部通过。
- 并行命令：`npm run typecheck`；结果：通过。
- 并行命令：`npm run bundle:test`；结果：10 tests，9 pass、1 fail、0 cancelled、0 skipped、0 todo（exit code 1）。失败位置：`scripts/bundle-report.test.mjs:145`，新增“知识需求路由和反馈单独统计”用例。
- 原因：向入口注入 GapFeedbackDialog 时，新增场景已将该模块纳入既有 protectedModules，首先抛出 `入口包含受保护页面实现`；用例统一期待 `提前包含知识问题`，因此错误消息断言不匹配。隔离拒绝实际发生，并非非法依赖被放行。
- 下一步应按注入位置和模块类别精确断言既有/新增规则对应的拒绝消息，不删除旧规则、放宽业务隔离或跳过用例；修正后重跑脚本单测，再继续实际构建及隔离检查。
- 按实施流程遇错暂停；6.7 保持未勾选，进度 34/47。本次尚未执行生产构建/实际包体报告，未启动外部组件、执行迁移、联调、部署或提交/推送代码。

## 2026-11-14：任务 6.7 修复后通过

- 用户授权后续同类明确实现/断言问题直接修正重跑。本次按入口/列表位置及反馈模块类别精确区分既有和新增拒绝消息，不放宽生产隔离规则。
- 命令（`kb-web` 目录）：`npm run bundle:test`；结果：10 tests 全通过，0 fail/cancelled/skipped/todo。
- 同步重跑上一节明确列出的 11 文件前端命令：151 tests 全通过；`npm run typecheck` 通过。
- `npm run build` 通过；`npm run bundle:report -- --check-isolation --out .bundle-analysis/knowledge-gap-intermediate.json` 通过。入口/登录累计 gzip 127215 bytes，文档列表 233098 bytes；问题列表/新建/详情/反馈分别 171830/177502/156132/183544 bytes。报告只反映当前中间实现，不作为最终 9.2 或部署验收证据。
- 使用说明已交付；实际生产模块图确认入口、登录和文档列表没有问题业务实现，极小路由助手例外保留。任务 6.7 完成，进度 35/47。

## 2026-11-14：任务 7.1

- 四项服务端精确 code 加入前端资格映射；受理导航、列表和详情懒加载均只按 read 开放，不以 assign/close/handle/角色名称或身份目录替代。管理返回只允许固定根及 BIGINT 正整数详情，不携带条件/正文。
- 两个管理外壳复用共享业务组件而非普通页面模块；不请求空间/身份目录，read 撤销时取消挂载，迟到或缓存模块不能延续资格。
- 命令（`kb-web` 目录）：`npm test -- --run src/admin/knowledge-gaps-access.test.ts src/admin/knowledge-gaps-pages.test.ts src/knowledgeGaps/routes.test.ts src/knowledgeGaps/pages.test.ts src/knowledgeGaps/recovery.test.ts src/navigation.test.ts src/routing/lazy-routes.test.ts src/admin/access.test.ts`；结果：8 test files、99 tests 全通过。
- 首次 `npm run typecheck` 报测试 Mock 字典丢失调用签名（TS2345）；按用户指示直接将有类型局部 loader 传入 lazyPage 后重跑，同一 99 tests 与类型检查均通过，无类型断言或放宽门禁。
- 证据只来自内存路由/身份及受控加载。任务 7.1 完成，进度 36/47。

## 2026-11-14：任务 7.2

- 同一 99 tests 回归及类型检查通过。统一队列只调用 admin 列表的有界状态/游标参数，改变状态从首页开始；六态各自展示，不把待确认/关闭/撤回计作解决。
- 队列与共享详情仅显示服务端 needsReassignment 的人工接管提示，不泄露停用/离职/部门/角色原因或自动改变事实；503 独立显示故障，不变为成功空队列。详情资料与历史继续使用原独立投影，无目录/文档补权。
- `knowledge-gaps-pages.test.ts` 的实际 DOM/内存响应覆盖六态、分页/条件重置、接管、故障与只读详情，非数据库 SQL 或部署证据。任务 7.2 完成，进度 37/47。

## 2026-11-14：任务 7.3

- 受理分派/关闭分别核对精确资格及详情能力，使用同页命令互斥；人员候选只经问题作用域 POST 返回 userId/label，无手填内部 ID 或身份目录回退。条件/资格/目标变化和失败清理候选与选择。
- 改派明确原方案失效、历史保留和旧负责人失权；关闭必须原因及明确确认，提示 CLOSED 而非 RESOLVED。409 清理旧输入重读，未知结果只按原载荷/版本/标识显式重试，不自动重发。
- 命令（`kb-web` 目录）：`npm test -- --run src/admin/knowledge-gaps-commands.test.ts src/admin/knowledge-gaps-access.test.ts src/admin/knowledge-gaps-pages.test.ts src/knowledgeGaps/pages.test.ts src/knowledgeGaps/lifecycle.test.ts src/knowledgeGaps/recovery.test.ts`；结果：6 test files、47 tests 全通过。
- `npm run typecheck` 通过。全部为内存 transport、jsdom 和受控 Promise，不证明真实人员撤权或数据库事务。任务 7.3 完成，进度 38/47。

## 2026-11-14：任务 7.4

- 通知列表/计数统一 KNOWLEDGE_GAPS_V1 单值头；契约计入列表与共享轮询作用域，失败不去头降级。八类问题提示固定本地文字，最小目标仅 kind/gapId，额外字段、类型/目标不匹配及不安全整数均拒绝，旧五字段目标保持。
- 打开问题直接导航到同一 gapId 的受保护详情，重读资格和当前状态，不自动已读/确认；改派后 404 清理，旧待确认通知可显示现在已关闭。文档/评论固定目标和显式已读、单在途/30 秒底线保持。
- 命令（`kb-web` 目录）：`npm test -- --run src/notifications/api.test.ts src/notifications/page.test.ts src/notifications/unread.test.ts src/notifications/gap-target.test.ts src/knowledgeGaps/recovery.test.ts src/admin/knowledge-gaps-commands.test.ts src/routing/lazy-routes.test.ts`；结果：7 test files、72 tests 全通过。
- 首轮类型检查发现测试默认参数的 options 可为 undefined（TS18048）；按用户授权修正可选访问并重跑同一 72 tests 和 `npm run typecheck`，均通过。
- 实际 Session/内存路由用于目标及能力头证据，不进行前后端联调。任务 7.4 完成，进度 39/47。

## 2026-11-14：任务 7.5

- 已交付 README 与问题运维的 read/assign/handle/close 页面及操作矩阵、受理/接管步骤、未知结果/撤权恢复和新旧通知阅读流程；同步通知/API 说明的中间交付状态，网关/配置及最终离线验证仍未完成，不宣称可发布。
- 新通知只打开同一 gapId 的当前事实，已读不执行问题动作，旧客户端缺省旧契约，不去头降级；未引入外部 IM 或隐藏内部评论。不执行说明文档内容/存在性断言。
- 命令（`kb-web` 目录）：`npm test -- --run src/admin/knowledge-gaps-access.test.ts src/admin/knowledge-gaps-pages.test.ts src/admin/knowledge-gaps-commands.test.ts src/admin/access.test.ts src/admin/access-spaces.test.ts src/notifications/api.test.ts src/notifications/page.test.ts src/notifications/unread.test.ts src/notifications/gap-target.test.ts src/knowledgeGaps/routes.test.ts src/knowledgeGaps/recovery.test.ts src/navigation.test.ts src/routing/lazy-routes.test.ts`；结果：13 test files、120 tests 全通过。
- 以上为管理/通知/相邻导航离线回归与正常说明交付。任务 7.5 完成，进度 40/47。

## 2026-11-14：任务 8.1

- 基础与 dev 的 app Path 谓词同时新增普通问题根及子路径，管理问题沿用 admin 路由；所有方法均交 app 严格处理，不匹配 UI/内部/相似根，AI SSE 独立 -1 超时及原相邻路径保持。
- 命令：`mvn -o -pl kb-gateway -am '-Dtest=KnowledgeGapRouteTest,NotificationRouteTest,SearchRouteTest,ConversationRouteTest,ReviewRouteTest,ImportRouteTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`。
- 结果：BUILD SUCCESS；10 tests，0 failures/errors/skipped。实际 YAML 绑定/内存谓词和认证公开列表检查，不启动网关、Redis、客户端或下游，不证明部署路由。任务 8.1 完成，进度 41/47。

## 2026-11-14：任务 8.2

- app 问题/通知私有响应包装在下游 set/add/reset 和提交前保持 no-store；路径处理覆盖 context path、一次 URI 解码及错误/异步派发。全局问题异常使用同一匹配逻辑，固定 JSON 错误不回显正文。通知 PUT 保留一字节正文检查包装。
- gateway 问题响应先于身份拒绝设置 no-store，提交前再次保护；通知列表/计数严格解析单值能力头，非法/重复值返回固定 JSON 400 且不进入下游；合法能力头通过身份头净化保持，已读 PUT 不要求声明。AI/SSE 不受影响。
- 命令：`mvn -o -pl kb-app,kb-gateway -am '-Dtest=KnowledgeGapResponsePolicyTest,KnowledgeGapControllerTest,KnowledgeGapInputTest,NotificationResponsePolicyTest,NotificationContractTest,NotificationControllerTest,UserNotificationServiceTest,GlobalExceptionHandlerTest,EpochAuthenticationInterceptorTest,PasswordChangeRequiredInterceptorTest,SaTokenConfigTest,SaTokenGatewayConfigTest,KnowledgeGapRouteTest,NotificationRouteTest,ModuleBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`。
- 首次 app 75 tests 通过，gateway 13 tests 中 1 failure：`MockServerHttpRequest.post(String)` 二次编码 `%`，导致编码路径替身不代表原请求。改为 `method(HttpMethod.POST, URI.create(path))` 并断言 raw path 后完整重跑；结果 BUILD SUCCESS，app 75 + gateway 13 = 88 tests，0 failures/errors/skipped，已核对对应 Surefire XML。
- 全部为内存请求/响应、控制器及身份替身，没有启动 app/gateway、Redis 或下游，不证明实际部署/网络行为。任务 8.2 完成，进度 42/47。

## 2026-11-14：任务 8.3

- 公共生产校验新增 knowledgegap.mapper 敏感前缀，app YAML 显式 OFF；覆盖父级继承、Mapper 类/方法子 logger、DEBUG/TRACE/ALL、自定义及索引日志组、配置采集与安全 override 不可掩盖子 SQL 日志。
- `KnowledgeGapLoggingTest` 使用实际 YAML 级别和 Logback 子 logger 验证正文/参数不输出；固定错误投影和双来源调度异常不输出原文/异常 cause；内存 span exporter 只保留固定路由及异常类型，不输出候选名称/正文/凭据。既有低基数通知 metrics 回归通过。
- 原 JavaSourceConventionTest 于 2026-10-27 删除且包含文档格式断言；按本任务明确要求新增同名的纯代码 AST 检查，只扫描本变更相关主代码的 import、大括号与多行体，不恢复文档断言，不声称全库治理通过。首次发现五个已受本变更影响的通知源文件共 26 处无大括号控制流；仅补齐大括号，业务逻辑不变，完整重跑。
- 命令：`mvn -o -pl kb-app,kb-gateway -am '-Dtest=ProductionSettingsTest,ProductionSettingsCollectorTest,JavaSourceConventionTest,KnowledgeGapLoggingTest,KnowledgeGapControllerTest,KnowledgeGapAssigneeTest,GapNotificationMetricsTest,TelemetrySpanFilterTest,KnowledgeGapResponsePolicyTest,SaTokenGatewayConfigTest,ModuleBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`。
- 首次 common 91 tests 中 1 failure，app/gateway 未执行；修复后 BUILD SUCCESS，common 91 + app 35 + gateway 11 = 137 tests，0 failures/errors/skipped。没有网络导出、启动服务或连接外部组件。任务 8.3 完成，进度 43/47。

## 2026-11-14：任务 8.4

- 交付 `docs/knowledge-gap-rollout.md` 的准备/排空旧 app/迁移/app→gateway→web/权限配置发布清单，各实际发布动作明确未执行。说明 V37/V38 单内引用、双来源 XOR/复合外键/唯一键/首次已读不可变结构、旧前端缺省合同、静态资产顺序与 API 不回退 HTML。
- 止损撤下入口、受控停止修改 POST、保留安全历史读取及源意图；派生开关不等于问题写开关。只允许认识新目录/双来源的兼容二进制回退，不删数据、权限、绑定、projection 或反迁移。
- README 新增普通/受理入口及当前 V38 说明，保留原未提交成果；API、通知、运维及 web README 同步当前阶段，最终离线回归仍在进行。不新增或执行说明文档断言，不部署。任务 8.4 完成，进度 44/47。

## 2026-11-14：任务 9.1 最终后端离线回归与打包

### 实际命令

工作目录为仓库根；白名单执行前核对 77 个名称均有源码，实际匹配 79 个类（app/gateway 各有 KnowledgeGapResponsePolicyTest 和 TelemetrySpanFilterTest）。未自动发现其他测试，也未运行说明文档、OpenAPI 描述或外部组件测试。

```powershell
$tests = 'ProductionSettingsTest,ProductionSettingsCollectorTest,JavaSourceConventionTest,KnowledgeGapStateTest,KnowledgeGapSchemaTest,KnowledgeGapNotificationSchemaTest,KnowledgeGapMappingTest,KnowledgeGapAccessTest,KnowledgeGapAssigneeTest,KnowledgeGapAssigneeControllerTest,KnowledgeGapCreateTest,KnowledgeGapReceiptTest,KnowledgeGapAssignmentTest,KnowledgeGapResolutionTest,KnowledgeGapClosureTest,KnowledgeGapTransactionTest,KnowledgeGapQueryTest,KnowledgeGapDocumentAccessTest,KnowledgeGapDocumentSolutionTest,KnowledgeGapReferenceTest,KnowledgeGapInputTest,KnowledgeGapControllerTest,KnowledgeGapEventTest,KnowledgeGapLoggingTest,GapNotificationDerivationTest,GapNotificationVisibilityTest,GapNotificationTargetTest,GapNotificationMetricsTest,NotificationContractTest,NotificationModelTest,NotificationControllerTest,NotificationMappingTest,NotificationDiscoveryTest,NotificationLeaseTest,NotificationRetryTest,UserNotificationServiceTest,ReviewNotificationContractTest,ReviewNotificationVisibilityTest,CommentNotificationContractTest,PermissionCatalogTest,RolePermissionValidatorTest,RoleManagementServiceTest,RolePermissionWriteTest,AdminBootstrapRunnerTest,AdminQueryAccessTest,PermissionServiceTest,IdentityQueryServiceTest,DocumentVersionAccessServiceTest,DocumentCitationAuthorizationTest,DocumentVersionProjectionTest,DocumentReviewQueryTest,DocumentReviewMappingTest,ReviewPermissionScopeTest,CommentPermissionScopeTest,DocumentAclServiceTest,SpaceAclServiceTest,QueryCursorTest,QueryParametersTest,GlobalExceptionHandlerTest,EpochAuthenticationInterceptorTest,PasswordChangeRequiredInterceptorTest,SaTokenConfigTest,KnowledgeGapResponsePolicyTest,NotificationResponsePolicyTest,ModuleBoundaryTest,TelemetrySpanFilterTest,HttpObservationPrivacyTest,KnowledgeGapRouteTest,NotificationRouteTest,SearchRouteTest,ConversationRouteTest,ReviewRouteTest,ImportRouteTest,CorsConfigurationTest,SaTokenGatewayConfigTest,InboundHeaderSanitizeFilterTest,StartupEnvironmentCheckTest'
mvn -o -pl kb-app,kb-gateway -am "-Dtest=$tests" '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -o -pl kb-app,kb-gateway -am '-DskipTests' package
```

### 结果与证据

| 模块 | 实际类数 | tests | failures | errors | skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| kb-common | 3 | 91 | 0 | 0 | 0 |
| kb-app | 63 | 516 | 0 | 0 | 0 |
| kb-gateway | 13 | 33 | 0 | 0 | 0 |
| 合计 | 79 | 640 | 0 | 0 | 0 |

- 测试命令 BUILD SUCCESS（22.765 秒）；已按本次时间核对 79 份 `target/surefire-reports/TEST-*.xml`，640 项全通过，无 assumption 跳过。不是全仓库后端测试全量通过声明。
- 打包命令另行 BUILD SUCCESS（29.283 秒），产物为 `kb-app/target/kb-app-0.1.0-SNAPSHOT.jar`、`kb-gateway/target/kb-gateway-0.1.0-SNAPSHOT.jar`（及 common jar）。该命令明确跳过测试，仅作为打包证据；不能替代上一条动态测试。
- 存在既有编译 varargs/deprecation/unchecked、Commons Logging 与 ByteBuddy 动态 agent 警告，没有测试失败，不把警告修复混入本变更。
- 代表性实际方法：`KnowledgeGapStateTest.completeTransitionMatrixHasOnlyExplicitLegalActions`、`KnowledgeGapCreateTest.quotaUsesCommandThenActorLockAndRejectsAtOneHundred`、`KnowledgeGapAccessTest.revocationAndReassignmentUseNewRequestWithoutHistoricalAccess`、`KnowledgeGapReceiptTest.reassignedOrReturnedHandlerCannotUseOldSuccessfulReceipt`、`KnowledgeGapTransactionTest.everyFailureRollsBackAllSourceRecordsAndAllowsSameRequestRetry` / `eachSerializationOrderHasOnlyOneLegalWinner`、`KnowledgeGapDocumentSolutionTest.confirmingAfterRevocationFailsButExplicitRejectionRemainsAvailable`、`KnowledgeGapQueryTest.actualSqlFiltersCurrentScopeBeforeFixedOrderAndLimit`、`KnowledgeGapLoggingTest.failedGapSpanKeepsOnlySafeRouteAndExceptionType`。
- 通知及兼容实际方法：`GapNotificationDerivationTest.expiredExecutorCannotWriteAfterFreshClaimAndPartialProgressIsPreserved`、`GapNotificationVisibilityTest.sparseMixedPageAndUnreadUseTheSameCurrentlyVisibleUnion`、`NotificationContractTest.defaultContractKeepsLegacyTypesCountsAndOriginalCursorDigest` / `declaredListAndCountBothReturnSameMixedContractAndExactMinimalTarget`、`GapNotificationTargetTest.hiddenGapNotificationCanBeMarkedReadIdempotentlyWithoutTargetQualificationOrContract`、`NotificationMappingTest.listAndCountBindTheSameOwnerScopeAndVisibilityParameters`。
- 网关实际方法：`KnowledgeGapRouteTest.rootsSubpathsAndUnsupportedMethodsReachOnlyAppInBothProfiles`、gateway `KnowledgeGapResponsePolicyTest.legalCapabilityPassesThroughSanitizationWhileInvalidValuesNeverReachDownstream`、`SaTokenGatewayConfigTest.gapAuthenticationFailuresUseJson401AndNoStoreWithoutRedis`；生产配置/源码方法见 8.3 与七份规格映射。
- 全部为领域/服务/事务代理/内存仓储、无连接 MyBatis/SQL 声明、MockMvc/内存交换、配置及编译后模块边界检查。未启动应用或连接数据库/Redis/对象存储/模型服务，不执行迁移；真实约束、锁/回滚、SQL 计划和部署行为未验证，未作为隐藏完成条件。任务 9.1 完成，进度 45/47。

## 2026-11-14：任务 9.2 最终前端离线回归及包体隔离

工作目录 `kb-web`，实际执行：

```powershell
npm run typecheck
npm test -- --run
npm run build
npm run bundle:test
npm run bundle:report -- --check-isolation --out .bundle-analysis/knowledge-gap-final.json
```

- `typecheck` 通过；首轮完整 Vitest 为 **113 test files / 1290 tests passed**（122.22 秒），0 failed/skipped。此前全量命令被工具 120 秒时限 SIGTERM（exit 143），不记为通过；以 600 秒时限完整重跑后通过，不改测试、门禁或运行范围。
- Vite 生产构建通过，2145 modules；10 项包体脚本单测全通过（0 fail/cancelled/skipped/todo）；最终实际模块图隔离检查通过。它晚于管理/通知实现，不使用 6.7 中间报告替代最终结果。
- 最终报告 `kb-web/.bundle-analysis/knowledge-gap-final.json`（构建产物，未纳入源码提交）：入口/登录累计 gzip **127843 bytes**，文档列表 **233710 bytes**；问题 List/New/Detail/Feedback/Queue/Reception 分别 **172958 / 178172 / 158297 / 184207 / 173036 / 158366 bytes**。仅极小路由助手可进入入口，问题业务实现不进入入口/登录/文档列表；普通和受理页面外壳没有互相内联。
- 回归包含 `knowledgeGaps/{api,commands,routes,feedback,pages,lifecycle,recovery}.test.ts`、三个 `admin/knowledge-gaps-*.test.ts`、`notifications/{api,page,unread,gap-target}.test.ts`，以及相邻搜索、助手、文档、审核、身份管理、空间和懒加载/恢复测试。
- 证据为 jsdom、内存 Session/transport、受控 Promise/虚拟时间及生产模块图，不是浏览器端到端、服务联调或实际部署验证。未新增/执行 README、Markdown、Javadoc 或 OpenAPI 描述断言。任务 9.2 完成，进度 46/47。

### 9.3 映射复核时补充的最终前端证据

- 为“既有通知混合显示”补充 `notifications/page.test.ts` 的直接 DOM 用例：同页审核/评论/问题三类型分别渲染固定标签与精确目标，打开原版本、评论和 gapId，只有明确点击才标记该通知已读，没有问题 POST 或自动确认。不改变生产逻辑。
- 定向命令：`npm test -- --run src/notifications/page.test.ts src/notifications/api.test.ts src/notifications/gap-target.test.ts`；结果 3 文件、18 tests 全通过；`npm run typecheck` 再次通过。
- 完整重跑 `npm test -- --run`（600 秒工具时限）：最终 **113 test files / 1291 tests passed**，119.47 秒，0 failed/skipped。这个结果取代上一轮 1290 项作为最终前端单测计数，不能把两轮相加。

## 2026-11-14：任务 9.3 逐场景映射与严格校验

- 已交付 `traceability.md`：七份增量规格全部 **44 项需求 / 104 个场景**映射到实际代码、离线单测方法/前端用例及静态证据，明确真实数据库并发、容量、约束、锁/回滚、旧二进制启动及部署不由替身证明。
- 复核 proposal/design/tasks 的阶段表述，标明用户后续已授权实施，不再把最初“仅规划”的历史限制误作当前未编码状态；不改变七份规格行为、47 项任务范围或完成标准。README/API/通知/运维状态同步为实现及离线验证完成、未部署。
- 补混合通知测试后再次执行 `npm run build`：2145 modules、构建通过；再次执行 `npm run bundle:report -- --check-isolation --out .bundle-analysis/knowledge-gap-final.json`：通过，累计 gzip 数字与 9.2 最终报告相同。
- 命令：`openspec validate knowledge-gap-feedback-loop --strict`；结果：`Change 'knowledge-gap-feedback-loop' is valid`。`git -c core.safecrlf=false diff --check` 通过。OpenSpec 结构校验和工作区空白检查不被当作业务动态测试或文档内容断言。
- 未启动应用、浏览器、数据库、Redis、容器或外部服务，未执行迁移、联调、部署、主规格同步、归档、代码提交或推送。原 README/路线图未提交成果保留；不恢复外部依赖或文档内容测试任务。任务 9.3 完成，进度 **47/47**。

## 2026-11-14：独立核验及索引修正

在实施结束后，按用户核验请求重新对照 proposal、design、tasks、七份增量规格与实际代码/测试，不把 `all_done` 或证据映射本身当作通过证明。44 项需求均找到实现证据，104 个场景均有离线代码/测试依据；已执行范围未发现 CRITICAL 或 WARNING。唯一 SUGGESTION 为 traceability.md 的 SQL 片段名误写，随后按用户请求改为实际的 `notificationWorkCurrent`，未修改业务代码。

### 核验阶段实际补跑

仓库根执行：

```powershell
$tests = 'KnowledgeGapStateTest,KnowledgeGapAccessTest,KnowledgeGapAssigneeTest,KnowledgeGapCreateTest,KnowledgeGapReceiptTest,KnowledgeGapAssignmentTest,KnowledgeGapResolutionTest,KnowledgeGapClosureTest,KnowledgeGapTransactionTest,KnowledgeGapQueryTest,KnowledgeGapDocumentAccessTest,KnowledgeGapDocumentSolutionTest,KnowledgeGapReferenceTest,KnowledgeGapInputTest,KnowledgeGapControllerTest,KnowledgeGapEventTest,KnowledgeGapLoggingTest,GapNotificationDerivationTest,GapNotificationVisibilityTest,GapNotificationTargetTest,GapNotificationMetricsTest,NotificationContractTest,KnowledgeGapResponsePolicyTest,KnowledgeGapRouteTest,ModuleBoundaryTest'
mvn -o -pl kb-app,kb-gateway -am "-Dtest=$tests" '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -o -pl kb-app,kb-gateway -am '-Dtest=ProductionSettingsTest,ProductionSettingsCollectorTest,JavaSourceConventionTest,KnowledgeGapSchemaTest,KnowledgeGapNotificationSchemaTest,KnowledgeGapMappingTest,NotificationMappingTest,NotificationResponsePolicyTest,TelemetrySpanFilterTest,HttpObservationPrivacyTest,SaTokenConfigTest,SaTokenGatewayConfigTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

两组白名单互不重复；同名类按模块分别计数。实际 Surefire XML 的更新时间为 15:20–15:25，汇总为：

| 模块 | 类数 | tests | failures | errors | skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| kb-common | 3 | 91 | 0 | 0 | 0 |
| kb-app | 31 | 313 | 0 | 0 | 0 |
| kb-gateway | 5 | 14 | 0 | 0 | 0 |
| 合计 | 39 | 418 | 0 | 0 | 0 |

`kb-web` 目录执行：

```powershell
npm test -- --run src/knowledgeGaps/api.test.ts src/knowledgeGaps/commands.test.ts src/knowledgeGaps/routes.test.ts src/knowledgeGaps/feedback.test.ts src/knowledgeGaps/pages.test.ts src/knowledgeGaps/lifecycle.test.ts src/knowledgeGaps/recovery.test.ts src/admin/knowledge-gaps-access.test.ts src/admin/knowledge-gaps-pages.test.ts src/admin/knowledge-gaps-commands.test.ts src/notifications/api.test.ts src/notifications/page.test.ts src/notifications/unread.test.ts src/notifications/gap-target.test.ts
npm run typecheck
```

结果为 14 test files、124 tests 全通过，0 failed/skipped；类型检查通过。核验阶段还重跑变更严格校验及工作区空白检查，均通过。418/124 是独立核验的定向补跑，不与实施阶段 640/1291 累加，也不称为重新执行了全量前端回归或构建。

真实数据库约束、触发器、锁、回滚、并发、执行计划、迁移、旧二进制启动、前后端联调、真实浏览器和部署继续为 **Not verified**。说明文档断言按约定不执行；上述边界不新增为本变更的隐藏完成条件。

## 2026-11-14：主规格同步与归档

用户在核验及索引修正后另行要求“同步主规格，然后归档并更新相关文档”，并确认“同步后归档”。归档按自身流程检查产物和任务完成情况，不把此前核验中的未验证边界改写为已通过。

- `openspec list --json` 确认根为当前仓库、任务 47/47；`openspec status --change knowledge-gap-feedback-loop --json` 的 proposal/specs/design/tasks 均为 done，schema 为 spec-driven，无未完成任务或产物。
- 已读取本次 archive 输入及 specs 指令；无额外项目规则或归档指导。同步以 status 返回的七个增量路径为唯一来源，逐份合并，保留既有标题、Purpose、未涉及的需求及场景。
- 新建 `admin-web-knowledge-gaps`、`knowledge-gaps`、`user-web-knowledge-gaps` 三份主规格；更新 `admin-web-access`、`functional-authorization`、`user-notifications`、`user-web-notifications` 四份既有主规格。共新增 39 项、修改 5 项需求，无删除或重命名。
- 同步后逐项复核 44 项增量需求及其场景均已进入相应主规格；主规格统一使用 `## Requirements`，没有复制增量操作章节。再次应用本次增量无需进一步内容变更。
- `openspec validate --specs --strict`：62 passed、1 failed。唯一原因是 `admin-web-access` 原有“管理菜单与页面必须按独立读取资格开放”需求正文超过 500 字；该段本次未改动，未为消除既有警告扩写或重构无关规格。
- 按同步流程执行 `openspec validate --specs`：63 passed、0 failed，保留同一条既有 WARNING；移动前执行 `openspec validate knowledge-gap-feedback-loop --strict` 通过。因此主规格常规校验通过，但不能记为全库严格校验通过。
- 主规格同步及复核完成后，确认归档目标不存在，再使用 PowerShell `Move-Item` 将完整变更目录移动至 `openspec/changes/archive/2026-11-14-knowledge-gap-feedback-loop/`，保留 `.openspec.yaml`、增量规格、任务、设计和证据。随后 `openspec list --json` 返回 `changes: []`。
- 更新根 README、前端 README、路线图 F-01、问题 API/运维/协调发布、站内通知、前端静态托管及代码规范说明的交付状态和证据入口；修正归档提案到路线图的相对链接，保留原 README/路线图的其他未提交成果。
- 同步和文档更新后执行 `git -c core.safecrlf=false diff --check` 通过；人工检视相关状态与相对链接，未新增文档断言测试。活动变更列表为空，归档保留原 schema 和 47/47 任务。
- 本轮同步归档仅改动规格及说明记录，不重新运行或增加文档内容/存在性断言，也未改动业务代码。未执行迁移、联调、浏览器或部署；未提交或推送。归档不是上线验收或真实数据库验证证明。
