# 核验问题修复与复核

**最新状态：** [第三轮报告](verification-report-3.md) 记录预演弹窗恢复 R1 的修复及最终核验，结果为 0 CRITICAL、0 WARNING、0 SUGGESTION，前端最终白名单 145/145 通过。[第二轮报告](verification-report-2.md) 保留 R1 的失败复现；下文是首次修复阶段的历史证据。

日期：2026-11-25。用户要求修复 [初次核验报告](verification-report.md) 的全部问题，包括既有搜索分层失败。修复范围记录在 tasks.md 第 9 节；原 34 项加 5 项修复任务，共 39 项。

当前 W1–W4 均已关闭。本轮受影响白名单后端 **129/129**、前端 **129/129** 通过，失败、错误、跳过均为 0。类型检查、生产静态构建、包体工具及隔离检查通过。初次报告中的 4 项 WARNING、111/112 后端结果和临时失败复现保留为历史，不代表修复后状态。

## 修复与有效证据

| 问题 | 修改 | 本轮复核 |
| --- | --- | --- |
| W1 矛盾响应被接受 | `kb-web/src/authorization/api.ts` 把目标 ID 传入来源校验，拒绝其他用户的直接来源；双向核对 action、outcome 与前后来源。对目标本人及已披露的角色／部门命中关系验证必然存在的行，不推断未披露成员关系。 | `api.test.ts` 的四个合法操作对照覆盖 ADDED／重复授予／REMOVED／缺失撤销；拒绝已有行标新增、本人新增但 after 缺失、错误 no-op、错误撤销及其他 USER 来源。另验证已披露角色交叉约束、无关角色及身份 BLOCKED 的合法边界。原三个探测漏洞均进入正式拒绝用例。 |
| W2 404 提示丢失 | `DiagnosticPanel.vue` 上报固定状态，由 `DiagnosticHost.vue` 在清理私有选择之前保存固定提示；复用按 Session／epoch／资源隔离的内存反馈。403／404 仍有界重核，405 显示不可用；重新诊断仍需主动打开和选择。 | 新 `recheck.test.ts` 使用真实 `AdminAccessProvider`／store、资源读取及 ACL 面板，内存传输模拟 404／405，确认父级重挂载后提示仍存在、不自动重发诊断、原授权仍可主动提交。 |
| W3 场景覆盖缺口 | 新增或扩展既定离线组件测试；`fixtures.ts` 按具体来源和操作构造合法前后结果。`DiagnosticPanel.vue` 将授权／访问结论与来源变化分开比较，来源减少而能力不变时给出明确提示。 | 逐场景具体方法见下表。 |
| W4 搜索反向依赖 | 新增 `search/model/AssistedSearchInput`，接收规范化条件及累计选择，两个搜索服务只依赖服务输入；HTTP 请求保留原平坦契约，由控制器转换。同步补充受影响接口及嵌套成功模型的 OpenAPI 描述。 | 原 `ModuleBoundaryTest` 13 项全部通过，未改测试或增加忽略项；`AssistedSearchInputTest`、`AssistedSearchResponseTest`、`AssistedSearchServiceTest` 13 项通过，覆盖请求转换、原文／辅助模式、过滤条件、模型替身、累计选择及失败处理。 |

## W3 场景证据补充

后端测试路径均为 `kb-app/src/test/java/com/zhishiku/kb/app/auth/service/diagnostics/`；前端均为 `kb-web/src/authorization/`。

| 原缺口 | 本轮直接证据 |
| --- | --- |
| 全局 manage 不隐含阅读 | `DiagnosticEvaluationTest.globalManageHasOnlyFixedManagementSourceAndNeverGrantsReading` 传入 globalManage=true，检查固定来源、manage 引用、其他权限未命中，以及 view/download 拒绝原因。 |
| 两个角色来源及移除一项 | `DiagnosticEvaluationTest.twoRoleSourcesRemainDistinctAndRevokingOneKeepsTheOtherEffective` 验证两个 ROLE 来源均存在，撤销一项后保留另一个并继续允许查看。 |
| 归档保留 manage | `DiagnosticEvaluationTest.archivedResourcePreservesManageAndBlocksAllOtherPermissions` 检查归档时 manage PASSED、其余五项及文档权限受阻；`panel.test.ts` 的归档新增授权用例覆盖渲染。 |
| 跨操作者／资源／q／epoch 游标 | `DiagnosticServiceTest.minimalSearchEscapesLiteralQueryAndCursorBindsIdentityEpochAndResource` 保留原条件续页成功对照，分别改变资源、q、actor、epoch 后拒绝，并确认未查询候选。 |
| 合法空间附选及搬离 | `DiagnosticAccessTest.spaceObservationStaysInPathSpaceEvenWhenActorManagesBothSpaces` 覆盖合法同空间资源返回、移出后旧空间拒绝、新空间合法。 |
| ID 清单选择与分页绑定 | `panel.test.ts`“同空间文档选择只读取 ID 清单，分页后按明确选择绑定诊断并清除旧结果”检查列表路径、游标、请求中的 documentId、无标题读取及改变观察文档后清除结果。 |
| 完整合法前后差异 | `api.test.ts` 四个合法操作夹具及矛盾负例；`panel.test.ts`“完整撤销预演显示个人来源拟移除、部门来源保留和访问结论不变”；另有归档／密级两项新增来源但仍不可读的完整响应渲染。 |
| 真正的资格重核 | `recheck.test.ts`“诊断 403 后仅失去诊断资格时清除目标和结果，保留授权操作”；“诊断 404 后资源管理范围失效，旧响应不能恢复来源，恢复后不自动诊断”。测试使用真实 store 的 loading／ready 变化及父级重挂载。 |
| 未知写入与迟到诊断 | `recheck.test.ts`“未知正式写入跨真实资格重核保留原请求标识，迟到诊断不能解除绑定”实际发起内存传输写入失败，重核后重开表单，断言重试 body 与 X-Request-Id 相同、无新增预演。 |

上述补充关闭了初次报告 W3 指明的离线缺口。它们分别是服务结果替身和组件交互证据，不声称全部 95 个规格场景都具有独立自动化测试；原需求矩阵中的 SQL、迁移及版本指针语义仍保留人工审阅标识。

## 最终检查

| 检查 | 结果 |
| --- | --- |
| 后端扩大白名单 | 25 类／129 项通过：kb-app 22 类／123 项、kb-common 1 类／1 项、kb-gateway 2 类／5 项；0 failures、0 errors、0 skipped。 |
| 其中诊断后端 | 7 类／34 项通过，包含于上行，不重复累计。 |
| 其中原架构检查 | `ModuleBoundaryTest` 13 项通过，原 25 条反向依赖已消除。 |
| 前端白名单 | 9 文件／129 项通过，无失败、跳过。 |
| vue-tsc | 通过。 |
| Vite production | 通过，2227 modules。 |
| 包体分析工具 | 13 项通过。 |
| 包体隔离 | 通过；入口／登录累计 gzip 134670 字节、普通文档列表 241033 字节、诊断场景累计 142476 字节。诊断实现继续按需加载。 |
| 最终 Java 编译 | 对 OpenAPI 模型名称补充后执行 `mvn -o -pl kb-app -am compile -DskipTests`，通过；此命令只计编译，不增加测试计数。 |
| OpenSpec strict | `openspec validate permission-explanation-and-authorization-preview --strict` 通过。 |
| 差异空白检查 | 已跟踪差异及本次新增实现、测试、规划文件检查通过。 |

后端计数只取本次命令列出的类；不累加 target 内历史 XML，也不重复计算针对性重跑。编译中原有 deprecated／unchecked、MetadataAccessTest varargs 和运行时 agent 提示未作为失败隐藏。

仓库根目录：

```powershell
mvn -o -fae -pl kb-app,kb-gateway -am test '-Dtest=DiagnosticPermissionTest,DiagnosticAccessTest,DiagnosticTargetLoaderTest,DiagnosticInputTest,DiagnosticEvaluationTest,DiagnosticBoundaryTest,DiagnosticServiceTest,PermissionCatalogTest,TermPermissionTest,PermissionServiceTest,IdentityServiceTest,DocumentCapabilitiesServiceTest,DocumentCitationServiceTest,DocumentReviewDecisionTest,VersionAccessPurposeTest,VersionAccessRulesTest,DocumentCitationMapperTest,DocumentVersionProjectionTest,NotificationMappingTest,ModuleBoundaryTest,SearchRouteTest,SearchTermGatewayProtectionTest,AssistedSearchServiceTest,AssistedSearchInputTest,AssistedSearchResponseTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

`kb-web` 目录：

```powershell
node node_modules/vitest/vitest.mjs run src/authorization/access.test.ts src/authorization/api.test.ts src/authorization/state.test.ts src/authorization/panel.test.ts src/authorization/layer.test.ts src/authorization/recheck.test.ts src/admin/space-pages.test.ts src/admin/spaceApi.test.ts src/admin/MultiPrincipalPicker.test.ts
node node_modules/vue-tsc/bin/vue-tsc.js --noEmit
node node_modules/vite/bin/vite.js build
node --test scripts/bundle-report.test.mjs
node scripts/bundle-report.mjs --check-isolation --out .bundle-analysis/authorization-diagnostics.json
```

## 范围与状态

只使用独立单元／组件、Mock／Stub、内存传输、受控事务／时钟和 jsdom，没有启动应用、HTTP 服务、真实浏览器、数据库、Redis、对象存储或容器；没有文档内容或 SQL 文本断言测试。

真实 SQL、约束／锁／隔离、V43 迁移、跨实例及前后端联调仍未验证，继续遵循用户明确排除范围。本轮未提交、推送、同步主规格、归档或部署。
