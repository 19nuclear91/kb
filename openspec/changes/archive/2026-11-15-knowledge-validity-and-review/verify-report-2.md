# 第二轮独立核验：knowledge-validity-and-review

日期：2026-11-15；工作流：`spec-driven`；对象：整改后的当前未提交工作区。本轮重新取得 CLI 状态，读取全部规划产物，审查实际实现及测试，并独立复跑离线检查。未修改业务代码、正式测试、任务或规格；未执行迁移、部署、同步主规格或归档。

以上为核验时点记录。随后经用户确认完成的主规格同步与归档见[归档记录](archive-record.md)，不补造本轮未验证证据。

## 结论与评分卡

**已执行检查中，CRITICAL / WARNING / SUGGESTION 均为 0；原 W1、W2、S1 已确认关闭。** Requirement Implementation Mapping 与 Scenario Coverage 各有部分 **Not verified**，因此不宣称所有动态验收通过或可归档；未验证范围不是本报告新增的归档前置条件。

| 维度 | 本轮结果 |
|---|---|
| Completeness | Task Completion：37/37，有完整任务描述、无不可读跟踪文件；Spec Coverage：42/42 项 ADDED 要求找到实现或迁移交付位置，没有整体缺失实现 |
| Correctness | 42 项要求的意图与 110 个场景均已核对代码/测试证据，未发现新偏差。Requirement Implementation Mapping（真实数据库约束、资格 SQL、锁与持久化效果）部分 **Not verified**；Scenario Coverage（组合场景的完整动态链路、真实环境）部分 **Not verified** |
| Coherence | Design Adherence：D1–D11 的目录、公开服务依赖、日期、锁/事务编排、最小投影、前端生命周期和路由/审计静态设计一致；Code Pattern Consistency：未发现显著模式偏离；实际部署/回退未演练，不计作设计已在生产生效 |

六项适用检查中，四项完成所述静态/任务检查，两项为部分未验证。没有可读产物被跳过；没有有意省略、缺失或空的规格/设计。九份增量规格均为 ADDED，合计 **42 项要求、110 个场景**；没有 MODIFIED、REMOVED 或 RENAMED 要求，不需要恢复删除/重命名基线。CLI `all_done` 只证明任务跟踪完成，不代替本轮正确性结论。

## 原问题的独立复核

| 原问题 | 当前实现与证据 | 判定 |
|---|---|---|
| W1：适用期重读丢失原绑定 | `kb-web/src/validity/ApplicabilityPanel.vue:18–31,48–49` 将写状态与原草稿置于父级、编辑器独立于读取数据分支；`ApplicabilityEditor.vue:12,16–27` 使用父级状态，未知结果不能新提交。`kb-web/src/validity/detail.test.ts:49–104` 直接驱动旧声明/新声明/503 重读、关闭恢复、原精确载荷/原 ID 重试、显式放弃及身份/目标/隐藏隔离 | 关闭；重读不视为回执或放弃 |
| W2：竞争错误码与鉴权顺序冲突 | 当前规格 `specs/document-stewardship/spec.md:189,195–199` 与 D5 已按用户确认采用先鉴权。`kb-app/src/main/java/com/zhishiku/kb/app/document/stewardship/service/StewardshipConfirmService.java:50–67` 先当前责任人/资格/可读性，再回执或版本。`kb-app/src/test/java/com/zhishiku/kb/app/document/stewardship/StewardshipConfirmTest.java:83–139` 覆盖确认先完成时 409、改派先完成时 403、仍合格的陈旧新请求 409、撤权/不可访问及旧匹配重放被拒绝 | 关闭；不再按旧“一律 409”契约重复报错，不代表真实并发已验证 |
| S1：管理对话框关闭后无原重试入口 | `kb-web/src/admin/StewardshipBox.vue:18–27,48–49` 父级保留写状态/action，关闭仅隐藏，可继续核对；`ResourceWriteDialog.vue:17,26–35,40–41` 接收父级状态并冻结未知结果的新提交。`kb-web/src/admin/stewardship-box.test.ts:38–78` 覆盖关闭/恢复/旧值/新值/503、原 ID/载荷、显式放弃、删除独占和迟到隔离 | 关闭；关闭与重读不释放删除互斥 |

补充核对：`kb-web/src/validity/useValidityWrite.ts:8–18` 同步整页显式放弃、强制改密和隐藏清理；`kb-web/src/admin/hooks.ts:89–136,164–180` 的 reset 与 discard 区分“放弃核对”和“离开上下文”，旧在途请求仍按实际结果结算；`kb-web/src/documents/operations.ts:25–53` 在途/适用期复审未知结果继续阻止删除。未把前端 abort 当作服务端撤回。

这些组件回归使用真实组件和共用写状态，仅替换 API 读/写边界；不把 HTTP 回执解析、真实权限、数据库提交或浏览器事件计作这些测试的直接证据。

## 完整性与实现定位

本轮按下列每项要求检查实现位置与意图；表格表示实现定位/静态核对，不等于全部运行环境验收。

路径简称：**A** = `kb-app/src/main/java/com/zhishiku/kb/app/document/applicability/`；**S** = 同级 `stewardship/`；**W** = `kb-web/src/`；**AX** = `kb-app/src/main/resources/mapper/applicability/ApplicabilityMapper.xml`；**SX** = `kb-app/src/main/resources/mapper/stewardship/StewardshipMapper.xml`。

| 规格/要求 | 实现位置 |
|---|---|
| document-applicability：固定版本独立声明 | AX:58–85；A `service/ApplicabilityWriteService.java:79–96`；上传/发布路径不写适用期表 |
| 按业务日期读取状态 | A `service/BusinessDate.java:14–38`、`model/ApplicabilityState.java:13–30` |
| 沿用版本当前访问规则 | A `service/ValidityAccess.java:33–60`、`ApplicabilityReadService.java:36–51` |
| 候选编辑与审核冻结 | A `service/ApplicabilityWriteService.java:48–66`、AX:45–56 |
| 已发布附原因更正与永久标记 | A `service/ApplicabilityWriteService.java:61–87`、AX:87–98 |
| 历史不可删、不暴露操作者 | A `service/ApplicabilityReadService.java:54–75`、AX:100–105；V39 仅交付，触发器效果未验证 |
| 严格输入 | A `support/ValidityInput.java:40–120,154–195` |
| 请求绑定与拒绝冲突 | A `service/ValidityCommands.java:37–63`、`ApplicabilityWriteService.java:58–76` |
| 声明/历史/审计/回执原子提交 | A `service/ApplicabilityWriteService.java:42–97`；事务代理/快照故障测试，不是真实数据库回滚 |
| 批量逐项鉴权、不泄露 | A `service/ApplicabilityReadService.java:78–108`、AX:70–79 |
| 有界安全失败 | A `web/ValidityAdvice.java:25–47`、ValidityBody/ValidityResponseFilter、ValidityInput；生产日志保护 |
| document-stewardship：单责任人、不构成授权 | SX:74–94、117–125；S 写入只改复审事实，不改 ACL |
| 每次判定当前资格 | SX:4–17、100–108；S `service/StewardshipQueryService.java:54–60` |
| 当前空间管理员指定/改派/取消 | S `service/StewardshipAccess.java:26–48`、`StewardshipWriteService.java:45–88` |
| 管理读取最小投影 | S `service/StewardshipQueryService.java:45–60`、model/StewardshipViews |
| 最小候选、不借用身份目录 | S `service/StewardshipCandidateService.java:27–44`、SX:104–109 |
| 确定周期/基准/月末/首次日期 | S `model/StewardshipSchedule.java:10–39`、SX:90–94 |
| 按业务日期读取复审状态 | S `model/StewardshipState.java:13–28` |
| 仅当前合格责任人确认 | S `service/StewardshipConfirmService.java:42–84` |
| 本人当前负责且可读清单 | S `service/StewardshipQueryService.java:63–87,132–151`、SX:111–134 |
| 精确数量与清单一致 | S `service/StewardshipQueryService.java:90–99`、SX:117–138 共用 WHERE |
| 空间有界 ID 级清单 | S `service/StewardshipQueryService.java:102–129`、SX:140–163 |
| 删除/归档/搬迁保留事实 | S 访问/查询使用当前文档空间；SX:64–72、117–121、145–147；既有删除/搬迁不写复审表 |
| 严格请求绑定、原子提交、先鉴权 | A ValidityCommands；S 两写服务的 READ_COMMITTED 与鉴权→重放/版本→审计→回执 |
| 有界安全失败 | S support/StewardshipInput、web 控制器；A 严格输入/Advice/ResponseFilter |
| audit-query：进入既有审计范围 | `kb-app/src/main/java/com/zhishiku/kb/app/auth/service/AuditScope.java:116–118`、IdentityAuditService、AuditQueryService |
| 固定安全摘要投影 | `kb-app/src/main/java/com/zhishiku/kb/app/audit/service/AuditSummaryProjector.java:141–146,202–219` |
| user-web-documents：详情/历史标识 | W `pages/DocumentDetail.vue:66,200–209`、validity/ApplicabilityPanel、ApplicabilityHistory |
| 独立编辑与核对回执 | W `validity/ApplicabilityEditor.vue:12–38`、`validity/api.ts:149–163`；原 W1 复核见上表 |
| 详情页操作协调 | W `documents/operations.ts:25–53`、`validity/useValidityWrite.ts:8–18` |
| 搜索按固定版本批量标识 | W `search/SearchWorkspace.vue:30–35,54–60,94,119–125` |
| user-web-conversations：实时/历史固定引用标识 | W `assistant/ConversationContent.vue:39–41,230,235`、validity/useValidityBatch、ui/TurnCard/References |
| 模型不感知说明与身份隔离 | W `ui/References.vue:19–21`；ConversationContent quarantine/卸载；useValidityBatch:7–15 |
| user-web-document-review：只读原声明/冻结/故障隔离 | W `reviews/ReviewContext.vue:65–69` 与 readonly ApplicabilityPanel |
| user-web-document-stewardship：服务端本人清单 | W `stewardship/StewardshipPage.vue:18–41`、admin/useCursorList |
| 共享数量、身份隔离与后台暂停 | W `stewardship/count.ts:6–9`、`stewardship/poller.ts:19–43` |
| 明确确认与未知结果核对 | W `stewardship/ConfirmationDialog.vue:10–20`、validity/api.ts、useAdminWrite |
| 本人页面身份与迟到隔离 | W `stewardship/StewardshipPage.vue:23–27`、带 key 的确认对话框及 useCursorList |
| admin-web-documents：管理清单控制区块 | W `admin/DocumentPanel.vue:30–32,91–96`、StewardshipBox 独立读取 |
| 显式责任人写入与核对 | W `admin/StewardshipBox.vue:22–37,48–52`、StewardPicker、ResourceWriteDialog；原 S1 复核见上表 |
| 责任人写入/删除互斥 | W `admin/DeletionBox.vue:14,22–24`、documents/operations.ts；不解除旧元数据既有规则 |
| admin-web-spaces：ID 级复审概况 | W `admin/SpaceDetail.vue:33,42`、`admin/SpaceStewardship.vue:15–25` |

任务交付包括 V39、配置、文档和路由/模块测试。文件存在与静态编排已核对，文档/迁移 SQL 未做内容断言。配置扫描沿用 @ConfigurationPropertiesScan；网关新路径仍在既有 documents/admin 前缀，不新增路由或功能权限。公开服务依赖、具体 import、控制流大括号、父级写状态与懒加载结构符合周边模式。

## 场景覆盖与证据口径

九份规格的场景数依次为：document-applicability 34、document-stewardship 36、audit-query 4、user-web-documents 10、user-web-conversations 5、user-web-document-review 3、user-web-document-stewardship 8、admin-web-documents 7、admin-web-spaces 3，合计 110。

已逐项用当前 [traceability.md](traceability.md) 定位后再核对生产分支及对应测试；没有把映射本身或历史记录作为通过结论：

- **直接离线证据**：日期/状态/输入；当前访问拒绝；请求绑定、重放、版本冲突与写入顺序；实际事务注解/代理配合内存快照的故障回滚；控制器响应；组件状态、标识故障、原 ID 恢复及迟到隔离。
- **组合证据**：新版本不继承、撤回后重送审、AUTO/REVIEW_REQUIRED 的真实空间更正、责任人生命周期恢复、搬迁/归档恢复、SQL count 减一、新审计事件经既有查询、前端历史完整分页、本人改派续页、真实 SSE/审核资格等。实现条件存在，但没有独立完整链路实验，不计作直接动态通过。
- **仅交付/未验证**：V39 执行、不可修改触发器实际拒绝、数据库/JDBC/锁/索引效果与部署回退。
- **BoundSql**：加载生产 XML 并检查映射/绑定/超时/资格与共享 WHERE；不执行 SQL，共用 CSV 不证明 Java/SQL 的实际等价性。

## 本轮实际执行

全部离线；未启动 app、gateway、数据库、Redis、容器、浏览器或开发服务器。后端仅运行以下明确白名单与全模块测试编译，不运行可能包含监听型/可选真实库用例的全量后端入口：

```powershell
mvn -o -q -pl kb-app,kb-gateway -am test '-Dtest=ProductionSettingsTest,BusinessDateTest,ApplicabilityStateTest,StewardshipScheduleTest,ApplicabilityMappingTest,ApplicabilityInputTest,ApplicabilityWriteServiceTest,ApplicabilityReadServiceTest,ApplicabilityBatchQueryTest,ApplicabilityControllerTest,ValidityTransactionTest,StewardshipMappingTest,StewardshipCandidateTest,StewardshipAssignmentTest,StewardshipConfirmTest,StewardshipQueryTest,StewardshipControllerTest,AuditScopeTest,AuditSummaryProjectorTest,ModuleBoundaryTest,DocumentVersionAccessServiceTest,VersionAccessPurposeTest,AuditQueryServiceTest,ValidityRouteTest,ReviewRouteTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
mvn -o -q test-compile '-DskipTests' '-Dstyle.color=never'
```

仅统计本轮 21:57:56–21:58:12 的白名单对应 Surefire XML，不累计整改定向或历史报告：

| 模块 | 类 | 测试 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|---:|
| kb-common | 1 | 98 | 0 | 0 | 0 |
| kb-app | 22 | 118 | 0 | 0 | 0 |
| kb-gateway | 2 | 2 | 0 | 0 | 0 |
| 合计 | **25** | **218** | **0** | **0** | **0** |

从 `kb-web` 执行：`npm test -- --run`、`npm run typecheck`、`npm run build`、`npm run bundle:test`、`npm run bundle:report -- --check-isolation --out .bundle-analysis/current-report.json`。

- 全量前端：21:57:53 开始，136.34 秒，**122 文件/1343 项通过**，没有失败或跳过；未排除测试。
- 类型检查、生产构建、八模块 test-compile 均退出码 0；包体工具 **11 项通过**，隔离检查退出码 0。
- 入口累计 gzip 132864 字节，本人页面 153596 字节，增量 20732 字节；本人页面不在入口静态闭包，普通/管理双向隔离检查通过。这不是实际网络首屏性能证据。
- 严格规格校验返回 `Change 'knowledge-validity-and-review' is valid`；`git diff --check` 退出码 0。
- 本轮没有失败重跑、临时问题复现测试或业务修复；仅保留 Byte Buddy/JVM/Spring logging 与 Windows LF→CRLF 的非阻塞提示。

## 问题等级

- **CRITICAL：0**。无未完成任务、整体缺失要求实现或需要恢复基线的删除/重命名项。
- **WARNING：0**。已执行静态/离线检查未发现新的规格、设计偏差或显然缺失的场景处理；原 W1/W2/S1 不再作为未解决问题。
- **SUGGESTION：0**。未发现值得单独提出的显著代码模式偏离；不以样式细节或有意禁止的真实环境实验新增整改事项。

## Not verified 与最终判断

1. **Requirement Implementation Mapping 部分 Not verified**：V39 执行、JDBC 日期映射、检查/外键约束、不可修改触发器、真实隔离/锁竞争/回滚、资格 SQL 与状态谓词、索引和执行计划均未验证。替身与 BoundSql 不证明数据库或多实例即时权限效果。
2. **Scenario Coverage 部分 Not verified**：上述组合场景的完整跨层动态链路，以及实际浏览器、Redis/对象存储、真实 SSE/模型、发布/搬迁/归档恢复、混部与部署回退均未执行。没有把 110 个场景或 1343/218 项测试等同真实端到端验收。

**最终判断：已执行检查未发现 CRITICAL、WARNING 或 SUGGESTION；原三个问题关闭。Requirement Implementation Mapping 与 Scenario Coverage 的上述部分仍 Not verified，不宣称“全部检查通过”或“可归档”。本轮只新增核验报告并保留历史记录，不修改业务、同步规格或归档。**
