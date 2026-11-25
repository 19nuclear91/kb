# 实施验证记录

最新修复及复核见 [第三轮报告](verification-report-3.md)：W1–W4 及 R1 全部关闭，前端最终白名单 145/145 通过；后端第二轮白名单 129/129 通过，原搜索架构失败已修复。2026-11-25 已同步主规格并归档，见 [归档记录](archive-record.md)。以下保留首次实施验证的历史计数与失败证据。

日期：2026-11-25。仅独立离线测试、编译、类型检查、生产静态构建和包体隔离；未启动应用、HTTP 监听服务、真实浏览器、数据库、Redis、存储或容器，未编写文档存在性／内容断言测试。实现范围与场景证据见 [traceability.md](traceability.md)。

## 结果

| 检查 | 实际结果 |
| --- | --- |
| Java 指定白名单，最终各类结果去重 | 22 类、112 项：111 通过、1 失败、0 error、0 skipped |
| 本次诊断后端测试 | 7 类、30 项全部通过，含最终受控事实交错补测 |
| 前端指定白名单 | 8 文件、114 项全部通过；先执行 7 文件 113 项，再执行 layer 1 项 |
| vue-tsc | 通过 |
| Vite production 静态构建 | 通过，2227 modules |
| 包体分析工具单测 | 13 项通过 |
| 当前生产包体隔离 | 通过；入口／登录累计 gzip 134659 字节；普通文档列表 241019 字节；诊断场景累计 142209 字节（相对入口增量 7550 字节） |
| OpenSpec strict | 通过 |

Java 整体检查未全绿。唯一失败是 `ModuleBoundaryTest.layersMustNotBeInverted`，列出 25 条已有搜索服务到 web 请求类型的反向依赖，涉及 `SearchAssistanceService`、`SearchService`、`AssistedSearchRequest`、`SearchRequest`。这些文件及原架构规则相对本次起点 `1736531` 均无修改（`git diff --name-only -- ...` 为空）。本次新增 Mapper 到服务层 Entry 的依赖已移到 `auth/model/diagnostics/DiagnosticEntry` 并消除，重跑只剩上述 25 条。没有删除失败用例、增加忽略规则或将整体结果改称通过。

`kb-app` 最终各类报告合计 106 项、1 失败；`kb-common` 指定 1 项通过；`kb-gateway` 本次指定 2 类、5 项通过。gateway target 中其他历史报告不计入本次结果。编译时仍有原有 deprecated／unchecked 与 `MetadataAccessTest` varargs 提示。

## 实际命令与白名单

仓库根目录执行；所有列出的后端用例为 Mock／Stub、JDBC 结果集替身、ArchUnit 或进程内路由组件，未连接服务。首次 clean 用于清除移动 Java 包后残留字节码；后续只重跑受影响测试。

```powershell
mvn -o -fae -pl kb-app,kb-gateway -am test '-Dtest=DiagnosticPermissionTest,DiagnosticAccessTest,DiagnosticTargetLoaderTest,DiagnosticInputTest,DiagnosticEvaluationTest,DiagnosticBoundaryTest,DiagnosticServiceTest,PermissionCatalogTest,TermPermissionTest,PermissionServiceTest,IdentityServiceTest,DocumentCapabilitiesServiceTest,DocumentCitationServiceTest,DocumentReviewDecisionTest,VersionAccessPurposeTest,VersionAccessRulesTest,DocumentCitationMapperTest,DocumentVersionProjectionTest,NotificationMappingTest,ModuleBoundaryTest,SearchRouteTest,SearchTermGatewayProtectionTest' '-Dsurefire.failIfNoSpecifiedTests=false'
mvn -o -pl kb-app -am test '-Dtest=DiagnosticEvaluationTest,DiagnosticBoundaryTest,DiagnosticServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false'
mvn -o -pl kb-app -am test '-Dtest=DiagnosticServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false'
openspec validate 'permission-explanation-and-authorization-preview' --strict
git diff --check
```

第二条补测 18 项通过，第三条最终交错回归 4 项通过；重复执行不重复计数。只编译的其他测试不计为通过。首次新用例曾发现跨命名空间 SQL include 未完全限定及 Mapper 反向依赖，已修复；这些历史失败不覆盖最终通过证据。

在 `kb-web` 执行（本机没有 pnpm，使用已安装的本地 Node 工具）：

```powershell
node node_modules/vitest/vitest.mjs run src/authorization/access.test.ts src/authorization/api.test.ts src/authorization/state.test.ts src/authorization/panel.test.ts src/admin/space-pages.test.ts src/admin/spaceApi.test.ts src/admin/MultiPrincipalPicker.test.ts
node node_modules/vitest/vitest.mjs run src/authorization/layer.test.ts
node node_modules/vue-tsc/bin/vue-tsc.js --noEmit
node node_modules/vite/bin/vite.js build
node --test scripts/bundle-report.test.mjs
node scripts/bundle-report.mjs --check-isolation --out .bundle-analysis/authorization-diagnostics.json
```

前端测试使用真实组件／API 模块与内存传输、jsdom；测试宿主禁止真实 fetch。新未知写入回归曾因测试宿主英文关闭按钮标签失败，改为匹配实际中英文标签后通过。最终生产报告位于本地 `kb-web/.bundle-analysis/authorization-diagnostics.json`，未将构建资产提交。

## 人工审阅与未验证范围

- V43 只为存量 ROLE_ADMIN 做一次性绑定；新建角色通过 Permission.codes 引导，已撤权不自动补回。只交付文件，未执行数据库迁移。
- 原读路径及诊断／假设行集共用主体匹配、空间授权、文档例外、归档和发布证据片段。Mapper 装配／参数／结果集替身不证明真实 SQL 授权等价。
- REQUIRES_NEW、read-only、REPEATABLE_READ、逐语句剩余超时、完整编码预检由替身核对；数据库实际隔离、并发可见性、锁、连接超时及回滚行为未验证。
- 真实浏览器、网络、Redis、部署、前后端联调、跨实例行为均未验证。这些层次按用户要求排除，不恢复为新实施待办，也不作为已通过证据。
- 对外 Swagger 请求模型、嵌套响应字段、六个路径、固定错误与文档说明经人工对照；未运行文档内容断言。

本次未提交、推送、同步主规格或归档。工作区已有的 `.agents/skills/openspec/` 及用户更新的 `AGENTS.md` 不属于本次实现修改。
