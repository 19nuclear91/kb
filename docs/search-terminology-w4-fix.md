# 搜索辅助 W4 修复与验证

2026-11-25，已按用户要求修复 W4，并完成定向离线回归。该问题记录在 [2026-11-24 归档核验报告](../openspec/changes/archive/2026-11-24-search-correction-and-enterprise-terminology/verify-report.md)中；本记录单独说明后续修复，不改写归档时的核验结果。未部署。

## 问题与修复

固定合成词典包含组 1（标准名 `PM`、别名 `项目`）及组 2（标准名 `产品`、别名 `PM`）。搜索 `PM 项目` 并将第一处 PM 选择为组 2 后，服务端正确生成 `effectiveQuery = 产品 项目`、`executionText = 产品 项目\nPM`。旧前端把已替换掉的 PM 仍计为已存在表达，抛出 `AssistanceProtocolError`，丢弃整个成功响应。

`kb-web/src/search/assistedApi.ts` 现在使用已验证选择的原文位置区分表达：已选择位置按 `canonicalName` 判重，未选择的已采用位置按 `matchedText` 判重，待确认建议按 `sourceText` 判重。规则与 `SearchQueryPlanner` 的 `present` 集合一致。真实重复表达、同组说明一致性、扩展并集、32 表达／2048 新增 code point 及实际查询长度限制继续生效。

生产代码只修改前端解码；后端增加纯计划器回归，无协议、数据库迁移或主规格变更。更新前端构建即可交付此修复，完整功能首次部署仍按既有 app → gateway → web 顺序执行。

## 回归覆盖

新增 **11 项**回归：前端 API 8 项、页面 1 项、后端计划器 2 项。

- 原始 W4、替换位置在自动扩展之后、ASCII 大小写及 emoji 原文坐标，均接受完整双版本响应并保留命中。
- 替换后的标准名称、未替换的匹配原词、另一处未确认的同名表达仍不能重复补充。
- 同组重复位置共享扩展并集，边界长度不重复计量；组内 `PM`／`pm` 规范等价重复继续拒绝。
- 页面使用实际 `assistedApi.search` 和解码，仅替换 Session 传输；用户点击“产品”后显示命中结果，累计选择保持原文坐标且不额外发起搜索。
- 后端通过固定内存词典确认混合计划与位置换序；有一处 PM 未确认时不补充，全部替换后可由另一处项目补充 PM。

修改生产代码前，新增 API 回归执行结果为 **27 项中 4 项失败、23 项通过**，四项均在旧判重语句复现预期误拒绝。随后修复并加入页面回归，执行下述完整定向集合。

## 实际执行结果

环境：PowerShell 7、Node.js 24.21.0、Maven 3.9.9、Temurin JDK 21.0.7。

| 检查 | 结果 |
| --- | --- |
| 后端纯计划器、限额及合成样例 | 3 类／40 项通过，0 失败、0 错误、0 跳过 |
| 前端搜索及相邻收藏、反馈、组件回归 | 10 文件／120 项通过，0 失败、0 跳过 |
| `npm run build` | `vue-tsc --noEmit` 与 Vite 生产构建通过，2219 模块 |
| 包体隔离 | 通过；报告位于本地忽略目录 `.bundle-analysis/search-terminology-w4-report.json` |
| `git -c core.safecrlf=false diff --check` | 通过 |

从仓库根目录执行后端检查：

```powershell
mvn -o -pl kb-app -am test '-Dtest=SearchQueryPlanTest,SearchQueryPlanBoundsTest,SyntheticQuerySamplesTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

`surefire.failIfNoSpecifiedTests=false` 允许上游模块无匹配类，所选三个测试类均实际运行。Maven 同时完成相关模块编译；其他测试仅编译，不计为已执行。

从 `kb-web` 执行前端检查：

```powershell
npx --no-install vitest run src/search/assistedApi.test.ts src/search/submitted.test.ts src/search/assistance-page.test.ts src/search.test.ts src/search/page.test.ts src/search/api.test.ts src/search/selectors.test.ts src/favorites/documents.test.ts src/knowledgeGaps/feedback.test.ts src/components.test.ts --maxWorkers=2
npm run build
npm run bundle:report -- --check-isolation --out .bundle-analysis/search-terminology-w4-report.json
```

前端使用 jsdom、固定合成响应与 Session 替身；后端使用内存词典和纯计划器。没有启动真实浏览器、应用或 HTTP 服务器，没有连接数据库、Redis、对象存储或模型。此结果关闭 W4 的实现问题，不表示完整前后端联调或真实检索排序效果已验证，也不冒充重新执行原变更的全部核验。
