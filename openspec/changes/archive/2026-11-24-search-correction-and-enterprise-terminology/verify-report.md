# 核验报告：search-correction-and-enterprise-terminology

日期：2026-11-24。Schema：`spec-driven`。规划与实现范围：`D:/WorkProject/kb`，repo-local。

> 后续修复（2026-11-25）：W4 已按用户要求修复，新增前后端回归并完成定向验证，见 [W4 修复记录](../../../../docs/search-terminology-w4-fix.md)。下方所有核验轮次及警告数保留 2026-11-24 的历史事实，不代表当前 W4 仍未解决；本次没有重新执行原变更的完整核验。

> 归档状态：第二轮核验交付后，用户明确要求先同步主规格再归档。已于 2026-11-24 同步全部 10 份主规格并归档到本目录，保留未修复 W4；主规格严格校验 76/76 通过。本报告下方“不归档／不同步”等表述记录各核验轮当时的执行边界，不代表当前工作流状态。详见 [archive-record.md](archive-record.md)。归档不修改本报告的 **0 CRITICAL / 1 WARNING / 0 SUGGESTION** 结论，也不表示部署或真实环境验收。

## 第二轮完整核验（最新结论）

**0 CRITICAL / 1 WARNING / 0 SUGGESTION。** 原 W1～W3 已重新核对并保持关闭；发现新的 **W4：已替换片段与另一处自动扩展混合时，前端和实际计划的“已存在表达”判定不一致**。任务仍为 51/51，没有整项需求缺失，但本次不是无问题通过。

本轮读取全部规划产物，重新核对当前实现及已有场景证据，并重新执行完整显式离线白名单、编译、类型检查、生产构建和隔离。没有修改生产实现、正式测试或任务复选框，没有同步主规格或归档；新增诊断文件只位于忽略目录。下方修复轮与首次核验记录保留为历史，不代表最新警告数。

### 三维记分表

| 维度 | 当前结果 |
| --- | --- |
| Completeness | Task Completion：**51/51**，taskTrackingConfigured=true，所有任务描述可读，无不可用跟踪文件。Spec Coverage：**57/57 找到实现**，51 ADDED／6 MODIFIED／0 REMOVED／0 RENAMED。 |
| Correctness | Requirement Implementation Mapping：**57 项核对**，W1～W3 修复符合原需求，发现 W4。Scenario Coverage：**168 个命名场景**已按当前实现、已有测试及直接／组合／仅交付边界复核；额外混合窗口由诊断确认且缺少正确行为回归，不把 419 项全绿当作完全正确。 |
| Coherence | Design Adherence：D1～D11 已核对，W4 是 D4／D7 的计划与响应校验一致性偏差，其余未发现显著矛盾。Code Pattern Consistency：新代码可识别，目录、命名、具体 Java import、生命周期和共享组件模式无显著偏离。 |

**六项适用检查均执行，0 项因证据不可用而跳过。** 真实数据库、网络、浏览器、运行中多实例及排序质量是 D11 明确排除的验证层次，不补入检查计数，也不恢复为归档前置条件；这些实际效果仍为 Not verified。规格中的 SQL、事务及发布行为以人工审阅／替身证据说明，未宣称完整动态验收。

### 当前问题

#### CRITICAL

无。

#### WARNING — W4：前端错误拒绝另一处已采纳组合法补充的标准名称

- **当前实现定位**：`kb-web/src/search/assistedApi.ts:119-121`；服务端对照为 `kb-app/src/main/java/com/zhishiku/kb/app/search/expansion/SearchQueryPlanner.java:74-81,197-200`。
- **对应需求／设计**：`specs/enterprise-terminology/spec.md:33-43` 允许标准名称与其他组别名碰撞；`specs/search-query-assistance/spec.md:53-79,157-167` 规定唯一关系扩展、显式选择及同源完整说明；`specs/search-filtering/spec.md:37-51`、设计 D4／D7 要求客户端按真实计划核对完整响应。
- **合法固定词典**：组 1 标准名称 `PM`，唯一别名 `项目`；组 2 标准名称 `产品`，别名 `PM`。两组均启用，PM 歧义，项目唯一对应组 1；这些关系符合现有管理写入规则。
- **复现条件**：搜索 `PM 项目`，明确把 `[0,2)` 的 PM 选择为组 2；`[3,5)` 的项目仍自动采纳组 1。
- **实际计划**：服务端生成 `effectiveQuery = 产品 项目`、`executionText = 产品 项目\nPM`。第一个 appliedTerm 属于组 2，expressions=[]；第二个属于组 1，expressions=[PM]。旧位置 PM 已被替换；另一处唯一组的标准名称 PM 是实际新增表达。未递归匹配，也没有因歧义选择自动合并全部组。
- **错误行为**：前端 `used` 无条件纳入所有 appliedTerms.matchedText，包括已经替换掉的旧 PM，于是把第二项真实 expressions=[PM] 判为重复，抛出 AssistanceProtocolError 并丢弃整个成功响应。服务端与前端对“仍在有效原文中的表达”使用了不同规则。
- **独立证据**：`kb-app/target/verify-probes/ReplacementExpressionProbe.java` 单独调用实际纯计划器与合成词典，输出上述计划和 assistance；前端 `.bundle-analysis/verify-replaced-expression.test.ts` 对同形完整 DTO 单独调用实际 decodeAssisted，确认错误拒绝。两边没有直接调用、服务或网络连接，不是前后端联调。现有 `SearchQueryPlanTest.java:160-168` 覆盖标准名称碰撞时仍补充标准名称，`assistedApi.test.ts:39-44` 覆盖普通选择，尚未覆盖两种行为在不同位置同时发生。
- **建议**：统一客户端与纯计划器的存在表达判定：已选择位置以所选 canonicalName 计入，未替换位置才以 matchedText／sourceText 计入；不再把已删除的旧片段无条件列为重复。保留同源并集、真正重复表达及独立规模的拒绝规则。为上述混合位置增加独立纯计划与解码正确行为回归，确认完整结果被接受，且最终重复表达仍被拒绝；不需要真实环境测试。

#### SUGGESTION

无。W4 的缺陷和回归建议计为同一项 WARNING，不重复计数。

### 原问题的关闭复核

- **W1 保持关闭**：`TermEditor.vue:26-50` 同时核对绑定对象和核对代次，放弃／重试／资格失效中止旧回调；`pages.test.ts:99-147` 已覆盖旧 A 成功／404 不结清 B、B 关闭重开沿用原载荷和标识、同意图重试及连续核对。
- **W2 保持关闭**：`readAccess.ts:12-46`、`useDirectory.ts:21-32`、`TermDirectory.vue:24-48` 和 `TermEditor.vue:49` 对所有管理读取统一拒绝；`api.ts:69-72` 不等待 403 错误体或取消完成。pages 的拒绝、迟到、重挂载／主动恢复及 API 的无限等待替身回归通过。`adminAccess.ts:29-50` 的重核先清空资格，未引入空间目录依赖。
- **W3 保持关闭**：`submitted.ts:7-10` 冻结实际上限，`SearchWorkspace.vue:89` 传入页面配置，`assistedApi.ts:80-81,122-123` 使用同一上限而保留 32／2048 独立限额；长 ORIGINAL／ASSISTED、补充字符、模式切换及边界回归通过。W4 与字符上限无关。

### 本轮实际执行结果

完整后端 59 类和前端 30 文件命令与 [verification.md](verification.md) 保存的显式白名单相同；包体输出改为 `.bundle-analysis/search-terminology-reverify-report.json`。运行前确认 59 个所选类全部可定位，并审阅／扫描白名单依赖，未加入 MockMvc、应用、监听服务器、真实依赖、跳过用例或文档／SQL 内容断言。其他测试只编译，不记为通过。

| 检查 | 本轮结果 |
| --- | --- |
| 后端显式白名单 `mvn -o -pl kb-app,kb-gateway -am test ...` | **59 类／325 项通过**：app 57 类／320 项，gateway 2 类／5 项；0 失败、0 错误、0 跳过，BUILD SUCCESS。 |
| 全模块 `mvn -o -DskipTests test-compile` | 父 POM＋9 个模块全部 BUILD SUCCESS，仅编译未选中的测试。 |
| 前端显式白名单 `npx --no-install vitest run ... --maxWorkers=2` | **30 文件／419 项通过**，模拟 DOM／受控替身，0 跳过。 |
| `npm run typecheck`／`npm run build` | 通过，生产构建转换 2219 模块。 |
| `npm run bundle:test` | **13 项通过**，0 跳过。 |
| `npm run bundle:report -- --check-isolation --out .bundle-analysis/search-terminology-reverify-report.json` | 隔离通过；普通搜索未静态包含词典管理模块。entry raw/gzip/brotli=396836/133415/115555；search=512062/181071/156796；searchTerms=457070/157460/136539 字节。不宣称网络测量或质量提升。 |
| `openspec validate search-correction-and-enterprise-terminology --strict`／`git -c core.safecrlf=false diff --check` | 通过。 |

额外诊断：使用本轮 Surefire 产物的类路径编译／运行 ReplacementExpressionProbe，只打印固定合成计划；另运行 `npx --no-install vitest run .bundle-analysis/verify-replaced-expression.test.ts --config .bundle-analysis/verify-replaced-expression.config.ts --maxWorkers=1`，**1 文件／1 探针通过**。该探针断言当前错误行为，仅作为 W4 的复现证据，不计入 419 项或规格通过数。旧回归与前轮数字均重叠，不累计。

### 最终评估

没有关键缺失，存在 **1 项应考虑修复的 WARNING**。按核验工作流可带已记录警告进入归档评审，但建议先处理 W4 后再次核验；本轮没有归档，也没有把 all_done 或测试全绿当成无问题结论。

真实约束、锁／回滚、快照、执行计划、网络取消、供应商调用、代理／遥测导出、多实例运行、真实浏览器及业务排序仍 **Not verified**，不新增为隐藏待办或完成／归档门槛。W4 完全可以在既定独立离线范围修复和回归。

## 修复后定向复核（当前状态）

> 本节保留先前修复轮的状态与运行记录；最新完整核验结论以上方第二轮为准。

2026-11-24，用户授权处理核验问题后，**W1～W3 已全部修复并通过离线回归，原 3 项 WARNING 均已关闭**。本节是修复范围的定向复核，不冒充重新执行全部后端／规格核验；下方首次核验结论、问题细节与原行号保留为历史记录。

| 问题 | 修复与当前定位 | 正确行为回归 |
| --- | --- | --- |
| W1 | `kb-web/src/searchTerms/TermEditor.vue:26-50`：接受回执前同时核对当前绑定对象、查询 controller、会话、资格及 uncertain 状态；放弃、重试、资格失效和卸载中止旧核对。 | `src/searchTerms/pages.test.ts`：放弃 A 后未知 B 的原标识／载荷仍可重试；旧成功／404 不覆盖 B；同一意图重试也作废旧代次；连续核对仅接受最后一代。 |
| W2 | `kb-web/src/searchTerms/readAccess.ts:12-46`、`useDirectory.ts`、`TermDirectory.vue:24-48`、`TermEditor.vue:49`、`SearchTermsPage.vue`：所有目录、含义、目标、详情和本人回执的 403 共用拒绝边界，先同步失效整页状态，再一次资格重核；同资格重挂载不循环读取，显式重核后才重新开放。`src/searchTerms/api.ts:69-74` 不等待 403 错误体或取消完成。 | 管理 pages／api：主／子视图及详情／回执拒绝、旧草稿／绑定／在途保存清理、迟到目录／详情隔离、慢重核、同资格重挂载与主动恢复；以不结束的响应流取消替身证明拒绝即时交付。 |
| W3 | `kb-web/src/search/limits.ts`、`submitted.ts:7-10`、`SearchWorkspace.vue:89`、`assistedApi.ts:78-81,122-123`：默认配置集中到独立模块，实际上限作为本地已提交快照沿用，未加入协议正文；输入与最终表达使用同一上限。 | 辅助 API／submitted／页面：9000 配置接受 8001 个补充字符的 ORIGINAL／ASSISTED 完整成功；模式切换与采用保留上限；100／9000 最终表达及换行边界；提高上限仍不放宽 32 表达／2048 新增 code point 限额。 |

本轮新增 **21 项正确行为回归**，完整前端显式白名单 **30 文件／419 项通过**（不累计早先的 398 项或定向运行）；`npm run typecheck`、包体工具 **13 项**、生产构建（2219 模块）、包体隔离、OpenSpec strict 及 diff 检查均通过。完整白名单命令仍与 [verification.md](verification.md) 原前端命令相同，包体输出改为 `.bundle-analysis/search-terminology-fixes-report.json`。本轮未修改后端、未复跑 Maven，后端 325 项为首次核验的实际结果，不计作本轮测试。

首批新增回归先对旧实现运行，出现预期失败，随后修复、补充相邻窗口并完整复跑通过；没有跳过失败、删除场景或降低断言。旧忽略目录的诊断探针仅记录首次错误复现，不再作为修复正确性的检查。

任务跟踪仍为 **51/51**；本轮是原任务 8.1／8.2／9.2～9.4 的缺陷修复，没有增加需求、缩减规格或同步主规格。未提交、推送、部署或归档。真实依赖、数据库、网络、浏览器及排序质量继续 **Not verified**，不转为完成或归档条件。

## 首次核验结论（历史记录）

**0 CRITICAL / 3 WARNING / 0 SUGGESTION。** 任务跟踪为 **51/51**，57 项新增／修改需求均找到实现，168 个命名场景的代码与已有证据已审阅；未发现整项需求缺失。三个额外前端边界存在可复现的规格／设计偏差，**本次不是无问题通过**，建议修复后重新核验。

本次只核验既定独立离线范围。不修改实现、不调整任务复选框、不同步主规格、不归档或部署。新增长期交付物仅为本报告；诊断探针与构建报告放在前端的忽略目录 `.bundle-analysis/`。

## 摘要记分表

| 维度 | 状态 |
| --- | --- |
| 完整性 | Task Completion：51/51，跟踪文件可读、无未描述或未完成任务。Spec Coverage：57/57 找到实现；51 ADDED、6 MODIFIED、0 REMOVED、0 RENAMED。 |
| 正确性 | Requirement Implementation Mapping：57 项已建立代码映射；发现 W1～W3。Scenario Coverage：168 个命名场景已核对代码／独立测试／仅交付证据；新增探针确认三个已有回归遗漏的窗口，不能据原白名单全绿认定行为完全正确。 |
| 一致性 | Design Adherence：D1～D11 已审阅，主要架构遵循设计，D7／D9 的前端边界存在下述偏差。Code Pattern Consistency：新增目录、命名、具体 import、控制流及共享组件模式未发现显著偏离。 |

六项适用检查均执行，没有因为产物缺失而跳过的检查。真实依赖、迁移效果、并发数据库／网络、多实例、浏览器及排序质量验证已由本变更 D11 明确排除：**不纳入本次检查计数，也不恢复为归档前置条件**；其实际效果仍为 Not verified，不能以代码审阅或替身测试冒充验收。

## 问题清单

### CRITICAL

无。任务没有未完成项，未发现新增／修改需求整项没有实现。

### WARNING

#### W1：迟到的本人回执可能结清另一个新写意图

- **实现位置**：`kb-web/src/searchTerms/TermEditor.vue:30-39`，`kb-web/src/admin/WriteNotice.vue:12-13`，`kb-web/src/admin/hooks.ts:165-171`。
- **规格／设计**：`specs/admin-web-terminology/spec.md:69-87`，设计 D9；回执必须结清其绑定的原意图，当前读值或其他提交不能代替该回执。
- **复现**：同一身份／词条下，提交 A 得到未知结果 → 查询 A 的本人回执并保持等待 → 点击“放弃核对” → 编辑并提交 B，B 也未知 → A 的回执才返回。
- **实际结果**：回执回调只验证 live、身份、资格和目标，没有确认当前未决绑定仍是查询时捕获的 A。`write.reset()` 清掉当前 B 的绑定，随后 `emit('done', receiptA)` 显示确认并关闭表单。重开后 B 的待核对提示消失，新提交恢复可用，尽管 B 从未取得回执。
- **离线证据**：诊断探针以可控 Promise、Mock transport 和 jsdom 完整复现上述窗口；确认 B 的请求标识与 A 不同，而 A 返回后仍丢失 B 绑定。原 `src/searchTerms/pages.test.ts:75-97` 只覆盖单一未决意图的回执成功与关闭重开，不覆盖放弃后产生新意图。
- **建议**：接受回执前比较当前绑定对象／requestId 与查询时捕获的绑定，并核对回执查询代次；放弃或更换意图须使旧核对回调失效。可把核对状态纳入保存／重试互斥。为“旧 A 回执不得清理未知 B”增加独立组件回归，不需要真实服务。

#### W2：管理子视图收到读取 403 后未统一清理和重核资格

- **实现位置**：`kb-web/src/searchTerms/MeaningsPanel.vue:9`，`kb-web/src/searchTerms/TargetPicker.vue:11-15`，`kb-web/src/searchTerms/useDirectory.ts:19-23`，`kb-web/src/searchTerms/TermDirectory.vue:22,28-37`。
- **规格／设计**：`specs/admin-web-access/spec.md:21-35`、`specs/admin-web-terminology/spec.md:85-95`，设计 D9；资格失效须清理目录、草稿、选择与旧回调，恢复重新核对。
- **复现**：读取主目录与共享含义第一页 → 打开编辑器并输入草稿 → 共享含义续页返回 `403 FORBIDDEN`，此前缓存的管理资格尚未重新读取。
- **实际结果**：子视图的 `useTermDirectory` 只清理自己的 items，未向父页面传播资格拒绝或调用 `access.check(true)`。主目录和编辑草稿仍显示，写按钮继续依赖旧资格。父页面的 403 watcher 仅观察主目录；详情 `open` 的失败也只变成通用读取错误。
- **离线证据**：探针确认共享含义 403 后资格核对调用为零，主目录及旧草稿仍存在。原 pages 生命周期回归主动修改客户端权限事实，不能证明服务端拒绝到达、而客户端事实尚旧时的清理行为。
- **建议**：将目录／详情／目标／含义等所有管理读取的 403 统一上报父级资格拒绝边界，立即使私有状态和在途回调失效，再有界重核资格；避免仅对主目录生效。补充子视图及详情拒绝、慢资格核对与迟到响应的模拟 DOM 回归。

#### W3：辅助响应校验硬编码 8000，与可配置输入上限不一致

- **实现位置**：`kb-web/src/search/assistedApi.ts:118-119`，`kb-web/src/search.ts:5-15`，`kb-web/src/pages/SearchPage.vue:7,15`。
- **规格／设计**：`specs/user-web-documents/spec.md:5-20`、`specs/search-resilience/spec.md:67-77`，设计 D7／D9；8000 是默认值，输入与最终表达须遵守实际配置上限。
- **复现**：前端及服务端允许 9000 个 code point，提交 8001 字符的 ORIGINAL 查询，服务返回合法恒等 assistance 和空 hits。
- **实际结果**：页面输入校验允许发送，但 `decodeAssisted` 无条件按 8000 拒绝完整响应，提示协议不可确认。该问题也影响合法 ASSISTED 最终表达；即使 ORIGINAL 也无法避开。
- **离线证据**：纯函数探针确认 `searchInputError(query, 9000)` 接受，而相同查询的合法 ORIGINAL 对象被 `decodeAssisted` 拒绝。后端 `SearchLimitsProperties.java:17-30` 允许配置高于默认值；现有 API 回归没有覆盖该配置差异。
- **建议**：把本次有效前端上限传入增强解码／输入快照，统一输入与最终表达校验；保留独立 32 表达／2048 新增字符限额。增加非默认上限的 ORIGINAL／ASSISTED 及扩展后边界回归，不将默认 8000 改成不可配置协议常量。

### SUGGESTION

无额外建议。三个问题的修复与回归建议均归属于对应 WARNING，不重复计数。

## 规格覆盖与实现映射

已读取 apply 返回的全部 proposal、design、tasks 和 10 份 delta specs，按 ADDED／MODIFIED 当前文字核对。不存在删除／重命名块，因此无需恢复主规格历史基线。逐命名场景的原映射见 [traceability.md](traceability.md)；下表给出本次独立审阅的主要实现定位，W1～W3 是对原“已覆盖”结论的具体补充限制。

| 能力 | 新增／修改需求 | 场景 | 主要实现证据 |
| --- | ---: | ---: | --- |
| enterprise-terminology | 10／0 | 24 | `TermInput.java:34-267`；`TermWriteService.java:36-158`；`CorrectionWriteService.java:35-135`；`TermCommands.java:51-159`；`TermReadService.java:32-216`；V42 人工审阅。 |
| search-query-assistance | 11／0 | 29 | `NormalizedQuery.java:61-147`；`CandidateBatches.java:46-80`；`TermMatcher.java:37-91`；`SearchQueryPlanner.java:34-230`；`SearchSelectionContext.java:92-176`；`SearchAssistanceService.java:32-63`。 |
| admin-web-terminology | 7／0 | 16 | `TermManagement.vue:7-12`；`TermDirectory.vue:13-75`；`TermEditor.vue:13-71`；`TargetPicker.vue:10-25`；`MeaningsPanel.vue:9-16`；`src/searchTerms/api.ts:36-139`。W1／W2。 |
| admin-web-access | 2／0 | 6 | `src/admin/access.ts:23,30`；`src/routing/routes.ts:51`；`src/routing/ProtectedLayout.vue:22`；`SearchTermsPage.vue:6-10`；固定登录返回与懒加载代码／访问回归。W2。 |
| functional-authorization | 3／0 | 11 | `TermAccess.java:21-51`；`TermLookup`／`TermReceiptService`；精确 Permission code；V42 一次性绑定；`TermPermissionTest` 的引导／撤权／未知 code 替身。 |
| audit-query | 3／0 | 9 | `TermAudit.java:27-79`；`AuditSummaryProjector` 固定实体字段；`TermCommands.java:77-105`；`src/admin/auditApi.ts:142-177` 与既有 audit:read 查询，未知合法筛选兼容保留。 |
| search-filtering | 4／0 | 13 | `AssistedSearchRequest.java:15-56`；`AssistedSearchRequestDeserializer.java:26-53`；`SearchController.java:79-112`；`SearchService.java:188-233,277-321`；原 `SearchMapper.xml:24-79,134-158` 条件与截取顺序人工审阅。 |
| search-query-confidentiality | 1／3 | 19 | `SearchResponseFilter.java:19-43`；`SearchTermResponseFilter.java:19-50`；`GlobalExceptionHandler.java:47-56`；`SignedSearchContext.java:56-121`；gateway 身份净化／no-store；Embedding／rerank 传输替身与遥测投影代码。 |
| search-resilience | 5／2 | 21 | `SearchBudget.java:35-50`；`SearchExecutor.java:99-219`；`SearchService.java:192-233`；`TermSnapshotReader.java:71-103,125-139`；`SearchResponseEncoder.java:31-95`；`SearchMetrics.java:22-29,49-95`。W3。 |
| user-web-documents | 5／1 | 20 | `SubmittedSearch` 冻结条件／累计选择；`SearchWorkspace.vue:63-126,137-155`；`AssistancePanel.vue` 原文 code point／安全文本；辅助 API 完整解码、页面生命周期及反馈／收藏／固定版本／适用期相邻回归。W3。 |
| 合计 | **51／6** | **168** | **57 项实现映射**，不是 168 条完整环境验收。 |

## 设计与模式一致性

- **D1／D7**：独立 `/api/search/assisted`，旧数组及旧查询路径不读词典；同一 executor 准入，完整对象编码后才生成双版本成功头。W3 是非默认上限的前端校验偏差。
- **D2／D8／D10**：独立五表及请求锁→修订锁→清缓存／资格重核；首次实体／修订／回执／审计同短事务，读取另用短一致快照；独立读写和 audit:read，不由角色名或资源 ACL 推导。只审阅迁移及 SQL，不宣称真实约束或事务运行效果。
- **D3／D4**：完整字素簇 NFC／ASCII、原文 code point、最左最长、批次精确键探测及第九项超限；不递归、不加载全词典；说明和统一 executionText 同源，三阶段复用一次检索。
- **D5**：独立 HMAC-SHA-256、有界信封／载荷、用途隔离、当前／前任密钥；绑定真实身份、authEpoch、令牌指纹、精确原文与全部规范化筛选／数量；数据库时间 Stub 与修订到期测试；原令牌消费后不跨模型调用保留。
- **D6**：TERMINOLOGY 默认 1000ms，共享总期限与实际退出占用；ORIGINAL／旧端点绕过词典；只用固定 mode／outcome 指标，不误入 options。
- **D9**：草稿／SubmittedSearch 隔离、原词恢复、SafeText 和固定管理懒路由符合设计；W1／W2 是回执与资格生命周期不完整的窗口。
- **D11**：测试及构建范围遵循独立离线约束，旧 MockMvc 等文件仍排除。生产组件不引入测试合成词典；包体工具确认普通搜索无词典管理静态依赖。

## 本轮实际执行证据

本轮重新执行 [verification.md:14-31](verification.md) 中相同的完整后端类白名单、前端 30 文件白名单和编译／构建命令，仅将包体报告输出路径改为下列 verify 文件；并补充独立诊断探针。运行前结合测试源码及搜索确认白名单未包含 MockMvc、应用启动、监听服务器、真实依赖或文档／SQL 内容断言。没有以跳过测试替代通过。

| 检查 | 实际结果 |
| --- | --- |
| 后端 `mvn -o -pl kb-app,kb-gateway -am test`，完整显式 `-Dtest` 白名单 | app **57 类／320 项**、gateway **2 类／5 项**；0 失败、0 错误、0 跳过，BUILD SUCCESS。 |
| 全模块 `mvn -o -DskipTests test-compile` | 父 POM＋9 模块全部 BUILD SUCCESS；仅编译其他测试，不把它们记为测试通过。 |
| 前端完整显式白名单 `npx --no-install vitest run ... --maxWorkers=2` | **30 文件／398 项通过**，0 跳过；jsdom 和受控替身。 |
| `npm run typecheck` | 通过。 |
| `npm run bundle:test` | **13 项通过**，0 跳过。 |
| `npm run build` | 类型检查及生产静态构建成功，2217 模块转换。 |
| `npm run bundle:report -- --check-isolation --out .bundle-analysis/search-terminology-verify-report.json` | 隔离通过；search 40 文件／511960 raw 字节；searchTerms 28 文件／455561 raw 字节，与前次产物一致。不宣称质量或网络测量收益。 |
| `openspec validate search-correction-and-enterprise-terminology --strict` | 通过。 |
| `git -c core.safecrlf=false diff --check` | 通过，无空白错误；不修改 Git 仓库配置。 |

额外诊断命令（`kb-web` 目录）：

```powershell
npx --no-install vitest run .bundle-analysis/verify-terminology-probes.test.ts --config .bundle-analysis/verify-terminology-probes.config.ts --maxWorkers=1
```

诊断为 **1 文件／3 探针通过**。它们刻意断言当前错误行为，分别证明 W3、W2、W1，**不是规格通过用例，不计入上述 398 项或覆盖通过数**。只用纯函数、Mock API、可控 Promise 与模拟 DOM，禁止真实 fetch；没有浏览器、服务或外部连接。原回归全部通过与发现这些问题并不矛盾，缺的是对相应边界的正确行为断言。

## 最终评估与未验证范围

没有关键缺失，存在 **3 项应考虑修复的 WARNING**。按核验工作流可带已记录警告进入归档评审，但建议先修复 W1～W3 并再次核验；归档仍须用户另行确认，本轮没有执行。任务全勾选及测试全绿不是无问题结论。

真实数据库唯一约束、外键、锁竞争、快照隔离、提交／回滚、执行计划、网络取消、多实例密钥发布、前置代理／实际遥测、真实浏览器、供应商调用与业务 topK 效果均 **Not verified**。上述边界是本变更明确保留的运行限制，不新增为隐藏待办、核验或归档门槛。W1～W3 都能在既定独立离线范围修复和回归，不要求扩展到真实环境测试。
