# Embedding OpenSpec 实现核验

## 2026-11-07 Worker 调用限界证据

变更：`harden-worker-embedding-calls`，schema：`spec-driven`。本节仅对应本变更三份增量规格。
初次统一白名单于 10:53:14 完成，241 项通过（common 22、app 36、Worker 183）；W1／W2 修复影响回归于 12:34:55 完成，Worker 20 类、103 项通过。两次均为 0 失败／错误／跳过，结果不累加；完整命令、测试类清单及环境见 `docs/embedding-offline-validation.md` 的同日两节记录。

实现路径约定：执行组件位于 `kb-worker/src/main/java/com/zhishiku/kb/worker/embedding/execution/`，解析组件位于相邻 `parse/`，重建服务位于 `embedding/service/`。公共组件位于 `kb-common/src/main/java/com/zhishiku/kb/common/embedding/`。下列测试均位于对应模块的 `src/test/java/`，名称可唯一定位；同名 app／Worker 测试明确标注模块。

### W1／W2 修复证据

- **W1 已修复**：`WorkerExecution.dispatchStop` 在首次逻辑停止时同步通知，`WorkerExecutions.recordStop` 绑定短内存观测，`WorkerEmbeddingMetrics` 预注册 `kb.worker.embedding.execution.stops{purpose,reason}`。该计数不等待调用或取消动作退出；`calls`／`call.duration` 保留完整调用返回口径。`WorkerEmbeddingMetricsTest.timeoutIsObservedBeforeBlockedCallAndCancellationExitWithoutDoubleCounting` 覆盖 PARSE／REBUILD／PROBE、阻塞取消、重复信号、最终退出和跨通道隔离；`emptyClaimsAndSuccessfulHandoffsAreNotCountedAsStops` 验证空领取及成功不误计。
- **W2 已补齐**：`WorkerExecutionRaceTest.simultaneousSuccessDeadlineAndLeaseLossHaveOneOutcomeWithoutAssumingAWinner` 运行 32 轮无偏序竞争；`eachWinnerKeepsOneSettlementAndOneRelease` 确定覆盖 SUCCESS／TIMEOUT／LEASE_LOST 三种胜者。真实执行协调器配合受控时钟、三方屏障和内存提交／恢复计数，验证唯一收尾、旧截止隔离、退出前禁止新领取、取消完成后恰好释放一次。JUnit 共计 4 项。
- 修复回归覆盖执行层、三条生产路径、恢复和追踪，共 103 项；原 241 项记录为初次统一验收，未作为本次重跑结果。任务完成 34/34，当前核验为 **0 CRITICAL、0 WARNING、0 SUGGESTION**；21 条需求及 76 个场景的完整矩阵见 `openspec/changes/archive/2026-11-07-harden-worker-embedding-calls/verification.md`。

### worker-embedding-resilience（13 条需求）

| 需求 | 实现入口 | 本次直接／组合证据 |
| --- | --- | --- |
| 全部入口统一运行限界 | WorkerEmbeddingSettings、StartupEnvironmentCheck、EmbeddingProfilesConfiguration | WorkerEmbeddingSettingsTest 覆盖默认／非法配置；WorkerEmbeddingWiringTest 与 WorkerEmbeddingPathsTest 覆盖三个用途实际调用；EmbeddingProfileTest／EmbeddingRegistryTest 回归身份 |
| 条数与编码字节双限制 | WorkerEmbeddingBatchPlanner、WorkerEmbeddingJson、公共 EmbeddingBatchPlanner | WorkerEmbeddingBatchPlannerTest：UTF-8、补充字符、转义、前缀、包络、等号边界、末尾单条超限零发送 |
| 输入对应与原子交付 | RemoteProfileEmbeddingClient、EmbeddingResult、两条生成流水线 | WorkerEmbeddingResultsTest：局部 index 重排、重复下标、后批失败；RemoteProfileEmbeddingClientTest：缺失下标等协议错误；WorkerEmbeddingPathsTest：成功整批交付／失败零提交 |
| 读取前响应字节限界 | WorkerEmbeddingTransport.subscriber/read | WorkerEmbeddingTransportTest：声明超限零需求、无长度头、谎报长度、等号／多一字节、错误状态零正文消费 |
| 严格 JSON | WorkerEmbeddingJson.decode | WorkerEmbeddingTransportTest：重复字段、深度、尾随根、损坏／空／非对象、UTF-8、未知字段与编码 |
| 单调父／子截止 | WorkerExecution、WorkerEmbeddingTransport、各 Runner | WorkerExecutionTest；ParsePipelineBudgetTest；EmbeddingRebuildBudgetTest；WorkerEmbeddingTransportDeadlineTest：持续未结束正文、迟到响应、客户端终止时间计入截止 |
| 双通道实际容量 | WorkerExecutions、ParseTaskRunner、EmbeddingRebuildRunner、EmbeddingReadinessScheduler | WorkerExecutionsTest、ParseTaskAdmissionTest、EmbeddingRebuildRunnerTest；WorkerEmbeddingPathsTest：解析仍占用时重建零领取，探测可完成 |
| 逻辑终结不提前释放 | WorkerExecutions.releaseIfExited、WorkerExecution 资源登记 | WorkerCancellationTest：忽略中断、阻塞取消、关闭；WorkerEmbeddingTransportDeadlineTest／EmbeddingProbeExecutionTest：实际退出之前保留槽位；WorkerExecutionRaceTest：三方竞争、唯一收尾与恰好一次释放，W2 已关闭 |
| 执行绑定取消 | WorkerExecution.stop、两条 heartbeat、各阶段交接 | WorkerExecutionTest：晚注册及旧计时器；ParseTaskRunnerRecoveryTest／EmbeddingRebuildRunnerTest：旧心跳不取消新执行；EmbeddingRebuildBudgetTest：失租后不发送后批 |
| 单次传输不重放 | EmbeddingHttpPolicy、WorkerEmbeddingTransport.JdkSession | WorkerEmbeddingWiringTest 验证启动策略；WorkerEmbeddingTransportTest 验证 3xx／429／错误状态一次 Session.send 且不消费错误体。真实 JDK 网络行为仅人工审阅配置，未作网络验收 |
| 有限故障分类 | WorkerEmbeddingFailure、FailureClassifier、EmbeddingRetryPolicy | WorkerEmbeddingFailureTest；ParseEmbeddingRecoveryTest 全原因分支；EmbeddingRetryTest 全原因恢复及旧持有者拒绝写回；WorkerEmbeddingPathsTest 真实 401 路径 |
| 探测独立限时与唯一结果 | EmbeddingReadinessScheduler.poll/probe | EmbeddingProbeExecutionTest：截止处成功只写 false、持久化一次失败、实际退出后下一 profile 新预算、关闭不继续；Worker/app EmbeddingReadinessSchedulerTest 与公共 EmbeddingProbeTest 兼容回归 |
| 诊断反映占用且无敏感内容 | WorkerExecution.dispatchStop、WorkerEmbeddingMetrics、固定故障对象、隔离 JDK transport | WorkerEmbeddingMetricsTest：退出前 stops、重复信号不重计、跨通道隔离、occupied／lingering 和固定标签，W1 已关闭；WorkerEmbeddingFailureTest：无 cause／suppressed；WorkerEmbeddingWiringTest 和两侧 EmbeddingSupplierIsolationTest：内部上下文不外发 |

### document-parsing（4 条需求）

| 需求 | 实现入口 | 本次直接／组合证据 |
| --- | --- | --- |
| 分类决定重试／死信 | FailureClassifier、ParseTaskRunner.handleFailure、ParseTaskRecoveryService | FailureClassifierTest、ParseEmbeddingRecoveryTest、ParseTaskRunnerRecoveryTest：固定摘要、永久死信、暂时退避、控制停止；失败服务原资格 SQL 的离线验证由 ParseRecoveryIdentityTest 提供 |
| 在途无伪失败收敛 | ParseTaskRunner.heartbeat、ParseMaintenanceService、ParseTaskRecoveryService | ParseMaintenanceServiceTest：幂等维护补偿和历史保留；ParseRecoveryIdentityTest：维护／旧身份拒绝；WorkerEmbeddingPathsTest：超时无部分发布；人工核对维护期间重放只入队 |
| 领取前业务容量 | ParseTaskRunner.poll、ParseTaskClaimService、WorkerExecutions | ParseTaskAdmissionTest：配置多条仍只领取一条、忙时零领取；ParseTaskClaimServiceTest：维护／无解析器门禁；WorkerExecutionsTest：空领取和领取异常释放 |
| 剩余预算与迟到隔离 | DefaultParsePipeline、ParseTaskRunner.runPipeline／成功交接 | ParsePipelineBudgetTest：899 秒前置耗时和到期零调用；ParseTaskAdmissionTest：逻辑结束仍占用；ParseTaskRunnerRecoveryTest：旧心跳隔离；ParsePublishIdentityTest：原发布身份复核 |

### embedding-rebuild（4 条需求）

| 需求 | 实现入口 | 本次直接／组合证据 |
| --- | --- | --- |
| 原子进度与续建 | EmbeddingBatchGenerator、GeneratedRebuildBatch、EmbeddingBatchCommitter | WorkerEmbeddingPathsTest：第二 HTTP 子批失败不交给 committer；EmbeddingBatchCommitterTest：资格／正文复核、写入与进度顺序、失败传播；EmbeddingResumeTest：内存重建服务仅重做未提交项 |
| 失败保留进度且受控恢复 | EmbeddingBatchProcessor、EmbeddingRetryPolicy、EmbeddingFailureService、既有 app resume 服务 | EmbeddingRetryTest：退避、耗尽 BLOCKED、旧持有者写回无效；EmbeddingResumeTest：成功项保留；人工核对原 resume 的 expectedVersion、目标与权限／幂等门禁，本次未重跑管理 API 全集 |
| 共享容量内领取 | EmbeddingRebuildRunner.tick、WorkerExecutions | EmbeddingRebuildRunnerTest：解析占用时零领取、空批次释放；WorkerEmbeddingPathsTest：真实解析退出后方可重建；WorkerCancellationTest：取消动作仍占用 |
| 独立截止与失租取消 | EmbeddingBatchProcessor、EmbeddingBatchGenerator、EmbeddingRebuildRunner.heartbeat | EmbeddingRebuildBudgetTest：读取计时、续租不延长、所有子批共用、旧计时器不撤销交接；EmbeddingRebuildRunnerTest：false／异常取消和迟到心跳；EmbeddingLeaseServiceTest／EmbeddingLeaseClockTest：token／epoch 与数据库时间契约 |

### 证据范围

21 条需求均定位到实现与本次离线证据，组合测试包含真实三条生产组件链路；存储、原件和 HTTP 会话是内存替身。事务声明、SQL 条件和失败传播只能证明调用契约，不能证明真实数据库回滚／锁互斥。没有运行外部组件、前后端联调、文档内容断言或真实供应商验证；JDK 网络重试／终止、真实崩溃恢复、上游取消计算和计费行为均不由这些测试证明。

运维配置、错误码、恢复门禁与指标由人工对照实现核对；以下 2026-10-07 内容保留为另一变更的历史证据，不纳入上述两次回归结果。

日期：2026-10-07

变更：`embedding-profile-consistency-and-rebuild`，schema：`spec-driven`。

## 当前结论（2026-10-07 W3-W5 补测后，Asia/Shanghai）

已完成 4 份增量规格、23 条 ADDED requirement、65 个 scenario 的逐项审阅及 D1-D9 设计核对；没有
MODIFIED/REMOVED/RENAMED。两处实现偏差 W1/W2 已修复，本轮按用户确认补齐 W3-W5 的 4 个专门测试场景；新增任务
11.1-11.3，完成后清单为 39/39。任务完成不等于真实环境验证通过。

当前已知 CRITICAL/WARNING 均已关闭：原 UUID 初始化阻断保持已修复，W1/W2 修复回归保持通过，W3-W5 专门测试缺口已补齐。65
个场景中，64 个有代码与离线测试/静态断言证据，1 个为升级操作文档要求。这里的“证据”不等于真实数据库行为已经验证。

| 维度  | 检查结果                                                                                                                                                             |
|-----|------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 完整性 | Task Completion：39/39（原 34 项加 2 项修复和 3 项补测）；Spec Coverage：23/23 需求定位到实现，未发现整条需求完全缺失                                                                              |
| 正确性 | Requirement Implementation Mapping：23/23 已审阅，EC6/ER9 已修复复核；Scenario Coverage：65/65 已审阅，DP-01、ER-06、ER-07、ER-20 的专门回归已补齐；64 个离线/静态或组合证据加 1 个文档证据，不计为 65 个真实环境测试通过 |
| 一致性 | Design Adherence：D1-D9 均已核对，D2/D8 的 W1/W2 已修复；Code Pattern Consistency：保持公共纯 JDK 契约、短事务栅栏、现有受权管理 API 和无密钥 DTO；未新增依赖或迁移                                           |

结论：在既定离线范围内，先前核验发现的实现偏差和专门测试缺口已全部关闭，完整白名单 397
项通过；本轮仅修改测试、白名单和记录，不修改生产代码或规格行为。核验为建议性检查，不增加真实环境验收前置条件，也不宣称生产可用性已经验证；未归档或同步主规格。

## W3-W5 补测证据

- DP-01 / W3：
  `DocumentMaintenanceContractTest.maintenanceVersionUploadStoresOriginalAndQueuesWithoutReplacingIndexedCurrentVersion`
  在 QUIESCING/REBUILDING/VERIFYING/BLOCKED 四种状态调用真实 `uploadVersion -> createVersion`。存储及 Mapper Stub
  保存原件/版本/任务；断言文件字节和摘要、先授权再存储、pending/零 attempts、旧 current_version/indexed
  和原件保持、旧版本授权下载、进度等待原因；写 Mapper 调用白名单和 INSERT/事务静态断言限制伪发布。组合
  `ParseTaskClaimServiceTest.maintenanceNeverClaimsOrChangesAttemptAndVersionState`、既有授权 BM25/当前版本 SQL
  测试支持领取与检索边界，不作为真实集成测试。
- ER-06 / W4：`EmbeddingCommandScenarioTest.competingDifferentRequestsAndTargetsHaveOneWinnerAfterInterleavedPreflight`
  双向交换赢家/输家；在一个请求锁前预检过程中执行另一请求，验证真实命令服务与独立 MockMvc 返回一个 202、另一个 409。当前
  epoch 的后续竞争仍 409，只创建一个 run、一条成功审计，首次回执可重放；结合 V15 唯一索引静态断言，不声称真实数据库锁互斥已验证。
- ER-07 / W4：`EmbeddingCommandScenarioTest.partiallyRebuiltRunRejectsOldTargetWithoutChangingProgressOrMaintenance` 覆盖
  REBUILDING/VERIFYING/BLOCKED，使用已有成功进度及旧 active/新 target 的状态。当前 epoch 请求旧模型 START 返回 409，RESUME
  携带修改目标字段返回 400；原 target/run/计数/epoch/维护状态和审计快照保持，除 operation 查询外不允许命令 Mapper 调用。
- ER-20 / W5：`EmbeddingCommandScenarioTest.resumePreflightFailureBeforeOrInsideLockReturns503AndPreservesBlockedRun` 以真实
  readiness 服务及可控时钟覆盖三种 resume_phase 的锁前到期和锁内刚好到期，共 6 例。真实命令服务及独立 MockMvc 返回
  503，BLOCKED/原目标/已成功计数/claim token/attempts/恢复阶段和审计保持；只允许预期只读调用，禁止
  resumeRun/resumeItems/resumeControl/record，故拒绝时不会重置 item 或增加成功审计。
- 共新增 15 例（上传 4、竞争 2、旧模型拒绝 3、恢复预检 6）。定向回归分别 12/12、21/21；最终完整白名单 397/397（common 23、app
  261、gateway 8、Worker 102、ai 3），0 失败/错误/跳过。日志：`target/embedding-upload-focused-20261007-155036.log`、
  `target/embedding-command-scenarios-focused-20261007-155356.log`、
  `target/embedding-four-scenarios-final-20261007-155642.log`。新增测试类已加入白名单，83 个名称对应 88 个顶层测试类，另 73
  个顶层测试类未执行；OpenSpec strict 通过。详细命令见 `docs/embedding-offline-validation.md`。

## W1/W2 修复证据

- W1：`EmbeddingActivationService` 完全移除 `EmbeddingClientRegistry` 依赖，使用独占控制锁内读取的目标 ID/fingerprint
  校验集群双侧 readiness。覆盖复核、协议/角色/时效检查、状态 CAS 及同事务审计保持。采用原建议中的“基于持久身份与集群
  readiness”方案，因此无目标模型的协调者可以安全完成激活，而不是强制每台协调者都配置目标。
- W1 回归：`kb-worker/src/test/java/com/zhishiku/kb/worker/embedding/EmbeddingActivationTest.java` 新增
  `coordinatorWithoutTargetClientUsesFleetReadinessAndOtherCoordinatorDoesNotCompleteTwice`、
  `fleetReadinessStillRequiresBothMatchingFreshRolesAndProtocol`；前者用共享控制 Stub 验证两协调服务交错只激活一次且无误阻塞，后者验证缺侧/错
  ID/fingerprint/协议/失败/到期/未来时间的拒绝。结构断言禁止激活服务重新依赖本地模型注册表或客户端。
- W2：`kb-app/src/main/java/com/zhishiku/kb/app/search/embedding/EmbeddingInstanceReadiness.java`
  保存本机所见控制事实及本机探测；与调度器共用启动 UUID，拒绝较旧 epoch 覆盖，读取时检查探测有效期。`SearchService` 缺配置仍
  BM25，同时报告 MISSING_PROFILE；`EmbeddingAdminReadService.status` 在原 embedding:read 边界内返回
  localEmbedding，不新增公开健康端点，不影响全站存活或 BM25。
- W2 回归：新增 `kb-app/src/test/java/com/zhishiku/kb/app/embedding/EmbeddingInstanceReadinessTest.java` 5 项；扩展
  `QueryConsistencyTest` 验证缺配置不调用旧模型及实例信号，扩展 app `EmbeddingReadinessSchedulerTest` 验证与持久探测相同
  UUID、无查询时发现缺配置/控制失败，扩展 `EmbeddingAdminReadTest` 验证受权 HTTP
  字段和集群/实例状态区分。覆盖恢复、过期、错误身份/协议、旧进程探测不复用、日志固定原因去重/脱敏。
- 定向回归 23/23；完整白名单 382/382（common 23、app 246、gateway 8、Worker 102、ai 3），0 失败/错误/跳过，另有 73 个顶层测试类未执行。日志：
  `target/embedding-deviations-focused-20261007-145415.log`、`target/embedding-deviations-offline-20261007-145709.log`。详见
  `docs/embedding-offline-validation.md`；OpenSpec strict 通过。

## 问题追踪（W1-W5 均已关闭）

以下 W1-W5 内容是修复或补测前的定位及建议，保留用于追踪；源码行号也对应当时版本。历史“未找到”“仅测”等表述不代表当前状态，当前实现和证据以本文件顶部为准。

### W1：落后 Worker 可把集群可完成的作业置为 BLOCKED（已修复）

- 位置：`kb-worker/src/main/java/com/zhishiku/kb/worker/embedding/service/EmbeddingActivationService.java:48`，调用入口
  `kb-worker/src/main/java/com/zhishiku/kb/worker/embedding/EmbeddingVerificationRunner.java:28`，状态接入
  `kb-worker/src/main/java/com/zhishiku/kb/worker/embedding/service/EmbeddingVerificationService.java:26`。
- 每台 Worker 都会执行最终核验；`begin()` 对 VERIFYING 直接返回控制事实，没有目标客户端资格门禁。`activate()` 将本地
  `clients.find(...)` 为空直接解释为 `READINESS_EXPIRED`，在读取集群 readiness 之前就执行全局阻塞。
- 触发条件：同协议 Worker A 只有旧 profile，Worker B 和 app 已配置目标且有新鲜双侧 readiness，数据覆盖正确。A 先进入激活事务即推进
  control/run 为 BLOCKED；B 随后因代次变化不能激活。不是使用旧向量的问题，而是单实例配置落后影响其他就绪实例的可用性，恢复后也可能再次发生。
- 对照：ER9 / ER-24 的“不得影响其他就绪实例正确服务”、D4 的“至少一台就绪 app 和 Worker”、D8 的落后实例隔离。严格说，ER-24
  的激活后查询/领取保护已存在；偏差出现在到达激活前的协调路径，不能把它表述成激活后直接重新阻塞 ACTIVE。
- 建议：在参与核验前排除缺少目标兼容客户端的 Worker，并在激活边界保留防御；或使用持久 profile 身份结合集群 readiness
  作决定，不以本机客户端缺失判定全局失败。保留真实双侧 readiness 过期时的全局阻塞逻辑。
- 补测：同一控制事实下旧/新两个注册表交错执行；断言落后实例不调用 block/event，配置完整实例可完成一次激活；另保留全局
  readiness 真正失效的拒绝分支。

离线诊断使用本轮编译产物、Mockito 仓储和固定时钟，未建 DataSource、未调用模型或启动 Spring 应用。给两种注册表完全相同的有效覆盖与新鲜集群
readiness，实际输出：

```text
LAGGING: global BLOCKED with READINESS_EXPIRED; fresh fleet readiness was not read
CONFIGURED: ACTIVE with identical coverage and fresh fleet readiness
```

历史诊断源码：`target/EmbeddingLaggingActivationProbe-39a4d245cbac4e8eb9a89ea704af3c04.java`；日志：
`target/EmbeddingLaggingActivationProbe-39a4d245cbac4e8eb9a89ea704af3c04.log`。它复现修复前服务分支，不证明真实事务或多进程竞争；不计入
JUnit 通过数。激活服务构造器已移除模型注册表参数，该临时源码仅作历史记录，修复验证使用正式 JUnit 回归，不再原样运行它。

### W2：查询实例缺少 active profile 时未报告本实例不就绪（已修复）

- 位置：`kb-app/src/main/java/com/zhishiku/kb/app/search/service/SearchService.java:88`、
  `kb-common/src/main/java/com/zhishiku/kb/common/embedding/EmbeddingProbe.java:33`、
  `kb-app/src/main/java/com/zhishiku/kb/app/embedding/service/EmbeddingAdminReadService.java:29`。
- `SearchService` 对缺失客户端静默保留空 query，因此 BM25 降级和“不用旧模型代替”正确；但没有产生本实例 embedding
  不就绪信号。探测器仅遍历本地已配置客户端，无法为缺失 active profile 生成失败记录；管理 profiles 返回集群聚合
  readiness，status 只返回全局模式，也不能代表当前实例。
- 对照：EC6 / EC-17、D2/D8。启动时拒绝无法装配 ACTIVE 的新实例已实现；该问题针对已运行实例在切换之后落后，不能用启动检查抵消。
- 建议：增加可观测的实例级 embedding readiness，绑定当前 active 身份、实例 UUID，区分本地配置缺失与集群目标就绪；提供固定原因和低基数指标/去重日志，继续允许
  BM25，不泄露 endpoint/凭据。
- 补测：扩展 `kb-app/src/test/java/com/zhishiku/kb/app/search/QueryConsistencyTest.java:84`
  ，同时断言降级、不调用旧模型及本实例不就绪，覆盖目标配置恢复或状态再次切换后的信号更新。现有测试只断言前两类检索选择行为。

### W3：维护期间上传“既有文档新版本”缺少专门成功路径回归（已补齐）

- 场景 DP-01；实现 `kb-app/src/main/java/com/zhishiku/kb/app/document/service/DocumentWriteService.java:80`、
  `kb-app/src/main/java/com/zhishiku/kb/app/document/service/DocumentManagementService.java:165` 存在，静态检查只创建
  pending 版本/任务，不修改 current_version。
- `kb-app/src/test/java/com/zhishiku/kb/app/document/DocumentMaintenanceContractTest.java:40` 实际调用的是新文档
  `create()`，不是 `createVersion()`；`DocumentManagementServiceTest` 的版本上传用例仅覆盖非法内容拒绝。测试源码搜索未找到对
  `createVersion()` 的直接调用。
- 建议：用存储、权限和 Mapper Stub 模拟 QUIESCING/BLOCKED 下的合法版本上传，断言原件写入、新版本/任务 pending、attempts 不增加、旧
  current_version 和 indexed 不变，并与普通领取门禁组合验证。无需外部服务。

### W4：单作业冲突的两个目标场景缺少专门回归（已补齐）

- 场景 ER-06、ER-07；实现 `kb-app/src/main/java/com/zhishiku/kb/app/embedding/service/EmbeddingCommandTransaction.java:60`
  和 V15 的 `uq_embedding_unfinished_run` 有单作业保护。
- `kb-app/src/test/java/com/zhishiku/kb/app/embedding/EmbeddingCommandTest.java:75` 模拟的是“同一标识的赢家重放”；不是“不同标识/不同目标竞争”。已有
  stale-epoch 测试也不能单独证明当前 epoch 下未完成作业仍拒绝新目标。
- 建议：增加保存状态的 Stub：第一个 START 成功后，另一目标/标识返回 409；分别覆盖
  QUIESCING/REBUILDING/VERIFYING/BLOCKED，并验证部分成功后请求旧模型仍被拒绝，原目标、计数和成功审计不变。无需真实并发或数据库。

### W5：RESUME 预检失败缺少专门状态保持回归（已补齐）

- 场景 ER-20；实现 `kb-app/src/main/java/com/zhishiku/kb/app/embedding/service/EmbeddingCommandService.java:30` 与
  `kb-app/src/main/java/com/zhishiku/kb/app/embedding/service/EmbeddingCommandTransaction.java:79` 复用双侧预检。
- `kb-app/src/test/java/com/zhishiku/kb/app/embedding/EmbeddingCommandTest.java:88` 的 503 分支只执行 START；`:114` 的
  RESUME 用例覆盖成功、旧版本和未知 run，没有 readiness 失败。Controller 的 503 映射也只执行 START。
- 建议：覆盖锁前与锁内 readiness 过期两种 RESUME 情况，断言 503、control/run 仍 BLOCKED、目标和已成功 item
  不变、不重置待办项、不新增成功审计。

## 需求到实现映射

编号按各规格内出现顺序排列。DP=`document-parsing`，EC=`embedding-consistency`，ER=`embedding-rebuild`，FA=
`functional-authorization`；场景名称在后续矩阵中逐字保留。

| 需求              | 实现入口                                                                                                                                                                                                  | 结论                                               |
|-----------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------|
| DP1 维护上传排队      | `kb-app/src/main/java/com/zhishiku/kb/app/document/service/DocumentWriteService.java:80`；`kb-worker/src/main/java/com/zhishiku/kb/worker/parse/ParseTaskClaimService.java:27`                         | 实现存在；W3 四种维护状态的新版本上传专测已补齐                        |
| DP2 在途无伪失败收敛    | `kb-worker/src/main/java/com/zhishiku/kb/worker/parse/ParseMaintenanceService.java:21`；`kb-worker/src/main/java/com/zhishiku/kb/worker/parse/ParseTaskRecoveryService.java`                           | 已定位维护延期、token、巡检及迟到回调保护                          |
| DP3 普通发布身份      | `kb-worker/src/main/java/com/zhishiku/kb/worker/parse/ChunkPublisher.java:34`；`kb-worker/src/main/java/com/zhishiku/kb/worker/parse/DefaultParsePipeline.java`                                        | 生成身份随结果传递，发布复核                                   |
| EC1 不可变 profile | `kb-common/src/main/java/com/zhishiku/kb/common/embedding/EmbeddingProfile.java:47`；两侧 `EmbeddingProfileRegistration`                                                                                 | 指纹、只追加登记、冲突拒绝                                    |
| EC2 配置/存储保护     | `kb-app/src/main/java/com/zhishiku/kb/app/search/embedding/EmbeddingEnvironmentValidator.java:13`；`kb-worker/src/main/java/com/zhishiku/kb/worker/embedding/EmbeddingEnvironmentValidator.java:13`    | 显式 dev/生产拒绝及存储维度检查                               |
| EC3 禁止任意外发配置    | `kb-app/src/main/java/com/zhishiku/kb/app/embedding/web/EmbeddingCommandController.java`；`kb-common/src/main/java/com/zhishiku/kb/common/embedding/EmbeddingProbe.java:30`                            | 请求白名单、合成输入、摘要无连接配置                               |
| EC4 严格向量契约      | `kb-common/src/main/java/com/zhishiku/kb/common/embedding/EmbeddingResult.java:66`；`kb-common/src/main/java/com/zhishiku/kb/common/embedding/RemoteProfileEmbeddingClient.java:30`                    | 角色/身份/数量/维度/数值/index/模型校验                        |
| EC5 查询身份代次      | `kb-app/src/main/java/com/zhishiku/kb/app/search/service/QueryRetrievalService.java:24`；`kb-app/src/main/resources/mapper/SearchMapper.xml`                                                           | 共享锁内复核，向量专属 profile 条件，拒绝裸向量                     |
| EC6 BM25 授权降级   | `kb-app/src/main/java/com/zhishiku/kb/app/search/service/SearchService.java`；`kb-app/src/main/resources/mapper/SearchMapper.xml`；`EmbeddingInstanceReadiness`                                         | 授权与状态失败边界存在；实例报告偏差 W2 已修复                        |
| EC7 生成身份写入      | `kb-worker/src/main/java/com/zhishiku/kb/worker/parse/ChunkPublisher.java:34`；`kb-worker/src/main/java/com/zhishiku/kb/worker/embedding/service/EmbeddingBatchCommitter.java:19`                      | 不从提交时默认客户端改标                                     |
| EC8 首次信任初始化     | `kb-app/src/main/resources/db/migration/V15__embedding_profile_rebuild.sql`；`docs/embedding-rebuild-operations.md:112`                                                                                | 旧行 NULL、UNINITIALIZED、显式初始化及旧二进制停用说明             |
| ER1 显式发起及预检     | `kb-app/src/main/java/com/zhishiku/kb/app/embedding/service/EmbeddingCommandService.java:25`                                                                                                          | 双侧 readiness、202、同 profile 修复                    |
| ER2 幂等管理        | `kb-app/src/main/java/com/zhishiku/kb/app/embedding/service/EmbeddingCommandTransaction.java:34`；`kb-app/src/main/java/com/zhishiku/kb/app/embedding/model/EmbeddingCommand.java`                     | 请求规范化、actor/input 绑定、锁内重放优先                      |
| ER3 单作业固定目标     | `kb-app/src/main/java/com/zhishiku/kb/app/embedding/service/EmbeddingCommandTransaction.java:60`；V15 唯一索引                                                                                             | 实现存在；W4 竞争和部分重建后旧目标拒绝专测已补齐                       |
| ER4 持久维护模式      | `kb-app/src/main/java/com/zhishiku/kb/app/embedding/service/EmbeddingControlService.java:23`；两侧 `EmbeddingStateMapper`                                                                                | 非 ACTIVE 门禁，不因进程内服务重建重置状态                        |
| ER5 当前版本范围      | `kb-worker/src/main/java/com/zhishiku/kb/worker/embedding/mapper/EmbeddingTargetMapper.java:24`                                                                                                       | 包含归档，不包含历史/删除/无 current_version                  |
| ER6 正文与权限保持     | `kb-worker/src/main/java/com/zhishiku/kb/worker/embedding/service/EmbeddingChunkStore.java:43`；`kb-worker/src/main/java/com/zhishiku/kb/worker/embedding/service/EmbeddingBatchGenerator.java:23`     | 仅更新三项向量字段；文档锁和输入摘要复核                             |
| ER7 原子批次与续建     | `kb-worker/src/main/java/com/zhishiku/kb/worker/embedding/service/EmbeddingBatchCommitter.java:19`；`kb-worker/src/main/java/com/zhishiku/kb/worker/embedding/service/EmbeddingLeaseService.java:31`   | 同事务结构、有效租约/token、成功项不重领                          |
| ER8 失败恢复        | `kb-worker/src/main/java/com/zhishiku/kb/worker/embedding/service/EmbeddingFailureService.java:29`；`kb-app/src/main/java/com/zhishiku/kb/app/embedding/mapper/EmbeddingCommandMapper.java`            | BLOCKED/退避/原目标恢复存在；W5 锁前/内 RESUME 503 和状态保持专测已补齐 |
| ER9 覆盖与激活       | `kb-worker/src/main/java/com/zhishiku/kb/worker/embedding/service/EmbeddingVerificationStore.java`；`kb-worker/src/main/java/com/zhishiku/kb/worker/embedding/service/EmbeddingActivationService.java` | 覆盖/旧资格/双侧 readiness 复核存在；W1 已修复，不再依赖本地模型         |
| ER10 状态与审计      | `kb-app/src/main/java/com/zhishiku/kb/app/embedding/service/EmbeddingAdminReadService.java:35`；`kb-worker/src/main/java/com/zhishiku/kb/worker/embedding/EmbeddingMetrics.java:51`                    | 白名单 DTO、事务审计、固定标签及错误分类                           |
| FA1 独立资格        | `kb-app/src/main/java/com/zhishiku/kb/app/embedding/service/EmbeddingAuthorizer.java:21`；`kb-app/src/main/java/com/zhishiku/kb/app/auth/model/Permission.java`                                        | 精确 read/rebuild，先鉴权后查作业；不改变正文授权                  |
| FA2 一次初始化       | `kb-app/src/main/resources/db/migration/V16__embedding_permissions.sql`；`kb-app/src/main/java/com/zhishiku/kb/app/auth/service/AdminBootstrapRunner.java:150`                                         | 迁移一次授予、新角色全目录、既有角色不补权                            |

## 65 个场景证据矩阵

证据口径：

- “离线证据”指已阅读对应代码，并找到本轮白名单测试中的行为断言或范围允许的 SQL/事务/字段静态断言，不指真实运行通过。
- “组合证据”表示由多个单元测试及代码路径共同支持，没有将多个服务拼成真实集成测试；锁等待、回滚、崩溃恢复等真实效果均范围外。
- 历史“缺专测”指先前发现并于本轮补齐的覆盖缺口；历史“偏差”指现已修复的实现问题；“文档”是该场景本身要求部署流程说明。
- 表中 `TestClass.method` 用于定位测试。common 测试根为 `kb-common/src/test/java/com/zhishiku/kb/common/embedding/`；app
  embedding/search/document/auth 测试分别位于 `kb-app/src/test/java/com/zhishiku/kb/app/` 对应子目录；Worker
  embedding/parse 测试位于 `kb-worker/src/test/java/com/zhishiku/kb/worker/` 对应子目录。
  `EmbeddingProfileRegistrationTest`、配置及 UUID 测试的“两侧”均指 app 与 Worker 各自的类。

### document-parsing（7）

| ID    | 场景                  | 实现/测试证据                                                                                                                                                                                                                                                                                      | 判定             |
|-------|---------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------|
| DP-01 | 维护期间上传新版本           | `DocumentMaintenanceContractTest.maintenanceVersionUploadStoresOriginalAndQueuesWithoutReplacingIndexedCurrentVersion`：四种维护状态原件/pending/旧当前版本/授权读取；组合普通领取门禁与授权 BM25/当前版本 SQL 测试                                                                                                              | 离线/组合证据；W3 已关闭 |
| DP-02 | 用户查询等待进度            | `DocumentManagementService.progress`；`DocumentMaintenanceContractTest.progressEndpointReturnsWaitingReasonForAuthorizedVersion`、`DocumentManagementServiceTest.progressDistinguishesMaintenanceQueueFromIndexedAndFailedVersions`                                                            | 离线证据           |
| DP-03 | 在途任务因维护中止           | `ParseMaintenanceService.quiesce`；`ParseMaintenanceServiceTest.storedClaimIsDeferredOnceAndPreviousFailuresRemain`、`preservesHistoricalFieldsAndNeverReusesAttemptCounterAsToken`                                                                                                            | 离线证据           |
| DP-04 | 巡检与维护收敛并发           | `ParseMaintenanceMapper.defer`、`ParseTaskRecoveryService.sweepExhausted`；`ParseMaintenanceServiceTest.storedClaimIsDeferredOnceAndPreviousFailuresRemain`、`ParseRecoveryIdentityTest.allMaintenanceModesPreventLateCallbacksAndDeadLetters`                                                  | 组合证据；真实并发未运行   |
| DP-05 | 手工重试处于维护期间          | `DocumentManagementService.retry`、`ParseTaskClaimService.claim`；`DocumentManagementServiceTest.retryResetsExhaustedTaskAndVersion`、`DocumentRecoveryMappingTest`、`ParseTaskClaimServiceTest.maintenanceNeverClaimsOrChangesAttemptAndVersionState`                                           | 组合证据           |
| DP-06 | 旧任务在新 profile 激活后返回 | `ChunkPublisher.lockActive`；`ParsePublishIdentityTest.rejectsMaintenanceChangedEpochAndWrongProfileBeforeTaskLock`、`expiredOrReclaimedTaskDoesNotTouchVersionsOrChunks`                                                                                                                      | 离线证据           |
| DP-07 | 新任务与升密或搬迁并发         | `ChunkPublisher` 文档锁、INSERT SELECT 最新权限；`ParsePublishIdentityTest.publishesGeneratedProfileAndReadsCurrentPermissionFacts`、`ChunkPublisherTest.takesTheDocumentRowLockBeforeTouchingChunks`、`ParseTaskClaimServiceTest.profileSwitchStopsLaggingRegistryAndNewRegistryClaimsWithNewIdentity` | 组合/SQL 证据      |

### embedding-consistency（23）

| ID    | 场景                 | 实现/测试证据                                                                                                                                                                                                                                                                                                           | 判定                     |
|-------|--------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------|
| EC-01 | 同名同维度但不同模型来源       | `EmbeddingProfile.fingerprint/compatibleWith`；`EmbeddingProfileTest.fingerprintIsDeterministicAndSemanticChangesAreIncompatible`                                                                                                                                                                                  | 离线证据                   |
| EC-02 | 重启试图改写已有身份         | 两侧 `EmbeddingProfileRegistration`；app `existingIdentityIsNotUpdatedAndConflictsAreRejected`、Worker `missingConflictingOrWrongDimensionProfilesAreRejected`；V15 不可变触发器                                                                                                                                             | 离线/SQL 证据              |
| EC-03 | 配套查询与文档预处理         | `EmbeddingProfileDefinition.transform`；`EmbeddingRegistryTest.transformsArePairedAndActuallyAppliedWithoutChangingOriginalText`、`RemoteProfileEmbeddingClientTest.batchesAndRestoresOrderForBothRoles`                                                                                                            | 离线证据                   |
| EC-04 | 凭据正常轮换             | 语义/连接值对象分离；`EmbeddingProfileTest.connectionSettingsCannotEnterProfileFingerprint`                                                                                                                                                                                                                                 | 离线证据                   |
| EC-05 | 生产误用离线实现           | 两侧 `EmbeddingEnvironmentValidator`；两侧测试 `acceptsExplicitRemoteProductionAndLocalDevOnly`、`rejectsLegacySettingsInvalidShapeAndLimitsWithoutLeakingRawValues`                                                                                                                                                      | 离线证据                   |
| EC-06 | 配置跨维度模型            | profile 构造与两侧存储 atttypmod 检查；`EmbeddingProfileTest.requiresExplicitWellFormedSemanticFieldsAndSupportedStorageShape`、两侧 registration/config 测试                                                                                                                                                                    | 离线证据；未执行 DDL           |
| EC-07 | 请求夹带外部地址           | `EmbeddingCommandController.checkFields`；`EmbeddingCommandControllerTest.rejectsExtraFieldsBadTypesAndMismatchedIdentifiersWithoutServiceCalls`；`EmbeddingProbeTest.usesOnlySyntheticInputThenPersistsWithBootIdentityAndHonorsInterval`                                                                          | 离线证据                   |
| EC-08 | 查询配置摘要             | `EmbeddingAdminReadService.profiles`、`EmbeddingViews`；`EmbeddingAdminReadTest.serviceReturnsOnlyWhitelistedFieldsAndSanitizesErrorCodes`                                                                                                                                                                          | 离线证据                   |
| EC-09 | 重复 index 但数量正确     | `RemoteProfileEmbeddingClient.parse`；`RemoteProfileEmbeddingClientTest.rejectsDuplicateMissingExtraOutOfRangeAndNonIntegralIndices`                                                                                                                                                                               | 离线证据                   |
| EC-10 | 非法数值或零向量           | `EmbeddingResult`、远程解析；`EmbeddingResultTest.rejectsInvalidVectorsBeforeTheyEnterTheContract`、`RemoteProfileEmbeddingClientTest.rejectsBadShapeNumbersModelsAndExtraQueryResults`                                                                                                                                  | 离线证据                   |
| EC-11 | 上游声明其他模型           | 远程响应模型精确映射；`RemoteProfileEmbeddingClientTest.rejectsBadShapeNumbersModelsAndExtraQueryResults`                                                                                                                                                                                                                    | 离线证据；不证明上游权重身份         |
| EC-12 | 模型响应期间开始重建         | `SearchService` 锁外生成，`QueryRetrievalService` 锁内复核；`QueryConsistencyTest.locksAndRechecksEpochProfileAndStateBeforeVectorSql`                                                                                                                                                                                      | 组合证据                   |
| EC-13 | 向量检索正在执行时切换        | `EmbeddingStateMapper` SHARE/UPDATE 控制锁、检索结果物化；`QueryConsistencyTest.transactionBoundariesExcludeModelAndRerankAndRequireSharedLockTransaction`、`EmbeddingCommandTest.startChecksReadinessOutsideAndInsideLockThenStoresFirstReceipt`                                                                             | 静态/调用顺序；真实锁等待未验证       |
| EC-14 | 同维度不兼容历史分块         | `SearchMapper.xml` vec profile + current_version 过滤；`SearchProfileSqlTest.onlyVectorBranchHasProfilePredicateAndEveryBranchKeepsVisibilityFilters`                                                                                                                                                                | SQL 渲染证据               |
| EC-15 | 内部调用仅提供向量字面量       | `SearchService` 显式拒绝；`QueryConsistencyTest.publicServiceRejectsBareVectorsAndDoesNotFallBackOnControlFailure`、`SearchTextOnlyControllerTest`                                                                                                                                                                      | 离线证据                   |
| EC-16 | 维护期间撤权或升密          | `SearchService` 每请求解析权限、`SearchMapper.xml` 保留 ACL/当前权限与密级；`SearchProfileSqlTest`、权限/搬迁/升密白名单回归                                                                                                                                                                                                                    | 组合/SQL 证据；不声称真实授权查询已执行 |
| EC-17 | 当前实例未配置新 profile   | `SearchService` + `EmbeddingInstanceReadiness`；扩展 `QueryConsistencyTest.activatedProfileIsSelectedWhileLaggingInstanceUsesBm25`，新增实例状态测试及管理 API/周期更新断言                                                                                                                                                            | 离线证据；W2 已关闭            |
| EC-18 | 无法读取控制状态           | `EmbeddingControlService` 错误传播；`QueryConsistencyTest.publicServiceRejectsBareVectorsAndDoesNotFallBackOnControlFailure`、`locksAndRechecksEpochProfileAndStateBeforeVectorSql`、`ParseTaskClaimServiceTest.missingOrFailedControlReadFailsClosed`                                                                   | 离线证据                   |
| EC-19 | 旧 Worker 迟到提交      | 普通 publish 与重建 lease/token 栅栏；`ParsePublishIdentityTest`、`EmbeddingBatchCommitterTest.staleLeaseFailsBeforeAnyDocumentOrVectorAccess`、`EmbeddingRetryTest.transientFailurePersistsBackoffThenExhaustionBlocksAndStaleFailureDoesNothing`                                                                          | 组合证据                   |
| EC-20 | 提交时模型配置改变          | `ParsedDocument.execution`、`GeneratedRebuildBatch.batch.profile`；`ParsePublishIdentityTest.publishesGeneratedProfileAndReadsCurrentPermissionFacts`、`EmbeddingBatchCommitterTest.jdbcWritesOnlyVectorFieldsAndFencesItemProgress`                                                                                 | 生成身份/参数证据              |
| EC-21 | 旧数据库只有 embed_model | V15 可空新增列，无历史 UPDATE；`EmbeddingMigrationTest.declaresPersistentBoundariesAndUninitializedControlWithoutRewritingOldChunks`、`followupMigrationsDoNotClaimHistoricalVectorsOrRepairRevokedPermissionsAtStartup`                                                                                                     | SQL 静态证据               |
| EC-22 | 空库首次部署             | 显式 START、空目标封存、最终覆盖/readiness 门禁；`EmbeddingCommandTest.startChecksReadinessOutsideAndInsideLockThenStoresFirstReceipt`、`EmbeddingTargetServiceTest.pagesAreIdempotentAndOnlyEmptyPageSealsTargets`、`EmbeddingVerificationTest.emptyTargetAndDeletionShrunkRangeStillRequireFinalChecks`、`EmbeddingActivationTest` | 组合证据；未执行真实空库集成启动       |
| EC-23 | 首次协议升级仍有旧二进制       | `docs/embedding-rebuild-operations.md:112` 要求排空并停全部旧 app/Worker，禁止回退旧二进制                                                                                                                                                                                                                                          | 文档已核对；未部署              |

### embedding-rebuild（27）

| ID    | 场景            | 实现/测试证据                                                                                                                                                                                                                                                                      | 判定                                     |
|-------|---------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------|
| ER-01 | 目标只有查询侧可用     | `EmbeddingReadinessService.ready`；`EmbeddingReadinessServiceTest.requiresBothFreshCompatibleRolesAndPropagatesStoreFailure`、`EmbeddingCommandTest.rejectsUnknownProfileStaleEpochAndNotReadyWithoutWrites`                                                                   | 组合证据                                   |
| ER-02 | 正常开始重建        | `EmbeddingCommandTransaction.execute`；`EmbeddingCommandTest.startChecksReadinessOutsideAndInsideLockThenStoresFirstReceipt`、Controller 202 断言；同 profile 修复测试                                                                                                                 | 离线证据                                   |
| ER-03 | 同一开始请求并发重试    | 控制独占锁后重查 operation；`EmbeddingCommandTest.concurrentCommittedRequestWinsBeforeVersionAndReadinessChecksInsideLock`、V15 唯一约束                                                                                                                                                   | 交错 Stub/SQL 证据；非真实并发                   |
| ER-04 | 他人复用请求标识      | `EmbeddingCommandTransaction.replay` actor/digest 检查；`EmbeddingCommandTest.replaySurvivesLaterStateAndUnavailableProfileButRejectsOtherActorOrInput`                                                                                                                         | 离线证据                                   |
| ER-05 | 头和请求体标识不一致    | `EmbeddingCommand.requestId`、Controller 输入校验；`EmbeddingCommandControllerTest.rejectsExtraFieldsBadTypesAndMismatchedIdentifiersWithoutServiceCalls`                                                                                                                          | 离线证据                                   |
| ER-06 | 并发选择不同模型      | `EmbeddingCommandScenarioTest.competingDifferentRequestsAndTargetsHaveOneWinnerAfterInterleavedPreflight`：不同目标/标识双向交错，202/409、当前 epoch 仍拒绝、单 run/审计及重放；V15 索引静态断言                                                                                                            | 离线/SQL 证据；W4 已关闭，真实锁互斥未验证              |
| ER-07 | 部分重建后请求改回旧模型  | `EmbeddingCommandScenarioTest.partiallyRebuiltRunRejectsOldTargetWithoutChangingProgressOrMaintenance`：三种维护阶段，旧目标 START 409、改目标 RESUME 400、target/进度/维护保持及无写调用                                                                                                               | 离线证据；W4 已关闭                            |
| ER-08 | 重建中服务重启       | 两侧只读持久控制，成功项不重领；`EmbeddingControlServiceTest.reconstructedServiceReadsStoredStateWithoutActivatingOrResettingEpoch`、`EmbeddingResumeTest.reconstructedServicesKeepCommittedItemsAndRegenerateOnlyUncommittedCalls`                                                           | 仓储 Stub 重建服务证据；非真实重启                   |
| ER-09 | 旧向量查询尚未安全结束   | 同 EC-13，QUIESCING 独占控制锁先于目标建立与批次写入                                                                                                                                                                                                                                           | 静态/调用顺序；真实锁等待未验证                       |
| ER-10 | 文档有历史版本且空间已归档 | `EmbeddingTargetMapper.page` 无归档/published 裁剪；`EmbeddingTargetServiceTest.targetSqlIncludesArchivedCurrentVersionsButNotDeletedOrHistoricalContent`、字段白名单测试                                                                                                                  | SQL 证据                                 |
| ER-11 | 重建期间新上传文档     | 目标 current_version 等值谓词；`EmbeddingTargetServiceTest.targetSqlIncludesArchivedCurrentVersionsButNotDeletedOrHistoricalContent`、`EmbeddingVerificationTest.sqlChecksFiniteNonzeroVectorIdentityDigestAndCoverageWithoutTrustingCounters`                                       | SQL 证据：NULL current_version 不进入范围/缺块错误 |
| ER-12 | 模型调用期间删除文档    | 提交重新读取范围，SKIPPED 同批计数；`EmbeddingBatchCommitterTest.deletedFirstItemDoesNotShiftSecondVectorAndDoesNotResurrectContent`                                                                                                                                                       | 离线证据                                   |
| ER-13 | 重建与搬迁及升密重叠    | 文档锁、仅更新向量；`EmbeddingRebuildScopeTest.movedAndUpclassifiedSnapshotKeepsLatestFactsAndOnlyReceivesVectorUpdate`、`EmbeddingBatchCommitterTest.jdbcWritesOnlyVectorFieldsAndFencesItemProgress`                                                                                  | Stub/SQL 证据                            |
| ER-14 | 分块内容意外改变      | 生成前及提交摘要复核，回滚后独立阻塞；`EmbeddingBatchGeneratorTest.changedInputFailsBeforeExternalCallWithoutLeakingText`、`EmbeddingBatchCommitterTest.changedContentRejectsOldVectorWithoutExposingText`、`EmbeddingRebuildScopeTest.contentMismatchAfterGenerationBlocksInSeparateTransaction` | 离线证据                                   |
| ER-15 | 写入向量后进度更新失败   | `EmbeddingBatchCommitter.commit` 同事务并传播异常；`EmbeddingBatchCommitterTest.progressFailurePropagatesInsteadOfReturningSuccessfulCommit`、`leaseThenSortedDocumentsThenVectorAndProgressInSingleShortTransaction`                                                                  | 异常/事务结构证据；真实回滚未验证                      |
| ER-16 | 成功批次后进程退出     | 仅领 PENDING/失效 RUNNING；`EmbeddingResumeTest.reconstructedServicesKeepCommittedItemsAndRegenerateOnlyUncommittedCalls`                                                                                                                                                         | Stub 续建证据；提交前重复调用边界明确                  |
| ER-17 | 租约接管后旧实例返回    | 控制 epoch、run/item token、实时到期校验；`EmbeddingLeaseClockTest.expiryTakeoverAndBackoffPreserveIndependentMonotonicTokens`、`EmbeddingLeaseServiceTest.expiryOrChangedEpochCannotRenewOrReleaseNewOwnerState`、`EmbeddingRetryTest`                                                   | 离线证据                                   |
| ER-18 | 模型持续限流直至耗尽    | `EmbeddingRetryPolicy`、`EmbeddingFailureService.handle`；`EmbeddingRetryTest.classifiesRateLimitTimeoutAndPermanentErrorsWithoutRemoteBody`、`transientFailurePersistsBackoffThenExhaustionBlocksAndStaleFailureDoesNothing`                                                   | 离线证据                                   |
| ER-19 | 管理员修复后恢复      | RESUME 原 run/target/phase，推进 epoch/token；`EmbeddingCommandTest.resumeOnlyRestoresFixedTargetAndSavedPhaseWithNewEpochAndVersion`、`transactionAndResumeSqlPreserveSuccessfulItemsAndHistoricalAttempts`                                                                       | 离线/SQL 证据                              |
| ER-20 | 恢复预检仍失败       | `EmbeddingCommandScenarioTest.resumePreflightFailureBeforeOrInsideLockReturns503AndPreservesBlockedRun`：三种恢复阶段锁前/内到期，HTTP 503、BLOCKED/目标/进度保持、无 item 重置及成功审计                                                                                                                 | 离线证据；W5 已关闭                            |
| ER-21 | 计数完成但有分块未覆盖   | `EmbeddingVerificationStore` 不信计数，实际扫描向量、身份、摘要/item；`EmbeddingVerificationTest.scansActualPagesAndRejectsInvalidOrMissingIdentityEvenWhenCountsClaimComplete`、`actualCoverageOutstandingClaimsAndMissingChunksEachPreventCompletion`、SQL 断言                                  | 离线/SQL 证据                              |
| ER-22 | 当前版本记录存在却无分块  | 建目标及最终校验 `missingChunks()`；`EmbeddingTargetServiceTest.missingCurrentChunksBlocksWithAuditInsteadOfCompletingEmptyTarget`、`EmbeddingVerificationTest.emptyTargetAndDeletionShrunkRangeStillRequireFinalChecks`                                                               | 离线/SQL 证据                              |
| ER-23 | 校验通过后恢复       | `EmbeddingActivationService.activate`；`EmbeddingActivationTest.exclusiveTransactionRechecksCoverageAndFreshReadinessBeforeStateAndAudit`、`ParseTaskClaimServiceTest.profileSwitchStopsLaggingRegistryAndNewRegistryClaimsWithNewIdentity`                                    | 组合证据；真实原子提交未验证                         |
| ER-24 | 激活后有实例配置落后    | 保留查询/普通领取降级及停止测试；`EmbeddingActivationTest.coordinatorWithoutTargetClientUsesFleetReadinessAndOtherCoordinatorDoesNotCompleteTwice` 验证协调不依赖本地模型；实例状态测试补齐报告                                                                                                                    | 离线证据；W1/W2 已关闭                         |
| ER-25 | 查询阻塞任务        | `EmbeddingAdminReadService.run` 固定字段、剩余数可由总/成功/跳过推得；`EmbeddingAdminReadTest.serviceReturnsOnlyWhitelistedFieldsAndSanitizesErrorCodes`                                                                                                                                       | 离线证据                                   |
| ER-26 | 审计写入失败        | START/RESUME 同事务 record，激活同事务 event；`EmbeddingCommandTest.sameProfileRepairIsAllowedAndAuditFailurePropagates`、事务结构断言、`EmbeddingActivationTest.auditFailurePropagatesAndAlreadyActivatedStateDoesNotWriteAgain`                                                              | 组合/异常证据；真实回滚未验证                        |
| ER-27 | 多次巡检同一阻塞      | 控制版本栅栏、operation 唯一键、指标读持久事实；`EmbeddingRebuildScopeTest.blockFencesExecutionAndRecordsAuditButStaleExecutorCannotBlockAgain`、`EmbeddingMetricsTest.fixedLabelsPersistedEventCountsAndSanitizedLogsDoNotGrowWithRuns`                                                         | 离线/SQL 证据                              |

### functional-authorization（8）

| ID    | 场景         | 实现/测试证据                                                                                                                                                                                                                       | 判定           |
|-------|------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------|
| FA-01 | 只持读取资格     | 精确权限检查；`EmbeddingPermissionTest.readAndRebuildRequireExactIndependentGrants`、`EmbeddingCommandControllerTest.authenticatesBothCommandsAndDoesNotGrantWriteWithReadOrReturnReplayAfterRevocation`、`EmbeddingAdminReadTest`     | 组合证据         |
| FA-02 | 只持其他管理资格   | `IdentityManagementAuthorizer.require`；`EmbeddingPermissionTest.readAndRebuildRequireExactIndependentGrants` 枚举其他资格/通配符/角色 code 拒绝；Controller 先鉴权                                                                             | 离线证据         |
| FA-03 | 资格被撤销后重放请求 | Controller 每次先鉴权；`EmbeddingCommandControllerTest.authenticatesBothCommandsAndDoesNotGrantWriteWithReadOrReturnReplayAfterRevocation`                                                                                          | 离线证据         |
| FA-04 | 无会话或身份停用   | 共享 `EmbeddingAuthorizer.require` 先 login/resolvePrincipal；`EmbeddingAdminReadTest.allReadEndpointsRequireLoginActiveIdentityAndReadGrant`、命令 Controller 无会话断言                                                                 | 组合证据         |
| FA-05 | 全局运维与正文隔离  | 管理只接 profileId、返回白名单；正文仍 resolve(view/download)；`EmbeddingPermissionTest.functionalGrantDoesNotCreateContentAcl`、`EmbeddingAdminReadTest`、`DocumentManagementServiceTest.downloadDoesNotTouchStorageWhenDocumentIsNotVisible` | 组合证据         |
| FA-06 | 既有管理员升级    | V16 仅 ROLE_ADMIN 一次 INSERT；`EmbeddingPermissionTest.migrationGrantsOnlyExistingAdminAndDoesNotTouchContentPermissions`、`RolePermissionMigrationTest`                                                                          | SQL 静态证据；未迁移 |
| FA-07 | 撤销后重启      | 引导仅为新角色赋全目录；`AdminBootstrapRunnerTest.existingIncompleteAdminRoleWarnsWithoutRepairingPermissions`、`EmbeddingMigrationTest.followupMigrationsDoNotClaimHistoricalVectorsOrRepairRevokedPermissionsAtStartup`                  | 离线/SQL 证据    |
| FA-08 | 新空库管理员引导   | `AdminBootstrapRunner.ensureRolePermissions` 遍历 Permission.codes；`AdminBootstrapRunnerTest.emptyDatabaseCreatesRoleDepartmentUserAndBinding`、`PermissionCatalogTest`                                                          | 离线证据         |

## 设计一致性与范围

| 决策 | 核对内容                                                         | 结果                                         |
|----|--------------------------------------------------------------|--------------------------------------------|
| D1 | 公共纯 JDK profile、语义/连接分离、配套 transform、app 登记/Worker 只校验       | 已核对；模块边界与注册测试支持                            |
| D2 | 显式合法配置、存储形状、严格响应、控制不可读时失败关闭                                  | 已核对；运行中实例不就绪信号 W2 已补齐                      |
| D3 | V15-V17 单控制行、未完成唯一作业、不可变 profile、NULL 旧身份、独立 token、历史不级联删除   | 已核对迁移及 Mapper；真实约束执行未验证                    |
| D4 | 持久阶段、版本、请求绑定、重放顺序、事务外周期合成预检                                  | 已核对；单作业、旧目标拒绝和 RESUME 专测已补齐，W4/W5 已关闭      |
| D5 | SearchService NOT_SUPPORTED、检索专用短事务及 SHARE 锁、向量独占 profile 条件 | 已核对代码和动态 SQL 渲染；真实锁等待未验证                   |
| D6 | control -> task -> document/chunks，维护延期退款一次，巡检/失败/心跳/手工重试门禁  | 已核对关键写路径；版本上传四种维护状态专测已补齐，W3 已关闭；真实死锁/回滚未验证 |
| D7 | 当前版本独立清单、现有正文输入、文档排序锁、仅三项向量字段、删除跳过及摘要阻塞                      | 已核对；无解析器/切块器调用，权限快照不回填                     |
| D8 | 有界退避、原目标续建、实际范围检查、最终有界事务和双侧 readiness                        | 已核对；W1/W2 已修复，协调不依赖本机客户端；实例固定原因日志/管理状态已补齐  |
| D9 | 独立资格、迁移一次绑定、原子审计结构、低基数指标、网关/强制改密边界                           | 已核对；日志/响应白名单及网关离线测试支持                      |

真实
PostgreSQL/ParadeDB/pgvector、Redis、MinIO、Docker、真实模型、监听服务、完整业务应用均未启动或连接；未运行真实迁移、集成、端到端或多进程部署。真实锁互斥、回滚、原子提交、崩溃恢复、执行计划及检索效果未验证，保持
proposal/design/tasks 的原定范围，不列为新增缺陷或强制归档前置项。代码模式核对依据当前源码、增量规格和任务，不依赖 git
diff；工作区不是 Git 仓库。

## 逐场景核验时离线验证（14:36，修复前历史）

2026-10-07 14:36:02（Asia/Shanghai）完成，保持原白名单、不扩大到外部测试：

```powershell
$tests = @(Get-Content -LiteralPath 'docs\embedding-offline-tests.txt' | Where-Object { $_.Trim() })
$selector = $tests -join ','
mvn -o package "-Dtest=$selector" '-Dsurefire.failIfNoSpecifiedTests=false' *> 'target\embedding-scenario-verification-20261007-143537.log'
openspec validate 'embedding-profile-consistency-and-rebuild' --strict
```

| 模块         | 执行/通过 | 失败 | 错误 | 跳过 |
|------------|-------|----|----|----|
| kb-common  | 23    | 0  | 0  | 0  |
| kb-app     | 241   | 0  | 0  | 0  |
| kb-gateway | 8     | 0  | 0  | 0  |
| kb-worker  | 100   | 0  | 0  | 0  |
| kb-ai      | 3     | 0  | 0  | 0  |
| 合计         | 375   | 0  | 0  | 0  |

全模块 `BUILD SUCCESS`，OpenSpec strict 通过。数量取该次日志各模块汇总，不通过只枚举顶层 XML 文件漏算嵌套测试，也不混入旧报告；UUID
9 项已含在 375 中。当时白名单为 81 个名称，对应 86 个顶层测试类；其余 73 个顶层测试类未执行，不记为通过。W1 的诊断不是新增
JUnit 测试，W1-W5 当时未被既有测试覆盖/关闭；后续 W1/W2 修复回归 382 项见顶部。

## 历史记录

以下保留原核验过程以追踪 UUID 缺陷及中断点。历史段落中的“本轮”“下一步”仅指该记录当时；当前状态以本文件顶部结论与完整场景矩阵为准。

### UUID 修复后状态（2026-10-07 11:30，历史）

原 UUID Mapper 初始化阻断已在离线回归中修复：两侧真实 MyBatis-Plus 自动配置成功注册 TypeHandler 和 embedding Mapper，9 项
UUID 专项测试全部通过；完整白名单 375 项通过、0 失败、0 错误、0 跳过，73 个测试类明确未执行。OpenSpec strict 校验通过，清单仍为
34/34。

本次修正测试扫描范围，仅发现 UUID TypeHandler，不再意外加载认证配置；异常断言校验 `ResultMapException` 及其 `SQLException`
cause，生产异常语义未变。还显式声明 Mockito 的空 UUID 参数，消除编译歧义。

证据：`target/embedding-uuid-focused-rerun.log`、`target/embedding-offline-build-uuid.log`；详细统计见
`docs/embedding-offline-validation.md`。未修改迁移或共享模块依赖，未连接外部服务。

本轮关闭 UUID 缺陷及其测试夹具问题，不将离线成功解释为真实数据库或完整业务应用启动成功。此前中断的 65
个规格场景逐项核验尚未全部完成，仍不宣称整个变更已通过完整核验；未同步规格或归档。

### UUID 修复进展（2026-10-07 11:24，已解决）

用户已确认修复方案。已新增 app/Worker 各自的 `config/UuidTypeHandler.java`，以 Spring Bean 注册，使用
`setObject(..., Types.OTHER)` 和 JDBC 类型化 UUID 读取；未给 `kb-common` 增加依赖，未修改迁移脚本。新增两侧
`EmbeddingUuidMappingTest` 并加入离线白名单。

首次定向执行：
`mvn -o -pl kb-app,kb-worker -am test '-Dtest=EmbeddingUuidMappingTest' '-Dsurefire.failIfNoSpecifiedTests=false'`。app
编译成功，但新增 4 项测试全部失败，Worker 因前序失败未执行。日志：`target/embedding-uuid-focused.log`。本轮没有进行全模块打包，也不能宣布原
UUID 阻断已解决。

已定位为新测试夹具中的两个问题，按 apply 错误暂停规则保留现场：

- 测试对整个 `app.config` 包执行 ComponentScan，意外加载 `SaTokenConfig`，进而缺少 `IdentityDepartmentMapper`。应限制测试扫描范围为
  UUID TypeHandler，保留真实 MyBatis-Plus 自动配置与 embedding Mapper 扫描。
- 异常断言误期望 `SQLException` 直接抛出；`BaseTypeHandler.getResult` 实际将其包装为 `ResultMapException`。应断言包装类型及
  `SQLException` cause，不修改生产异常语义。

下一步先修正这两个夹具问题，再运行两侧定向测试、白名单打包及后续核验。DataSource 使用拒绝连接的
Mock；未启动监听服务或访问外部服务。以下为此前核验记录，仍保留用于追踪原问题。

发现 1 项 CRITICAL 问题：app 和 Worker 的 readiness Mapper 缺少 UUID 类型处理器，离线解析即可失败。应先修复再继续核验和归档。

OpenSpec 的 `all_done` 仅表示清单已勾选，不表示运行时映射已经验证。本轮不修改实现、不重新勾选任务、不同步主规格、不归档。

| 维度  | 本轮结果                                                                                                                     |
|-----|--------------------------------------------------------------------------------------------------------------------------|
| 完整性 | CLI 确认 34/34 项完成；4 份增量规格共 23 条 ADDED requirement、65 个 scenario，无 MODIFIED/REMOVED/RENAMED；已定位相关实现，但 readiness 的运行时接入存在阻断 |
| 正确性 | 已检查主要需求的代码路径和现有测试；离线 Mapper 解析失败。Scenario Coverage 未完整验证：发现启动阻断后停止逐场景核验，不能将 65 个场景计为全部通过                                 |
| 一致性 | 已检查 D1-D9 的关键实现边界及分层；类型映射接入存在缺口。代码模式检查未发现其他需单独列出的重大偏离                                                                    |

## 历史 CRITICAL（已修复）：UUID 映射缺失使 readiness Mapper 初始化失败

位置：

- `kb-app/src/main/java/com/zhishiku/kb/app/embedding/mapper/EmbeddingReadinessMapper.java:13`
- `kb-worker/src/main/java/com/zhishiku/kb/worker/embedding/mapper/EmbeddingReadinessMapper.java:13`
- 参数类型来自 `kb-common/src/main/java/com/zhishiku/kb/common/embedding/EmbeddingReadiness.java:6` 的
  `UUID instanceId`。

两侧 `save(EmbeddingReadiness)` 使用 `#{instanceId}`，当前 MyBatis-Plus 配置中 `hasTypeHandler(UUID.class)` 为
false，源码及配置也未找到 UUID TypeHandler 注册。两侧应用均通过 `@MapperScan` 扫描这些 Mapper，因此不是只在真实模型预检时才会发生的问题，而是
SqlSessionFactory/Mapper 初始化阻断。

使用现有 Surefire 报告中的模块 classpath，创建普通 `MybatisConfiguration`，设置与应用相同的下划线映射选项，直接调用
`addMapper(EmbeddingReadinessMapper.class)`。未创建 DataSource，未访问数据库或模型，app 和 Worker 均得到：

```text
UUID handler: false
java.lang.IllegalStateException: Type handler was null on parameter mapping for property 'instanceId'. It was either not specified and/or could not be found for the javaType (java.util.UUID) : jdbcType (null) combination.
```

诊断源文件：`target/EmbeddingMapperProbe-803cf3687dc84ace80227e9784db571d.java`。最终确认使用
`java --class-path <module-classpath> <source-path> <mapper-class>` 在进程内执行；此前一次 JShell local 模式的 classpath
加载失败不作为项目问题或核验证据。

同次检查中两侧 `EmbeddingStateMapper` 的注册成功，但这不证明其 UUID 结果映射能够正确执行；应在修复时一并覆盖。

### 建议修复

1. 在 app 和 Worker 注册兼容 PostgreSQL UUID 的 TypeHandler，覆盖参数绑定以及 ResultSet/CallableStatement 读取；不要仅把
   UUID 转为普通 varchar 后假定数据库自动转换。
2. 增加不连接数据库的 MyBatis/MyBatis-Plus Mapper 注册、参数绑定及结果映射测试，至少覆盖 readiness、control/run 和重建租约路径。
3. 将新增测试纳入 `docs/embedding-offline-tests.txt`，重跑定向测试和完整白名单离线打包，再继续 OpenSpec 核验。
4. 不修改既有迁移脚本，不为此向 `kb-common` 引入框架依赖；保持现有模块边界。

现有测试缺口：两侧 `EmbeddingReadinessSchedulerTest.java:30` 均 Mock `EmbeddingReadinessMapper`，不会解析 Mapper SQL，因此之前
366 项测试通过不能排除此问题。

## 已检查的主要实现

| 范围                 | 代码和测试证据                                                                                                                                                       |
|--------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------|
| profile 身份、配置与向量契约 | `EmbeddingProfile`、`EmbeddingProfileDefinition`、`RemoteProfileEmbeddingClient`；profile/registry/result/probe 单测；两侧注册与配置校验实现                                   |
| 旧数据初始化、持久状态与权限     | V15/V16/V17、两侧 `EmbeddingProfileRegistration`、`EmbeddingCommandTransaction`、`EmbeddingAuthorizer`；迁移、命令、身份与控制器测试                                              |
| 查询一致性与授权 BM25      | `SearchService`、`QueryRetrievalService`、`SearchMapper.xml`；查询事务边界、profile 过滤和权限谓词测试                                                                           |
| 普通解析暂停与发布          | `ParseTaskClaimService`、`ParseMaintenanceService`、`ParseMaintenanceMapper`、`ParseTaskRecoveryService`、`ChunkPublisher`；维护、迟到结果和身份回归                           |
| 目标、租约与批次提交         | `EmbeddingTargetService`、`EmbeddingLeaseService`、`EmbeddingLeaseMapper`、`EmbeddingBatchCommitter`、`EmbeddingChunkStore`；目标范围、接管、删除和正文变化测试                     |
| 故障恢复、覆盖扫描与激活       | `EmbeddingBatchProcessor`、`EmbeddingFailureService`、`EmbeddingVerificationService`、`EmbeddingVerificationStore`、`EmbeddingActivationService`；续建、失败退避、伪完成与激活测试 |

这些检查不替代逐场景完整核验，也不证明真实事务回滚、锁互斥、多实例恢复或生产可用性。

## 验证与范围

- 本轮重新运行 `openspec validate 'embedding-profile-consistency-and-rebuild' --strict`：通过；它验证规格格式，不加载业务
  Mapper。
- 上一轮全模块离线打包的 366 项通过、73 个测试类未执行记录仍保留于 `docs/embedding-offline-validation.md`；本轮未重跑或扩大该统计。
- 本轮额外执行两侧真实依赖下的离线 Mapper 解析检查，均复现上述问题；不属于新增 JUnit 测试通过数。
- 未启动业务应用、数据库、Redis、MinIO、Docker 或真实模型服务；未执行迁移。
- Scenario Coverage 未完整验证的原因是启动阻断，而非缺少规格文件。真实外部环境验证依旧在本变更范围之外。

下一步：修复 UUID 映射并补足离线映射回归，然后继续核验；本轮不宣称已具备归档条件。
