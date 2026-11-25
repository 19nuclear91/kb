# 第三轮实现核验报告

**归档补记：** 2026-11-25 按用户后续指示同步四份主规格并完成归档，见 [归档记录](archive-record.md)。以下保留第三轮 verify 当时的结果、范围和停止状态。

变更：`permission-explanation-and-authorization-preview`。日期：2026-11-25。Schema：`spec-driven`。

**最终结论：0 CRITICAL、0 WARNING、0 SUGGESTION。** 第二轮 R1 已修复，原 W1–W4 也无未关闭问题。已达到用户指定的停止条件，停止修复／verify 循环，等待进一步指示；不执行归档、提交或部署。

## 核验结果

| 维度 | 结果 |
| --- | --- |
| 完整性 | Task Completion：42/42 完成，包括新增 10.1–10.3；跟踪文件可读。Spec Coverage：28/28 ADDED 要求有实现，无 MODIFIED／REMOVED／RENAMED 条目。 |
| 正确性 | Requirement Implementation Mapping：核对 28 项要求及对应实现；Scenario Coverage：复核 95 个场景的证据映射，补充预演失败恢复的直接组件证据。未发现剩余实现偏差或约定范围内的证据缺口。 |
| 一致性 | Design Adherence：D1–D10 一致，D9 补充正式表单恢复作用域；Code Pattern Consistency：使用现有 Vue 组合式函数、父级 provide/inject、Session epoch 和资源反馈模式，未扩展服务端契约或写入协议。 |

本轮重新读取 proposal、四份 delta specs、design、tasks、历史报告及修复记录，逐项复核原需求／场景映射，并检查本次改动影响的准入、私有状态、正式草稿、写入协调和组件生命周期。完整导航见 [traceability.md](traceability.md)，未改变部分的详细实现位置及方法证据见 [第二轮报告](verification-report-2.md) 和 [首次修复记录](remediation.md)。

95 个场景的证据包含独立组件测试、规则／查询结果替身和约定的人工审阅，不表示有 95 个一一对应的自动化用例，也不表示真实环境验收通过。六项核验检查均有可用证据；用户排除的验证层次单列于文末。

## R1 修复与复核

| 问题环节 | 当前实现 | 核验证据 |
| --- | --- | --- |
| 403／404 资格重核卸载表单 | `kb-web/src/admin/aclRecovery.ts:14` 在资源详情页保存一次待恢复的正式表单；`DocumentPanel.vue:34`、`SpaceDetail.vue:35` 提供真实身份、归属和管理读取状态。 | 真实权限 store、ACL 组件及实际文档／空间详情页均通过恢复回归。重核仍执行，不能借恢复状态绕过当前准入。 |
| 主体、权限与操作丢失 | `AclPanelContent.vue:80` 在重核前保存动作、主体和权限；`:105` 等待管理资格与 ACL 读取成功后消费一次恢复记录。空间批次继续使用既有正式草稿。 | USER 8／download 原内容恢复后可直接主动提交；文档单条撤销和空间批量撤销仍使用原路径、HTTP 方法和完整请求体。 |
| 候选首页不能表示原选择 | `PrincipalPicker.vue:12` 接受正式草稿的初始主体，`:28` 将该主体保留在选项中；类型／搜索条件改变时清理。 | 候选首页为空仍保留原主体和 download；改变搜索条件后主体清空并禁用无主体提交。未添加任意 ID 输入，原服务端写校验继续生效。 |
| 提示失去可见宿主 | `authorization/DiagnosticHost.vue:15` 使用身份／资源级共享固定通知；`:33` 根据管理资格和当前可见区块显示。 | 文档和空间预演 403／404／405 均保留提示；仅诊断资格被撤销时仍可看到固定提示并按原资格提交。 |
| 恢复范围过宽或重复恢复 | `aclRecovery.ts:17` 在身份变化、确认失权及归属改变后清除记录，`:20` 随详情页销毁清除；ACL 消费后立即清空。 | 失权、搬迁、身份刷新、离页及明确关闭后的重核均不能恢复旧文档表单；恢复过程没有自动正式写入或新增预演。 |
| 诊断和未知写入互相干扰 | 恢复对象仅含正式草稿，不保存诊断目标、来源、结果或写入回执；既有只读 generation 和正式写入 binding 各自管理。 | 原失权／迟到成功测试、未知写入原 body 与 X-Request-Id 重试测试均通过；本次恢复后目标选择已清空。 |

对应规格：`authorization-diagnostics` 的只读／正式写独立及未升级端点场景，`admin-web-spaces` 的可选预演与故障场景，`admin-web-documents` 的写入协调及生命周期要求。设计 D9 的可选预演和资格重核要求均满足；无须降低规格或取消资源重核。

## 需求与设计复核范围

| 能力 | 要求／场景 | 本轮处理 |
| --- | --- | --- |
| authorization-diagnostics | 14／49 | 核对既有准入、最小目标、来源、受控投影、当前发布层、精确意图、一致只读读取、限额与通信证据；对正式写独立和旧端点反馈增加本轮恢复证据。 |
| functional-authorization | 3／12 | 独立精确 code、真实资源范围、一次性初始化的实现及既有离线证据继续成立。恢复草稿不产生任何新资格。 |
| admin-web-spaces | 6／18 | 重跑资格、来源、完整草稿、前后展示、故障隔离及失效回归；补充批量草稿 403／404／405、撤销恢复和实际详情页用例。 |
| admin-web-documents | 5／16 | 重跑功能／归属、当前发布展示、单项例外、父级写入协调和生命周期回归；补充原草稿恢复、撤销动作、候选缺席及四种清理边界。 |

D1–D8 的准入、最小选择、类型隔离、共享规则、只读事务、意图、投影与限额保持原实现和证据；D9 的恢复缺口已由上述实现和测试关闭；D10 的验证范围始终保持离线。新恢复模块放在既有 `admin` 层，使用仅当前详情页存活期间有效的内存状态，不增加浏览器持久化、网络调用或后端依赖。

原 W1 的严格解码合法／损坏对照在本轮白名单再次通过；W2 由独立入口及预演弹窗两组用例完整覆盖；W3 原补充场景证据保留，前端相关用例再次通过；W4 搜索服务输入分层及原架构规则保持第二轮核验状态。

## 实际验证结果

| 检查 | 结果与证据归属 |
| --- | --- |
| 本轮前端最终白名单 | **9 文件、145 项通过，0 失败、0 跳过**；包含 `recheck.test.ts` 的 21 项，较上一轮增加 16 项。 |
| 本轮恢复定向回归 | 21/21 通过，包含在最终 145 项中，不重复累计。 |
| 本轮类型检查 | `vue-tsc --noEmit` 通过。 |
| 本轮静态生产构建 | Vite 通过，2228 modules。 |
| 本轮包体隔离 | 通过；入口累计 gzip 134669 字节，普通文档列表 241026 字节，诊断场景 142474 字节。 |
| 本轮 OpenSpec strict | 通过。最终任务状态另由 apply instructions 核对为 42/42、all_done。 |
| 后端既有白名单 | 沿用第二轮 **25 类／129 项通过** 的证据（app 123、common 1、gateway 5；含 ModuleBoundaryTest 13 项）。本次无后端改动，未重复执行，不计为本轮新测试。 |
| 包体工具单测 | 沿用首次修复的 13 项通过证据；本轮运行实际包体隔离，不重复计工具单测。 |

测试开发过程中，实际详情页的两个新用例曾在预演前失败：无关管理区块统一返回 403 的替身触发了额外资格重核，导致刚打开的表单卸载。将这些独立依赖的替身改为 503 并等待初始读取完成后，两个用例均通过；断言、生产页面和原重核逻辑均保留。该阶段结果不混入最终 145 项通过数。

本轮最终命令（前端命令在 `kb-web` 执行）：

```powershell
node node_modules/vitest/vitest.mjs run src/authorization/access.test.ts src/authorization/api.test.ts src/authorization/state.test.ts src/authorization/panel.test.ts src/authorization/layer.test.ts src/authorization/recheck.test.ts src/admin/space-pages.test.ts src/admin/spaceApi.test.ts src/admin/MultiPrincipalPicker.test.ts
node node_modules/vue-tsc/bin/vue-tsc.js --noEmit
node node_modules/vite/bin/vite.js build
node scripts/bundle-report.mjs --check-isolation --out .bundle-analysis/authorization-diagnostics.json

# 仓库根目录
openspec list --json
openspec status --change permission-explanation-and-authorization-preview --json
openspec instructions apply --change permission-explanation-and-authorization-preview --json
openspec validate permission-explanation-and-authorization-preview --strict
```

## 结论和边界

CRITICAL：0。WARNING：0。SUGGESTION：0。R1 已关闭，约定范围内的核验通过；本变更具备进入后续归档流程的条件，当前按用户要求停止并等待指示。

未启动应用、HTTP 服务或真实浏览器，未访问数据库、Redis、对象存储或外部服务；只使用独立组件、Mock／Stub、内存传输、可控 Promise 和 jsdom。没有执行文档内容断言、SQL 文本断言、迁移或前后端联调。

真实 SQL 授权等价、数据库约束／锁／隔离、V43 执行、真实网络与跨实例、真实浏览器及前后端联调仍未验证。这些仍是用户明确排除的层次，不新增为告警或任务；本报告不将离线通过表述为真实环境验收。未提交、推送、同步主规格、归档或部署。
