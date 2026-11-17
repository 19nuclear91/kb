# 离线验证记录

归档状态：2026-11-17 后续已按用户指令同步七份主规格并归档，见[归档记录](archive-summary.md)；以下实施及修复记录保留当时的实际执行范围。

日期：2026-11-17。环境：Windows / PowerShell 7，Temurin Java 21.0.4，Node.js 24.22.1，npm 11.19.1。变更已实现，未部署；没有执行 V40，没有同步主规格或归档。

## 后端

在仓库根目录运行以下明确白名单，未运行依赖外部基础设施的测试：

```powershell
mvn -o -q -pl kb-app,kb-worker,kb-gateway -am test '-Dtest=DocumentDiffPropertiesTest,AuditSummaryProjectorTest,AuditScopeTest,DocumentDiffMappingTest,BlockDiffTest,InlineDiffTest,TableDiffTest,DiffSimilarityTest,ValueChangeExtractorTest,DiffExecutionTest,DocumentDiffServiceTest,DocumentDiffControllerTest,ChangeNoteMappingTest,ChangeNoteInputTest,ChangeNoteWriteServiceTest,ChangeNoteReadServiceTest,ChangeNoteControllerTest,ModuleBoundaryTest,TextChunkerTest,DefaultParsePipelineTest,ChunkPublisherTest,VersionSnapshotWriterTest,VersionSnapshotSchemaCheckTest,ParsePipelineBudgetTest,ReviewRouteTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
mvn -o -q test-compile '-DskipTests' '-Dstyle.color=never'
```

两条命令均退出 0。按本次白名单对应的 Surefire XML 汇总，未计入历史或未选中的报告：

| 模块 | 测试类 | 测试项 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: | ---: |
| kb-app | 18 | 74 | 0 | 0 | 0 |
| kb-worker | 6 | 19 | 0 | 0 | 0 |
| kb-gateway | 1 | 1 | 0 | 0 | 0 |
| 合计 | 25 | 94 | 0 | 0 | 0 |

全模块 `test-compile` 仅编译测试，不表示执行了所有模块的测试。补齐说明权限查询的 BoundSql 参数证据后，另运行：

```powershell
mvn -o -q -pl kb-app -am test '-Dtest=ChangeNoteMappingTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
```

退出 0，1 项通过，0 失败、0 错误、0 跳过。Mapper 测试只加载配置、核对绑定值及超时，不连接数据库，也不以 SQL 文本包含某个片段作为证据。

## 前端

工作目录：`kb-web`。使用锁文件安装依赖（`npm ci --ignore-scripts`），没有更改依赖版本或锁文件。

```powershell
npm run typecheck
npm test -- --run
npm run build
npm run bundle:test
npm run bundle:report -- --check-isolation --out .bundle-analysis/version-diff-report.json
```

全部命令退出 0：类型检查通过，前端全量 **127 文件、1409 项通过**，0 失败、0 跳过；生产构建通过；包体工具 **12 项通过**，0 失败、0 跳过；实际生产构建隔离检查通过。最终全量回归耗时 134.49 秒。首次全量运行的 20 项失败来自下载/审核顺序替身接收新增只读请求；补齐读取隔离后，相关 46 项定向回归及全量复跑均通过，没有放宽既有业务断言。

包体报告保存在本地忽略目录 `.bundle-analysis/version-diff-report.json`。报告以生产构建的实际静态依赖计算集合，`compare` 独立统计；入口及文档列表不含 `src/versionDiff/` 或 `src/changeNotes/`。最终构建统计如下，单位为字节，gzip 按逐文件 level 9 压缩后求和：

| 场景 | 累计文件 | 累计 raw | 累计 gzip | 相对入口新增 gzip |
| --- | ---: | ---: | ---: | ---: |
| entry | 13 | 396052 | 133037 | 0 |
| documents | 43 | 708874 | 239056 | 106019 |
| compare | 30 | 473829 | 164789 | 31752 |

没有执行跨基线增长门槛，不将隔离通过表述为包体相对旧版本下降。

## 变更与交付核对

`openspec validate version-diff-and-change-notes --type change --strict --json` 通过，1 项、0 问题。`git diff --check` 无空白错误。迁移、文档和主规格状态均按任务边界交付，未运行迁移或归档命令。

## 证据边界

本次只执行内存替身、纯函数、MockMvc、MyBatis 配置解析和模拟 DOM 测试，以及类型检查、编译、构建和包体分析。算法包含 Unicode 重建随机用例；并发测试使用可控时钟和阻塞替身；说明服务测试核对调用顺序和回执行为。

以下均未验证：真实 PostgreSQL 的 V40、外键、约束、不可修改触发器、事务回滚、锁竞争及顺序、级联删除、SQL 执行计划；真实第三方解析服务的结构质量与对齐效果；真实浏览器、前后端联调、网关在线流量、部署及回退。没有启动数据库、Redis、容器或浏览器。迁移、Mapper SQL 与 Markdown 文档只交付和人工核对，没有编写文件内容断言。

场景与证据的具体对应关系见 [traceability.md](traceability.md)。后续发布顺序为全部 app 与 V40、Worker、web；本记录不代表已发布或真实环境验收通过。

## 核验问题修复回归（2026-11-17）

按用户指令修复 `verify-report.md` 的 W1/W2：详情页持有未确认说明的请求绑定和表单，跨同一目标的资格重读恢复；对比选择器独立读取所选目标说明，隔离迟到响应并保留手选基准。新增 11 项实际详情页的模拟 DOM 回归，覆盖父页面重读旧值/新值/503、详情 503 恢复、卸载期间显式放弃、在途写入重建、切换版本清理，以及目标说明默认基准和迟到响应。

在 `kb-web` 执行：

```powershell
npm run typecheck
npm test -- --run
npm run build
npm run bundle:report -- --check-isolation --out .bundle-analysis/version-diff-fixes-report.json
```

全部退出 0。全量 **127 文件、1420 项通过，0 失败、0 跳过**，耗时 138.81 秒；生产构建和实际包体隔离均通过。入口及文档列表仍不含 `src/versionDiff/`、`src/changeNotes/`。首轮定向回归为 3 文件、40 项通过，之后新增的在途重建用例随全量执行通过。

本轮只修改前端及证据记录，未重复后端测试。没有执行真实数据库、浏览器、联调、迁移、部署或归档，验证边界与前述记录一致。
