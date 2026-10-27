# 离线检索质量评测

`kb-eval` 是 Java 21 命令行工具，只读取本地 UTF-8 文件。输入文件保持只读，报告写到显式指定的新目录。运行不需要业务服务、PostgreSQL、Redis、MinIO、HTTP
或模型。

## 数据格式

所有清单使用 `schemaVersion: 1`。清单路径可为本地绝对路径；清单引用的文件相对清单所在目录解析，不接受 URI、网络共享或扫描通配符。JSONL
每行一个对象，不允许空行。清单、阈值及必要身份结构拒绝未知字段；所有对象拒绝重复 JSON 属性。

数据集清单 `dataset.json`：

```json
{"schemaVersion":1,"datasetId":"demo","datasetVersion":"v1","corpusSnapshotId":"synthetic-v1","labelsFile":"labels.jsonl"}
```

标注 `labels.jsonl`：

```json
{"caseId":"q001","tags":["term"],"expectedEmpty":false,"relevance":[{"docId":101,"versionId":201,"grade":3},{"docId":102,"versionId":202,"grade":1},{"docId":103,"versionId":203,"grade":2}]}
{"caseId":"q002","tags":[],"expectedEmpty":true,"relevance":[]}
```

`caseId` 表示固定查询、身份与权限快照的场景。制作者负责人工标注完整性和身份边界，不同身份场景必须使用不同 caseId；工具不核验实际
ACL，也不读取查询文本。标注变化需升级 `datasetVersion`。等级 0/1/2/3 表示不相关/弱相关/相关/强相关，未标注版本按 0 计算。
`expectedEmpty=false` 必须有正相关标注，true 不得有正相关标注。

所有 docId、versionId、chunkId 必须为 JSON 整数，范围 `1..Long.MAX_VALUE`，不接受字符串或小数。数据集、版本、快照、运行、配置、caseId
和 tag 标识必须为 1–128 字符的 `[A-Za-z0-9][A-Za-z0-9._:-]*`。标签不能重复，空列表归入 `UNTAGGED`
，该名字不能作为输入标签。每个场景的版本标注唯一，同一文档不同版本分别判断。

运行清单 `bm25.run.json`：

```json
{"schemaVersion":1,"runId":"bm25","datasetId":"demo","datasetVersion":"v1","corpusSnapshotId":"synthetic-v1","configurationId":"bm25-demo-v1","sourceType":"SYNTHETIC","labelsSha256":"<标注文件的实际64位SHA-256>","resultsFile":"bm25.jsonl"}
```

摘要占位符只用于格式说明，实际输入必须填写真实摘要。PowerShell 可获取摘要：

```powershell
(Get-FileHash -LiteralPath '.\labels.jsonl' -Algorithm SHA256).Hash.ToLowerInvariant()
```

`sourceType` 仅接受 `SYNTHETIC` 或 `RECORDED`，分别声明合成结果或已有记录。名称如 bm25/rerank 只表示配置标识，工具不会执行算法，也不会验证来源真实性。

结果 `bm25.jsonl`：

```json
{"caseId":"q001","status":"OK","hits":[{"docId":101,"versionId":201,"chunkId":301},{"docId":101,"versionId":201,"chunkId":302},{"docId":102,"versionId":202,"chunkId":303}],"latencyMs":12.5}
{"caseId":"q002","status":"OK","hits":[]}
```

每个运行必须恰好覆盖全部 caseId。OK 必须有 hits 数组，空数组合法；ERROR
表示检索执行失败，整个评测拒绝，不将其转为零分。相同三元组不得重复，同版本不同分块合法。身份和重复校验覆盖完整数组，包括 max(
k) 之后的结果。只保留计算所需的前 max(k) 项。

标注行、结果行的额外顶层业务字段以及 hit 的非身份字段会被跳过，正文、title、query、score、token 不保存、不回显；score 不参与排序。真实
API 的响应包装需要由独立流程整理成本格式，本工具不提供在线接入、导出或转换命令。

## 指标口径

默认 k 为 5、10、20，可用 `--k 1,5,10` 指定无重复正整数，按升序计算。先截取前 k 个原始分块，再按 `docId + versionId`
判断相关性。重复版本后续位置收益为 0，但仍占预算；不压缩排名，不补取 k 以外结果。

| 指标                  | 单样本定义                                                        |
|---------------------|--------------------------------------------------------------|
| hit                 | 命中任一正相关版本为 1，否则为 0                                           |
| recall              | 命中的不同正相关版本数 / 全部正相关版本数                                       |
| mrr                 | 首个相关命中的原始排名倒数，没有命中为 0                                        |
| ndcg                | DCG / IDCG；收益 `(2^grade-1)/log2(rank+1)`，理想排序按全部版本等级降序取前 k 项 |
| duplicateRate       | `(实际分块数-不同版本数)/实际分块数`；空结果为 null                              |
| emptyResultAccuracy | 只对 expectedEmpty 样本计算；完整结果为空为 1，否则为 0；不带 k                   |

有相关目标但空结果时，四项相关性指标为 0；无相关目标时四项为 null，只计算空结果符合率。JSON 使用 null，CSV 使用空单元格，Markdown
使用 N/A。总体和分类均为适用样本宏平均，展示 totalCases 和每项 eligibleCases；零适用样本为 null。多标签样本分别进入分类，但总体只计一次。

手算例：A=3、B=1、C=2，排名 A/A/B/C，k=3 时 hit=1、recall=2/3、mrr=1、duplicateRate=1/3、DCG=7.5；IDCG 为 `7+3/log2(3)+1/2`
。文档版本命中不保证返回分块包含答案，本工具不评测精确片段或生成答案质量。

## 比较与附加耗时

`--baseline <runId>` 显式选择已加载基线，至少还有一个候选；未指定时仅汇总。所有运行的标注摘要、数据集、版本与语料快照必须一致，不取样本交集。总体、分类和逐场景展示候选减基线
rawDelta；质量指标正差为改善，重复率负差为改善，任一侧 null 则差值为 null。退步清单按指标列出 caseId。

latencyMs 可缺省或为 null，存在时为非负有限数。仅统计文件记录的覆盖数、均值和 nearest-rank P95，后者为排序后第
`ceil(0.95*N)` 项。五条中三条有记录时覆盖数为 3/5，无记录时统计为 null。CLI 执行耗时不是检索耗时，不提供时延门禁、吞吐或线上性能结论。

## 报告与诊断

默认交付四种文件：

- `summary.json`：完整机器报告，schemaVersion 和 metricDefinitionVersion 均为
  1；包含身份、实际摘要、来源声明、k、适用样本数、所有指标及差值、耗时覆盖、退步用例与门禁结果。
- `summary.csv`：总体和各标签的长表，包含 totalCases、eligibleCases、当前值、基线值和 rawDelta。
- `cases.csv`：逐 caseId 的长表，包含标签、指标、适用数及基线差值。
- `report.md`：人读汇总、分类比较、输入摘要、来源、附加耗时、退步场景和门禁结果。

JSON 保留未展示舍入的 double；CSV 和 Markdown 显示六位小数。稳定顺序为 runId、tag、caseId 字典序与 k 数值升序，不插入当前时间、绝对路径、输出位置或
CLI 用时。摘要基于此次实际读取字节，与解析同源；同一输入和选项的报告内容稳定。改变 JSON 空格或换行也会改变输入摘要。

输出目录必须尚不存在。全部校验和计算结束后，在同父目录创建独有暂存目录，写完四种文件后发布。既有目标以及并发创建的目标不得覆盖或删除；失败只清理本次暂存目录，不修改输入。质量门禁失败也发布完整报告；输入、配置或输出错误不发布本次最终报告目录。

诊断形式为 `EVAL_ERROR <固定类别> role=run[1].results line=17 field=versionId`。角色是别名，行号只用于 JSONL
定位，错误不回显实际路径、字段值、原始行或底层异常。正常完成只输出 `EVAL_COMPLETE`；质量门禁失败输出
`EVAL_QUALITY_GATE_FAILED`。

## 阈值和退出码

阈值示例只演示配置，不构成生产质量门槛：

```json
{"schemaVersion":1,"rules":[{"metric":"recall","k":10,"min":0.7,"maxRegression":0.02},{"metric":"duplicateRate","k":10,"max":0.4},{"metric":"emptyResultAccuracy","min":0.9}]}
```

首版仅对总体宏平均设规则。hit/recall/mrr/ndcg/emptyResultAccuracy 支持 min；duplicateRate 支持 max；所有指标支持
maxRegression。退步量是改善方向上的绝对差：质量指标为 baseline-candidate，重复率为 candidate-baseline，`0.02` 指标差不是 2%
相对比例。改善时该量为负。

每个限制必须为 0..1 的有限数。每项规则至少一个限制，同一 metric+k 不得重复；emptyResultAccuracy 不允许 k，其他指标必须指向本次计算的
k。未知指标/键、方向不符、空规则、退步规则缺少基线均拒绝。被评测运行或退步规则所需基线的指标为 null 时，属于配置错误，不能视为通过。

指定基线时只对每个候选判定，未指定时绝对规则应用于所有运行。边界相等通过，计算容差为 1e-12；不使用六位小数展示值判定。JSON
门禁结果含 actual、baseline、min、max、maxRegression、observedRegression 和 passed，所有候选及规则完整输出。

| 退出码 | 含义                        |
|-----|---------------------------|
| 0   | 输入有效且门禁通过，或未配置门禁；完整报告发布   |
| 1   | 输入有效，但至少一个运行违反质量规则；完整报告发布 |
| 2   | 输入、配置或输出错误；不发布本次最终报告目录    |

## PowerShell 用法

从项目根目录打包（不运行测试）：

```powershell
mvn -pl kb-eval -am package '-DskipTests'
```

离线样例位于 `kb-eval/examples/demo`，标签摘要已填写。两套配置都是合成结果，没有实际执行 BM25 或
rerank。以下命令作为使用说明交付，不属于本次集成验收：

```powershell
java -jar '.\kb-eval\target\kb-eval-0.1.0-SNAPSHOT.jar' `
  --dataset '.\kb-eval\examples\demo\dataset.json' `
  --run '.\kb-eval\examples\demo\bm25.run.json' `
  --run '.\kb-eval\examples\demo\rerank.run.json' `
  --baseline bm25 --k 5,10,20 `
  --thresholds '.\kb-eval\examples\demo\thresholds.json' `
  --output '.\target\retrieval-eval-demo'
$LASTEXITCODE
```

再次运行需更换 output 目录。`--run` 可以重复，其他参数只能指定一次；`--help` 无需评测文件，返回 0，不读文件或写报告。

本次验证仅为进程内组件单元测试，使用合成内存数据与本地临时文件。明确白名单命令如下：

```powershell
mvn -pl kb-eval test '-Dtest=CliOptionsTest,DiagnosticFormatterTest,InputReaderTest,MetricCalculatorTest,AggregatorTest,ComparisonCalculatorTest,ThresholdReaderTest,QualityGateTest,JsonReportWriterTest,CsvReportWriterTest,OutputPublisherTest'
```

未安排集成测试、完整 CLI 回放、独立 JAR 进程烟测、外部服务验证或针对 README、使用文档、Markdown
内容、注释和文档示例的测试。单测和打包成功仅证明离线组件的已覆盖行为，不证明真实检索质量、线上权限或性能。

## 本次实施验证记录

2026-10-11，JDK 21.0.7、Maven 3.9.9：上述 11 个白名单测试类共 116 项组件单元测试通过，失败、错误和跳过均为
0。仅新增模块的测试被执行，未运行其他业务模块的测试。

`mvn -pl kb-eval -am package '-DskipTests'` 成功，产物为 `kb-eval/target/kb-eval-0.1.0-SNAPSHOT.jar`；通过读取 JAR
manifest 核对 Main-Class 并确认包含 Jackson 运行时，没有启动独立 JAR 进程。
`mvn -pl kb-eval dependency:tree '-Dscope=runtime'` 显示运行时只有 Jackson 2.17.2 的
databind、core、annotations；源码包依赖检查未发现业务模块、Spring、数据库或服务客户端导入。

`openspec validate retrieval-quality-evaluation --strict` 通过。合成文件和 PowerShell 示例仅作交付，未将执行这些示例作为验收。

2026-10-11，W1 修复复核：耗时均值改为按最大值缩放后求平均，补充极大值、极小值、混合零值和全零共 5 项边界组件单测。上述白名单现共
121 项单测通过，失败、错误和跳过均为 0；跳过测试的 Maven 打包和严格规格校验再次通过。
