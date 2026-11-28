# 知识健康雷达实施与离线验证

验证执行日期：2026-11-27；交付记录收尾：2026-11-28。首期实现已交付，任务 28/28 完成；已于 2026-11-28 同步五份主规格并归档，见 [archive-record.md](archive-record.md)。V46 未执行、未部署。仅使用用户确认的独立离线验证范围。

## 交付

- 后端 `GET /api/admin/spaces/{spaceId}/health-radar`、独立 `knowledge:health:read`、V46 一次性权限绑定。
- 单空间六类信号、精确计数与文档并集、最小管理投影、当前可读发布版本日期、业务时区、上下文游标与同次只读快照。
- 6 秒服务预算和单语句最多 3 秒，app/gateway no-store，固定错误类别和归档/跨日恢复。
- 前端懒加载路由、空间详情入口、单空间切换、严格协议解码、分页替换、固定版本预览、原处理入口及身份/请求代次隔离。
- 使用、API、运维说明及 README/路线图更新。新 Java 代码按现有技术分层放置。

## 实际执行

最终后端显式白名单：

```powershell
mvn -o -pl kb-app,kb-gateway -am '-Dtest=HealthAccessTest,HealthRulesTest,HealthReadTest,HealthFactsTest,HealthBudgetTest,HealthMappingTest,HealthControllerTest,HealthResponseFilterTest,AiQueryCacheFilterTest,PermissionCatalogTest,AdminBootstrapRunnerTest,AdminQueryAccessTest,PermissionServiceTest,RolePermissionValidatorTest,StewardshipQueryTest,StewardshipScheduleTest,ApplicabilityStateTest,ApplicabilityReadServiceTest,SpaceFunctionalPermissionTest,ModuleBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：app **19 类 / 105 项**，gateway **1 类 / 3 项**，失败、错误、跳过均为 0。common 在该白名单下只编译，无选中测试。包含本次健康诊断、现有身份/权限/复审/适用期/空间规则和 ArchUnit 模块边界。standalone MockMvc 只使用模拟 Servlet 环境，没有网络监听。

最后按 Java 格式规范展开状态分支花括号后，执行 `mvn -o -pl kb-app -am '-Dtest=HealthRulesTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，重新编译及 **7 项**规则测试通过；这 7 项已包含在上述 105 项中，不重复累计。

前端从 `kb-web` 执行：

```powershell
npx vitest run src/admin/healthApi.test.ts src/admin/healthQuery.test.ts src/admin/healthPages.test.ts src/admin/space-pages.test.ts src/admin/space-stewardship.test.ts src/routing/lazy-routes.test.ts src/navigation.test.ts
npm run typecheck
npm run build
npm run bundle:test
npm run bundle:report -- --check-isolation
```

结果：**7 文件 / 181 项**通过；类型检查、静态生产构建通过；包体工具 **13 项**通过，包体隔离通过。新增 `knowledgeHealth` 场景，页面未进入普通入口或文档列表静态依赖集合。统计是各命令自己的结果，不与前期重复运行累加。

`openspec validate knowledge-health-radar --strict` 已通过；已人工整理五份增量规格的 74 个场景并核对方案与实现。`git diff --check` 通过。任务表 28/28 完成。本文件保留实施阶段的验证结果；2026-11-28 后续 verify 的四项修复、定向回归和最终结论见 [verify-report.md](verify-report.md)。

## 检查中修正

- 无连接 Mapper 测试发现跨 Mapper 引用复审资格片段时，其内部相对名称被解释到新命名空间。将既有片段中的两处依赖改为完整命名空间，保持业务条件不变；健康映射与旧复审回归均通过。
- 首轮后端的一个故障注入测试在重设抛异常替身时再次触发旧异常，改用明确的替身返回设置。
- 首轮前端两处测试设置问题分别为同步权限变更前未先配置响应替身，以及使用未安装的 DOM 断言扩展；修正后全部通过。
- 汇总与当前页增加类别数量一致性检查，固定六类计数中的两个互斥分组各自不超过文档并集；前端同步严格校验。
- 收尾补齐成功包装/错误码、授权依赖失败、预算到期不交付、首页缺项、分页矛盾和换号迟到错误的直接行为断言；缺项首页和与总数矛盾的下一页响应整体视为不可用。

## 证据边界

`HealthFactsTest` 从原始内存文档/版本/授权事实产生仓储结果，再通过真实查询服务投影；`HealthMappingTest` 加载实际 XML、绑定参数并使用 JDBC 结果集替身构造响应。两者都没有执行 PostgreSQL，不能据此证明实际 SQL 语义、查询计划、隔离或并发行为。事务代理和连接替身只证明事务设置、调用边界及预算传递。

本期没有启动完整前端/后端、HTTP 测试服务器、数据库、Redis、对象存储、Office 或容器，没有真实供应商请求、迁移、部署、浏览器端到端或前后端联调。没有增加或执行 README、规格、使用文档、生成 Swagger 文档等文件的存在性、关键词、内容匹配或文本快照测试。OpenSpec 使用标准格式校验，说明文档和迁移脚本人工审阅。

真实 SQL、权限关系执行、事务隔离、数据库约束、查询预算的实际终止时限、性能、迁移及浏览器联调继续标记为未验证，不作为本变更实施完成或归档条件。逐场景对应关系见 [traceability.md](traceability.md)。
