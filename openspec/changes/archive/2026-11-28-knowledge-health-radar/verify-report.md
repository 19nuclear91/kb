# 核验报告：knowledge-health-radar

日期：2026-11-28。按用户要求循环执行“verify → 修复 CRITICAL/WARNING → 再次 verify”，直到两类问题均为零。核验采用 `openspec-verify-change`，范围为用户已确认的进程内离线验证；本报告不执行主规格同步、归档、迁移或部署。

最终结论：第二轮核验 **CRITICAL 0 / WARNING 0 / SUGGESTION 0**。首轮 4 项 WARNING 已修复并经回归及再次代码审阅关闭，满足用户停止条件。

## 第二轮核验结果

| 维度 | 结果 |
|---|---|
| 完整性 | 28/28 任务；27/27 ADDED 要求存在对应实现；无不可读或缺描述的任务文件；REMOVED/RENAMED 不适用 |
| 正确性 | 27/27 要求完成实现映射；74/74 场景完成代码及直接/组合离线证据核对；W1–W3 全部关闭。真实 SQL、并发事务隔离与联调未验证，属于约定排除范围 |
| 一致性 | D1–D10 设计遵循；技术分层、构造器注入、接口模型、只读处理及项目既有权限模式一致；W4 已关闭 |

再次读取 OpenSpec status/apply：schema 为 spec-driven，state 为 all_done，28/28 完成。第二轮检查了各修复及相邻边界：协议内 404 保留资格重核；非法时间拒绝但合法统计时间仍可解码；空态使用服务端完整数量且不改变分页操作；正常查询仍交由 MyBatis 关闭语句，失败时及时释放。未发现新的核验问题。

| 问题 | 修复位置 | 关闭证据 |
|---|---|---|
| W1 | `kb-web/src/admin/healthApi.ts:124` | healthApi.test.ts 两种旧端点错误拒绝为 503，同一测试保留带 NOT_FOUND 头的 404；healthQuery.test.ts 资格重核及显式重试回归 |
| W2 | `kb-web/src/admin/KnowledgeHealthPage.vue:18` | healthPages.test.ts 翻页空态保留大整数汇总和返回首页；所选类别为空与诊断范围零信号分别展示 |
| W3 | `kb-web/src/admin/healthApi.ts:91` | healthApi.test.ts 非法 2 月 30 日及 24:00 两项复现用例转为通过，原合法协议与日期边界测试通过 |
| W4 | `kb-app/src/main/java/com/zhishiku/kb/app/config/HealthStatementBudget.java:33` | HealthBudgetTest.statementPreparedAfterDeadlineIsClosedBeforeFailureEscapes 断言到期拒绝且 close 已执行；预算缩减、事务边界及末尾到期回归通过 |

## 本轮验证命令

后端在仓库根目录执行：

```powershell
mvn -o -pl kb-app -am '-Dtest=HealthBudgetTest,HealthReadTest,HealthRulesTest,HealthControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

结果：重新编译通过；4 类 / **19 项**通过，失败、错误和跳过为 0。没有启动应用或网络监听。

前端在 `kb-web` 执行：

```powershell
npx vitest run src/admin/healthApi.test.ts src/admin/healthQuery.test.ts src/admin/healthPages.test.ts
npm run build
npm run bundle:report -- --check-isolation
```

定向测试 3 文件 / **39 项**通过；build 包含 vue-tsc 类型检查及生产静态构建，均通过。使用本轮新构建结果的包体隔离检查也通过。测试数量不与实施阶段重复运行累计。

标准 `openspec validate knowledge-health-radar --strict` 和 `git diff --check` 通过。上述“WARNING 0”指本变更的 OpenSpec 核验结果，不把既有其他测试源码的 javac 提示或 JVM 动态代理提示算作本变更问题。

## 首轮核验

| 维度 | 结果 |
|---|---|
| 完整性 | 28/28 任务完成；27 项 ADDED 要求均有实现；无 MODIFIED、REMOVED 或 RENAMED 要求 |
| 正确性 | 已逐项核对 74 个场景及其实现/离线证据；发现 W1–W3 行为偏差 |
| 一致性 | D1–D10 的单空间、分层、授权、时间、分页及处理流程整体遵循；预算故障资源清理存在 W4 |

CRITICAL：0。WARNING：4。SUGGESTION：0。

| 编号 | 具体发现与位置 | 修复要求 |
|---|---|---|
| W1 | `kb-web/src/admin/healthApi.ts:123` 原样传播旧端点的 404/405；404 会触发目标资格重核，而“旧服务不支持”场景要求服务不可用 | 无雷达 NOT_FOUND 原因头的 404、旧端点 405 转为不可用；保留协议内 404 的资格重核语义 |
| W2 | `kb-web/src/admin/KnowledgeHealthPage.vue:62` 对所有空结果使用同一句提示，没有按汇总区分所选类别无项目、游标后无项目和诊断范围无信号 | 依据服务端完整汇总展示不同空态，保留范围说明和返回首页入口 |
| W3 | `kb-web/src/admin/healthApi.ts:91` 仅检查时间正则与 Date.parse；JavaScript 会把 2026-02-30 或 24:00 自动归一化，可能展示非法统计时间 | 严格校验原始日历日期及小时/分/秒，再核对业务时区日期 |
| W4 | `kb-app/src/main/java/com/zhishiku/kb/app/config/HealthStatementBudget.java:32` 创建 Statement 后若剩余预算检查失败，Statement 未交付 MyBatis 且未关闭 | 预算检查/设置超时失败时关闭已创建 Statement，正常成功仍交由 MyBatis 管理 |

四项均已通过离线测试复现：前端 30 项中出现 6 项预期失败，覆盖旧端点两种状态、两种非法时间及两种空态；后端 HealthBudgetTest 4 项中出现 1 项预期失败，证明预算耗尽后语句未关闭。复现使用实际解码、模拟 DOM 和 JDBC 替身，不启动网络或数据库。

## 修复

- W1：仅把无 `NOT_FOUND` 原因头的 404 及 405 映射为页面不可用；协议内 404、403 及 409 的恢复分类保留。
- W2：空态按服务端并集及所选类别数量分别呈现，分页空结果仍显示服务端完整汇总和返回首页入口。
- W3：统计时间先校验原始日历日期与 00–23 时、00–59 分/秒及合法偏移，再核对业务日期；不接受运行时归一化后的非法原值。
- W4：Statement 创建后预算检查或设置超时异常时关闭语句，原异常继续交给已有安全错误边界；正常成功交由 MyBatis 管理。

## 核验依据与边界

- 规划：`proposal.md`、`design.md` D1–D10、`tasks.md` 和五份增量规格；CLI 已确认本地 OpenSpec 根目录及全部可读上下文。
- 权限：精确目录、多角色并集、`AdminQueryAccess`、`HealthAccess`、`SpaceQueryService` 和 `PermissionService`；V46 仅人工审阅一次性绑定范围，旧版本目录拒绝与引导撤权通过原离线组件证据核对。
- 后端：共享 `HealthMapper` 关系、既有发布/view/责任人资格谓词、`HealthProjection`、`HealthQueries`、`HealthCursor`、只读事务及时间预算、控制器与 app/gateway 响应保护。
- 前端：独立路由门禁与懒加载、合法返回路径、空间详情入口、`healthApi`、`healthQuery`、雷达页面及原 `VersionPreview`/管理页面资格边界。
- 逐场景对应见 [traceability.md](traceability.md)，已执行的实施回归见 [verification.md](verification.md)。既有结果不与本轮重跑累加。
- 真实 SQL、事务并发隔离、性能、迁移和浏览器/前后端联调未验证；它们是用户明确排除的验证范围，不作为本轮 WARNING 或完成/归档条件。未运行文档存在性、内容匹配、快照或生成 Swagger 文档断言测试。
