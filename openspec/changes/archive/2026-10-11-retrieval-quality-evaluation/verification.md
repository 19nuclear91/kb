# 验证报告：retrieval-quality-evaluation

核验日期：2026-10-11。Schema：`spec-driven`。核验依据为当前 proposal、design、delta spec、tasks 和实现文件。

W1 修复复核：2026-10-11。本轮针对耗时均值修正进行代码检查、组件单测重跑和打包检查；其他映射记录沿用首次核验。

## 结论

| 维度  | 结果                                                |
|-----|---------------------------------------------------|
| 完整性 | 23/23 任务标记完成并找到对应交付；12/12 ADDED 需求找到实现，无缺失能力      |
| 正确性 | 12/12 需求完成实现映射，25/25 场景已对照代码与相关组件单测；W1 已修复并通过回归单测 |
| 一致性 | 已检查 D1–D7 及项目代码模式；D5 的耗时均值边界已补充单测，未发现未解决的显著偏差     |

当前未解决问题：CRITICAL：0；WARNING：0；SUGGESTION：0。

本次约定范围内没有缺失证据而跳过的适用检查。任务完成、规格覆盖、需求实现映射、场景覆盖、设计遵循及代码模式一致性均已执行。场景核验采用静态代码检查和相关组件单测，不表示执行了
25 个端到端场景。

约定范围内的检查通过，W1 已关闭，可归档。本次修正耗时均值算法并新增组件单测，任务进度保持 23/23；未执行归档。

## 已修复问题

### W1：合法有限耗时的均值可能溢出或下溢（已修复）

- 位置：[Aggregator.java:49](../../../../kb-eval/src/main/java/com/zhishiku/kb/eval/metrics/Aggregator.java:49)。
- 依据：规格“离线结论必须明确来源与验证范围”要求统计文件中的非负有限耗时；设计 D5 和任务 4.3 明确输出均值。输入读取器接受非负有限
  double，未限制其数量级，见 [InputReader.java:168](../../../../kb-eval/src/main/java/com/zhishiku/kb/eval/input/InputReader.java:168)。
- 修复前先计算每条 `value / values.size()`，再对结果求和。逐项除法的舍入仍可能造成总和溢出，也会使次正规数提前归零。
- 首次复现使用本地 JShell，仅调用 `Aggregator.latency` 组件。修复时先加入回归单测，确认极大值、极小值及零与极小值混合三种情况在原实现下失败，再修改算法；未调用
  CLI 执行入口、运行完整回放或连接外部服务。

| 记录值                    | 修复前 meanMs | 修复后 meanMs                      |
|------------------------|------------|---------------------------------|
| 3 条 `Double.MAX_VALUE` | `Infinity` | 有限的 `Double.MAX_VALUE`          |
| 2 条 `Double.MIN_VALUE` | `0.0`      | `Double.MIN_VALUE`，即 `4.9E-324` |

该偏差影响附加耗时均值，未发现其改变检索质量指标、P95
或门禁方向。原 [AggregatorTest.java:75](../../../../kb-eval/src/test/java/com/zhishiku/kb/eval/metrics/AggregatorTest.java:75)
仅以两条 `1e308` 验证大数边界；现新增极大值、极小值、混合零值与大数、混合零值与次正规数、全零共 5 项组件单测，并保留原有的耗时覆盖数、P95
和全缺失测试。

修复实现：按最大值缩放后计算归一化均值，再恢复尺度；全零输入直接返回 0，并将归一化均值上界限制为
1，保证合法有限耗时不会生成无限均值。新增测试在修复后全部通过，没有增加集成测试、外部组件或文档测试。

## 执行证据与边界

| 检查                                                        | 结果                                                                                      |
|-----------------------------------------------------------|-----------------------------------------------------------------------------------------|
| OpenSpec status / apply instructions                      | 规划文件齐全，任务跟踪可读；23/23，remaining=0。`all_done` 仅作为任务跟踪状态，不作为正确性结论                           |
| 组件单元测试白名单                                                 | 修复后 121 项通过，失败 0、错误 0、跳过 0                                                              |
| `openspec validate retrieval-quality-evaluation --strict` | 通过                                                                                      |
| `mvn -pl kb-eval -am package '-DskipTests'`               | BUILD SUCCESS                                                                           |
| JAR 静态检查                                                  | manifest 声明 Java 21 和 `com.zhishiku.kb.eval.cli.EvaluationCli`；入口类及 Jackson 类存在，未启动 JAR |
| `mvn -pl kb-eval dependency:tree '-Dscope=runtime'`       | 仅 Jackson databind/core/annotations 2.17.2，无业务模块及服务客户端依赖                                |
| 耗时数值边界                                                    | 首次组件探测及修复前回归单测复现 W1；修复后新增 5 项边界单测全部通过                                                   |

打包有 Maven Shade 的重叠资源及 `module-info.class` 警告，构建成功；本报告未据此推断运行失败。

测试命令：

```powershell
mvn -pl kb-eval test '-Dtest=CliOptionsTest,DiagnosticFormatterTest,InputReaderTest,MetricCalculatorTest,AggregatorTest,ComparisonCalculatorTest,ThresholdReaderTest,QualityGateTest,JsonReportWriterTest,CsvReportWriterTest,OutputPublisherTest'
```

| 测试类                      | 执行数 |
|--------------------------|----:|
| CliOptionsTest           |  12 |
| DiagnosticFormatterTest  |   2 |
| InputReaderTest          |  54 |
| MetricCalculatorTest     |   7 |
| AggregatorTest           |   9 |
| ComparisonCalculatorTest |   3 |
| ThresholdReaderTest      |  18 |
| QualityGateTest          |   5 |
| JsonReportWriterTest     |   3 |
| CsvReportWriterTest      |   3 |
| OutputPublisherTest      |   5 |

按用户已确认的任务调整及 design 的 Migration Plan，完整 CLI 回放、独立进程烟测、集成测试、真实服务/DB/Redis 等外部组件验证，以及
README、使用文档、Markdown 内容和文档示例测试均不适用本次验收。文档及样例是交付物，未将其内容作为自动化测试对象，也未执行其中的示例。上述有意排除项不计为缺陷或新增归档条件。

## 需求实现映射

以下文件定位均相对仓库根目录。主源码目录为 `kb-eval/src/main/java/com/zhishiku/kb/eval/`，测试目录为
`kb-eval/src/test/java/com/zhishiku/kb/eval/`。

| 需求            | 实现证据（相对主源码目录）                                                                                                                                          | 单测证据                                                                                  |
|---------------|--------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------|
| 仅依赖本地文件       | `input/JsonInput.java:67` 拒绝非本地路径；`cli/EvaluationCli.java:36` 仅编排文件及计算组件；`report/OutputPublisher.java:32` 禁止覆盖                                         | InputReaderTest、OutputPublisherTest；运行时依赖树和源码依赖检查                                     |
| 输入身份一致且可追踪    | `input/InputReader.java:48` 校验唯一 runId；`:78` 校验身份与标注摘要；`input/JsonInput.java:42` 和 `InputReader.java:204` 对同份读取内容解析及计算摘要                               | InputReaderTest、JsonReportWriterTest                                                  |
| 文档版本相关性       | `input/InputReader.java:110` 校验标注；`model/InputModel.java` 使用 VersionKey；`metrics/MetricCalculator.java:17` 按版本判断命中                                     | InputReaderTest、MetricCalculatorTest                                                  |
| 完整覆盖并区分空结果与失败 | `input/InputReader.java:78` 校验 caseId 集合；`:145` 校验状态与 hits                                                                                             | InputReaderTest、MetricCalculatorTest                                                  |
| 原始排名和分块预算     | `input/InputReader.java:145` 保留数组顺序并校验全数组；`metrics/MetricCalculator.java:17` 先截取、原始位置计算、不补位                                                            | InputReaderTest、MetricCalculatorTest                                                  |
| 统一相关性计算口径     | `metrics/MetricCalculator.java:17` 实现 hit/recall/mrr/ndcg；`:51` 计算分级收益和折扣                                                                              | MetricCalculatorTest 的独立手算夹具                                                          |
| 无相关目标单独统计     | `metrics/MetricCalculator.java:17` 区分 null 与 0；`metrics/Aggregator.java:32` 排除不适用值并保留分母                                                                | MetricCalculatorTest、AggregatorTest                                                   |
| 独立文档版本重复率     | `metrics/MetricCalculator.java:17` 计算 `(n-u)/n`，空结果 null                                                                                               | MetricCalculatorTest、AggregatorTest                                                   |
| 一致范围的方案比较     | `comparison/ComparisonCalculator.java:70` 显式基线、候选减基线、方向及退步用例；`metrics/Aggregator.java:18` 分类与总体计数                                                      | ComparisonCalculatorTest、AggregatorTest                                               |
| 可复核且控制内容输出    | `report/ReportFactory.java:15` 白名单投影；`report/ReportOrdering.java` 确定性排序；JSON/CSV/Markdown 序列化器；`cli/DiagnosticFormatter.java` 固定诊断                     | JsonReportWriterTest、CsvReportWriterTest、DiagnosticFormatterTest；Markdown 仅检查实现代码     |
| 明确门禁及退出语义     | `comparison/ThresholdReader.java:36` 校验规则；`comparison/QualityGate.java:45` 原始数值判定；`cli/ExitCodes.java` 映射 0/1/2；`EvaluationCli.java:36` 完整报告发布后返回门禁退出码 | ThresholdReaderTest、QualityGateTest、JsonReportWriterTest、OutputPublisherTest；入口编排静态检查 |
| 明确来源与离线范围     | `report/EvaluationReport.java:45` 范围声明；`metrics/Aggregator.java:45` 记录耗时覆盖与 nearest-rank P95                                                           | JsonReportWriterTest、AggregatorTest；均值极端边界 W1 已修复并有回归单测                               |

Delta 中只有 12 条 ADDED 需求，无 MODIFIED、REMOVED 或 RENAMED 项。

## 场景核验

“组件证据”表示覆盖目标逻辑的单测，不要求把规格文字复刻为集成测试；需要入口编排的部分补充静态代码证据。

|  # | 场景           | 实现及组件证据                                                                                                                                       |
|---:|--------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
|  1 | 无服务环境完成评测    | 本地输入和纯计算编排、运行时依赖树；InputReaderTest、指标/聚合/报告组件测试。未执行完整 CLI                                                                                      |
|  2 | 输出目录已经存在     | OutputPublisherTest `existingOutputIsNotTouchedAndWriterIsNotCalled`，入口将固定错误映射为 2                                                             |
|  3 | 相同评测条件的两个方案  | InputReader 的共同身份校验；ComparisonCalculatorTest 的基线/候选夹具；JsonReportWriterTest 保留来源、配置和摘要                                                         |
|  4 | 标注或语料快照发生变化  | InputReaderTest `readsRelativeFilesAndHashesExactBytes`、`rejectsMismatchedIdentity`                                                           |
|  5 | 文档相同但版本不同    | MetricCalculatorTest `sameDocumentDifferentVersionsRemainDistinctAndUnlabelledVersionGetsZero`                                                |
|  6 | 空结果预期与相关目标冲突 | InputReaderTest `rejectsInvalidLabelStructures`                                                                                               |
|  7 | 成功返回空数组      | InputReaderTest `acceptsSuccessEmptyAndOptionalLatency`；MetricCalculatorTest 的空结果口径                                                           |
|  8 | 缺失或执行失败的样本   | InputReaderTest `rejectsMissingUnknownFailedAndInvalidResults`；入口读取失败即返回 2，不发布报告                                                              |
|  9 | 重复版本占用预算     | MetricCalculatorTest `repeatsConsumeBudgetAndGainOnlyOnce`、`keepsOriginalRanksAndDoesNotPullPastK`                                            |
| 10 | 分数顺序与数组顺序不一致 | InputReaderTest `skipsExtraFieldsAndKeepsArrayOrderAndPrefix`；计算器仅使用数组位置                                                                      |
| 11 | 缺失版本或重复分块    | InputReaderTest `rejectsMissingUnknownFailedAndInvalidResults`、`validatesHitsBeyondBudgetAndDuplicateResults`                                 |
| 12 | 可手算的重复版本样本   | MetricCalculatorTest `repeatsConsumeBudgetAndGainOnlyOnce`：DCG=7.5，独立 IDCG、Recall、MRR 断言                                                      |
| 13 | 有相关目标但结果为空   | MetricCalculatorTest `irrelevantAndEmptyResultsGetZeroRelevanceButEmptyDuplicateRateIsNull`                                                   |
| 14 | 无相关目标且返回内容   | MetricCalculatorTest `expectedEmptyUsesFullResultAndRelevanceIsInapplicable`；Aggregator 的适用分母测试                                               |
| 15 | 数据集全部预期为空    | AggregatorTest `allEmptyExpectationsLeaveRelevanceNullWithZeroEligibleCases`                                                                  |
| 16 | 同版本多个不同分块    | MetricCalculatorTest `repeatsConsumeBudgetAndGainOnlyOnce` 验证重复率 1/3；InputReaderTest 接受不同 chunkId                                             |
| 17 | 没有返回分块       | MetricCalculatorTest 的空结果重复率 null；AggregatorTest 排除不适用值                                                                                       |
| 18 | 分类结果掩盖了总体退步  | ComparisonCalculatorTest `exposesOppositeClassChangesRegressionDirectionAndNullDifference`                                                    |
| 19 | 指标对一侧不适用     | 同一 ComparisonCalculatorTest 明确验证 null 差值                                                                                                      |
| 20 | 输入额外带有业务正文   | InputReaderTest 跳过额外字段；JsonReportWriterTest `projectionNeverSerializesAttachedInputRecordsOrTheirSentinels`；DiagnosticFormatterTest 排除原始内容和异常 |
| 21 | 相同输入重复运行     | JsonReportWriterTest、CsvReportWriterTest、ComparisonCalculatorTest 的确定性排序测试；报告代码无时间戳或输出路径。未执行两次完整 CLI                                          |
| 22 | 候选违反质量门禁     | QualityGateTest 的实际值越界及退出码 1；JsonReportWriterTest 保留失败门禁数据；OutputPublisherTest 完整写入后发布；入口编排静态检查                                               |
| 23 | 门禁引用不适用指标    | QualityGateTest `nullCandidateOrRequiredBaselineIsConfigurationError`，ExitCodes 错误映射 2；入口先判定再发布                                               |
| 24 | 合成样例获得满分     | EvaluationReport.Scope 固定离线边界，JsonReportWriterTest 验证 SYNTHETIC/RECORDED 及范围字段；不依据样例声明真实质量已通过                                                 |
| 25 | 只有部分用例记录检索耗时 | AggregatorTest `latencyOnlyUsesRecordedSamplesAndNearestRank` 验证 3/5、均值、P95 和全缺失                                                              |

## 设计与代码模式

| 决策              | 结果                                                        |
|-----------------|-----------------------------------------------------------|
| D1 独立 Maven CLI | 六个职责包、独立 DTO、固定 Shade/Surefire 版本、Java 21 入口；依赖树仅 Jackson |
| D2 版本化清单与 JSONL | UTF-8、严格 schema、受控标识、整数身份、流式跳过业务字段，未加入在线采集                |
| D3 严格覆盖与输入验证    | 不取交集；校验完整 hits；解析与摘要同源；错误诊断受控；出错不发布报告                     |
| D4 分块预算与公式      | 原始排名、先截断后判定、重复零收益及 null 语义均有手算和边界组件测试                     |
| D5 比较、聚合及耗时     | 基线和多标签语义符合设计；耗时覆盖与 P95 有测试；均值 W1 已修复，新增 5 项边界组件单测通过       |
| D6 报告及目录发布      | 白名单、稳定排序、JSON 原始精度、CSV 展示精度；临时目录发布及失败清理有组件测试              |
| D7 门禁与退出码       | 规则方向、null 配置错误、最大 1e-12 容差、所有候选判定和 0/1/2 映射符合设计           |

新模块沿用 `com.zhishiku.kb` 命名、Java record、显式 import、中文 Javadoc、JUnit 5 与 AssertJ；对照既有 `kb-common` record 及父
POM 后，未发现需要单独记录的代码模式建议。
