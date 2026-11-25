# 第二轮实现核验报告

**后续状态：R1 已修复并复核关闭，最新结果为 0 CRITICAL、0 WARNING、0 SUGGESTION，见 [第三轮报告](verification-report-3.md)。** 以下保留第二轮的发现及失败证据。

变更：`permission-explanation-and-authorization-preview`。日期：2026-11-25。Schema：`spec-driven`。

本轮依据用户再次发起的 verify 请求，独立核对当前代码、39 项任务、28 项 ADDED 要求、95 个场景及 D1–D10。重新执行离线白名单、类型检查、静态构建和包体隔离，并用进程内组件复现疑点。`all_done` 与修复记录仅作为输入，不作为核验结论。

## 结论

**0 CRITICAL、1 WARNING、0 SUGGESTION。** 原 W1 的解码问题、W3 指出的主要离线覆盖缺口及 W4 搜索分层失败已得到修复证据。原 W2 在独立诊断入口已修复，但授权弹窗中的预演路径仍有同类问题，见本报告 R1。

| 维度 | 核验结果 |
| --- | --- |
| 完整性 | Task Completion：CLI 返回 39/39 完成，任务描述全部可读，无不可用跟踪文件；Spec Coverage：28/28 要求均定位到实现，无 REMOVED／RENAMED。未发现整项需求缺失。 |
| 正确性 | Requirement Implementation Mapping：28/28 已审阅；Scenario Coverage：已核对 95 个场景的处理路径与测试／人工审阅证据。R1 直接复现预演故障隔离偏差；不能宣称所有行为通过。其余未发现新的明确偏差。 |
| 一致性 | D1–D10 均已检查，D9 的预演失败恢复仍有 R1；代码分层、类型隔离、注入、公开模型及共享规则已审阅，原架构检查 13/13 通过。 |

本轮范围内的检查均已执行，没有因缺少规划产物或无法读取实现而跳过检查。95 个场景的“核对”包含组件证据组合及明确约定的人工审阅，不等于 95 个独立自动化用例，也不等于真实环境验收。

无 CRITICAL 阻断，可进入归档评审并保留此警告；建议先修复 R1 再归档。本轮不执行修复、修改任务勾选或归档。

## WARNING R1：预演返回 404 后授权弹窗关闭，提示和文档草稿丢失

### 触发和实际结果

操作者具备诊断及文档管理资格，在文档例外授权表单选择 USER 8、download；最小用户搜索成功，主动计算预演时 `/preview` 返回 404。随后功能权限及资源管理重核均成功，操作者仍能正式授权。

实际结果：

- 授权弹窗被关闭，页面没有显示“权限诊断暂不可用，请稍后重试”。
- 页面只剩普通“授予授权”入口，没有原“确认提交”按钮。
- 重新打开授权表单，原主体选择变为空，权限从 download 恢复为 view。
- 该问题发生在尚未正式提交的草稿阶段；本轮未发现未知正式写入绑定被清除，相关既有测试仍通过。

本轮使用真实 `AdminAccessProvider`／权限 store、`AclPanel`、Session、内存传输及 jsdom 直接复现；资源宿主采用与生产文档面板相同的“管理清单加载时卸载 ACL 面板”逻辑，没有启动应用、HTTP 监听或真实浏览器。

复现输出：

```text
preview recovery: { visibleNotice: false, confirmButton: false }
reopened draft: { principal: '', permission: 'view' }
AssertionError: expected '' to be '8'
```

临时定向用例 1 项失败，确认的是上述行为偏差；不混入正式前端白名单 129 项通过数。复现文件已删除，保留本报告中的步骤、输出和实现位置。

### 实现原因和位置

1. `kb-web/src/authorization/DiagnosticHost.vue:15` 将反馈键区分为 preview／explain，`:21` 的拒绝分支只把提示保存在当前预演对应的反馈中，随后 `:24` 调用权限重核并通知父级。
2. `kb-web/src/admin/AclPanelContent.vue:78` 同时刷新 ACL 与父资源。生产 `DocumentPanel.vue:32` 在管理清单加载时令 owner 为空，`:37` 触发清单刷新，`:91`–`:94` 的 ACL 子树因此卸载。空间详情 `SpaceDetail.vue:33`、`:39`–`:41` 也具有同类加载门禁。
3. 授权弹窗状态和文档草稿只保存在 `AclPanelContent.vue:48`、`:63` 的局部 ref 中。重挂载后 dialog=null、principal=null、documentPermission=view；`:104` 的重新打开行为也重置文档输入。
4. 预演 Host 仅存在于 `AclPanelContent.vue:150`、`:157`、`:165`、`:170` 的条件弹窗中。弹窗消失后，保留的 preview 提示没有可见宿主；外部 Host `:149` 使用 explain 对应的另一条反馈。重新打开预演时，`DiagnosticHost.vue:20` 又会主动清除该提示。

因此，虽然 W2 已让固定提示能够跨组件卸载留在内存中，但尚未保证弹窗场景下提示可见，也未保证原文档草稿可继续提交。

### 对照规格和测试缺口

- `specs/authorization-diagnostics/spec.md:209`：预演不可用后，用户仍可主动提交原授权草稿。
- `specs/authorization-diagnostics/spec.md:253`：未升级端点应仅使诊断提示不可用。
- `specs/admin-web-spaces/spec.md:77`：预演 404／405、503 或契约失败时，区块显示不可用，原写资格有效时仍可走原流程。
- `specs/admin-web-documents/spec.md:57`：文档预演不应成为原提交的新增障碍；design D9 `:150`–`:158` 约定其可选性、失败恢复及写入独立性。
- 当前 `kb-web/src/authorization/recheck.test.ts:63` 先打开弹窗外“权限诊断”，再模拟候选端点 404／405，之后才新建授权草稿；没有覆盖“已有授权草稿 → 弹窗内预演失败 → 父级重挂载”。`panel.test.ts:79` 覆盖的是 503，不触发本次 404 重核链路。

### 修复建议

将预演失败的固定通知放到重核后仍可见的资源级反馈位置；按现有身份／资源作用域保留合法授权草稿及可恢复的表单状态。真实资格或资源变化时仍清理诊断目标、来源和在途响应；不应通过跳过 404 资源重核来保住表单，也不能自动提交或修改草稿。

增加真实权限 store 和父资源门禁组合的离线回归：先选择 USER 8／download，再令 preview 返回 404，重核继续返回合法资格；断言提示可见、主体和权限保持、用户可主动提交原草稿且没有自动写入。并保留资源失权时清理、迟到诊断不能恢复来源、未知正式写入标识不变的对照用例。空间预演弹窗的反馈也应沿同一路径检查。

## 原问题复核

| 原问题 | 当前证据 | 本轮判断 |
| --- | --- | --- |
| W1 矛盾成功响应 | `kb-web/src/authorization/api.ts:75` 核对 USER 来源归属，`:143` 双向核对已知命中关系、条目动作和前后来源；`api.test.ts:46`–`:78` 包含合法新增／重复／撤销／缺失撤销和对应损坏输入。 | 原三个反例均有拒绝断言，角色与身份受阻边界有通过对照；修复成立。 |
| W2 资格重核吞提示 | `DiagnosticHost.vue:15` 固定通知跨重核保留；`recheck.test.ts:63` 使用真实 store 验证独立入口。 | 独立入口修复成立；预演弹窗残留 R1，不能整体关闭。 |
| W3 离线覆盖缺口 | Evaluation 新增 globalManage=true、两个 ROLE 来源及 archived=true 求值；Service 游标有原条件续页对照和 actor／epoch／resource／q 拒绝；Access 附选成功及搬离拒绝；panel 有 ID 分页、前后多来源和密级／归档渲染；recheck 有未知写入与迟到诊断。 | 原点名缺口已补充有效证据；预演失败弹窗链路属于 R1 的剩余组合缺口，不另重复计数。 |
| W4 搜索反向依赖 | `search/model/AssistedSearchInput.java:9` 承载不可变服务输入，控制器转入 model；两个服务不再依赖 web 类型。`ModuleBoundaryTest` 本轮 13/13 通过，测试文件相对基线无修改。 | 原 25 条反向依赖已消除，修复成立；相关辅助搜索 13 项也通过。 |

## 需求及场景核对矩阵

表中每行覆盖对应需求下的全部场景；全名和逐场景列表见 [traceability.md](traceability.md)，补充测试方法见 [remediation.md](remediation.md)。以下位置均按仓库相对路径。`后端` 指 `kb-app/src/main/java/com/zhishiku/kb/app/auth/`，`web` 指 `kb-web/src/authorization/`。测试简称为 `Diagnostic*Test`。

| 能力／需求（场景数） | 实现位置与核验要点 | 离线证据／审阅结论 |
| --- | --- | --- |
| diagnostics：先操作者再目标（3） | 后端 `service/diagnostics/DiagnosticAccess.java:42`、`DiagnosticService.java:46` | Access／Permission／Service 拒绝前不解析目标；文档按当前管理清单准入。 |
| 最小用户选择（4） | `DiagnosticService.java:81`、`DiagnosticTargetLoader.java:51` | 搜索仅 ID/label；Target 区分身份阻断、缺失和依赖故障；Service 实测游标绑定与续页。 |
| 单用户与明确范围（3） | `DiagnosticInput.java:78`、`DiagnosticAccess.java:53` | Input、Service、Access 附选成功／旧空间拒绝；未选文档不评估版本。 |
| 全部命中来源（4） | `DiagnosticEvaluator.java:47`；`mapper/DiagnosticMapper.xml:18` | Evaluation 覆盖多来源、全局 manage 与两 ROLE 来源；SQL 匹配／并集人工审阅，未运行真实 SQL。 |
| 授权与门槛分层（4） | `DiagnosticEvaluator.java:62`；`web/DiagnosticLayer.vue:11` | archived/globalManage/clearance/download 分支有直接用例；edit/review/comment 不作为业务动作承诺，相关原审核回归通过。 |
| 身份／密级受控投影（3） | `DiagnosticTargetLoader.java:57`；后端 `model/diagnostics/DiagnosticViews.java:17` | Target 的不同身份阻断和故障、Evaluation 未评估投影、layer 渲染；公开模型不随操作者目录权限增量披露。 |
| 当前发布范围（4） | `mapper/DiagnosticMapper.xml:68`、`AuthorizationRules.xml:31` | 指针为空／损坏结果替身、版本规则用例及当前指针 SQL 人工审阅；无版本列表或原件依赖。 |
| 单种精确意图（4） | `DiagnosticInput.java:104`、`DiagnosticAccess.java:68` | Input 50/51、300/301、重复及文档权限限制；Access 额外 manage 准入；api 完整意图绑定。 |
| 并集及条目变化（5） | `DiagnosticOverlay.java:34`、`DiagnosticEvaluator.java:24` | 撤个人保留部门、撤例外保留空间、新增受密级、无关主体及 no-op 有用例；panel 完整前后来源与结论通过。 |
| 同次一致读取（3） | `DiagnosticDatabase.java:28`、`DiagnosticService.java:52` | Boundary 捕获事务属性；Service 捕获行集与下一请求新事实；权限撤销重读。真实隔离继续排除。 |
| 只读及正式写独立（3） | `DiagnosticDatabase.java:28`、`DiagnosticOverlay.java:34` | 无写服务／DML／原件调用路径；Service 和 panel 的副作用／503 手动提交回归通过。404 后原草稿连续性仍有 R1。 |
| 最小投影及来源披露（3） | 后端 `model/diagnostics/DiagnosticViews.java:17`；`DiagnosticService.java:54` | 固定模型审阅，匹配来源有界，撤销来源只留 before；USER 来源错配现已拒绝。 |
| 有界与故障（3） | `config/DiagnosticLimits.java:8`、`DiagnosticStatementBudget.java:20`；`DiagnosticInput.java:60` | Input／Target／Service／Boundary 覆盖请求字节、规模、输出编码及受控超时／中断；依赖故障不输出部分结果。 |
| 私有通信及完整解码（3） | `config/DiagnosticResponseFilter.java:17`；`web/api.ts:117` | no-store、净化、固定错误、目标／资源／模式及矛盾结果组件测试通过；固定遥测投影沿前轮人工审阅。预演不可用可见反馈见 R1。 |
| functional：独立精确资格（4） | 后端 `model/Permission.java:39`、`service/AdminQueryAccess.java:25` | Permission/Catalog/access 覆盖精确 code、角色名和其他权限不隐含；下一次调用观察撤权。 |
| 真实管理范围（4） | `DiagnosticAccess.java:42`、`:68` | 无管理、拟自授、额外授出门禁、自撤 manage 的真实／假设上下文分离有测试。 |
| 一次性初始化（4） | `db/migration/V43__authorization_diagnostics_permission.sql:1`；`AdminBootstrapRunner.java:123` | V43 人工审阅，TermPermission 内存引导／撤销后重构，未知 code 拒绝单测；迁移和旧实例部署不执行。 |
| spaces：双重资格（3） | `web/DiagnosticHost.vue:17`；`admin/AclPanelContent.vue:62` | 资格未确认不加载，USER 行只作目标引用；父级管理范围与 ACL 成功状态共同控制入口。 |
| 来源及条件展示（3） | `web/DiagnosticLayer.vue:11`、`DiagnosticPanel.vue:38` | panel 多来源／归档／ID 级分页选择有直接回归，不补查标题。 |
| 完整草稿绑定（3） | `admin/AclPanelContent.vue:68`；`web/api.ts:170`、`state.ts:5` | 完整笛卡尔积／显式项、草稿／目标变化、generation 和迟到响应有测试。 |
| 前后差异及保留来源（3） | `web/DiagnosticPanel.vue:24`、`:66` | panel 对来源减少但访问不变、归档／密级下新增来源分别断言；身份 BLOCKED 用例保留未评估语义。 |
| 可选预演与写入独立（3） | `admin/AclPanelContent.vue:150`、`:157` | 原写回归及未知绑定通过；404 预演弹窗的提示承载不足见 R1。 |
| 私有状态失效（3） | `web/state.ts:8`、`DiagnosticHost.vue:26`；`SessionHost.vue:21` | 隐藏、epoch、资格及资源变更清理；真实 store 的失权／恢复和迟到回调回归通过。 |
| documents：功能及归属资格（3） | `admin/DocumentPanel.vue:30`、`:91`；`web/DiagnosticHost.vue:17` | 无正文仍能管理、只有密级资格不能诊断、503 独立失败有组件证据。 |
| 授权及当前发布层（4） | `web/DiagnosticLayer.vue:14`、`api.ts:75` | no-current、download 缺 view、review/manage 来源约束；无历史选择、版本身份或原件入口。V1/V2 指针规则为审阅与规则组件证据。 |
| 单项例外（3） | `admin/AclPanelContent.vue:165`；`DiagnosticOverlay.java:53` | 四权限、保留空间来源、归档／密级阻断、草稿切换与迟到响应有用例。 |
| 父级写入协调（3） | `admin/AclPanelContent.vue:66`；`admin/DocumentPanel.vue:26` | 删除确认、写入未知、搬迁清理和无预演写入回归通过；尚未提交的文档草稿在预演 404 后丢失见 R1。 |
| 身份资源生命周期（3） | `web/DiagnosticHost.vue:18`、`state.ts:8`、`api.ts:117` | 关闭、换号、迟到及损坏结果按当前作用域清理；未发现读取结清未知写入。 |

## 设计及代码一致性

| 决策 | 本轮结论 |
| --- | --- |
| D1 组合准入 | 真实会话、精确诊断权限、当前管理范围及 manage 额外资格顺序保持。 |
| D2 最小选择 | POST 名称搜索、绑定游标、USER ACL 入口、同空间 ID 清单保持。 |
| D3 身份类型隔离 | 目标 Target 与执行 PrincipalHandle 分离，身份受阻时停止成员及版本读取。 |
| D4 共用规则 | 线上空间／文档与诊断引用 AuthorizationRules 的主体匹配、授权、归档和发布片段；输入不能选择 SQL 模板。 |
| D5 一致只读读取 | 独立 read-only／REPEATABLE_READ 事务覆盖准入、事实与编码预检；仅叠加 ACL 意图，真实数据库行为不宣称通过。 |
| D6 精确意图 | 四类 intent 共用输入约束，角色授予检查存在性，撤销残留不要求角色仍存在，不展开成员。 |
| D7 三层最小投影 | 原 W1 修复成立，双向一致性与目标来源限制有合法／损坏对照测试。 |
| D8 限界与私有通信 | 实际字节、主体键、来源并集、响应体、剩余预算和 no-store 有实现及组件证据。 |
| D9 可选 UI 与失效 | 写入与诊断绑定分离；独立入口重核反馈修复成立，预演弹窗仍有 R1。 |
| D10 离线边界 | 全部操作遵循允许范围；临时复现也是组件／内存传输，无外部服务。 |

新增 Java 代码沿当前 controller／service／mapper／model 与 config 分层，路径和 package 一致，未混用注入方式。搜索服务输入移至 model 层，HTTP 请求保留原平坦解码与转换，辅助搜索回归证明原文／辅助模式、筛选、选择上下文与失败分支仍可用。OpenAPI 描述已人工核对，未启动 API 文档服务。

## 本轮实际执行结果

| 检查 | 结果 |
| --- | --- |
| Java 指定白名单 | 25 类／129 项通过，0 failures、0 errors、0 skipped；app 123、common 1、gateway 5。 |
| 其中诊断及架构 | 诊断 7 类／34 项；ModuleBoundaryTest 13 项；均包含在 129 中，不重复累计。 |
| 前端既定白名单 | 9 文件／129 项通过，无失败或跳过。 |
| 额外预演 404 定向复现 | 1 项失败，确认 R1；临时文件已清理，不计作既定白名单通过。 |
| 类型检查 | vue-tsc 通过。 |
| 静态生产构建 | Vite 通过，2227 modules。 |
| 当前包体隔离 | 通过；入口 gzip 134670 字节，文档列表 241033 字节，诊断场景 142476 字节。 |
| OpenSpec strict | 通过。 |

本轮后端与前端白名单命令与 [remediation.md](remediation.md) 所列最终命令完全相同。另执行：

```powershell
# 仓库根目录
openspec list --json
openspec status --change permission-explanation-and-authorization-preview --json
openspec instructions apply --change permission-explanation-and-authorization-preview --json
openspec validate permission-explanation-and-authorization-preview --strict

# kb-web 目录
node node_modules/vue-tsc/bin/vue-tsc.js --noEmit
node node_modules/vite/bin/vite.js build
node scripts/bundle-report.mjs --check-isolation --out .bundle-analysis/authorization-diagnostics.json
node node_modules/vitest/vitest.mjs run src/authorization/verify-preview-recheck.test.ts
```

最后一条仅为已清理的临时复现；其行为与失败输出已记录在 R1。包体分析工具的 13 项单测本轮未重跑，沿用修复阶段证据，不计为本轮执行。

## 验证边界及交付

真实 SQL 授权等价、数据库约束／锁／隔离、V43 执行、真实网络、Redis、存储、跨实例、真实浏览器及前后端联调仍未验证。这些是用户明确排除的层次，不作为新增待办、告警或归档前置条件。迁移、SQL 和文档仍按约定人工审阅，未编写或执行文档存在性／内容或 SQL 字符串断言测试。

本轮交付第二轮报告及历史报告的索引更新，未修改产品实现或任务完成标记，未提交、同步主规格、归档或部署。
