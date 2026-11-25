# 实现核验报告

变更：`permission-explanation-and-authorization-preview`。核验日期：2026-11-25。

**最新核验：0 CRITICAL、0 WARNING、0 SUGGESTION。** 原 W1–W4 及第二轮 R1 均已关闭，最新修复和回归见 [第三轮报告](verification-report-3.md)。[第二轮报告](verification-report-2.md) 和 [首次修复记录](remediation.md) 保留阶段证据；下文保留首次核验时的发现、行号和失败结果，作为历史证据。

本报告是实施完成后的独立核验，区别于 [verification.md](verification.md) 的实施记录。核对了 proposal、design、tasks、四份增量规格、实现及相关测试；重新执行了既定离线白名单，并对疑点进行了最小复现。本轮不修改实现、任务勾选或主规格，不执行归档。

## 首次核验结论（修复前）

发现 **0 项 CRITICAL、4 项 WARNING、0 项 SUGGESTION**。34 项任务均已勾选，28 项 ADDED 要求均能定位实现；没有发现整项需求缺失。存在两处已复现的规格偏差：前端接受部分矛盾成功响应，以及旧后端返回 404 时诊断不可用提示被资格重核清除。另有离线场景覆盖不足，以及一项已确认属于基线的架构测试失败。

| 维度 | 核验结果 |
| --- | --- |
| 完整性 | Task Completion：34/34；Spec Coverage：28/28 找到实现。schema 为 spec-driven，任务跟踪可读，无 REMOVED／RENAMED 要求。勾选及实现存在不等于正确性通过。 |
| 正确性 | Requirement Implementation Mapping：28/28 已审阅，W1、W2 存在规格偏差。Scenario Coverage：核对全部 95 个场景的处理路径与证据；**部分未验证**，现有离线用例不足以覆盖 W3 列出的情形，不能报告 95/95 场景通过。 |
| 一致性 | Design Adherence：D1–D10 均已审阅，D7、D9 有 W1、W2 偏差，D10 的部分离线证据不足见 W3。Code Pattern Consistency：新文件按 controller／service／mapper／model 及 config 分层，注入和模型格式基本一致；扩大架构回归保留 W4 的基线失败。 |

已执行的检查未发现 CRITICAL；尚有 4 项 WARNING。由于 Scenario Coverage 部分未验证，本报告不作“全部核验通过”或“归档就绪”结论。真实 SQL、数据库隔离、迁移、真实浏览器及联调属于事先明确排除的层次，不将它们转成新增任务或归档前置条件。

## WARNING

### W1. 严格解码仍接受矛盾的来源与条目变化

- 位置：`kb-web/src/authorization/api.ts:116`，尤其 `:142`–`:151`；来源解析在 `:43`–`:52`。
- 对照：`specs/authorization-diagnostics/spec.md:245` 的完整契约要求，以及 design D7。任务 5.1 的“全部损坏响应整体拒绝”尚不能成立。
- 原因：目前只要求旧来源在未标为 REMOVED 时保持相同，以及新出现的来源必须对应 ADDED。缺少反向约束：ADDED 不能已存在于 before；能够从目标用户 ID 确认必然命中的新增 USER 条目必须出现在 after；USER 来源必须属于本次目标。
- 本轮直接调用生产 `decodeResult`，以下三个矛盾对象全部被接受：

| 最小输入变化 | 实际结果 | 应有行为 |
| --- | --- | --- |
| before 和 after 都保留 `SPACE:1:8:view` 的 EXISTING 来源，changes 却将该授予标成 ADDED | ACCEPTED | 整份拒绝：已有行的再次授予应为 UNCHANGED |
| 目标用户 8 为 PASSED，给 USER 8 新增 view，changes=ADDED，但 after 仍无任何来源并显示 NOT_MATCHED | ACCEPTED | 整份拒绝：该新增行必然命中当前用户 |
| user.userId=8，解释来源却是 USER 9 的 `SPACE:1:9:view`，权限项引用该来源 | ACCEPTED | 整份拒绝：另一用户的直接授权不可能成为当前目标的命中来源 |

这些复现使用本地 TypeScript 转译并在 Node 进程中调用实际模块，不访问网络。结果可造成“拟新增”与“结果不变”同时显示，或把其他用户的直接授权呈现成目标用户的来源。它们不证明服务端已经产生这类响应，也不表示正式写入能够绕过鉴权；问题是约定的损坏响应拒绝机制不完整。

建议：把目标 userId 传入来源校验；根据来源键、action、outcome 和 before／after 集合双向核验。对目标 USER 的必然命中关系作确定性校验；对于未披露的角色／部门关系，保持不能推断的边界。增加上述拒绝用例，并使用真正含有新增、保留、移除来源的合法预演作为通过对照。

### W2. 404 后的资格重核会吞掉“诊断不可用”提示

- 位置：`kb-web/src/authorization/state.ts:16`、`DiagnosticPanel.vue:21`–`:22`、`DiagnosticHost.vue:14`–`:16`；实际重核状态切换位于 `kb-web/src/stores/adminAccess.ts:29`–`:36`。
- 对照：`specs/authorization-diagnostics/spec.md:253`“未升级后端”，`specs/admin-web-spaces/spec.md:77`“预演返回不支持或故障”，以及 design D9。
- 触发：操作者及资源资格有效，但新后端端点未部署，诊断候选或解释返回 404。
- 实际链路：读状态先写入 error，再调用 `access.check(true)`；真实权限 store 同步进入 loading，Host 的 eligible 变为 false 并执行 close。Panel 被卸载，其局部错误一起丢失。重核仍有资格后只剩“权限诊断”入口，没有不可用提示，再次点击会重复该过程。
- 复现：使用真实 `AdminAccessProvider`／store、`DiagnosticHost`、内存 Session／传输和 jsdom。权限接口两次都成功、空间管理资格不变、新候选端点返回 404；确认执行一次诊断请求和一次资格重核后，期望保留错误提示的断言失败，实际 alert 为 null。未启动应用或监听服务。
- 现有证据不足：`kb-web/src/authorization/panel.test.ts:20` 将 check 替换为空的 `vi.fn()`，`:64` 的用例只确认调用一次，没有经历实际 loading → ready → 组件卸载过程。既有 `space-pages.test.ts` 的 404／405 用例针对原写接口，不能代替新诊断入口的此场景。

建议：将固定的诊断失败类别保存在能跨资格重核保留的宿主状态中，私有目标和来源仍即时清除。资源重核成功后显示“诊断不可用”；管理资格失效时沿用资源清理流程。用真实 store 与内存传输覆盖新端点 404、资格恢复和不自动重发，并验证原授权提交仍可使用。

### W3. 部分“场景证据”仅覆盖邻近分支，未覆盖所声明的场景

这是当前允许的离线验证范围内的缺口，不要求恢复数据库或联调测试。

| 场景／约束 | 当前证据的具体缺口 | 建议补充的离线验证 |
| --- | --- | --- |
| 全局管理不隐含阅读 | `DiagnosticEvaluationTest.java:61` 的方法名提到 global manage，但使用 `:29` 中 globalManage=false 的固定 target，正文只测 download 缺 view。TargetLoader 测试只证明布尔事实被加载。 | 传入 globalManage=true、无 view/download 的目标，校验固定全局来源只关联 manage，阅读和下载保持拒绝。 |
| 多个角色命中同一资源权限 | `DiagnosticTargetLoaderTest.java:54` 验证两个角色键；`DiagnosticBoundaryTest.java:64` 只映射一条 USER 来源。没有针对两条 ROLE 来源保留、引用及撤销其中一条的诊断投影断言。 | 用 Mapper 返回值替身提供两个角色来源，校验完整投影及撤销后的另一来源。无需运行 SQL。 |
| 归档阻断非 manage、保留 manage | `DiagnosticAccessTest.java:50` 仅证明归档资源允许进入。`DiagnosticEvaluationTest` 没有 archived=true 的求值输入，前端 layer 测试也未校验该组合。 | 同时给出 manage 与 view 的归档资源，校验 manage 通用条件 PASSED，其余五项带 SPACE_ARCHIVED，文档访问层拒绝。 |
| 不存在用户及游标混用 | `DiagnosticServiceTest.java:106` 实际只改变 authEpoch；跨资源、q 和操作者的游标拒绝没有对应断言。实现已把这些字段加入 filters。 | 使用同一已生成游标分别改变 resource、q、actor，确认 400 且不读取候选；保留原条件可继续分页的通过对照。 |
| 指定同空间的一份文档 | `DiagnosticAccessTest.java:66` 覆盖 DOCUMENT 路径成功及 SPACE 附选失败；没有 SPACE + 合法同空间文档成功的调用参数和响应范围断言。UI 文档选择也没有内存清单分页及绑定用例。 | 用单个合法管理清单替身覆盖空间附选成功、两空间均可管理但归属不符仍拒绝；组件侧验证只请求 ID 清单及所选 documentId。 |
| 失去诊断资格／资源管理资格后的恢复 | `panel.test.ts:64` 使用空 check；原页面回归覆盖既有授权和删除流程，没有直接覆盖新诊断 403／404 的真实 store 重核。 | 分别覆盖仅撤诊断权限、资源重核失败、仍可管理但端点不存在；同时保留未知写入绑定并丢弃迟到诊断。W2 已证明这种组合测试有实际价值。 |
| 合法前后差异与损坏响应 | `kb-web/src/authorization/fixtures.ts:6`–`:12` 对任意意图都构造空来源、before=after、changes=UNCHANGED；对有效目标本人授予，这本身不符合已有行应当出现在 before 的语义。`api.test.ts:22` 只验证条目集合，未检验来源与 outcome 双向关系。 | 建立合法新增、重复授予、实际撤销和缺失撤销的响应夹具，再对这些合法对象施加 W1 的损坏变化。 |

此外，当前 UI 对来源变化而访问结论不变的场景主要依赖并排结果；现有 `layer.test.ts:7` 只检查单层身份／密级渲染，不能作为完整前后差异交互的证明。补充多来源预演组件用例时应同时核对“拟移除／保留来源”和访问结论。

`traceability.md` 可继续作为导航，但不能把列出相关类视作上述场景已经受测。建议后续按具体测试方法标明“直接覆盖／组件组合覆盖／人工审阅／证据缺口”。本轮不修改原任务完成标记。

### W4. 扩大后端回归仍有一项基线架构失败

- 位置：`kb-app/src/test/java/com/zhishiku/kb/app/ModuleBoundaryTest.java:239`。
- 本轮复现：`layersMustNotBeInverted` 失败，25 条依赖来自 `SearchAssistanceService`／`SearchService` 对 `search.web.AssistedSearchRequest`／`SearchRequest` 的依赖。
- 基线归属：对上述四个生产文件及该测试执行 `git diff 1736531 --name-only -- <五个路径>`，输出为空；本轮失败列表未出现新增诊断包。
- 影响：不能声称后端扩大回归全绿；没有证据将其归因于本变更。
- 建议：单独处理搜索请求模型与服务分层，或在评审中明确接受该既有失败。不要删除架构规则、加入忽略项或把失败计为通过。

## 需求与场景核对

下面覆盖全部 28 项要求及其 95 个场景。每行场景范围与 [traceability.md](traceability.md) 的同名条目对应；“实现证据”是本轮实际审阅的位置。测试证据只证明其组件范围，不把 Mapper 替身视作 SQL 求值，也不把后台数据状态直接等同于浏览器交互。

后端路径简称：`service` = `kb-app/src/main/java/com/zhishiku/kb/app/auth/service/diagnostics/`；`xml` = `kb-app/src/main/resources/mapper/`。后端测试简称对应 `kb-app/src/test/java/com/zhishiku/kb/app/auth/service/diagnostics/Diagnostic*Test.java`。前端路径简称 `web` = `kb-web/src/authorization/`。

### authorization-diagnostics：14 项／49 场景

| 要求（场景数） | 实现证据 | 场景证据与判断 |
| --- | --- | --- |
| 先操作者再目标（3） | `service/DiagnosticAccess.java:42`；`DiagnosticService.java:46` | Access 的准入／搬迁用例、Service 的拒绝前不读正文和目标；无目标身份替换操作者路径。 |
| 最小资源上下文用户选择（4） | `DiagnosticService.java:81`；`DiagnosticTargetLoader.java:51` | Target 覆盖停用、生命周期、主部门、改密、缺失与故障；Service 覆盖最小页和 epoch 游标；panel 覆盖 USER 行。跨资源／筛选负例不足见 W3。 |
| 单用户与明确范围（3） | `DiagnosticInput.java:78`；`DiagnosticAccess.java:53` | Input 拒绝文档路径另带 documentId；Service 仅空间不读取版本；Access 拒绝搬迁。附选成功路径缺口见 W3。 |
| 全部命中来源（4） | `xml/DiagnosticMapper.xml:18`、`:53`；`DiagnosticEvaluator.java:47` | 直接／部门叠加、文档例外用例存在；来源 SQL 的匹配和并集人工审阅。多角色结果与全局 manage 投影缺口见 W3。 |
| 授权与门槛分层（4） | `DiagnosticEvaluator.java:62`、`:80`；`web/DiagnosticLayer.vue:11` | Evaluation 覆盖密级与 download 缺 view；界面按独立层展示且无 canApprove。归档求值缺口见 W3；原审核独立回归通过。 |
| 身份／密级受控投影（3） | `DiagnosticTargetLoader.java:57`；`DiagnosticEvaluator.java:26`；`DiagnosticViews.java:17` | Target 的不同身份阻断与依赖失败用例、Evaluation 未评估投影、layer 通用提示；操作人额外目录权限不会选择另一个公开模型，依据代码审阅。 |
| 当前发布范围（4） | `xml/DiagnosticMapper.xml:68`；`xml/AuthorizationRules.xml:31` | Evaluation 覆盖无指针／非法指针布尔事实与密级；VersionAccessRules 覆盖发布／兼容规则；V1 指针而 V2 待审由 SQL 结构人工核对。没有原件客户端依赖。真实 SQL 不在范围内。 |
| 单种精确意图（4） | `DiagnosticInput.java:104`；`DiagnosticAccess.java:68` | Input 覆盖 50/51、300/301、重复与不允许的文档权限；Access 覆盖额外 manage 门禁；api 验证完整条目集合。 |
| 并集、条目与能力变化（5） | `DiagnosticOverlay.java:34` | Evaluation 覆盖保留部门、保留空间、低密级新增、无变化及角色引用；Service 覆盖无关角色不改变目标结果。完整客户端关系仍有 W1。 |
| 同次一致读取（3） | `DiagnosticDatabase.java:28`；`DiagnosticService.java:52` | Boundary 捕获 REQUIRES_NEW／RR／read-only；Service 在评估后改变模拟行集，当前请求保留捕获行集，下一请求读取新行集；Permission 验证下次资格撤销。真实事务隔离未验证。 |
| 只读与正式写独立（3） | `DiagnosticOverlay.java:34`；`DiagnosticDatabase.java:28`；`AclPanelContent.vue:150` | 依赖及 SQL 人工审阅无授权写服务／DML；Service 核对只读调用；panel 覆盖 503 后手动提交及未知写入，原页面用例覆盖未预演提交。 |
| 最小响应／来源披露（3） | `DiagnosticViews.java:17`；`DiagnosticService.java:54` | 固定模型人工审阅；Target 不回传身份全量；Evaluation 移除来源不保留到 after；前端对另一用户直接来源的拒绝不足见 W1。 |
| 有界与故障（3） | `DiagnosticLimits.java:8`；`DiagnosticStatementBudget.java:20`；`DiagnosticInput.java:60` | Input、Target、Service、Boundary 覆盖体积、主体／来源上限、输出预检、受控超时／中断及输入注入。连接实际取消行为不在范围内。 |
| 私有通信与严格解码（3） | `DiagnosticResponseFilter.java`；`web/api.ts:116`、`:181`；app/gateway 遥测配置 | no-store、身份头脱敏、错误最小化组件测试通过；目标／资源错配已有拒绝断言；W1 的矛盾对象漏检，W2 的旧端点不可用提示丢失。固定遥测投影人工审阅。 |

### functional-authorization：3 项／12 场景

| 要求（场景数） | 实现证据 | 场景证据与判断 |
| --- | --- | --- |
| 独立精确资格（4） | `auth/model/Permission.java:39`；`auth/service/AdminQueryAccess.java:25` | Permission 覆盖普通精确 code、角色名／通配符／全局管理不隐含、并集撤销；前端 access 用例证明只有新权限不能开放管理范围。 |
| 真实管理范围（4） | `service/DiagnosticAccess.java:42`、`:68` | Access 覆盖无管理范围、自授无效、manage 额外资格；Evaluation 覆盖自撤 manage 只改变假设行集。 |
| 一次性初始化（4） | `db/migration/V43__authorization_diagnostics_permission.sql:1`；`auth/service/AdminBootstrapRunner.java:123` | 迁移条件和运维说明人工审阅；TermPermissionTest 以内存集合验证新建角色完整目录、撤销后重构不补回；目录未知 code 拒绝有单测。未执行迁移或旧实例启动。 |

### admin-web-spaces：6 项／18 场景

| 要求（场景数） | 实现证据 | 场景证据与判断 |
| --- | --- | --- |
| 功能＋资源双重资格（3） | `web/DiagnosticHost.vue:14`；`AclPanelContent.vue:65`、`:146` | panel 覆盖无资格不加载、USER 行入口；父详情 manageable 与 ACL 成功状态负责资源边界，代码路径已核对。 |
| 来源与条件展示（3） | `web/DiagnosticLayer.vue:11`；`DiagnosticPanel.vue:30`、`:50` | 文档选择调用 ID 管理清单，不调用标题；展示层缺多来源／归档和选择清单的直接组件用例，见 W3。 |
| 完整草稿绑定（3） | `AclPanelContent.vue:68`；`web/api.ts:156`；`web/state.ts:5` | api 覆盖笛卡尔积／显式撤销；state 覆盖换目标、草稿及迟到；panel 覆盖文档草稿改变。含 manage 的额外控制已审阅。 |
| 前后差异与保留来源（3） | `web/DiagnosticPanel.vue:58`；`DiagnosticLayer.vue:9` | 后端 Overlay／Evaluator 有保留与阻断用例；前端通用身份和密级渲染有直接断言。完整真实差异夹具不足见 W3。 |
| 与正式写入独立（3） | `AclPanelContent.vue:150`；`web/DiagnosticPanel.vue:19` | 原页面测试覆盖未预演提交；panel 覆盖预演 503、不自动提交及未知写入重试绑定；旧端点 404 提示有 W2。 |
| 私有状态失效（3） | `web/state.ts:8`；`DiagnosticHost.vue:16`；`SessionHost.vue:21` | state／panel 覆盖取消、隐藏、关闭及迟到；页面恢复由 SessionHost 作废 epoch 后重查身份，未发现只靠缓存恢复。新诊断真实 403／404 重核组合覆盖不足见 W3。 |

### admin-web-documents：5 项／16 场景

| 要求（场景数） | 实现证据 | 场景证据与判断 |
| --- | --- | --- |
| 功能及归属资格（3） | `admin/DocumentPanel.vue:30`、`:91`；`web/DiagnosticHost.vue:14` | panel 覆盖管理但无正文、密级资格不能单独开放及 503 独立失败；原页面回归覆盖普通详情 404 仍保留管理。 |
| 授权与当前发布访问（4） | `web/DiagnosticLayer.vue:14`；`web/api.ts:75` | 无当前版本有 panel 断言；download 缺 view 有后端组件断言；review／manage 来源类型与无版本入口通过代码审阅。V1/V2 实际选择仍限 SQL 人工审阅。 |
| 单项例外及空间来源（3） | `AclPanelContent.vue:165`；`web/api.ts:157`；`service/DiagnosticOverlay.java:53` | Input 覆盖四个合法文档权限；Evaluation 覆盖撤例外保留空间和低密级新增；state／panel 覆盖草稿切换与迟到。归档及完整 UI 组合缺口见 W3。 |
| 父级写入协调（3） | `AclPanelContent.vue:66`；`admin/DocumentPanel.vue:26`、`:58` | panel 覆盖删除确认、密级未知与迟到解释；原页面回归覆盖无预演写入和搬迁，Access 覆盖搬迁后拒绝旧范围。完整诊断搬迁交互目前主要由这些组件证据与代码审阅组合支持。 |
| 身份及资源生命周期（3） | `web/DiagnosticHost.vue:15`；`web/state.ts:8`；`web/api.ts:116` | 关闭、epoch 变化、迟到和目标／资源损坏有组件证据；重开不带旧来源。完整契约仍有 W1；真实资格恢复的端点失败处理有 W2。 |

## 设计一致性

| 决策 | 核验结果 |
| --- | --- |
| D1 组合门禁 | 真实操作者先准入，目标仅为分析事实；拟议 manage 不参与准入，额外授出资格位于管理检查之后。 |
| D2 最小目标选择 | POST 名称搜索、最小候选、USER ACL 入口、ID 级观察文档；游标绑定包含 actor、epoch、资源、q、limit。负向组合测试不足见 W3。 |
| D3 身份类型隔离 | Target 不可铸造 PrincipalHandle；共用主部门有效性规则；阻断后不读角色／祖先链／版本。 |
| D4 共用规则 | PermissionMapper、DocumentMapper 与 DiagnosticMapper 引用共享主体匹配、授权、归档和发布片段；未发现请求字段进入 SQL 模板选择。SQL 实际求值未执行。 |
| D5 一致只读快照 | 独立 REQUIRES_NEW、REPEATABLE_READ、read-only 包住鉴权、事实、前后计算和完整编码预检；evaluatedAt 来自事务时间。事务真实行为不作验证声明。 |
| D6 单种精确意图 | 四类封闭意图、共用原写纯校验、角色授予引用检查、撤销残留不要求角色存在；不展开成员。 |
| D7 最小固定投影 | 服务端模型符合三层投影；前端严格解码的双向一致性不足见 W1。 |
| D8 接口与限界 | 六路径、固定错误、no-store、字节／主体／来源／响应／时间限额均有实现与组件证据；Swagger 的公开嵌套模型已人工审阅。 |
| D9 可选 UI 与失效 | 写入绑定和诊断 generation 分离，关闭／隐藏／写入开始清理结果；404 资格重核吞提示见 W2。 |
| D10 离线边界 | 本轮遵守既定边界；未启用外部服务或文档内容断言。相关测试实际通过，但部分声明场景未受测，见 W3。 |

## 本轮执行证据

| 检查 | 本轮结果 |
| --- | --- |
| Java 明确白名单 | 22 类／112 项：111 通过、1 失败、0 errors、0 skipped。kb-app 106 项含 1 失败；kb-common 1 项通过；kb-gateway 5 项通过。失败见 W4。 |
| 其中诊断新增后端用例 | 7 类／30 项通过；已包含在上一行，不重复计数。 |
| 前端既定白名单 | 8 文件／114 项通过。 |
| 解码器定向探测 | 3 个应拒绝的矛盾对象均 ACCEPTED，确认 W1；属于额外探测，不计入 114 项通过数。 |
| 404 真实 store 定向复现 | 1 项规格断言失败，alert 为 null，确认 W2；临时测试文件已删除，不混入正式白名单统计。 |
| vue-tsc 类型检查 | 通过。 |
| OpenSpec strict | 通过。 |
| 已跟踪差异空白检查 | `git diff --check` 通过，仅 Git 的既有 LF/CRLF 提示。 |

本轮重跑命令：

```powershell
mvn -o -fae -pl kb-app,kb-gateway -am test '-Dtest=DiagnosticPermissionTest,DiagnosticAccessTest,DiagnosticTargetLoaderTest,DiagnosticInputTest,DiagnosticEvaluationTest,DiagnosticBoundaryTest,DiagnosticServiceTest,PermissionCatalogTest,TermPermissionTest,PermissionServiceTest,IdentityServiceTest,DocumentCapabilitiesServiceTest,DocumentCitationServiceTest,DocumentReviewDecisionTest,VersionAccessPurposeTest,VersionAccessRulesTest,DocumentCitationMapperTest,DocumentVersionProjectionTest,NotificationMappingTest,ModuleBoundaryTest,SearchRouteTest,SearchTermGatewayProtectionTest' '-Dsurefire.failIfNoSpecifiedTests=false'
openspec validate permission-explanation-and-authorization-preview --strict
git diff --check
```

在 `kb-web` 执行：

```powershell
node node_modules/vitest/vitest.mjs run src/authorization/access.test.ts src/authorization/api.test.ts src/authorization/state.test.ts src/authorization/panel.test.ts src/authorization/layer.test.ts src/admin/space-pages.test.ts src/admin/spaceApi.test.ts src/admin/MultiPrincipalPicker.test.ts
node node_modules/vue-tsc/bin/vue-tsc.js --noEmit
```

既有 Vite 生产构建、包体工具 13 项及隔离结果沿用 [verification.md](verification.md) 的实施证据，本轮未重复执行，不计为本轮新增检查。

## 验证边界

实际执行限独立单元／组件、内存传输、Mock／Stub、受控事务／时钟、Mapper 装配／结果集替身及 jsdom。SQL、迁移、发布说明和遥测配置为人工审阅；未编写文档存在性或内容断言测试。

真实 SQL 授权等价、数据库约束／锁／隔离、V43 执行、网络、Redis、对象存储、跨实例、真实浏览器及前后端联调均未验证。这些明确排除的层次不作为 W3，也不要求补成实施任务。W3 仅针对仍在约定范围内、可以用离线组件验证而尚缺有效证据的场景。
