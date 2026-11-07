# 核验报告：harden-worker-embedding-calls

日期：2026-11-07；schema：`spec-driven`；范围：仓库内实现与离线证据。

归档状态：2026-11-07 已同步主规格并归档至 `openspec/changes/archive/2026-11-07-harden-worker-embedding-calls/`。下文保留归档前各次核验与执行记录。

## 结论

**0 CRITICAL、0 WARNING、0 SUGGESTION。** 原 31 项任务及新增 3 项修复任务均已完成，W1 观测偏差已修复，W2 直接竞争回归已补齐。修复影响回归 20 类、103 项全部通过；在既定离线核验范围内全部检查通过，可进入归档流程。

| 维度 | 结果 |
| --- | --- |
| 完整性 | Task Completion：34/34；Spec Coverage：21/21 找到实现（17 ADDED、4 MODIFIED；无 REMOVED／RENAMED） |
| 正确性 | Requirement Implementation Mapping：21/21 已核对，W1 已修复；Scenario Coverage：76/76 已审阅，W1／W2 专门回归通过 |
| 一致性 | Design Adherence：9 组决策已核对，D9 即时停止观测和竞争验证已落实；Code Pattern Consistency：分层、命名、公共兼容扩展及既有事务模式一致，未发现显著新增风格偏差 |

六项核验检查均执行，无因缺少或不可读工件而跳过的检查。外部网络／数据库／供应商验收不在本次执行范围，离线结论不代表这些环境行为已验证。

## 修复后再次核验（2026-11-07）

- 重新读取 `status`、`instructions apply` 和 `list`：本地规划根有效，schema 为 `spec-driven`，四类工件完整可读；34 项可评估任务全部完成，无不可读的跟踪文件。任务状态与下述代码／测试证据分别核对。
- 复核三份规格的 21 条需求、76 个场景及 D1–D9：生产注册表向三个入口提供相同分批器与有界传输；父／子截止、实际退出准入、严格响应校验、故障分类、维护和提交资格检查与原矩阵一致。
- W1：核对一次性停止选择到内存计数的完整通知路径及 Spring 装配，确认停止观测在取消派发之前完成；三用途阻塞回归同时核对退出前计数、跨通道隔离及退出后去重。
- W2：核对三方屏障、受控父截止回调、无偏序循环及三种确定胜者；提交／重试／放弃计数、迟到回调、旧执行和新执行隔离、取消资源退出及释放断言均有直接证据。
- 重新汇总修复日志得到 20 类、103 项，0 失败／错误／跳过。本次再次核验采用源码／测试审阅及既有执行证据，没有重新运行 Maven；103 项为 12:34:55 完成的修复回归，241 项为此前统一回归，均不算作本次新增执行。
- 本轮严格校验及差异检查通过。未发现新增 CRITICAL／WARNING／SUGGESTION，W1／W2 保持关闭；六项核验检查均完成，当前离线范围内可归档。

## 问题关闭记录

### W1 — 已修复：逻辑失败要等实际退出后才能按原因观测

- 修复入口：`kb-worker/src/main/java/com/zhishiku/kb/worker/embedding/execution/WorkerExecution.java:114-123,219-249`；`WorkerExecutions.java:88-98,192-195`；`WorkerEmbeddingMetrics.java:25-36`（后二者同目录）。
- 对照：`specs/worker-embedding-resilience/spec.md:205-211`；`design.md:183-193`；任务 2.4／4.4／9.1。
- 首次选定停止原因时立即通知预注册的 `kb.worker.embedding.execution.stops{purpose,reason}`，观测独立于取消队列；已开始的执行只计一次，空领取、成功交接、重复停止及实际退出不重计。`calls`／`call.duration` 继续表达完整 Embedding 调用返回；例如调用先成功、流水线后超时，可以同时出现 SUCCESS 调用和 TIMEOUT 停止。
- 回归：`kb-worker/src/test/java/com/zhishiku/kb/worker/embedding/execution/WorkerEmbeddingMetricsTest.java:23-105`。三个用途均在调用及取消尚未退出时断言 stops=1，释放后仍为 1；同时验证重复信号、跨通道隔离及空领取／成功不误计。指标测试类本次 6 项全部通过。
- 历史发现：修复前仅有 `calls`／`call.duration`，在调用返回前缺少可区分原因的停止计数。首次核验使用真实执行层、内存指标注册表及屏障复现：

  ```text
  stopped=TIMEOUT occupied=1 lingering=1 timeoutCountBeforeExit=0.0
  timeoutCountAfterExit=1.0
  ```

- 上述历史诊断只读取旧 `calls` 口径；新增 stops 的修复结果以正式 JUnit 回归为准。

### W2 — 已补齐：缺少成功／超时／失租同时竞争的直接测试

- 对照：`specs/worker-embedding-resilience/spec.md:137-139`；任务 2.3／9.2；实现为 `kb-worker/src/main/java/com/zhishiku/kb/worker/embedding/execution/WorkerExecution.java:100-123`、`WorkerExecutions.java:122-130`。
- 新增回归：`kb-worker/src/test/java/com/zhishiku/kb/worker/embedding/execution/WorkerExecutionRaceTest.java:26-168`。包内构造入口注入受控时钟／调度器，实际执行通道仍使用真实协调器；三方屏障并发触发 `succeed()`、父截止回调、`stop(LEASE_LOST)`。
- 32 轮无偏序竞争只核对不变量，不依赖随机调度产生特定胜者；另有三个参数化用例确定覆盖 SUCCESS／TIMEOUT／LEASE_LOST。内存提交／重试／放弃计数之和为一，验证停止计数、迟到截止不撤销成功及不影响新执行、退出前禁止新领取、取消资源退出后恰好一次释放。共 4 项 JUnit 用例通过。
- 历史发现：原 `WorkerExecutionTest` 和 `WorkerCancellationTest` 主要覆盖顺序分支；本次直接并发回归已关闭该缺口。

## 验证与执行证据

### 修复影响回归与最终检查

- 2026-11-07 12:34:55（Asia/Shanghai）完成：Worker 20 个测试类、103 项全部通过，0 失败／错误／跳过，Maven `BUILD SUCCESS`。覆盖两项专门修复、三条生产路径、传输截止、恢复及追踪。
- 完整命令及 20 类清单见 `docs/embedding-offline-validation.md` 的“2026-11-07 Worker 核验 W1／W2 修复回归”；日志为 `C:/Users/Burning/AppData/Local/Temp/opencode/harden-worker-embedding-fixes-20261107.log`。
- 修复过程中 15／19 项定向回归也通过，不与最终 103 项相加；初次 241 项统一验收和首次核验 11 项结果分别保留为历史执行记录。
- 最终 `openspec validate harden-worker-embedding-calls --strict` 与 `git diff --check` 通过，运维指标口径和证据矩阵已人工核对；`openspec instructions apply --change 'harden-worker-embedding-calls' --json` 确认 34/34、`all_done`。

### 首次核验命令与历史结果

```powershell
openspec status --change 'harden-worker-embedding-calls' --json
openspec instructions apply --change 'harden-worker-embedding-calls' --json
mvn -o -pl kb-worker -am test '-Dtest=WorkerExecutionTest,WorkerExecutionsTest,WorkerCancellationTest,WorkerEmbeddingMetricsTest' '-DreuseForks=false' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
openspec validate harden-worker-embedding-calls --strict
git diff --check
```

- 首次核验复跑 11 项：WorkerExecutionTest 5、WorkerExecutionsTest 2、WorkerCancellationTest 2、WorkerEmbeddingMetricsTest 2；0 失败／错误／跳过，12:17:15（Asia/Shanghai）完成。
- OpenSpec strict 与 Git 差异空白检查成功；Git 仅输出换行转换提示。
- 241 项统一白名单是此前实现阶段的成功记录，首次核验读取并引用，未重跑 241 项，也不与 11 项相加。记录为 `docs/embedding-offline-validation.md` 的同日统一回归章节。
- W1 历史独立诊断文件：`C:/Users/Burning/AppData/Local/Temp/opencode/EmbeddingVerificationProbe.java`，不计为 JUnit 用例。使用当时已有编译产物与测试依赖，调用方式：

  ```powershell
  [xml]$report = Get-Content -LiteralPath 'kb-worker\target\surefire-reports\TEST-com.zhishiku.kb.worker.embedding.execution.WorkerEmbeddingMetricsTest.xml' -Raw
  $classPath = ($report.testsuite.properties.property | Where-Object { $_.name -eq 'java.class.path' }).value
  java --class-path $classPath 'C:\Users\Burning\AppData\Local\Temp\opencode\EmbeddingVerificationProbe.java'
  ```

### 传输和环境边界

人工复核 JDK 21.0.7 本机 `src.zip` 中的 MultiExchange 重试配置及 Http1Response／Http1AsyncReceiver 订阅取消路径。Worker 启动处理器设置禁止连接重试和非幂等自动重试，JdkSession 使用 HTTP/1.1、无代理及 NEVER 重定向。该源码审阅和内存 Session 调用次数不等价于真实网络终止／无重放验收。

本次没有启动监听服务、数据库、Redis、对象存储、供应商或前后端应用，也没有运行文档内容／标题／关键词／存在性断言测试。源码、SQL、事务注解和内存仓储证明调用契约；真实锁互斥、事务回滚、崩溃恢复及上游计费仍属报告边界。

## 需求实现映射

路径缩写：
- `WE` = `kb-worker/src/main/java/com/zhishiku/kb/worker/embedding/`
- `WP` = `kb-worker/src/main/java/com/zhishiku/kb/worker/parse/`
- `CE` = `kb-common/src/main/java/com/zhishiku/kb/common/embedding/`
- `AE` = `kb-app/src/main/java/com/zhishiku/kb/app/embedding/`

| ID | 需求 | 主要实现位置 | 核对结果 |
| --- | --- | --- | --- |
| WR1 | Worker 的全部 Embedding 入口必须使用统一运行限界 | WE/EmbeddingProfilesConfiguration.java:18-27；WE/execution/WorkerEmbeddingSettings.java:14-65 | 三条生产路径使用同一注册表与预算，远程无预算入口拒绝 |
| WR2 | HTTP 子批必须同时满足条数与实际编码字节限制 | WE/execution/WorkerEmbeddingBatchPlanner.java:21-54；WorkerEmbeddingJson.java:32-41 | 完整单条预检、同编码器计数、有序拆分 |
| WR3 | 子批结果必须保持输入对应关系与原子提交边界 | CE/RemoteProfileEmbeddingClient.java:56-124；WE/service/EmbeddingBatchProcessor.java:59-69 | 整批校验后交接，不返回成功前缀 |
| WR4 | 响应必须在完整读取和解析之前受字节限界 | WE/execution/WorkerEmbeddingTransport.java:235-301 | 非 200 零需求分支；实际长度和一字节越限探测 |
| WR5 | 响应 JSON 必须完整且严格校验 | WE/execution/WorkerEmbeddingJson.java:24-57 | 严格 UTF-8、深度、重复字段、尾随根检查 |
| WR6 | 执行必须共享单调时钟截止时间 | WE/execution/WorkerExecution.java:50-112；WorkerEmbeddingTransport.java:120-212 | 父／子截止不因子批或读取活动重置 |
| WR7 | 业务与探测必须使用独立且有界的实际执行容量 | WE/execution/WorkerExecutions.java:33-69,168-179 | 单业务／单探测；零业务等待队列；claim 在准入后 |
| WR8 | 逻辑终结不得提前释放实际执行容量 | WE/execution/WorkerExecutions.java:100-130,198-229 | 工作、资源和取消任务共同控制释放；WorkerExecutionRaceTest 直接三方竞争回归通过，W2 已关闭 |
| WR9 | 取消必须绑定本次执行并阻止后续工作 | WE/execution/WorkerExecution.java:114-249；两类 Runner heartbeat | 每次执行独立状态，旧心跳和计时器不作用于新执行 |
| WR10 | 单次传输不得自动重试或重定向外发 | WE/execution/EmbeddingHttpPolicy.java:11-18；WorkerEmbeddingTransport.java:355-393 | 一次 Session.send；JDK 策略静态审阅通过，网络实测范围如上 |
| WR11 | 故障必须具有一致且有限的分类 | WE/execution/WorkerEmbeddingFailure.java；WP/FailureClassifier.java:23-30；WE/service/EmbeddingRetryPolicy.java:22-37 | 临时／永久／控制停止分类，持久写回仍有原围栏 |
| WR12 | 探测必须独立限时且不得被迟到成功复活 | WE/EmbeddingReadinessScheduler.java:72-134 | profile 游标、独立预算、唯一存储调用、迟到成功转失败 |
| WR13 | 调用诊断必须反映实际占用且不包含敏感内容 | WE/execution/WorkerExecution.java:234-249；WorkerEmbeddingMetrics.java:17-57；WorkerEmbeddingTransport.java:69-99 | 固定标签、即时停止计数及实际占用可独立观察；退出前和去重回归通过，W1 已关闭 |
| DP1 | 失败分类必须决定重试或死信 | WP/FailureClassifier.java:23-67；ParseTaskRunner.java:223-265 | 永久直接死信；暂时按原预算退避 |
| DP2 | 在途普通解析必须无伪失败地安全收敛 | WP/ParseMaintenanceService.java:21-45；ParseTaskRecoveryService.java:225-232；ParseTaskRunner.java:311-329 | 维护补偿、资格复核及停止信号沿原身份执行 |
| DP3 | 普通解析领取前必须取得实际业务容量 | WP/ParseTaskRunner.java:135-157；WE/execution/WorkerExecutions.java:52-69 | 实际领取上限一；未准入不 claim |
| DP4 | 普通解析必须传播流水线剩余预算并隔离迟到结果 | WP/ParseTaskRunner.java:175-189,268-284；DefaultParsePipeline.java:90-109 | 从流水线开始至完整结果交接共享预算 |
| ER1 | 批次提交与进度必须原子且可续建 | WE/service/EmbeddingBatchGenerator.java:34-65；EmbeddingBatchCommitter.java:21-45 | 生成与提交分离，原事务复核正文及进度；真实回滚未实测 |
| ER2 | 重建失败必须保留进度且只能受控恢复 | WE/service/EmbeddingFailureService.java:29-47；AE/service/EmbeddingCommandService.java:25-38；EmbeddingCommandTransaction.java:49-101 | 退避／BLOCKED、旧持有者围栏及原 resume 版本／目标／幂等门禁 |
| ER3 | 重建必须在共享业务容量内领取和执行 | WE/EmbeddingRebuildRunner.java:47-79 | 与解析共享容量，空批次按原租约释放 |
| ER4 | 重建生成必须具有独立截止并响应失租取消 | WE/service/EmbeddingBatchProcessor.java:50-97；EmbeddingRebuildRunner.java:82-107 | 读取前开始预算，心跳 false／异常取消，成功交接后保留原提交围栏 |

## 场景覆盖清单

编号按各增量规格出现顺序。下表是代码／测试覆盖审阅，不将静态证据计成真实集成测试通过。类名对应各模块 `src/test/java`；`组合` 表示由多个现有测试及实现路径共同支撑。76 个场景均找到直接或组合证据，W1／W2 对应专门回归已补齐并通过。

### worker-embedding-resilience：40 场景

| 场景 | 证据／结果 |
| --- | --- |
| 三条入口一致受限 | WorkerEmbeddingWiringTest、WorkerEmbeddingPathsTest |
| 调整运行限额 | WorkerEmbeddingSettingsTest + EmbeddingProfileTest／EmbeddingRegistryTest；运行参数不进入 profile 构造，组合 |
| 非法配置 | WorkerEmbeddingSettingsTest |
| 条数允许但字节不足 | WorkerEmbeddingBatchPlannerTest |
| 转义和前缀占用预算 | WorkerEmbeddingBatchPlannerTest.exactUtf8EncodingIncludesPrefixEscapingAndEnvelope |
| 最后一条输入单独超限 | WorkerEmbeddingBatchPlannerTest.lastOversizedInputFailsBeforeAnyRequestAndDoesNotLeakInput |
| 请求恰好达到限额 | WorkerEmbeddingBatchPlannerTest |
| 各子批下标重新从零开始 | WorkerEmbeddingResultsTest.byteLimitedSubBatchesRestoreLocalIndicesToWholeInputOrder |
| 后续子批失败 | WorkerEmbeddingResultsTest、WorkerEmbeddingPathsTest |
| 无长度头的大响应 | WorkerEmbeddingTransportTest |
| 响应边界 | WorkerEmbeddingTransportTest.exactResponseLimitSucceedsAndOneExtraByteFailsWithoutLengthHeader |
| 错误正文包含敏感文本 | WorkerEmbeddingTransportTest.non200NeverRequestsBodyOrRetries |
| 重复关键字段 | WorkerEmbeddingTransportTest.invalidJsonRejectsEntireResponse |
| 深度与尾随内容 | WorkerEmbeddingTransportTest；默认深度绑定由 WorkerEmbeddingSettingsTest 支撑，组合 |
| 未知字段有界兼容 | WorkerEmbeddingTransportTest + WorkerEmbeddingJson.decode，组合 |
| 解析已消耗部分时间 | ParsePipelineBudgetTest |
| 多个 HTTP 子批 | EmbeddingRebuildBudgetTest |
| 持续小块响应 | 固定父截止、每次 read 前后 check；WorkerEmbeddingTransportDeadlineTest 的未结束正文与 WorkerExecutionTest 组合 |
| 迟到的合法响应 | WorkerEmbeddingTransportDeadlineTest、EmbeddingProbeExecutionTest |
| 旧解析仍在退出中 | ParseTaskAdmissionTest、WorkerEmbeddingPathsTest |
| 长业务与探测并行 | WorkerEmbeddingPathsTest、EmbeddingProbeExecutionTest |
| 没有可领取任务 | WorkerExecutionsTest、EmbeddingRebuildRunnerTest |
| 上游工作忽略中断 | WorkerCancellationTest、ParseTaskAdmissionTest |
| 取消动作阻塞 | WorkerCancellationTest |
| 完成与取消竞争 | WorkerExecutionRaceTest：32 轮无偏序三方竞争及三种确定胜者，唯一收尾、旧计时器隔离及恰好一次释放；W2 已关闭 |
| 首个子批返回前失租 | Runner 心跳测试 + WorkerEmbeddingResultsTest／EmbeddingRebuildBudgetTest，组合 |
| 旧计时器迟到执行 | WorkerExecutionTest.staleChildTimerCannotCancelNextBatchOrCompletedGeneration |
| 进程关闭 | WorkerCancellationTest、EmbeddingProbeExecutionTest |
| 上游要求稍后重试 | WorkerEmbeddingTransportTest、WorkerEmbeddingFailureTest |
| 返回重定向 | WorkerEmbeddingTransportTest + JdkSession NEVER 配置，组合 |
| 响应太大 | WorkerEmbeddingTransportTest |
| 临时模型故障 | ParseEmbeddingRecoveryTest、EmbeddingRetryTest |
| 输入或响应超限 | WorkerEmbeddingBatchPlannerTest／WorkerEmbeddingTransportTest + 两条恢复测试，组合 |
| 协议错误与认证拒绝 | WorkerEmbeddingPathsTest、WorkerEmbeddingMetricsTest、两条恢复测试 |
| 探测超时后返回合法向量 | EmbeddingProbeExecutionTest.successAtDeadlineStoresOnlyFailureEvenWithoutTimerCallback |
| 一个 profile 失败后继续 | EmbeddingProbeExecutionTest.nextProfileWaitsForActualExitAndReceivesFreshBudgetWhileBusinessStaysBusy |
| 探测槽位仍占用 | EmbeddingProbeExecutionTest |
| 逻辑失败后仍占用 | WorkerEmbeddingMetricsTest：三个用途均在调用和取消尚未退出时 stops=1，释放后不重计，另一通道可独立停止；W1 已关闭 |
| 出站上下文隔离 | WorkerEmbeddingWiringTest、两侧 EmbeddingSupplierIsolationTest |
| 可诊断的固定错误 | WorkerEmbeddingFailureTest、WorkerEmbeddingMetricsTest、ParseEmbeddingRecoveryTest |

### document-parsing：18 场景

| 场景 | 证据／结果 |
| --- | --- |
| 永久错误立即死信 | FailureClassifierTest、ParseTaskRunnerRecoveryTest、ParseEmbeddingRecoveryTest |
| 暂时性错误退避重试 | ParseTaskRunnerRecoveryTest、ParsePropertiesTest、ParseEmbeddingRecoveryTest |
| 租约失守不伪造失败 | ParseTaskRunnerRecoveryTest、ParseEmbeddingRecoveryTest、ParseRecoveryIdentityTest |
| Embedding 载荷或协议永久错误 | ParseEmbeddingRecoveryTest + 真实 transport／planner 测试，组合 |
| 模型返回认证拒绝或请求超时状态 | WorkerEmbeddingFailureTest + ParseEmbeddingRecoveryTest；WorkerEmbeddingPathsTest 的 401 链路 |
| 在途任务因维护中止 | ParseMaintenanceServiceTest |
| 巡检与维护收敛并发 | ParseMaintenanceServiceTest、ParseRecoveryIdentityTest + 控制锁／幂等 SQL，组合 |
| 手工重试处于维护期间 | CandidateIndexRefreshServiceTest.maintenanceOnlyQueuesNewCycleWithControlTaskDocumentLockOrder + ParseTaskClaimServiceTest，已有测试源码审阅 |
| 维护失效后模型仍未退出 | ParseTaskAdmissionTest、WorkerEmbeddingPathsTest + 维护资格围栏，组合 |
| 维护与调用超时竞争 | ParseEmbeddingRecoveryTest + ParseRecoveryIdentityTest.allMaintenanceModesPreventLateCallbacksAndDeadLetters，组合 |
| 调度时槽位仍被占用 | ParseTaskAdmissionTest |
| 配置一次领取多条 | ParseTaskAdmissionTest |
| 数据库领取返回空或异常 | WorkerExecutionsTest |
| 前置解析耗时 | ParsePipelineBudgetTest |
| 超时但仍有数据库资格 | ParseEmbeddingRecoveryTest、ParseRecoveryIdentityTest，组合 |
| 超时后底层忽略中断 | ParseTaskAdmissionTest、WorkerCancellationTest |
| 旧心跳迟到 | ParseTaskRunnerRecoveryTest.lateHeartbeatOnlyInvalidatesItsOwnExecution |
| HTTP 子批部分成功 | WorkerEmbeddingPathsTest 的解析后批失败；ParsePublishIdentityTest 支撑原发布门禁 |

### embedding-rebuild：18 场景

| 场景 | 证据／结果 |
| --- | --- |
| 写入向量后进度更新失败 | EmbeddingBatchCommitterTest.progressFailurePropagatesInsteadOfReturningSuccessfulCommit + 原事务边界；真实回滚未实测 |
| 成功批次后进程退出 | EmbeddingResumeTest；服务重建与内存仓储证明续建选择，真实崩溃未实测 |
| 租约接管后旧实例返回 | EmbeddingLeaseServiceTest、EmbeddingLeaseClockTest、EmbeddingBatchCommitterTest |
| 后续 HTTP 子批失败 | WorkerEmbeddingPathsTest 的重建后批失败 |
| 模型持续限流直至耗尽 | EmbeddingRetryTest.transientFailurePersistsBackoffThenExhaustionBlocksAndStaleFailureDoesNothing |
| 管理员修复后恢复 | EmbeddingCommandTest.resumeOnlyRestoresFixedTargetAndSavedPhaseWithNewEpochAndVersion，已有测试源码审阅 |
| 恢复预检仍失败 | EmbeddingCommandScenarioTest.resumePreflightFailureBeforeOrInsideLockReturns503AndPreservesBlockedRun，已有测试源码审阅 |
| 载荷超限立即阻塞 | EmbeddingRetryTest + planner／transport 超限测试，组合 |
| 生成超时仍持有资格 | EmbeddingRebuildBudgetTest、EmbeddingRetryTest |
| 故障写回前已失租 | EmbeddingRetryTest、EmbeddingLeaseServiceTest |
| 普通解析正在退出 | EmbeddingRebuildRunnerTest、WorkerEmbeddingPathsTest |
| 超时后下次调度到达 | WorkerCancellationTest、EmbeddingRebuildRunnerTest，组合 |
| 没有待处理条目 | EmbeddingRebuildRunnerTest.parseOccupancyPreventsClaimAndEmptyBatchReleases |
| 租约持续续期但生成未结束 | EmbeddingRebuildBudgetTest.readAndAllSubBatchesShareBudgetAndStaleTimersCannotRevokeCommit + WorkerExecutionTest，组合 |
| 多子批共用剩余时间 | EmbeddingRebuildBudgetTest |
| 心跳返回失败或抛出异常 | EmbeddingRebuildRunnerTest、EmbeddingRebuildBudgetTest |
| 生成完成后合法提交 | EmbeddingRebuildBudgetTest + EmbeddingBatchCommitterTest，组合 |
| 关闭期间仍有生成 | WorkerCancellationTest + EmbeddingRebuildRunner.close 路径，组合 |

## 设计一致性

| 决策组 | 核对 |
| --- | --- |
| D1 Worker 执行层与公共协议分工 | common 仅增加可选分批扩展，Worker 独立装配；app 默认构造兼容 |
| D2 配置与范围 | 五项默认值／最大值、运行限额与 profile 分离、启动绑定检查一致 |
| D3 双限制分批 | 预检全部单条、按需编码、顺序组装、无响应失败递归重发 |
| D4 隔离传输 | JDK 独立 Session、有界正文、严格 JSON、有限辅助线程及终止等待；真实网络边界已注明 |
| D5 逻辑结果与数据库资格 | TIMEOUT 不改变租约布尔事实，控制停止无模型恢复；成功交接后保留数据库围栏 |
| D6 准入与固定资源 | claim 在执行器准入后；业务／探测隔离；取消资源合并到有界排空任务 |
| D7 三路径交付 | 解析流水线／重建读取前／每 profile 分别启动预算，交接一次，清理后推进探测游标 |
| D8 错误映射 | 固定受控原因、解析细分类、重建粗码、原退避与人工恢复 |
| D9 观测与验证 | stops 即时计数且独立于完整调用和实际占用；三方竞争及确定胜者回归通过，W1／W2 已关闭 |

本报告保留首次核验发现及诊断，已按后续 W1／W2 修复、离线回归及再次核验更新结论。

## 主规格同步与归档

- `document-parsing`：修改失败分类及在途收敛要求，新增领取前准入、流水线预算两条要求。
- `embedding-rebuild`：修改原子进度及失败恢复要求，新增共享业务容量、独立生成截止两条要求。
- `worker-embedding-resilience`：新建主规格，写入 13 条要求，Purpose 沿用增量原文。
- 合计新增 17 条、修改 4 条；逐项对照确认全部增量已同步，原主规格的 Purpose 与增量未涉及的要求／场景均保留。三份目标主规格严格校验通过；全库普通校验 59 项通过、0 失败，另有 `admin-web-access` 的既有超长描述提示。
- 用户确认同步并归档，34 项任务与四类工件均完成，`.openspec.yaml` 随变更目录保留。README 已补充配置、容量、错误分类、停止指标和分次验证入口；文档引用已指向归档路径。
- 同步与归档阶段采用上述已完成回归作为实现证据，没有新增业务测试执行或外部环境验收。
