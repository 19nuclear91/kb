# 实施与离线验证记录

日期：2026-11-15；变更：`knowledge-validity-and-review`；工作流：spec-driven。功能已实现，未部署。当前任务 **37/37 完成**，CLI 已确认 `all_done`；下文先保留首轮 **34/34** 的实施记录，整改执行见末节，随后独立复跑的最新证据见[第二轮报告](verify-report-2.md)。实施阶段未执行迁移、同步主规格或归档；后续同步及归档结果见[归档记录](archive-record.md)。

## 交付与修复

- V39 新增适用期、修改历史、复审和命令回执四张表；存量不回填。迁移仅作为交付物，未执行，也未对迁移 SQL 文本做断言。
- `document/applicability/` 和 `document/stewardship/` 实现日期、状态、严格输入、受控读写、候选、清单、计数、确认和 no-store；读取共用版本交互规则和已有授权片段。
- 新写入统一“先审计、后回执，同一个 READ_COMMITTED 事务”；锁等待后重新读取当前发布/审核及管理事实。既有 MetadataWriteService 的顺序未改。`ValidityTransactionTest` 使用真实事务注解/代理与快照仓储，覆盖历史、审计、回执故障后整体恢复及原标识重试；不代表真实数据库回滚已经验证。
- 前端详情/版本历史、编辑、搜索、助手实时/历史引用和审核只读标识；本人 `/stewardship`、共享导航计数与确认；管理责任人区块和空间四标签清单。新适用期/复审在途或未确认阻止删除，旧元数据未确认规则不变。
- 修复初期 `AuditScopeTest` 漏登记既有 LIFECYCLE 前缀；三个新审计域只投影固定日期/身份字段及原因存在标记。生产设置检查登记两个新 Mapper 子包，覆盖父级继承、具体 Mapper/语句及日志组绕过。
- 本人 attention 的适用期条件排除尚未生效版本，与 Java 状态优先顺序一致。批量组合函数按去重版本集合监听，避免每个 SSE 文本增量重复查询。
- 文档见 `docs/knowledge-validity-review.md`、`docs/admin-space-document-web.md`、`kb-web/README.md`；文档只作为交付物，不做内容断言。

## 后端最终执行（任务 7.1）

2026-11-15 09:49 从仓库根目录用 PowerShell 执行：

```powershell
mvn -o -q -pl kb-app,kb-gateway -am test '-Dtest=ProductionSettingsTest,BusinessDateTest,ApplicabilityStateTest,StewardshipScheduleTest,ApplicabilityMappingTest,ApplicabilityInputTest,ApplicabilityWriteServiceTest,ApplicabilityReadServiceTest,ApplicabilityBatchQueryTest,ApplicabilityControllerTest,ValidityTransactionTest,StewardshipMappingTest,StewardshipCandidateTest,StewardshipAssignmentTest,StewardshipConfirmTest,StewardshipQueryTest,StewardshipControllerTest,AuditScopeTest,AuditSummaryProjectorTest,ModuleBoundaryTest,DocumentVersionAccessServiceTest,VersionAccessPurposeTest,AuditQueryServiceTest,ValidityRouteTest,ReviewRouteTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
mvn -o -q test-compile '-DskipTests' '-Dstyle.color=never'
```

两条命令退出码均为 0。全模块 `test-compile` 编译八个 Maven 模块的生产与测试代码，不执行它们的测试，也不运行 Flyway 或应用启动。没有执行 `mvn test` 全量入口（其中含监听型和可选真实库测试）。

仅统计该次白名单对应的 Surefire XML，时间为 09:49:28–09:49:41；不累计此前定向重跑或旧报告：

| 模块 | 测试类 | 用例 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|---:|
| kb-common | 1 | 98 | 0 | 0 | 0 |
| kb-app | 22 | 115 | 0 | 0 | 0 |
| kb-gateway | 2 | 2 | 0 | 0 | 0 |
| 合计 | **25** | **215** | **0** | **0** | **0** |

类级数量：ProductionSettingsTest 98；BusinessDateTest 3；ApplicabilityStateTest 12；StewardshipScheduleTest 10；ApplicabilityMappingTest 3；ApplicabilityInputTest 4；ApplicabilityWriteServiceTest 7；ApplicabilityReadServiceTest 3；ApplicabilityBatchQueryTest 3；ApplicabilityControllerTest 2；ValidityTransactionTest 7；StewardshipMappingTest 2；StewardshipCandidateTest 4；StewardshipAssignmentTest 5；StewardshipConfirmTest 5；StewardshipQueryTest 4；StewardshipControllerTest 2；AuditScopeTest 7；AuditSummaryProjectorTest 14；ModuleBoundaryTest 11；DocumentVersionAccessServiceTest 1；VersionAccessPurposeTest 2；AuditQueryServiceTest 4；ValidityRouteTest 1；ReviewRouteTest 1。

非阻塞提示：Byte Buddy 动态 agent、JVM class sharing 和 Spring Commons Logging 提示；未改依赖或忽略测试来隐藏它们。

## 前端最终执行（任务 7.2）

从 `kb-web` 目录执行：

```powershell
npm run typecheck
npm test -- --run
npm run build
npm run bundle:test
npm run bundle:report -- --check-isolation --out .bundle-analysis/current-report.json
```

全部退出码为 0。全量 Vitest 最终运行 09:49:26 开始，耗时 126.26 秒，**122 个文件、1322 项通过**，0 失败、0 跳过，无未处理错误；类型检查及生产构建成功。包体报告工具 **11 项通过**，0 失败、0 跳过。

已在报告脚本登记 stewardship 场景，并覆盖受保护入口隔离和普通/管理页面双向交叉内联检查。生产模块映射确认 `src/stewardship/StewardshipPage.vue` 在独立异步 chunk，不属于入口静态依赖闭包。当前累计 JS/CSS（逐文件 gzip 后求和，含入口共享依赖）为：

| 场景 | 文件数 | raw 字节 | gzip 字节 | brotli 字节 |
|---|---:|---:|---:|---:|
| entry | 13 | 395470 | 132845 | 115214 |
| stewardship | 29 | 448485 | 153545 | 133280 |

stewardship 相对入口新增 gzip **20700 字节**；包含页面及其额外静态依赖，不等于页面文件本身大小。临时 manifest、模块归属和报告位于本地 `.bundle-analysis`，不提交，也不作为实际网络首屏耗时证据。

### 中途失败与修复

- 初期审计回归 21 项中 1 项失败（漏 LIFECYCLE 前缀）；已补齐，最终白名单重新通过。
- 前端个别定向测试先后因 beforeEach 返回可调用 mock、路由导航尚未确认和 RouterLink stub 不渲染 slot 失败；改为显式清理、等待导航事实及渲染 slot 的替身，已重跑通过。
- 首轮全量 Vitest 在 120 秒被终止，不能算完整结果；随后定位的 5 文件定向运行为 64 项、27 失败。新增只读标识请求消耗了原文件/审核的顺序传输替身，导航本人计数也被旧“不得请求文档”断言误算。修复相应功能边界替身，并只为全站计数精确路径调整断言，保留原下载/审核/管理禁止读取检查。
- 修复后全量运行使用 300 秒命令预算，最终 1322 项全部通过；没有排除、跳过或放宽测试超时配置。

## 规格追踪与静态检查（任务 7.3）

[traceability.md](traceability.md) 为九份增量规格的 110 个场景建立实现/测试映射，区分直接离线证据、组合证据和仅交付项，明确真实数据库、浏览器及部署未验证。

```powershell
openspec validate knowledge-validity-and-review --strict
git diff --check
```

两条命令退出码均为 0；OpenSpec 返回 `Change 'knowledge-validity-and-review' is valid`。Git 仅提示 Windows LF→CRLF 转换，没有空白错误。规格结构校验不等于业务、数据库或部署验收，未同步主规格或归档。

任务 7.4 已交付：根 README 增加功能、配置及本人页面入口，最高迁移更新为 V39；路线图 F-02 更新为首期已实现、未部署，保留未同步主规格/未归档状态。文档未做内容断言。最后再次执行 apply instructions 确认 34/34、all_done，严格规格校验及 `git diff --check` 成功。

## 明确未验证范围

1. **真实数据库**：V39 执行、日期/JDBC 类型、外键/检查约束、不可修改触发器、真实锁竞争/隔离/回滚、索引和 SQL 执行计划均未验证。BoundSql 测试只加载生产 MyBatis 映射及绑定参数，不执行 SQL；共用 CSV 不证明 Java/SQL 真正等价。
2. **真实权限/并发**：Mock 资格与顺序、内存快照不能证明多实例撤权、送审/发布与更正、首次指定竞争、确认与改派、搬迁/删除等数据库竞态。未启动 app、gateway、数据库、Redis 或容器。
3. **浏览器与部署**：未启动浏览器、开发服务或进行前后端联调；没有真实网络、SSE、文件下载、缓存、哈希资产发布、旧二进制混部或回退演练。模拟 DOM 和静态 chunk 隔离不能替代这些证据。
4. **交付物约束**：文档和迁移脚本没有内容断言；禁止修改/删除历史的真实效果需接入数据库核对。首次真实库及部署检查见 `docs/knowledge-validity-review.md`。
5. **首期非目标**：不切换当前版本、不改变搜索/问答召回、不让模型感知适用期、不推送通知或 IM；模型仍可能将已届满内容当作现行规定。

## 独立核验整改与最新执行（2026-11-15）

用户要求修复独立报告 W1、W2、S1，并确认 W2 保留“先当前资格鉴权，再回执/版本比较”；另确认同步更新三份相关增量规格、design.md 及新增 8.1–8.3 三项整改任务，保留原 34 项完成记录。

- **W1 已修复**：ApplicabilityPanel 在父级持有 `useValidityWrite` 和原始草稿，Editor 接收父级写状态，不随读取数据分支卸载。重读旧值、新值或 503 不清理原标识/精确内容、不解除未确认状态；关闭后可继续核对。只有确认、明确拒绝或显式放弃后才允许新编辑；整页放弃同步清理。版本、身份、强制改密、隐藏和离开时丢弃草稿/旧回调，仍按实际结果结算在途写入。
- **S1 已修复**：StewardshipBox 父级持有写状态与原 action，ResourceWriteDialog 可接收父级写状态；关闭只隐藏对话框，恢复后仍按原标识和原载荷重试。重读与关闭不释放删除互斥，区块/整页放弃才解除。目标/身份变化及隐藏/离开清理旧草稿和回调。
- **W2 已协调并测试**：没有把版本检查移到鉴权前。确认先完成且管理员仍合格时陈旧改派为 409；改派先完成时原责任人确认为 403；目标不可访问按既有规则为 404，旧匹配请求也不能延续资格返回回执。`StewardshipConfirmTest` 新增三项直接服务回归；它们只覆盖合法串行化顺序，不证明真实数据库并发。

### 最新结果（不累计旧执行）

后端重新执行本文件首轮记录的同一明确白名单，以及全八模块 `mvn -o -q test-compile '-DskipTests' '-Dstyle.color=never'`；退出码均为 0。仅统计此次白名单对应 Surefire XML（21:37:22–21:38:03）：

| 模块 | 测试类 | 用例 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|---:|
| kb-common | 1 | 98 | 0 | 0 | 0 |
| kb-app | 22 | 118 | 0 | 0 | 0 |
| kb-gateway | 2 | 2 | 0 | 0 | 0 |
| 合计 | **25** | **218** | **0** | **0** | **0** |

`StewardshipConfirmTest` 从 5 项增至 8 项；其他白名单类数量不变。没有运行含监听型或可选真实库用例的后端全量入口。

从 `kb-web` 重新执行 `npm run typecheck`、`npm test -- --run`、`npm run build`、`npm run bundle:test` 和 `npm run bundle:report -- --check-isolation --out .bundle-analysis/current-report.json`，退出码均为 0。全量 Vitest 21:38:15 开始，126.30 秒，**122 文件/1343 项通过**，0 失败、0 跳过；包体工具仍为 **11 项通过**。新增组件回归未 Mock 写 hook，使用真实组件和共用写状态、只替换 API 传输边界。

本次定向回归：8 文件最终 149 项通过；补充强制改密/隐藏后，两个完整区块测试最终 **26 项通过**（detail 13、stewardship-box 13）。这些数量是定向执行记录，不加到全量 1343 项上。

| 最新场景 | 文件数 | raw 字节 | gzip 字节 | brotli 字节 |
|---|---:|---:|---:|---:|
| entry | 13 | 395524 | 132864 | 115235 |
| stewardship | 29 | 448656 | 153596 | 133338 |

本人页面相对入口增量 gzip **20732 字节**；页面仍不在入口静态闭包，普通/管理隔离检查通过。`.bundle-analysis` 为临时报告，不提交，不代表真实首屏性能。

### 中途错误与修复

首轮新测试引用 Vue 私有 `setupState` 造成类型错误，且猜测的关闭按钮中文 aria-label 导致 3 项失败（8 文件/149 项中 146 通过、3 失败）。已改为实际对话框关闭按钮和用户可见的“继续核对/重新读取核对”交互，不通过私有状态驱动；修复后定向、类型检查和全量回归均通过。未排除、跳过或放宽测试。

最新严格规格校验及 `git diff --check` 均退出码 0；CLI 重新确认 37/37、`all_done`。场景映射和 `verify-report.md` 记录原发现已关闭。真实数据库、权限 SQL、锁/触发器/执行计划、浏览器、联调和部署回退仍未验证，禁止范围未扩展；文档与迁移 SQL 没有内容断言。
