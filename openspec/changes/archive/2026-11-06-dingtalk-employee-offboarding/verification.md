# 核验报告：dingtalk-employee-offboarding

## 2026-11-06 主规格同步与归档记录

按用户明确请求，先同步主规格，再将本变更归档至 `openspec/changes/archive/2026-11-06-dingtalk-employee-offboarding/`，使用 `spec-driven`，规划产物完整、任务 59/59。12 个文件（含 `.openspec.yaml`、核验及修复历史）移动时已逐文件核对完整性；本节追加于归档后。

- 7 份主规格已合并全部 36 项新增、3 项修改要求；39 个变更块、104 个场景均与增量比较匹配，原有未修改要求、场景顺序、标题和 Purpose 保留。新能力 Purpose 原文沿用，无占位、删除或重命名项。
- 本变更归档前严格校验及 7 份目标主规格严格校验通过；58 份主规格普通校验通过。全库严格校验有一项既有 `admin-web-access` 描述超过 500 字符的警告，本轮未改该无关规格。
- 本轮同步/归档未修改业务实现、测试或任务勾选，也未重新运行业务测试、启动服务或补做真实环境验收。下方最新独立核验的 1 项 SUGGESTION 与 promtool 缺失导致的 Not verified 保留；归档不等于全部验证通过。

---

## 2026-11-06 W7/W8 完成后独立核验（当前结论）

本节为本轮 `openspec-verify-change dingtalk-employee-offboarding` 的结论，核对提交 `3ebb214` 与当前工作区的 W5/W6/S1、W7/W8 修复。重新读取 proposal、design、tasks 和全部 7 份增量规格，按当前实现和本轮独立运行核对，不以历史报告或 all_done 代替验证。工作区原有修改均保留；本轮没有修改实现、测试或任务勾选，仅更新本报告并生成检查产物，没有提交或归档。

**结论：0 项 CRITICAL、0 项 WARNING、1 项 SUGGESTION。** 59/59 任务可评估且均完成，39 项 ADDED/MODIFIED 要求均找到实现入口，104 场景逐项对照实现、直接/组合测试或约定的发布方案。未发现新的明确功能偏差；W5/W6/S1 与 W7/W8 修复链路仍成立。部分前端场景可补强直接组件断言，但不能把没有专门用例等同于共享路径未实现。**Scenario Coverage 的监控规则语法及合成时序执行仍为 Not verified（缺少 promtool）**，不能宣布全部核验通过或归档就绪。

### 汇总评分

| 维度 | 本轮结果 |
|---|---|
| 完整性 | Task Completion：59/59，taskTrackingConfigured=true，顶层 tasks/progress 可评估，无 unavailableTrackingFiles。Spec Coverage：39/39 要求有实现入口；无 REMOVED/RENAMED 项、缺失或刻意跳过的规划产物 |
| 正确性 | Requirement Implementation Mapping：39 项均核对，未发现明确偏差；Scenario Coverage：104 场景已对照代码与测试/约定方案，直接用例和共享组合证据分别标明。监控规则语法与合成时序部分 **Not verified（PATH、监控目录及会话 scratch 无 promtool）**；不把生成时序或历史运行计作动态通过 |
| 一致性 | Design Adherence：D1–D10 已核对，固定来源、独立映射、同事务屏障、显式复职、应急并集、认证代次、原输出许可及前端刷新/重新开始与当前实现一致。Code Pattern Consistency：新增内部异常/advice 和生命周期适配沿用现有服务/mapper、响应式许可、管理状态机结构，无需另报显著模式偏离 |

### 需求、场景及设计映射

| 能力 | 要求 / 场景 | 本轮核对的实现与测试证据 |
|---|---:|---|
| enterprise-account-lifecycle | 11 / 28 | [DingTalkEventParser](D:/WorkProject/kb/kb-app/src/main/java/com/zhishiku/kb/app/auth/lifecycle/DingTalkEventParser.java:22)、Settings/StreamProtocol/Http 的来源、大小、员工去重和出站边界；[MappingService](D:/WorkProject/kb/kb-app/src/main/java/com/zhishiku/kb/app/auth/lifecycle/LifecycleMappingService.java:86) 的独立映射、版本/一次性证明、历史和事件先到补映射；[Reception](D:/WorkProject/kb/kb-app/src/main/java/com/zhishiku/kb/app/auth/lifecycle/LifecycleReception.java:57) 持久提交前建立访问屏障、提交后 ACK、重放及冲突隔离；Restoration/Readiness/Retry/Query/Receipts 的复职顺序、启停独立、鉴权优先及原事实恢复。Mapping/Reception/Restoration/Protocol/Retry/Query/Readiness/Proof/HTTP 测试支撑相应分支；撤销/停止证据由 [OutputStopWindow](D:/WorkProject/kb/kb-app/src/main/java/com/zhishiku/kb/app/auth/lifecycle/OutputStopWindow.java:12) 区分 CONFIRMED/UNCERTAIN/DEFERRED，监控动态计算未验证 |
| identity-department | 5 / 14 | [V29](D:/WorkProject/kb/kb-app/src/main/resources/db/migration/V29__user_authentication_epoch.sql:2) 保持存量人工状态，[V30](D:/WorkProject/kb/kb-app/src/main/resources/db/migration/V30__identity_lifecycle_registry.sql:2) 单行受管/应急互斥及员工唯一性；IdentityAccessState/StateService 合成最终 enabled 并单调推进代次；[ManagementInvariant](D:/WorkProject/kb/kb-app/src/main/java/com/zhishiku/kb/app/auth/service/ManagementInvariant.java:19) 精确权限并集、有效部门/本地凭据和关闭订阅后持续保护；Identity/Department/RoleManagement 的全部资格收缩入口使用同一管理锁。[EmergencyMutationGuardTest](D:/WorkProject/kb/kb-app/src/test/java/com/zhishiku/kb/app/auth/service/EmergencyMutationGuardTest.java:55)、ManagementInvariant/MappingPolicy/EmergencyManagement 测试覆盖拒绝无部分写入、交错资格决策和本人先建后换，不冒称真实数据库并发证明 |
| enterprise-sso | 2 / 6 | SsoSession、LocalAuthService、AuthService 分别以 SSO/LOCAL/DEV 经 [EpochSessionService](D:/WorkProject/kb/kb-app/src/main/java/com/zhishiku/kb/app/auth/service/EpochSessionService.java:49) 签发新令牌；SSO 控制权/作用域仍独立，生命周期 Mapping 不写 SSO 绑定，解绑不解除限制。SsoSecurityService/Facade、登录契约、Mapping 和 Stream 配置测试组合覆盖未纳管边界、人工停用、受管离职及平台/SSO 开关独立性 |
| functional-authorization | 3 / 9 | [LifecycleAuthorizer](D:/WorkProject/kb/kb-app/src/main/java/com/zhishiku/kb/app/auth/lifecycle/LifecycleAuthorizer.java:20) 核对当前代次、部门、改密及精确权限，再读目标/回执/供应商；各写服务重放仍重新鉴权。[V32](D:/WorkProject/kb/kb-app/src/main/resources/db/migration/V32__identity_lifecycle_permissions.sql:2) 仅给存量 ROLE_ADMIN 一次性新增绑定，bootstrap 不补回运行期撤权。Authorizer、Read/WriteController、Mapping/Restoration/Retry 撤权重放和 bootstrap 测试覆盖四项资格正交及未知 code；旧节点排空按发布方案交付 |
| user-authentication | 6 / 13 | EpochSessionService 缺代次/旧代次拒绝、不回填，签发前后复核并只清理候选；app/gateway/AI 直接入口及 [OfficePreviewAccess](D:/WorkProject/kb/kb-app/src/main/java/com/zhishiku/kb/app/document/preview/OfficePreviewAccess.java:24) 仍核对身份和独立资源权限；[AuthenticationSignature](D:/WorkProject/kb/kb-common/src/main/java/com/zhishiku/kb/common/security/AuthenticationSignature.java:76) 固定 caller/操作、时间窗、nonce 和目标令牌绑定，不依赖会话开关；[SessionCleanupService](D:/WorkProject/kb/kb-app/src/main/java/com/zhishiku/kb/app/auth/service/SessionCleanupService.java:17) 仅清理原撤销代次。EpochSession/Interceptor/InternalAuthentication/Office/Client/Signature/Cleanup 测试覆盖签发竞态、401/403/503、伪造重放和新会话保护；协调升级/回退边界已核对 |
| multi-turn-chat | 5 / 16 | [OutputPermitService](D:/WorkProject/kb/kb-ai/src/main/java/com/zhishiku/kb/ai/auth/OutputPermitService.java:80) 请求内复用原许可、10/3/2 秒单调界限、独立取消及最终写出 guard；单轮 Controller/Service 覆盖模型、占位及采集开关，多轮保留估算/严格、原票据与固定结算意图；[MultiTurnChatService](D:/WorkProject/kb/kb-ai/src/main/java/com/zhishiku/kb/ai/conversation/MultiTurnChatService.java:204) 执行/清理首次可信撤销先取消再一次报告、受限 CANCELLED 收尾，明确无账本才释放预留。Permit/Chat/MultiTurnRevocation、MultiTurnIdentityFailure、协议/Execution/Controller/观察及前端 stream/Assistant 测试覆盖迟到、暂停、慢保存、检索 error、未知/未调用和完成竞争 |
| admin-web-users | 7 / 18 | 一次只读前端核对逐条映射 7 要求/18 场景：UserLifecyclePanel/LifecyclePage 的独立资格和最小事实，dialogs/api/scope 的准确核验、复职最终状态、逐项事件、本人应急及迟到清理；[UsersPage](D:/WorkProject/kb/kb-web/src/admin/UsersPage.tsx:74) 显式普通操作刷新代次，[dialogs](D:/WorkProject/kb/kb-web/src/admin/lifecycle/dialogs.tsx:22) 生命周期放弃后撤销证明、退出/重读，公共 reset 不变。users 新增 6 项联动及 lifecycle 新增 5 项刷新/重核验直接回归、原绑定精确重试均通过；部分其他场景依赖共享状态机/后端组合证据，见 S2 |

### 问题与建议

CRITICAL：无。WARNING：无。

#### S2（1 项 SUGGESTION）：补强少数生命周期场景的直接组件回归

当前相关分支由共享 useAdminResource/useLifecycleWrite、后端对应拒绝测试及现有组合回归支撑，未找到功能缺陷；但前端专门断言仍可更直接。建议在 [lifecycle.test.tsx](D:/WorkProject/kb/kb-web/src/admin/lifecycle/lifecycle.test.tsx:55) 增加以下有界 jsdom/API mock 场景：

- 摘要先成功后返回 503/403，断言旧事实与不可继续持有的确认域清除，不渲染为未纳管或恢复成功。
- 在 [纳管/退出组](D:/WorkProject/kb/kb-web/src/admin/lifecycle/lifecycle.test.tsx:92) 直接执行退出 DELETE 的 409，断言映射/限制保留、无恢复成功提示，并只重读事实。
- 在 [复职组](D:/WorkProject/kb/kb-web/src/admin/lifecycle/lifecycle.test.tsx:124) 让确认返回 stale/事件冲突，断言旧证明失效、事实重读、无自动确认写入，重新核验后才能再次确认。
- 在 [事件组](D:/WorkProject/kb/kb-web/src/admin/lifecycle/lifecycle.test.tsx:148) 同一事件返回 OFFBOARDED 与 UNMAPPED 两项，断言分别显示，未匹配不算停权成功、已读条数不是全局总数。

这是直接组合证据的补强建议，非新增任务、故障结论或归档前置条件；不要求为已由同一 helper 覆盖的每个入口再复制一套测试，也不要求真实服务或文档内容断言。

### 本轮实际运行与限制

| 检查 | 本轮结果 |
|---|---|
| 后端独立离线白名单 | **87 类/493 项通过，0 failures/errors/skipped/missing classes**；common 6/16、app 55/301、gateway 5/16、AI 20/151、notifier 1/9。唯一后缀/证据目录 `offboarding-verify-e78e8d30`，本轮 XML 与白名单/summary 对应，没有混入历史或重复累计 |
| 前端离线相关白名单 | **7 文件/141 项通过，无失败或跳过**：lifecycle 27、users 25、access 15、api 31、hooks 14、Assistant 17、stream 12；不是全量前端测试 |
| 类型检查 / 生产构建 | 本轮重新执行 typecheck、build 均通过，仅静态产物，不启动前端服务 |
| 监控配置与生成器 | **163 项配置行为检查通过**；重新生成 75 组主合成时序、29 类目录告警和 38 个面板查询，另 1 组 AI 未部署场景。生成不等于规则运行 |
| 监控规则语法 / 合成时序执行 | **Not verified**：PATH、ops 及会话 scratch 没有 promtool；既有固定工具下载超时限制未解除，本轮未重试下载或执行不完整二进制。不计历史运维文档的规则通过数，也不把工具缺失作为实现失败 |
| OpenSpec / 差异 | 严格校验通过（valid=true、issues=[]），git diff --check 通过；报告更新后再次检查。59/59、remaining=0、all_done 只作任务进度 |
| 真实环境与文档 | 未执行真实数据库迁移/并发、企业联调、跨节点 30 秒实测或部署演练，按 design/tasks 属本轮范围外；其实现/替身及方案映射已核对，不冒称真实验收。未启动业务、数据库、Redis、钉钉、模型、Office、容器或监控服务，未新增/运行文档标题关键词等断言 |

本轮证据保存在会话 scratch：`offboarding-final-verify-status.json`、`offboarding-final-verify-apply.json`、`offboarding-final-verify-spec-inventory.json`、`offboarding-verify-e78e8d30`（whitelist/arguments/日志/summary/XML）、`offboarding-final-verify-web-tests.log`、`offboarding-final-verify-typecheck.log`、`offboarding-final-verify-build.log`、`offboarding-final-verify-web-results.json`、`offboarding-final-verify-monitoring-cefe9fefb13e4ca8b89e679634330af4` 和本轮校验记录。只读前端核对的建议由主代理结合共享状态机、后端和当前测试重新判定，未追加代理复核或修复。

**最终评估：已运行的检查未发现关键问题或明确规格/设计偏差，保留 1 项 SUGGESTION。Scenario Coverage 的监控规则语法与合成时序部分未验证，原因为固定工具缺失；不能宣布全部核验通过或归档就绪。** 未验证描述本报告的证据限制，不自动变成额外归档条件。本轮不改变任务状态、不实施建议、不提交或归档。

---

## 2026-11-06 W7/W8 前端修复及离线回归（历史修复记录）

本轮根据下方独立核验的两项 WARNING 定向修复 W7/W8，保留既有后端修复、任务和核验历史。**W7、W8 均已修复，新增 11 项项目内组件回归通过，修复范围内未遗留已知实现缺陷。** 这不是新一轮全部需求/104 场景独立核验，也不把任务完成或构建通过等同于全部规格已验收。

### 修复与直接证据

| 项目 | 修复行为 | 回归证据 |
|---|---|---|
| W7 普通操作与摘要联动 | [UsersPage.tsx](D:/WorkProject/kb/kb-web/src/admin/UsersPage.tsx:74) 为普通详情/角色重读推进生命周期刷新代次，覆盖启停、主部门、密级、密码及角色操作成功与相关 404/409 重读；补上密码保护冲突的重读通知。生命周期操作反向通知普通详情不推进该代次，避免循环 | [users.test.tsx](D:/WorkProject/kb/kb-web/src/admin/users.test.tsx:374) 新增 6 项组合回归：启用/停用成功、普通启用的 LIFECYCLE_RESTRICTED，以及停用、密码重置、角色解除的 EMERGENCY_MANAGER_REQUIRED，均断言相关普通事实和独立生命周期摘要重新读取；成功后的延迟读取期间旧摘要不可操作 |
| W7 旧事实与证明隔离 | [UserLifecyclePanel.tsx](D:/WorkProject/kb/kb-web/src/admin/lifecycle/UserLifecyclePanel.tsx:14) 将刷新代次纳入操作域，卸载旧确认表单、证明和等待；仍由 lifecycle:read 门禁控制查询，不扩大普通 DTO 或本地推测限制 | [lifecycle.test.tsx](D:/WorkProject/kb/kb-web/src/admin/lifecycle/lifecycle.test.tsx:185) 新增 2 项，覆盖有效证明和在途核验被刷新隔离、请求中止、迟到证明不能回填，以及新摘要完成前没有旧确认入口 |
| W8 放弃后重新开始 | [dialogs.tsx](D:/WorkProject/kb/kb-web/src/admin/lifecycle/dialogs.tsx:22) 仅适配生命周期 write.reset：撤销证明、丢弃本地绑定、退出确认并重读当前事实；重新进入后必须显式核验、填写原因和确认。放弃不能撤回旧写入，不把重读当作原请求失败 | [lifecycle.test.tsx](D:/WorkProject/kb/kb-web/src/admin/lifecycle/lifecycle.test.tsx:209) 新增 3 项，覆盖纳管、复职、本人应急轮换：未知→放弃→重读中不能重开→新摘要→修改原因但不能确认→新核验→新证明、新版本、新 requestId；应急确认不含密码 |
| 原绑定与公共行为保持 | 未修改共用 [hooks.ts](D:/WorkProject/kb/kb-web/src/admin/hooks.ts:109) 或 [ui.tsx](D:/WorkProject/kb/kb-web/src/admin/ui.tsx:9)；原绑定精确重试仍携带原证明、原因、版本和请求标识，证明过期不妨碍核对原回执 | 原复职和事件匹配重试、普通密码及创建未知重试，以及共用 hook 的 14 项回归全部通过 |

设计 D9 与 [管理页面说明](D:/WorkProject/kb/docs/admin-identity-web.md:155) 已同步刷新和重新开始规则。W8 属于前端确认协议修复，不声称此前能绕过后端一次性证明、权限或版本保护。

### 本轮实际检查

| 检查 | 结果 |
|---|---|
| 修复前直接复现 | 项目内定向运行 **11 failed / 41 skipped**；跳过来自 testNamePattern 筛选，不是最终回归跳过。失败对应摘要未重读、旧表单未隔离和放弃后旧确认仍可用 |
| 修复后组件回归 | lifecycle 与 users：**2 文件/52 项通过**（27 + 25），包括新增 11 项及原有 41 项，不重复累计到最终总数 |
| 最终相关前端离线回归 | **7 文件/141 项通过，无失败、无跳过**：lifecycle 27、users 25、access 15、api 31、hooks 14、Assistant 17、stream 12。前 5 文件为 112 项，Assistant/stream 为 29 项；不是全量前端测试 |
| 类型检查 / 生产构建 | `npm --prefix .\kb-web run typecheck` 与 `npm --prefix .\kb-web run build` 均通过；只生成静态构建产物，没有启动前端服务 |
| OpenSpec / 工作区检查 | 任务 11.8–11.10 已完成；`openspec validate dingtalk-employee-offboarding --strict --json` 通过（valid=true、issues=[]），`git diff --check` 通过；完整工作区保留既有后端改动 |
| 后端及监控 | 本轮没有修改或重跑后端、监控实现；下方上一轮 87 类/493 项后端和 163 项监控配置检查仍为历史证据，不混入本轮计数。PATH 仍无 promtool，监控语法与合成时序执行仍为 **Not verified** |
| 实际环境范围 | 未启动前端/业务/数据库/Redis/钉钉/模型/Office/监控服务；未执行真实数据库并发、企业联调、跨节点 30 秒验收或部署演练；未新增/执行文档标题、关键词等内容断言 |

首次新增测试运行发现用户组合夹具的生命周期路径错误，已改为正式 lifecyclePaths，并在**修改实现前**再次运行确认上述 11 项真实失败；首轮夹具失败不作为缺陷复现证据。之前跨目录临时探针的 0 tests 加载失败记录保留为历史，本轮以项目内 jsdom 的有效断言闭环，不冒称那些探针成功。

本轮会话 scratch 证据：`offboarding-web-repair-red-confirmed.log`、`offboarding-web-repair-targeted-green.log`、`offboarding-web-repair-regression.log`、`offboarding-web-repair-typecheck.log`、`offboarding-web-repair-build.log`、`offboarding-web-repair-build-results.json` 及本轮 OpenSpec 状态/校验记录。没有提交或归档。监控动态规则工具缺失是保留的核验限制，不新增真实环境验收或归档前置条件。

---

## 2026-11-06 修复后再次独立核验（W7/W8 修复前历史记录）

本节为本轮 `openspec-verify-change dingtalk-employee-offboarding` 的结论，覆盖下方修复记录的评估。核对对象为提交 `3ebb214` 及其当前工作区内 W5/W6/S1 修复；核验开始时工作区已有修复和报告改动，本轮保留这些改动，仅更新本报告，没有修改实现、任务勾选、提交或归档。

**结论：0 项 CRITICAL、2 项 WARNING、0 项 SUGGESTION。** 56 项任务均已勾选，39 项 ADDED/MODIFIED 要求均找到实现入口，104 个场景已对照代码、测试或约定的部署说明；发现管理前端 W7/W8 两条具体偏差。后端撤销修复回归通过，但监控规则动态检查和临时前端缺陷探针未执行到有效断言，不能给出“全部核验通过或归档就绪”的结论。

### 汇总评分

| 维度 | 本轮结果 |
|---|---|
| 完整性 | Task Completion：56/56，顶层 tasks 与 progress 均可评估，未报告 unavailableTrackingFiles；Spec Coverage：39/39 有实现入口，0 REMOVED、0 RENAMED，无缺失需求 |
| 正确性 | Requirement Implementation Mapping：39 项均核对，W7/W8 为前端偏差；Scenario Coverage：104 个场景有代码/测试或约定方案映射，但不等于全部满足。监控规则语法及合成时序执行 **Not verified（缺少 promtool）**；W7/W8 临时动态探针 **Not verified（Vitest 跨目录 setup 加载失败，0 tests）**，两项问题以源码链路为证据 |
| 一致性 | Design Adherence：D1–D10 已核对，D9 存在 W7/W8 偏差；Code Pattern Consistency：实现、新增协议类型与内部 advice 的职责及目录符合现有模式，未发现需要单独报告的显著模式偏离 |

proposal、design、tasks 和 7 份增量规格均已完整读取，无缺失或刻意跳过的规划产物。部署、真实数据库并发、企业联调与跨节点 30 秒实测按 design/tasks 明确排除在本轮编码核验要求外；其方案交付已核对，不冒称真实环境验收通过，也不新增归档前置条件。

### WARNING（应修复）

#### W7：普通启停成功或生命周期冲突后，生命周期摘要仍可显示旧事实

- 依据：[账号启停真实状态](D:/WorkProject/kb/openspec/changes/archive/2026-11-06-dingtalk-employee-offboarding/specs/admin-web-users/spec.md:7)、[生命周期独立摘要](D:/WorkProject/kb/openspec/changes/archive/2026-11-06-dingtalk-employee-offboarding/specs/admin-web-users/spec.md:27) 和设计 D9 要求成功后重新读取目标事实、区分人工状态和最终状态，冲突不能呈现为成功。
- [UsersPage.tsx](D:/WorkProject/kb/kb-web/src/admin/UsersPage.tsx:74) 的 `done()` 仅刷新普通详情和列表；[面板挂载](D:/WorkProject/kb/kb-web/src/admin/UsersPage.tsx:116) 只传 userId 和由生命周期操作反向通知普通详情的 onChanged，没有普通操作向生命周期面板传递的刷新信号。
- [UserLifecyclePanel.tsx](D:/WorkProject/kb/kb-web/src/admin/lifecycle/UserLifecyclePanel.tsx:19) 的读取键只绑定目标和权限事实，自己的 reload 仅从面板动作触发。普通详情的 reload 保留同目标旧 data，不导致该面板卸载或读取键变化；普通启用的 409 也仅重读普通详情。
- 可复查路径：管理员持有账号读写及生命周期读资格，打开人工启用账号，成功执行普通停用；普通详情变为停用，而旁边生命周期摘要仍可显示人工启用、最终启用，直到人工点击“重读生命周期”。启用因新离职限制返回 LIFECYCLE_RESTRICTED 时，面板也可能仍显示“无限制”。这是陈旧投影，不表示后端继续授权。
- 建议：在普通启停成功及相关生命周期/应急 409 后，将明确的刷新代次或刷新回调传入面板，同步重新查询服务端摘要，不自行推断最终状态。于 [users.test.tsx](D:/WorkProject/kb/kb-web/src/admin/users.test.tsx:253) 增加 `UsersPage + UserLifecyclePanel` 组合回归，断言普通和生命周期详情都重新读取、事实一致；现有用户页面 fixture 的资格不含生命周期读权限，未覆盖此组合。

#### W8：放弃未知提交后，可以用旧核验证明建立新的确认请求

- 依据：[敏感写入作用域及重新编辑场景](D:/WorkProject/kb/openspec/changes/archive/2026-11-06-dingtalk-employee-offboarding/specs/admin-web-users/spec.md:95) 要求未知后编辑先核对原结果，再通过新的核验和确认建立新操作，不能把新输入当成原绑定重试。
- [WriteNotice](D:/WorkProject/kb/kb-web/src/admin/ui.tsx:13) 的“放弃核对”直接调用公共 `write.reset`；[hooks.ts](D:/WorkProject/kb/kb-web/src/admin/hooks.ts:115) 仅清除写绑定并回到 idle，不清除核验证明。
- [useLifecycleWrite](D:/WorkProject/kb/kb-web/src/admin/lifecycle/dialogs.tsx:16) 仅在 rejected 时调用 invalidate；映射、复职和应急对话框均复用该流程。放弃未知状态不触发 verify.reset，原 valid proof 仍使确认按钮可用；更改操作原因后，新的 requestId 会带上旧 proof，无须重新核验或核对原回执。
- 建议：为生命周期流程显式适配“放弃并重新开始”，保留原绑定匹配重试的能力，但开始新操作前废弃旧证明并重新核对/核验；不要直接改变其他管理操作的公共 reset 语义。于 [lifecycle.test.tsx](D:/WorkProject/kb/kb-web/src/admin/lifecycle/lifecycle.test.tsx:135) 补“未知→放弃→修改原因→旧证明不可确认→重新核验→新请求标识”回归。现有该用例只覆盖原绑定显式重试。此项是前端确认协议偏差，不声称能绕过后端一次性证明、版本或权限门禁。

CRITICAL：无。SUGGESTION：无。W7/W8 不通过未运行的临时探针冒称已动态复现；上述源码调用链足以定位具体行为和修复位置。

### 需求、场景及设计的实现映射

| 能力 | ADDED/MODIFIED 要求 / 场景 | 本轮主要核对证据 |
|---|---:|---|
| enterprise-account-lifecycle | 11 / 28 | [LifecycleReception](D:/WorkProject/kb/kb-app/src/main/java/com/zhishiku/kb/app/auth/lifecycle/LifecycleReception.java:57) 提交前原子屏障、提交后 ACK 及独立观测；[MappingService](D:/WorkProject/kb/kb-app/src/main/java/com/zhishiku/kb/app/auth/lifecycle/LifecycleMappingService.java:103) 精确映射、一次性证明、历史及补映射同事务限制；[RestorationService](D:/WorkProject/kb/kb-app/src/main/java/com/zhishiku/kb/app/auth/lifecycle/LifecycleRestorationService.java:111) 双重成员查询、完整事实快照与旧事件边界；RetryService/QueryService 的原事实重试、独立读取与稳定分页。Reception、Mapping、Restoration、Protocol、Retry、HTTP 和停止证据测试覆盖重复、回滚、迟到、未知及隐私边界；监控动态证据未验证 |
| identity-department | 5 / 14 | V29 原 enabled 回填、V30 受管/应急单行互斥及来源员工唯一约束；IdentityAccessState/StateService 合成人工状态、限制和代次；[ManagementInvariant](D:/WorkProject/kb/kb-app/src/main/java/com/zhishiku/kb/app/auth/service/ManagementInvariant.java:19) 精确权限并集与统一管理锁；IdentityService/DepartmentService/RoleManagementService 全部资格收缩入口；EmergencyManagementService 本人真实 LOCAL 验证及先建后换。State、ManagementInvariant、EmergencyMutationGuard、EmergencyManagement 测试覆盖领域和事务替身，不宣称真实数据库并发证明 |
| enterprise-sso | 2 / 6 | SsoSession/LocalAuthService/AuthService 统一由 EpochSessionService 签发；MappingService 不写 SSO 绑定；SSO 解绑不修改生命周期事实；LifecycleReadiness/StreamConnection 开关及重连准备独立。SSO 安全、Facade、Mapping、登录契约和 Stream 配置测试组合覆盖 |
| functional-authorization | 3 / 9 | [LifecycleAuthorizer](D:/WorkProject/kb/kb-app/src/main/java/com/zhishiku/kb/app/auth/lifecycle/LifecycleAuthorizer.java:20) 当前代次、部门及精确权限核对；各读写服务先鉴权再读目标、回执或调用平台；V32 对存量 ROLE_ADMIN 一次性绑定，AdminBootstrapRunner 不补回运行期撤权。Authorizer、读写控制器、Mapping、Restoration、Retry 和 bootstrap 测试覆盖资格正交及撤权后回执拒绝 |
| user-authentication | 6 / 13 | [EpochSessionService](D:/WorkProject/kb/kb-app/src/main/java/com/zhishiku/kb/app/auth/service/EpochSessionService.java:49) 新 token、可信代次、签发前后核验、候选清理；app/gateway/AI 直接入口与 Office 原 token 权限检查；独立 CHECK 签名、精确公开边界；[SessionCleanupService](D:/WorkProject/kb/kb-app/src/main/java/com/zhishiku/kb/app/auth/service/SessionCleanupService.java:17) 按原代次有限清理。认证、配置、签名、Office 和清理测试覆盖；升级和旧节点排空按协调发布方案交付核对 |
| multi-turn-chat | 5 / 16 | [OutputPermitService](D:/WorkProject/kb/kb-ai/src/main/java/com/zhishiku/kb/ai/auth/OutputPermitService.java:80) 同请求许可、10/3/2 秒单调时间约束、独立定时取消及最终 guard；单轮模型/占位/采集矩阵、多轮严格/估算与受限结算；TurnExecutionService 原 authEpoch 门禁及合法完成竞争；Chat/MultiTurnRevocation、MultiTurnIdentityFailure、Permit、StopReporter、Execution 和 Controller 测试覆盖。前端 Assistant/stream 白名单检索错误提示本轮另跑通过 |
| admin-web-users | 7 / 18 | [UserLifecyclePanel](D:/WorkProject/kb/kb-web/src/admin/lifecycle/UserLifecyclePanel.tsx:14)、LifecyclePage、dialogs、scope 和 API 的独立资格、来源投影、核验/确认、原绑定重试、本人应急及迟到清理；当前 18 场景逐项对照，W7/W8 为具体偏差，其关联组合路径缺少直接回归。公共状态机测试只计为组合证据，不冒称每个分支已有专门组件用例 |

设计 D1–D6、D8、D10 的固定来源、隔离映射、持久屏障、状态分离、复职与应急锁序及时间区间仍与实现相符；D7 明确撤销标记、取消先于报告、清理阶段复用原许可及无账本释放也与当前实现和回归一致。D9 的两条前端偏差见 W7/W8。代码结构沿用 app 领域/mapper/advice、AI 无数据库访问和 admin 公共状态机模式，没有因重复核验而发明范围外要求。

### 本轮实际检查与证据口径

| 检查 | 结果 |
|---|---|
| OpenSpec status / apply | repo-local、spec-driven，规划产物完整；56/56，remaining=0，all_done 仅是任务状态，不作核验判定 |
| OpenSpec strict validate / diff | `valid=true, issues=[]`；git diff --check 通过，报告更新后再检查 |
| 后端显式离线白名单 | **87 类、493 项通过，0 failures、0 errors、0 skipped，无遗漏类**；common 6/16、app 55/301、gateway 5/16、AI 20/151、notifier 1/9；本轮独立后缀 `offboarding-verify-30dc9c95`，没有混入前轮 XML 或重复累计 |
| 前端相关离线回归 | admin lifecycle/users/access/api：4 文件/87 项；Assistant/stream：2 文件/29 项；合计 **6 文件/116 项通过** |
| 类型检查和生产构建 | npm run typecheck、npm run build 均通过；没有启动前端服务 |
| 监控配置 | **163 项配置行为检查通过**；生成 75 组主时序及额外 1 组 AI 未部署场景，生成不等于规则计算通过 |
| 监控规则语法与时序 | **Not verified**：PATH、工作区及会话 scratch 无 promtool；前轮固定官方工具下载超时未解除，本轮未下载、执行或信任不完整二进制 |
| W7/W8 临时前端探针 | **Not verified**：两次运行均在 Vitest setup 的跨目录路径加载阶段失败，0 tests，无有效断言；尝试的临时源码链接已仅删除链接本身，目标源码未改。发现以源码链路为依据，不将探针列为通过或规格正确性失败测试 |
| 企业及真实环境 | 未启动业务、数据库、Redis、钉钉、模型、Office 或监控服务；未执行真实数据库迁移、并发、企业联调、跨节点 30 秒实测或部署演练；未新增/执行文档标题、关键词等内容断言 |

本轮重新读取全部规划文件并执行上述检查，不依靠上一轮报告宣称通过。后端并行核对报告在续轮后不可读取，已由主代理直接补读剩余领域、权限、迁移和测试入口完成核对，不把缺失报告当证据。只读前端核对的 W7/W8 又由主代理检查实际刷新和写入状态机链路确认；没有追加代理争论或实现修改。

本轮临时证据在会话 scratch：`offboarding-verify-30dc9c95`（白名单、命令、日志、统计和 XML）、`offboarding-verify-spec-inventory.json`、`offboarding-verify-web-tests.log`、`offboarding-verify-stream-ui.log`、`offboarding-verify-web-typecheck.log`、`offboarding-verify-web-build.log`、`offboarding-verify-monitoring-a29619a99dd64351822c8ad41d5bd4ad` 及 `offboarding-verify-ui-probe*.log`。缺少 promtool 的动态通过证据，不以历史检查替代。

**最终评估：已运行的检查未发现 CRITICAL，有 2 项 WARNING 应修复，无 SUGGESTION。Scenario Coverage 的监控规则动态证据，以及 W7/W8 临时动态复现未验证，原因分别为固定工具缺失和 Vitest 环境加载失败。不能宣布全部核验通过或归档就绪。** 本轮未修改实现、任务状态或执行归档，建议按 W7/W8 的具体位置修复并补回归；未验证项只是本报告限制，不自动成为额外归档前置条件。

---

## 2026-11-06 W5/W6 修复后复核（历史修复记录）

本节记录针对下方独立复核 W5/W6/S1 的修复和最终离线回归，覆盖其修复前结论，不冒充重新完成真实环境或全部场景验收。**W5、W6 已修复，S1 已补齐，任务 56/56；修复范围内未遗留已知代码缺陷。监控规则语法及合成时序执行仍为 Not verified。** 历史报告保留，历史通过数不混入本次结果。

### 修复及直接证据

| 项目 | 本次实现 | 直接回归证据 |
|---|---|---|
| W5 执行先确认撤销 | [AccountRevokedException.java](D:/WorkProject/kb/kb-common/src/main/java/com/zhishiku/kb/common/security/AccountRevokedException.java:8) 与内部响应固定标记保留明确的撤销类型；同一输出许可接收执行拒绝，先取消模型再提交一次停止报告，随后受限 CANCELLED / ACCOUNT_REVOKED 收尾，不保存剩余缓冲 | [MultiTurnIdentityFailureTest.java](D:/WorkProject/kb/kb-ai/src/test/java/com/zhishiku/kb/ai/conversation/MultiTurnIdentityFailureTest.java:72) 覆盖 dispatch/append/renew/seal/finish 在首次刷新前拒绝、原代次报告一次、无新 delta/done 及无普通 FAILED 收尾 |
| W5 清理及预留边界 | [MultiTurnChatService.java](D:/WorkProject/kb/kb-ai/src/main/java/com/zhishiku/kb/ai/conversation/MultiTurnChatService.java:211) 将普通错误清理保留在原许可生命周期；清理 append/finish 确认撤销时复用原许可报告并切换受限收尾；finish 明确无账本时取消先取得的配额租约 | 同一测试类覆盖清理同步/延迟拒绝、前缀不重复追加；reserve 输出前拒绝不伪造停止记录；无账本释放一次，已有意图走结算，收尾响应未知不按零用量提前释放 |
| W6 服务认证与账户撤销分离 | [InternalIdentityFailureAdvice.java](D:/WorkProject/kb/kb-app/src/main/java/com/zhishiku/kb/app/config/InternalIdentityFailureAdvice.java:15) 只给内部控制器的可信撤销异常返回唯一 `X-Auth-Result: ACCOUNT_REVOKED`、空正文及 no-store。AI 只接受 401/403 上单个精确值，认证刷新无标记拒绝使用 AUTHENTICATION_UNAVAILABLE | InternalIdentityFailureAdviceTest 验证服务签名先于用户核验、依赖失败无标记及输入同名头无效；AuthenticationClientTest/ConversationRevocationProtocolTest 覆盖无标记、未知/重复标记、非 401/403、正文不泄露及不重试；MultiTurnIdentityFailureTest 用真实 AI 客户端和进程内 exchange 对照有/无标记的 401/403 刷新，分别受限取消/普通失败，均取消模型且不发 done |
| S1 精确计时边界 | 不修改 10 秒硬上限、3 秒刷新及 2 秒检查上限 | [OutputPermitServiceTest.java](D:/WorkProject/kb/kb-ai/src/test/java/com/zhishiku/kb/ai/auth/OutputPermitServiceTest.java:133) 直接覆盖定时器暂停且响应耗时超过 2 秒、成功刷新从请求发起计时、新执行活跃时旧执行迟到响应不能改变绑定或期限；旧许可过期后的成功响应测试也确保实际走迟到回调而非提前超时 |

唯一一次只读复核发现了清理阶段首次撤销及无账本租约释放两条遗漏；新增测试先复现 5 个失败，修复后相关 6 类/65 项及最终白名单全部通过。没有再次启动广泛复核。设计 D7、认证代次说明和内部会话协议已同步；app/AI 的新撤销结果必须协调发布，不能把旧节点的无标记拒绝宣称为可信账户撤销。

### 本次实际执行结果

| 检查 | 结果 |
|---|---|
| 最终后端离线白名单 | **87 类、493 项通过，0 failures、0 errors、0 skipped，无遗漏类**；原 78 类加新增及受影响会话/结算测试，非全量 Maven 测试 |
| 分模块 | common：6 类/16 项；app：55 类/301 项；gateway：5 类/16 项；AI：20 类/151 项；notifier：1 类/9 项 |
| 构建 | `mvn -o -B -ntp -fae -pl kb-app,kb-gateway,kb-ai,kb-alert-notifier -am` 加显式测试白名单执行 test，全部模块 BUILD SUCCESS；依赖使用现有离线缓存 |
| 定向回归 | 初始取消顺序修复 13 类/95 项通过；补充清理修复 6 类/65 项通过；均被最终白名单覆盖，不重复累计 |
| 监控配置行为 | config.tests.ps1 **163 项通过**；生成 75 组主时序和额外 1 组 AI 未部署场景，未启动服务 |
| promtool 规则语法 / 时序 | **Not verified**：本轮 PATH 仍无 promtool；前次固定官方工具下载超时仍未解除，未执行或信任不完整压缩包；生成不等于规则测试通过 |
| OpenSpec / 工作区检查 | 严格校验通过；任务 11.5–11.7 完成；git diff --check 通过 |
| 前端 / 企业及真实环境 | 本次未改前端，未复跑前次 87 项前端测试、typecheck 或 build；未运行数据库并发、跨节点 30 秒验收、企业集成或部署演练 |

最终测试按本轮唯一后缀 `offboarding-repair-regression` 的 XML 与白名单核对，未混入历史报告、缺陷复现的失败运行或定向重复次数。第一次完整回归的统计脚本未剥离 Surefire 类名后缀而拒绝汇总，修正后已完整重跑；最终数字来自补充修复后的最近一次成功运行。

会话 scratch `C:\Users\Burning\.pi-desktop\scratch\7de6bb88-7988-48a3-b6fb-b828de84de3c` 保存：

- `offboarding-repair-regression.ps1` 及 `offboarding-repair-regression` 下的 whitelist.csv、arguments.json、maven-test.log、results.csv、summary.json 和按模块复制的最终 XML。
- `offboarding-repair-targeted-cancellation.log`、`offboarding-repair-cleanup-red-confirmed.log`、`offboarding-repair-cleanup-green.log`：取消顺序、补充缺陷复现及修复通过记录。
- `offboarding-repair-monitoring-dedb2c49f95b4ff0af7dbed9b4cd7ee1`：本次配置及合成时序产物；没有 promtool 执行通过记录。

本次没有启动业务/前端/数据库/Redis/钉钉/模型/Office/监控服务，没有新增或执行文档内容断言，没有提交或归档。**代码修复和约定的可执行离线检查已完成；监控规则动态核验的工具缺失限制保留，不宣布全部验证通过或真实环境验收通过。**

---

## 2026-11-06 独立复核（修复前记录）

以下为本次修复前 `/openspec-verify-change dingtalk-employee-offboarding` 的结论，现由上方修复后复核覆盖。核对基线为提交 `3ebb214`；当时核验开始时工作区干净，当时仅更新报告，没有修改实现、任务勾选或执行归档。

**结论：0 项 CRITICAL、2 项 WARNING、1 项 SUGGESTION。** 53 项任务均已勾选，39 项 ADDED/MODIFIED 要求均找到实现入口，104 个场景已对照代码和测试；没有 REMOVED/RENAMED 要求。存在两条已用进程内探针复现的 AI 撤销归因偏差，且本轮监控规则动态检查未能执行，不能给出“全部通过、已就绪归档”的结论。

### 本轮摘要

| 维度 | 状态 |
|---|---|
| 完整性 | Task Completion：53/53，无不可读跟踪文件；Spec Coverage：39/39 有实现入口，无缺失需求，无移除或重命名项 |
| 正确性 | Requirement Implementation Mapping：39 项均核对，发现 W5/W6；Scenario Coverage：104 个场景已作实现/测试映射，现有后端 440 项、前端 87 项通过，但新增错误分支缺少回归；监控场景动态复跑 **Not verified（本机无 promtool，固定工具下载超时）** |
| 一致性 | Design Adherence：主要遵循 D1–D10，撤销原因、终态及停止证据存在 W5/W6 偏差；Code Pattern Consistency：已核对本次实现，未发现需要另行报告的显著模式偏离 |

已执行任务完成、规格覆盖、需求实现映射、场景覆盖、设计遵循和代码模式核对；没有缺失、不可读或刻意跳过的规划产物。场景覆盖的监控规则运行证据仅部分完成，不能将历史报告的规则通过数计作本轮通过。真实数据库并发、企业联调、跨节点 30 秒验收及部署演练仍在约定范围外，不是新增任务或归档前置条件。

### WARNING（应修复）

#### W5：app 先拒绝旧执行时，多轮可能固化为 FAILED 并遗漏撤销停止报告

- 规格依据：[受限取消与结算](D:/WorkProject/kb/openspec/changes/archive/2026-11-06-dingtalk-employee-offboarding/specs/multi-turn-chat/spec.md:75) 要求离职取消使用 `CANCELLED / ACCOUNT_REVOKED`；[设计 D7/D10](D:/WorkProject/kb/openspec/changes/archive/2026-11-06-dingtalk-employee-offboarding/design.md:106) 要求独立停止及关联证据。
- [ConversationIdentityGate.java](D:/WorkProject/kb/kb-app/src/main/java/com/zhishiku/kb/app/auth/service/ConversationIdentityGate.java:31) 在执行代次不符时返回 401；[ConversationAppClient.java](D:/WorkProject/kb/kb-ai/src/main/java/com/zhishiku/kb/ai/conversation/ConversationAppClient.java:132) 将它保留为普通 `ResponseStatusException`。
- [MultiTurnChatService.java](D:/WorkProject/kb/kb-ai/src/main/java/com/zhishiku/kb/ai/conversation/MultiTurnChatService.java:200) 仅识别 `OutputRevokedException`，所以 dispatch/append/renew 等比下一次许可刷新更早观察到撤销时，会按 `FAILED` 收尾，绕过 `finishRevoked`。app 允许该失败收尾提交，之后恢复任务不会再改写已有终态。
- [OutputPermitService.java](D:/WorkProject/kb/kb-ai/src/main/java/com/zhishiku/kb/ai/auth/OutputPermitService.java:281) 仅在 `revoke` 中提交停止证据；普通错误结束走 `dispose`，故该分支可能漏掉撤销停止报告。此问题不是继续输出或重新生成的绕过。
- 临时探针复用现有多轮 fixture，保持许可有效、令 dispatch 返回旧代次拒绝；实际结果：`FAILED`，`finishRevoked=0`，`stopReports=0`，无成功 done。现有 [MultiTurnRevocationTest.java](D:/WorkProject/kb/kb-ai/src/test/java/com/zhishiku/kb/ai/conversation/MultiTurnRevocationTest.java:59) 只覆盖许可刷新先发现撤销，未覆盖此竞争顺序。
- 建议：在经过服务认证的内部执行协议中提供固定、可区分的代次撤销结果；AI 将该结果统一送入当前许可的撤销/停止报告及 `finishRevoked` 路径。不要简单把全部内部 401/403 当成账号撤销。补充 dispatch、append、renew 先拒绝、许可尚有效的替身回归，验证 CANCELLED、ACCOUNT_REVOKED、停止报告和无新增正文。

#### W6：内部服务签名失败被误记为已确认账号撤销

- 规格依据：[受限取消与结算](D:/WorkProject/kb/openspec/changes/archive/2026-11-06-dingtalk-employee-offboarding/specs/multi-turn-chat/spec.md:75) 及[设计 D7](D:/WorkProject/kb/openspec/changes/archive/2026-11-06-dingtalk-employee-offboarding/design.md:108) 要求身份依赖故障使用失败/中断规则，不能伪报已确认离职。
- [InternalAuthenticationService.java](D:/WorkProject/kb/kb-app/src/main/java/com/zhishiku/kb/app/auth/service/InternalAuthenticationService.java:41) 在签名、调用方、时间窗或 nonce 无效时返回 403，此时还没有执行用户身份检查。
- [AuthenticationClient.java](D:/WorkProject/kb/kb-ai/src/main/java/com/zhishiku/kb/ai/auth/AuthenticationClient.java:55) 丢弃错误正文，仅按 HTTP 状态分类；[OutputPermitService.java](D:/WorkProject/kb/kb-ai/src/main/java/com/zhishiku/kb/ai/auth/OutputPermitService.java:141) 将全部 401/403 转成 `ACCOUNT_REVOKED`。例如流已建立后内部密钥滚动不一致，会把无法可信核对身份记为已确认账号撤销，并可能保存离职取消原因。
- 临时探针使用真实 AI AuthenticationClient、进程内 WebClient exchange 替身和虚拟时钟，首次核验成功、刷新返回内部 403，实际得到 `ACCOUNT_REVOKED`。停止输出的行为正确，错误在原因、终态与观测分类。
- 建议：内部认证协议明确区分服务认证拒绝与已经核验的用户撤销/禁用，AI 仅将后者映射为 `ACCOUNT_REVOKED`；服务认证失败应失败关闭并按 `AUTHENTICATION_UNAVAILABLE` 收尾。增加“首次成功、刷新签名不匹配”和“真实用户撤销”对照测试，分别断言失败与撤销取消，均取消上游且不发送 done。

### SUGGESTION（可改进）

#### S1：补齐刷新计时与旧执行迟到响应的直接边界断言

[OutputPermitServiceTest.java](D:/WorkProject/kb/kb-ai/src/test/java/com/zhishiku/kb/ai/auth/OutputPermitServiceTest.java:76) 已覆盖旧许可过期、刷新超时与代次/执行绑定；[实现](D:/WorkProject/kb/kb-ai/src/main/java/com/zhishiku/kb/ai/auth/OutputPermitService.java:251) 也有相应防护。建议补三个独立断言：定时器暂停而成功响应已超过 2 秒但旧 10 秒期限未到；成功刷新期限仍从请求发起计算；新执行活跃时旧执行迟到回调不能授权新执行。代码存在，故不将这些精确边界的直接测试不足升级为缺失实现。

### 关键实现映射与历史问题复核

| 能力 | 要求 / 场景 | 本轮证据 |
|---|---:|---|
| enterprise-account-lifecycle | 11 / 28 | Reception 的提交前屏障与提交后 ACK；MappingService 的独立映射、补映射同事务限制；RestorationService 双重成员查询、短期凭据与事实快照；RetryService 只重放原事实；低基数监控及关联停止证据 |
| user-authentication | 6 / 13 | EpochSessionService 新 token、签发前后核对及永久代次撤销；app/gateway/AI 每请求权威核对；独立签名协议；Office 原 token 代次检查；按撤销上限清理 |
| identity-department | 5 / 14 | V29 回填、IdentityAccessState 状态合成；ManagementInvariant 显式权限并集、统一管理锁及全部资格收缩路径；EmergencyManagementService 本人真实本地登录和先建后换 |
| enterprise-sso | 2 / 6 | LOCAL/SSO/DEV 统一经 EpochSessionService；受管映射不写 SSO 绑定；接收开关独立；成员故障不直接停用本地账号 |
| functional-authorization | 3 / 9 | 四项精确权限、鉴权先于查询/供应商/回执；V32 只对存量 ROLE_ADMIN 一次性绑定；初始化不补回撤权 |
| multi-turn-chat | 5 / 16 | 10 秒硬上限、3 秒刷新、2 秒核验上限；单调时钟、逐条最终 guard、静默/慢持久化独立取消；轮次 auth_epoch 门禁与受限结算；W5/W6 为本轮发现 |
| admin-web-users | 7 / 18 | 独立读取投影和权限、来源展示、核验/确认分离、未知结果原绑定重试、应急本人验证、身份/目标/权限/隐藏作用域清理 |

历史 W1–W4 的修复实现仍存在：输入固定拒绝类别及计数；不依赖积压的事件冲突告警；接收提交区间、原代次与 AI 停止区间的持久关联；仅读权限可见受管来源和连接。相应 Java/jsdom 回归本轮通过，但告警表达式只完成静态阅读和配置生成，未冒称 promtool 动态通过。W5 是已有证据机制的另一条漏报分支，不表示历史 W3 修复不存在。

### 本轮实际执行结果

| 检查 | 结果 |
|---|---|
| OpenSpec status / apply | spec-driven；任务 53/53；全部 contextFiles 可读；无 unavailableTrackingFiles |
| OpenSpec strict validate | valid=true，issues=[] |
| 后端白名单 | 78 个测试类、440 项通过，0 failures、0 errors、0 skipped；未运行全量 Maven 测试 |
| 后端分模块 | common：5 类/15 项；app：51 类/288 项；gateway：5 类/16 项；AI：16 类/112 项；notifier：1 类/9 项 |
| SDK 与构建 | 解除首次离线缓存来源阻塞后 app 测试构建通过；dependency:tree 完整展开官方 Stream 1.3.12 依赖链，受控筛选未见 omitted for conflict 或缺失 POM |
| 临时缺陷探针 | W5/W6 两条当前行为均复现；进程内替身和虚拟时钟，不计入 440 项回归通过数 |
| 前端相关回归 | lifecycle、users、access、api 共 4 个文件、87 项通过 |
| TypeScript / 前端构建 | npm run typecheck、npm run build 均通过 |
| 监控配置 | config.tests.ps1：163 项通过；生成 75 组主时序和额外 1 组 AI 未部署场景 |
| promtool 规则语法 / 时序 | **Not verified**：PATH 及本会话 scratch 无工具；固定官方压缩包下载两次在时限内未完成，未校验成功、未解压或运行不完整工具；生成不等于测试通过 |
| git diff --check | 报告更新前、后均通过；工作区仅本验证报告有改动 |

本轮后端先以唯一报告后缀运行离线白名单，app 被依赖来源缓存阻塞、其他 27 类/152 项通过；随后允许正常 Maven 依赖解析，仅补跑 app 51 类/288 项并成功。统计没有混入历史 XML 或重复累计；旧离线失败不冒充成功。Maven、Java 均使用当前环境的 Maven 3.9.9 / Temurin Java 21.0.7。

临时证据统一位于会话 scratch `C:\Users\Burning\.pi-desktop\scratch\7de6bb88-7988-48a3-b6fb-b828de84de3c`：

- `offboarding-backend-20261106-203537-0b5cf534`：完整白名单、命令、离线日志及本次 XML 副本；app 补跑日志 `offboarding-app-online.log`，XML 使用 `offboarding-app-recheck` 后缀。
- `offboarding-sdk-dependency-recheck.log`、`offboarding-web-checks.log`、`OffboardingBranchProbe.java`、`offboarding-ai-branch-probe.log`。
- `offboarding-monitoring-8b362a1bee6b41bd8c512cc237b6c8ce`：配置及合成时序；`offboarding-promtool-download.log`：下载超时，不是规则执行日志。

**最终评估：已运行的检查未发现 CRITICAL；有 2 项 WARNING、1 项 SUGGESTION。监控场景动态复跑及规则语法检查未验证，原因是固定工具未能取得。建议修复 W5/W6 并在工具可用时补跑规则；本报告不宣布全部验证通过或归档就绪，也不自动归档。** 未启动前端/业务/数据库/Redis/钉钉/模型/Office/监控服务，未执行文档内容断言、真实并发或部署演练。

---


## 2026-11-06 修复后复核

前次核验的 **W1–W4 均已修复**。本轮核对新增实现、关联证据计算、迁移与受控协议，并运行相关回归；修复范围内没有遗留的 CRITICAL/WARNING。下方首次核验记录保留作为问题来源，不代表当前仍存在这些缺口。

| 原问题 | 修复结果与主要位置 | 验证证据 |
|---|---|---|
| W1 输入异常不可见 | DingTalkInputRejected 固定类别、DingTalkEventParser/StreamProtocol/StreamConnection 分片与输入拒绝观测；LifecycleObservations 初始化低基数计数；KbLifecycleInputRejected | 协议和分片拒绝测试验证分类、无成功持久化及无原文日志；超限合成时序告警 |
| W2 完成事件冲突不告警 | Monitoring.Rules.psm1 增加 KbLifecycleEventConflict，读取 CONFLICT 计数，独立于积压；固定目录和通知模板同步 | completed-conflict 合成时序同时验证冲突触发、积压及依赖失败不触发；固定通知隐私测试 |
| W3 缺少接收与停止关联 | V35、OutputStopEvidenceMapper/Service/Controller、OutputStopWindow、OutputStopReporter；按事件、被撤销原代次及执行 UUID 保存关联，主库 checkedAt 配合 AI 单调时差计算停止/耗时区间 | 主库记录映射、签名操作/正文/nonce 隔离、幂等冲突、回滚不观察提交、原代次绑定、取消先于报告、缺失锚点、队列满、区间质量、采样有界和告警测试 |
| W4 摘要缺少来源 | UserLifecyclePanel.tsx:30 展示钉钉来源与连接标识；API 拒绝受管记录缺失连接标识 | jsdom 仅读权限测试：仅请求账号摘要，无写请求，无员工标识展示 |

证据口径：接收事务的主库提交前/后界限与停止区间形成可核验的耗时上下界，不比较 AI 与 app 的墙上时钟。关联下界超过 30 秒触发 KbLifecycleRevocationSlow；只有上界越界或证据不足触发不可确认。后补映射、未关联记录和缺失提交观测不计作首次已映射员工达标。提交后观测失败不推翻已提交停权；后台停止报告故障不延迟输出取消。有关协议和部署说明已更新，没有放宽 30 秒行为要求。

| 本轮检查 | 结果 |
|---|---|
| 后端回归白名单 | 57 个测试类、285 项通过，0 失败、0 错误、0 跳过；首次发现的请求体测试替身时序问题修正后，AI/通知模块已复跑通过 |
| 前端相关组件/API | 4 个文件、87 项通过 |
| 类型检查和生产构建 | 通过 |
| 监控离线验证 | 163 项配置行为检查；74 条规则语法检查；75 组主时序及 1 组可选 AI 场景通过 |
| OpenSpec strict validate / diff 检查 | 通过 |

后端最终执行证据按测试类取最近一次结果，来自临时目录 `kb-offboarding-fix-final.log` 与 `kb-offboarding-fix-ai-final.log`，没有将失败的旧运行计为通过。监控产物位于 `kb-offboarding-fix-final-monitoring-8c711507acb34893b9c31edc17941a92`。

新增 V35 迁移尚未在真实数据库执行；app/AI 的 checkedAt 与停止证据协议须协调发布，保持旧节点排空。遵循既定范围，本轮没有启动外部组件、服务或运行文档内容断言，没有执行真实跨实例验收。没有提交代码或归档。

## 首次核验记录（修复前，保留依据）

核验日期：2026-11-06。核验对象为当前工作区中的实现及本变更的 proposal、design、tasks 和 7 份增量规格，使用 `spec-driven` schema。本轮仅核验并保存报告，没有修改实现、任务勾选或执行归档。

## 结论

未发现 CRITICAL 问题，发现 **4 项 WARNING**，没有额外 SUGGESTION。核心停权、永久撤销旧会话、人工复职、应急资格保护与 AI 输出许可均有实现和进程内测试证据。49 项任务勾选完成不等同于规格全部满足；异常观测、时限证据和摘要展示仍有下述差异。

| 维度 | 核验结果 |
|---|---|
| 完整性 | 任务 49/49；39 项 ADDED/MODIFIED 要求均找到实现入口；无 REMOVED/RENAMED 要求；没有不可读或缺失的规划工件 |
| 正确性 | 已对照 39 项要求、104 个场景的代码与测试证据；W1、W2、W3、W4 所列行为未完整满足，不能计为全部通过 |
| 一致性 | 持久状态、锁顺序、认证代次、短期证明、精确权限和输出许可符合主要设计；D4/D10 的异常告警及耗时证据存在差异；未发现需要单独报告的代码模式偏离 |

约定范围内的任务完成、规格覆盖、实现映射、场景覆盖、设计遵循及代码模式检查均已执行，没有因工件缺失跳过的检查。按 verify 工作流，可带以下改进项进入归档评估；建议先修复 W1/W2，并处理 W3 的设计口径差异。此结论不是自动归档或真实环境验收通过。

## WARNING

### W1：范围外、损坏和超限事件未记录固定异常类别

- 规格依据：`specs/enterprise-account-lifecycle/spec.md:17` 的“接收到范围外事件”、`:65` 的“接收输入超过固定上限”；`design.md:76` 要求超限拒绝并告警。
- 实现位置：`kb-app/src/main/java/com/zhishiku/kb/app/auth/lifecycle/DingTalkStreamProtocol.java:43`、`:55`、`:104`；`DingTalkEventParser.java:22`、`:36`。
- 触发方式：企业/应用不匹配、非法必要字段、员工数量超限或消息超限。解析器抛出的异常在协议层直接转换成 LATER/断开；这些路径不会进入 `LifecycleReception.receive`，因而也不会增加其 FAILED 计数。协议层没有固定错误类别的日志、计数或其他异常记录。
- 影响：账号资格没有被错误修改，但接入可以持续拒绝事件而没有对应的异常分类证据；连接类告警也不能替代该记录。
- 建议：在解析/协议边界记录有限枚举的拒绝类别，并为超限接入既有受控告警流程；禁止记录原始员工、企业、载荷和凭据。扩展 `DingTalkStreamProtocolTest`，在原有拒绝断言之外验证分类计数；使用离线规则测试验证超限告警，无需启动平台或服务。

### W2：同键不同内容的冲突没有可靠的告警路径

- 规格依据：`specs/enterprise-account-lifecycle/spec.md:77` 明确要求“隔离该冲突并告警”。
- 实现位置：`kb-app/src/main/java/com/zhishiku/kb/app/auth/lifecycle/LifecycleObservations.java:34`；`kb-app/src/main/java/com/zhishiku/kb/app/auth/mapper/LifecycleInboxMapper.java:61`；`kb-app/src/main/java/com/zhishiku/kb/app/auth/lifecycle/LifecycleMetricsReader.java:29`；`ops/monitoring/scripts/Monitoring.Rules.psm1:90`、`:93`。
- 触发方式：首份事件所有员工均已 OFFBOARDED，清理完成后，再收到相同 eventId、不同内容。注册器保留首份依据并设置 conflicted，接收计数增加 CONFLICT；隔离 SQL 只将 UNMAPPED/FAILED 项改成 REVIEW，所以此例不会产生 REVIEW 积压。
- 影响：现有规则不读取 CONFLICT，依赖失败规则仅匹配 FAILED；该冲突既不触发积压告警，也不触发依赖失败告警。隔离与原限制有效，但“告警”部分未满足。
- 建议：增加或扩展固定冲突告警，直接覆盖冲突计数或持久冲突状态，不依赖逐员工项恰好处于待处理状态。同步固定通知目录/模板，并加入“原员工项全部完成且无积压仍告警”的合成时序用例。现有 `LifecycleReceptionTest.restartReplayAndSameKeyConflictPreserveFirstEvidenceAndDoNotRevokeAgain` 只证明幂等和依据保留。

### W3：接收至 AI 本地停止的证据尚未形成，当前指标是另一种耗时

- 规格依据：`specs/enterprise-account-lifecycle/spec.md:89` 要求保留可核验的接收、阻断与本地停止证据；`design.md:142` 要求接收至 AI 本地停止耗时。
- 实现位置：`kb-ai/src/main/java/com/zhishiku/kb/ai/auth/OutputPermitMetrics.java:80`；`kb-app/src/main/java/com/zhishiku/kb/app/auth/lifecycle/LifecycleObservations.java:31`；`ops/monitoring/scripts/Monitoring.Rules.psm1:88`。
- 当前行为：AI 记录从最近一次成功身份检查的请求开始至本地停止的保守上界，另有许可逾期指标；app 记录接收提交后的独立观测。两者没有关联具体撤销事实与执行停止的证据。该实现没有伪造精确耗时，`docs/lifecycle-operations.md:62`、`:68` 也诚实说明了这一限制，但设计仍要求不同的测量口径。
- 影响：当前指标可以发现许可逾期、提示 30 秒目标不可确认，不能给出指定离职接收至对应 AI 停止的可核验耗时。此项不表示短期许可已经失效，也不是要求补做被明确排除的集成测试。
- 建议：补齐受控的内部撤销/执行证据关联，并在不把用户或事件标识放入指标标签的前提下生成约定耗时；用可控时钟和跨服务调用替身核对因果关系。若决定首期仅交付保守上界，应明确修订设计和相关规格的证据口径，保留 30 秒行为要求，不能将上界当成精确实测。

### W4：账号生命周期摘要未展示受管来源

- 规格依据：`specs/admin-web-users/spec.md:27` 要求摘要区分受管来源。
- 实现位置：`kb-web/src/admin/lifecycle/UserLifecyclePanel.tsx:28`。
- 当前行为：Descriptions 展示管理类型、人工状态、生命周期限制、最终状态及更新时间/复职时间，未展示来源；读取模型已有 connectionId，只有映射对话框显示相关来源信息。只有读取权限的管理员无法通过摘要确认来源。
- 建议：在受管账号摘要展示固定的“钉钉”来源及允许展示的连接标识，不请求额外写权限接口或暴露原始员工标识；补充 jsdom 摘要展示用例。

## 要求与实现映射

以下表格用于定位，标题为规格要求的简称；“有实现”不代表没有上述差异。场景覆盖依据包括代码控制流、仓储/事务替身、可控时钟、协议测试及组件测试，不把测试数量当作场景数量。

### enterprise-account-lifecycle：11 项要求、28 个场景

| 要求 | 主要实现 | 测试/核验依据 |
|---|---|---|
| 独立且受限的接入边界 | DingTalkLifecycleSettings、DingTalkEventParser、DingTalkStreamConnection、LifecycleHttp | Settings/StreamProtocol/StreamConnection/Http 测试；异常记录缺口见 W1 |
| 独立映射和明确确认 | LifecycleMappingService.verify/map、LifecycleMappingPolicy、LifecycleProofService | MappingService/MappingPolicy/ProofService 测试 |
| 映射历史与禁止绕过 | LifecycleMappingService.requireMutable/exit、LifecycleMapper | 事件先到后补映射、受限退出、在途复职测试 |
| 可恢复的持久接收 | LifecycleReception.receive、LifecycleStateService.persist、V31 | Reception 的提交/回滚/重投/多员工测试；超限异常缺口见 W1 |
| 稳定事件身份与有界处置 | LifecycleEventRegistry、LifecycleRetryService、LifecycleInboxMapper | Registry/Retry/Reception 测试；冲突告警见 W2 |
| 三十秒访问撤销 | EpochSessionService、各入口身份检查、OutputPermitService | 会话/Office/入口/输出许可测试；关联证据见 W3 |
| 人工复职与授权独立 | LifecycleRestorationService.check/restore | RestorationService 的双重成员核验、事实变动、手工禁用、审计回滚测试 |
| 复职和迟到事件顺序 | LifecycleReception.process、恢复边界和一次性证明 | 旧事件、新事件、迟到 REVIEW、复职竞争测试 |
| 启停与应急保障 | LifecycleReadiness、ManagementInvariant、独立清理/恢复调度 | Readiness/ManagementInvariant/Retry/Cleanup 测试 |
| 回执与审计 | LifecycleAuthorizer、LifecycleReceipts、IdentityAuditService | 精确权限、撤权后重放、绑定冲突、事务失败测试 |
| 未知状态与成功区分 | LifecycleMetrics/Reader/Observations、OutputPermitMetrics | 指标采样、固定通知、合成时序测试；W1/W2/W3 补足观测差异 |

### user-authentication：6 项要求、13 个场景

| 要求 | 主要实现 | 测试/核验依据 |
|---|---|---|
| 会话绑定认证代次 | EpochSessionService、IdentityAccessState | 旧代次、缺失代次、停用/复职、重复限制测试 |
| 并发登录与新令牌 | EpochSessionService.issue、LocalAuthService、SsoSession、AuthService | 三种认证方式、候选签发后竞争、只清理候选测试 |
| 全部受保护入口核对当前事实 | EpochAuthenticationInterceptor、gateway/AI CurrentIdentityFilter、OfficeLoginAccess | app/gateway/AI 认证、预览、强制改密、401/403/503 测试 |
| 独立的服务认证 | AuthenticationSignature、InternalAuthenticationService、两侧 AuthenticationClient | caller、时间窗、nonce、令牌绑定、最小返回、失败关闭测试 |
| 清理不是撤销正确性前提 | SessionCleanupService、RedisSessionTokenIndex | 清理失败、租约恢复、旧任务不撤销新令牌测试 |
| 无代次旧会话拒绝 | EpochSessionService.recorded/check、协调升级文档 | 缺失可信元数据拒绝测试；旧节点排空为发布方案，未执行部署演练 |

### identity-department：5 项要求、14 个场景

| 要求 | 主要实现 | 测试/核验依据 |
|---|---|---|
| 人工状态与限制分离 | V29、IdentityAccessState、LifecycleStateService | 状态组合及回滚测试；静态评审迁移保留原 enabled |
| 普通启用不能代替复职 | IdentityService、LifecycleStateService、管理错误反馈 | 普通启用 409、复职保留人工停用测试 |
| 独立本地应急管理员 | ManagementInvariant、EmergencyIdentityFact、生命周期唯一行约束 | 权限跨角色并集、无效凭据/部门、角色名无效、纳管互斥测试 |
| 所有资格收缩路径与并发 | IdentityService、DepartmentService、ManagementInvariant 全局管理锁 | EmergencyMutationGuard、ManagementInvariant 的逐路径/交错测试；锁顺序静态评审 |
| 本人本地验证和轮换 | EmergencyManagementService | LOCAL/SSO/DEV 区分、他人拒绝、凭据变动、原子先建后换和回执测试 |

### enterprise-sso：2 项要求、6 个场景

| 要求 | 主要实现 | 测试/核验依据 |
|---|---|---|
| 保留手工停用和生命周期边界 | SsoSession 经 EpochSessionService 签发；SSO 成员核验与本地认证独立 | SsoSecurityService、EpochSessionService、配置独立性测试及调用路径评审 |
| SSO 绑定和纳管无隐含授权 | 生命周期映射表/服务独立于 SSO 绑定入口 | MappingService/MappingPolicy 测试及两个服务写入范围评审 |

### functional-authorization：3 项要求、9 个场景

| 要求 | 主要实现 | 测试/核验依据 |
|---|---|---|
| 四项精确权限 | Permission、LifecycleAuthorizer、管理控制器、前端 access | Authorizer、读写控制器、QueryService、前端访问矩阵 |
| 鉴权先于目标/外部/回执 | Mapping/Restoration/Emergency/Retry/Query 各入口 | 撤权后重放、无权不存在目标、provider 无调用等测试 |
| 一次性迁移与撤权持久性 | V32、AdminBootstrapRunner | 空库初始化、既有角色不补权、重复启动测试和迁移静态评审 |

### multi-turn-chat：5 项要求、16 个场景

| 要求 | 主要实现 | 测试/核验依据 |
|---|---|---|
| 新旧流式协议兼容 | ChatController、ChatService、AiObservedChat、ConversationPrivacyFilter | ChatController/ChatObservation/ChatRevocation/PrivacyFilter 测试 |
| 独立短时输出许可 | OutputPermitService | 请求发起时间、2 秒超时、迟到响应、代次/执行绑定测试 |
| 静默/暂停不能绕过期限 | Permit.tick/require/guard、最终 SSE 层 guard | 静默模型/占位、调度暂停、缓慢落库/结算、排队 done 测试 |
| 轮次执行随代次撤销 | V34、ConversationIdentityGate、TurnExecutionService | reserve/dispatch/seal/append/renew/finish、完成竞争和旧票据测试 |
| 受限取消和结算 | TurnExecutionService 的撤销收尾/恢复、MultiTurnChatService | CANCELLED、UNKNOWN/NOT_INVOKED、固定意图、失败恢复与禁止继续生成测试 |

### admin-web-users：7 项要求、18 个场景

| 要求 | 主要实现 | 测试/核验依据 |
|---|---|---|
| 部门和启停独立提交 | UsersPage、useAdminWrite、固定冲突反馈 | users/API 测试及生命周期冲突反馈测试 |
| 独立权限和最小摘要 | UserLifecyclePanel、LifecycleScope、LifecycleQueryService | 无读取资格不请求、失败不当未纳管、身份隔离测试；来源展示见 W4 |
| 明确目标和成员核对 | MappingDialog、employeeInput/verify API | 准确标识、输入变更、核验过期、冲突重新核验测试 |
| 复职核对与最终状态 | RestorationDialog | 部门/角色/密级/人工状态、503、不自动重试、未知结果原绑定测试 |
| 逐员工事件与安全重试 | LifecyclePage、事件/员工项 API | 当前页计数、未匹配、撤权、原请求未知结果测试 |
| 本人应急登记与轮换 | 应急对话框、EmergencyManagementService | 本人密码、先建后换、资格不足、成功重读测试 |
| 敏感状态作用域 | LifecycleScope、useAdminWrite、AbortController | 账号/目标/权限/可见性切换和迟到响应组件测试 |

## 本轮执行结果

| 检查 | 结果 |
|---|---|
| OpenSpec strict validate | 通过 |
| 后端相关单元测试白名单 | 50 个测试类、206 项，0 失败、0 错误、0 跳过 |
| 前端 4 个相关测试文件 | 86 项通过（jsdom/API mock） |
| TypeScript 类型检查 | 通过 |
| 前端生产构建 | 通过 |
| 监控配置行为检查 | 160 项通过 |
| promtool 离线规则检查 | 71 条规则语法通过，66 组主场景和 1 组 AI 未部署场景通过 |
| git diff --check | 通过，仅有已有文件的 CRLF 转换提示 |

后端分两批运行：第一批 37 类/148 项覆盖生命周期、会话、身份签名、AI 撤销及通知；第二批 13 类/58 项覆盖应急不变量、初始化、Office、SSO、入口配置、轮次与协议兼容。没有运行 Maven 全套测试。

测试日志位于本机临时目录：`kb-offboarding-verify-maven.log`、`kb-offboarding-verify-compatibility.log`；离线监控产物位于 `kb-offboarding-verify-ec8f7feb23a342888b694649ad3a8c7f`。这些是本次执行证据，不是需要提交的项目文件。

## 验证边界

遵循 tasks/design 中已经明确的范围：没有启动前端服务、业务服务、数据库、Redis、钉钉、模型、Office 或监控服务，没有运行文档标题/关键词内容断言，也没有执行数据库迁移、真实平台联调、真实并发或部署演练。迁移和锁顺序采用代码评审，行为采用进程内替身及可控时钟验证。

真实跨实例 30 秒达标、数据库并发行为和旧节点排空没有经过实环境验证；这些被明确排除的活动不作为本次任务缺失或新增归档条件。W3 指向的是应交付代码与设计的观测口径差异，独立于是否进行实环境验收。
