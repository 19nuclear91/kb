# AI 用量与审计离线验证记录

执行日期：2026-10-24。变更：`ai-usage-and-audit-query`。仅验证离线实现；未部署、未执行真实迁移或第三方环境联调。

## 最终结果

| 检查 | 结果 | 范围 |
| --- | --- | --- |
| Maven 明确白名单 | 32 类、149 项通过，0失败、0错误、0跳过 | common 3、app 90、gateway 1、AI 55 |
| Vitest 明确白名单 | 13 文件、190 项通过 | 模拟 DOM、内存请求、受控 Promise、虚拟时间 |
| TypeScript | 通过 | `tsc --noEmit`，构建时再次检查 |
| Vite 静态构建 | 通过 | 1556模块；产物位于 kb-web/dist，无浏览器运行 |
| OpenSpec 严格结构校验 | 通过，issues为空 | 该 change 的结构与四份 delta |

本记录已更新为 C1、W1–W4 修复后的实际复跑结果。实现过程中出现的 Java 泛型/构造类型错误、可空 Long 条件表达式拆箱、Mock 重设方式、前端测试夹具类型等问题均已修复，随后按下面的最终白名单重新通过。当前没有未解决的白名单失败。

未执行项：全量自动发现测试、集成测试、端到端、服务/容器/监听端口、真实数据库/Redis/模型/浏览器、Flyway迁移、SQL执行与查询计划、真实锁/提交顺序/回滚/跨实例竞态。`JavaSourceConventionTest` 及 README、Markdown、说明文案、注释、示例等内容断言测试按本次约束排除。未执行项不是运行后跳过的成功用例，离线通过不证明这些真实环境行为。

## 实际执行命令

在 `D:\tmp\kb` 使用本地 Maven 缓存和明确测试类名单：

```powershell
mvn -o -pl kb-app,kb-ai,kb-gateway -am test '-Dtest=AiObservationTest,AiUsageMappingTest,AiUsageWriterTest,AiCollectionServiceTest,AiObservationTransactionTest,AiUsageQueryTest,AiUsageReadTest,AiObservationControllerTest,AiObservationServiceTest,AiUsageControllerTest,AiUsageMetricsTest,AiMultiObservationTest,AiQueryCacheFilterTest,AiObservationConfigurationTest,AdminQueryAccessTest,AdminBootstrapRunnerTest,PermissionCatalogTest,RoleManagementControllerTest,AiObservationClientTest,AiObservedChatTest,ChatQuotaAuditServiceTest,ChatObservationTest,TurnExecutionServiceTest,ModuleBoundaryTest,NoDatabaseAccessTest,ChatControllerTest,ChatServiceAdapterTest,ConversationSettlementTest,ConversationMaintenanceTest,ConversationMappingTest,AuditQueryServiceTest,AdminQueryControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
```

在 `D:\tmp\kb\kb-web` 使用已安装依赖，分别执行：

```powershell
npm run typecheck
npx vitest run src/admin/aiUsageApi.test.ts src/admin/aiUsageQuery.test.tsx src/admin/aiUsagePages.test.tsx src/admin/aiUsageAccess.test.tsx src/admin/aiUsageRoutes.test.tsx src/navigation.test.tsx src/admin/audit-access.test.tsx src/admin/auditApi.test.ts src/admin/auditQuery.test.tsx src/admin/audits.test.tsx src/admin/access.test.tsx src/admin/access-spaces.test.tsx src/session.test.ts
npm run build
```

回到项目根目录执行结构校验：

```powershell
openspec validate ai-usage-and-audit-query --strict --json
```

没有执行默认 `mvn test` 或不带文件名单的 `vitest run`。Maven 编译测试源文件与实际执行白名单分开，不将其他被编译的测试计为通过。

## 已核对的实现行为

元数据测试覆盖签名先于主体、原始载荷绑定、新 nonce 重试、重放/过期/篡改拒绝、32 KiB及严格 JSON、已有绑定收尾、启停、幂等冲突、真实用量未知/零/未调用区别、分阶段发布锁顺序及过期恢复。客户端替身不监听 HTTP；数据库与 Redis 操作均为 Mock、内存状态或假 JDBC 结果集。

单轮测试核对登记失败不领取/调度、模型调度前确认、可靠累计统计不累加、旧适配器兼容、失败/取消规则、原 Done 字段结算、取消与续期、确认不确定不标成功。原单轮 SSE 的终止事件和仅订阅一次行为回归通过。多轮测试核对 turnId稳定关联、部门绑定、原事务事实、删除正文后保留信息、停采收尾、旧调用不回填、原结算意图和维护重试窗口。

查询测试核对当前精确权限和先鉴权、HTTP状态与no-store、安全投影、超过Long的字符串精度、空token、固定微秒窗口和事件上界、分组键、覆盖缺口与异常保留。无连接映射测试验证真实 MyBatis 映射结果读取，不执行聚合SQL。旧管理审计服务和控制器的回归保持原范围；ModuleBoundaryTest 与 NoDatabaseAccessTest 分别检查分层及 AI 无数据库。

清理测试核对180天边界、晚确认anchor、执行中和RESERVED/PENDING/REVIEW保护、批次互斥与状态复核、配置不一致拒绝、删除失败向事务边界传播。存储替身只证明调用顺序和分支，不证明真实回滚、并发或提交顺序。指标测试核对无主体标签，日志仅固定状态及有界数量，不记录异常原文。

前端测试核对精确权限、目录故障、未登录返回、非法返回地址、不预取另一页、大整数、null、UNKNOWN/KNOWN零/NOT_INVOKED口径、草稿提交隔离、单次续页、迟到响应、同调用多事件、安全详情身份核对与404、未知安全代码按文本展示、403跨重挂载最多核对一次、身份/后台代次清理及离页取消。查询条件与详情不写持久存储或浏览器地址。

## 人工交付核对

| Delta | 对应交付 |
| --- | --- |
| ai-usage-audit | kb-common aiusage协议、app aiusage模块及V21/V22、AI observation编排、内部签名和公共查询、清理与低基数指标 |
| functional-authorization | Permission两项独立用途、V21一次性绑定、原空库引导和撤权行为、当前精确权限校验 |
| admin-web-access | App固定路由、独立导航与守卫、安全返回路径、现有Session身份/后台生命周期 |
| admin-web-ai-usage | 独立API解析、请求代次、用量/分组和调用审计页面、内存清理、覆盖及asOf说明 |

迁移和 Mapper 已静态审阅：事件只引用观测调用，无业务资料级联；阶段身份唯一；已知/未知/未调用约束分开；按调用时间与主体、事件时间/调用、单轮租约/未确认和保留anchor设置索引；查询使用有界limit及超时，所有事件发布在调用写入之后取发布锁。此审阅未实际执行SQL。

升级、停流、启用、停采收尾、停清理和兼容回退见 [运维说明](ai-usage-operations.md)；公共与内部协议见 [API说明](ai-usage-api.md)，页面口径和托管见 [前端说明](admin-ai-usage-web.md)。采集默认关闭，开启需要两侧一致配置与有效独立密钥。修复后的复核见 [验证报告](ai-usage-verification.md)。2026-10-24已将四份 delta 的27项新增需求同步到主规格并逐项核对，36份主规格结构校验及该 change 严格校验通过，34/34项任务完成，变更已[归档](../openspec/changes/archive/2026-10-24-ai-usage-and-audit-query/proposal.md)。

## 验证问题修复后的回归

本次新增20项回归用例，并将已有权限目录、目录控制器及配额服务测试纳入最终白名单，总数从111项增加为149项。权限目录继续检查23项唯一 code、用途及两项新增权限；不是文档内容测试。

清理测试验证删除前取得最大受影响时间、全部删除后推进界限、无删除不推进，以及缺失界限、删除失败或界限更新失败时拒绝确认。真实 MyBatis 结果映射将界限读取为微秒精度 Instant。查询测试覆盖先按1天清理、再改180天，以及用量/审计的界限两侧和精确边界。

主体测试覆盖用户变化、部门变化、停用和空主体，均在 Redis 替身调用前拒绝；不变主体只领取一次，复核推迟到订阅执行。编排测试检查明确拒绝时不调度模型，生成与结算分别保留 NOT_INVOKED、NOT_REQUIRED。

虚拟时间测试覆盖慢额度释放、慢 SETTLED、FINALIZED 确认延迟、在途续期拒绝，以及终态确认失败和生成中真正续期失败。成功分支只输出 done，原模型和额度释放不重放；失败分支仍返回安全 error。

AiObservationTransactionTest 使用真实 Spring 事务拦截器和内存提交队列：两个1天/180天活实例持续冲突至第121秒，独立心跳仍提交，区间事务回滚不撤销心跳，两方清理均被拒绝；同时验证互斥锁后的复核、心跳失败不改区间，以及界限更新失败撤销模拟删除、成功时共同提交。该结果只证明事务传播声明及内存替身行为，不代表真实 PostgreSQL 提交、隔离或并发验证。

最终复跑日志（`target/ai-usage-fixes-*.log`）只是本地构建产物，已于 2026-10-27 建立版本库前清理；结论以本节为准。V22 仅交付和静态审阅，未执行迁移或 SQL；未增加集成测试、外部组件任务或文档内容测试。
