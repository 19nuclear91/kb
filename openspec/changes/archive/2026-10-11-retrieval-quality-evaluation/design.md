# Design

## Context

动机见 [proposal.md](proposal.md)。用户已确认三项边界：首版完全离线、以文档版本标注并统计重复分块、采用独立 Java CLI 和
Maven。

当前实现提供以下接入事实：

- 根 `pom.xml` 聚合五个 Java 21 模块，尚无评测模块。
- `SearchRequest` 只接受查询及数量；本变更不增加在线检索模式或导出端点。
- `ChunkHit` 包含 `docId`、`versionId`、`chunkId`，其 `score` 是 RRF 分数。重排客户端返回重新排列的原命中对象，因此评测必须使用数组顺序，不能按
  score 再排序。
- `SearchServiceTest` 用模拟召回与模型验证编排，没有可直接用于人工相关性评测的数据集。
- `citation-provenance` 要求版本身份不可猜测，`search-query-confidentiality` 要求查询、正文和凭据不进入日志。本变更以独立离线数据格式承接这些边界，不修改主规格。

## Goals / Non-Goals

**Goals:**

- 将格式校验、纯计算、聚合比较、文件呈现和进程退出分开，使指标可通过手算样例核验。
- 固定输入身份、截断顺序、重复收益和空值语义，避免工具实现差异改变成绩。
- 使同一输入和选项产生确定性报告，供本地审阅或 CI 读取。

**Non-Goals:**

- 不将既有业务实体、Mapper 或服务实例加载进评测进程，不增加任何网络客户端。
- 不推断文档当前版本、查询身份或当前 ACL，不依据文档版本命中判断分块是否包含答案。
- 不以此次合成样例决定生产质量阈值，不将算法名称当作实际执行路径的证明。

## Decisions

### D1：独立 Maven 模块及命令行包

新增 `kb-eval`，包名为 `com.zhishiku.kb.eval`，根 POM 仅增加模块注册。模块不依赖 `kb-common`、`kb-app`、`kb-gateway`、
`kb-worker`、`kb-ai`，也不声明 Spring、数据库、Redis、S3 或 HTTP 客户端。

使用父 POM 管理的 Jackson 解析 JSON，沿用 JUnit 5 与 AssertJ；通过显式配置的 Maven Shade 插件生成带 Main-Class 的可运行
JAR。CLI 参数规模有限，首版直接解析固定参数，不增加命令行框架。插件版本在实施时显式固定，不使用动态版本。

按 `cli`、`input`、`model`、`metrics`、`comparison`、`report` 分包；入口提供可测试的执行方法，由 main 统一调用进程退出。计算代码不持有文件或日志对象。

备选是把工具放入 kb-app 或使用 Python 脚本。前者会引入业务运行时和服务配置，后者不符合本轮已确认的实现形态。

### D2：版本化清单与 JSONL 数据

所有文件使用 UTF-8，首版格式版本为 `schemaVersion=1`。数据集清单和运行清单使用 JSON；标注和结果使用逐行
JSONL，便于定位错误及流式读取。清单中的文件路径相对清单目录解析，只接受本地普通文件，不从 URI 或通配扫描读取输入。

数据集清单 `dataset.json`：

```json
{
  "schemaVersion": 1,
  "datasetId": "demo",
  "datasetVersion": "v1",
  "corpusSnapshotId": "synthetic-v1",
  "labelsFile": "labels.jsonl"
}
```

一行标注的示例：

```json
{"caseId":"q001-finance-v1","tags":["term"],"expectedEmpty":false,"relevance":[{"docId":101,"versionId":201,"grade":3},{"docId":102,"versionId":202,"grade":1},{"docId":103,"versionId":203,"grade":2}]}
```

`caseId` 对应一个固定查询与身份场景；不同身份或权限快照必须由数据制作者分成不同 caseId。工具不需要保存查询文本或实际用户
ID。等级 0、1、2、3 分别表示不相关、弱相关、相关、强相关；正相关集合为 grade 大于 0 的版本。未标注项按 0 计算，标注集需由制作者确认完整性；改变标注时升级
datasetVersion。

`expectedEmpty=false` 必须至少有一个正相关版本；`expectedEmpty=true` 不得有正相关版本。标签允许空列表，此时归入保留分类
`UNTAGGED`；非空标签采用受控技术标识，重复标签拒绝。

运行清单 `bm25.run.json`：

```json
{
  "schemaVersion": 1,
  "runId": "bm25",
  "datasetId": "demo",
  "datasetVersion": "v1",
  "corpusSnapshotId": "synthetic-v1",
  "configurationId": "bm25-candidates50-v1",
  "sourceType": "SYNTHETIC",
  "labelsSha256": "<actual-sha256-of-labels.jsonl>",
  "resultsFile": "bm25.jsonl"
}
```

示例中的摘要占位符仅用于说明；实际文件必须提供 64 位十六进制 SHA-256。来源枚举为 `SYNTHETIC` 或 `RECORDED`
，后者只声明输入已有导出结果，不证明来源已被工具核验。方案名可使用 bm25、vector、rrf、rerank 等技术标识，工具不执行对应算法。

一行成功结果的示例：

```json
{"caseId":"q001-finance-v1","status":"OK","hits":[{"docId":101,"versionId":201,"chunkId":301},{"docId":101,"versionId":201,"chunkId":302},{"docId":102,"versionId":202,"chunkId":303}],"latencyMs":12.5}
```

`status` 为 `OK` 或 `ERROR`；OK 必须有 hits 数组，ERROR 表示原检索执行失败，整个运行不能产生正常质量报告。latencyMs 可缺省或为
null，存在时须为非负有限数。真实 API 的 `R` 外层包装不作为输入契约；首版不提供自动导出或转换命令。

清单、阈值及评测必要结构拒绝未知字段和重复 JSON 属性；标注行的额外顶层业务字段、结果行的额外顶层业务字段及 hit
的非身份字段允许跳过，避免输入含 content、score 等字段时将其持久保存或回显。嵌套 relevance 身份结构严格校验。

ID 必须是 JSON 整数且位于 `1..Long.MAX_VALUE`，不允许字符串、小数、布尔或 null 被强制转换。数据集、方案、配置、caseId 和 tag
标识统一为 1–128 字符的 `[A-Za-z0-9][A-Za-z0-9._:-]*`，UNTAGGED 为保留标签。每个样本的 relevance 中版本身份唯一，hits
中完整三元组唯一；同版本不同分块合法。

备选是 CSV 或把元数据反复写入每行。JSONL 能表达有序分块及分级标注；独立清单避免重复元数据互相冲突，并支持明确的格式升级。

### D3：严格覆盖，先校验再计算

数据集至少包含一个样本。每个运行的 caseId 集合必须与标注集完全相等，且 runId 全局唯一。所有运行的
datasetId、datasetVersion、corpusSnapshotId 必须匹配数据集清单，labelsSha256
必须匹配此次读取的标注字节。报告另记录数据集清单、标注、运行清单、结果和阈值文件的实际摘要；文件路径和输出目录不进入确定性报告。

先完成全部输入和一致性校验，再计算；不默认取交集，也不跳过坏行、ERROR 用例或缺失版本。输出错误定位使用输入角色别名，例如
`run[1].results`、行号、受控字段名和固定错误类别；禁止复制源行、字段值或解析异常的原始文本。

输入摘要和解析必须基于同一份读取内容，不能读取后再独立重读文件计算摘要而产生错配。显式 schemaVersion
不匹配即拒绝，不猜测旧数据版本。输入校验覆盖完整 hits 数组，包括 max(k) 以后的身份和重复三元组；仅截短计算时保留的结果，不能截短校验范围。

备选是尽量评测可用样本。严格完整覆盖能防止只保留容易命中的样本而得到更好的平均值。

### D4：固定分块预算与指标定义

对每个 k 先取 `hits[0:min(k,hits.size)]`，设实际数量为 n；版本身份集合为 U，全部正相关标注集合为 R。使用原始位置 r，从 1
开始；不补位，不按 score 排序。同一版本的第二次及后续出现收益为 0，位置仍占用预算。

| 指标键                   | 单样本计算                                         |
|-----------------------|-----------------------------------------------|
| `hit`                 | R 非空时，U 与 R 交集非空为 1，否则为 0                     |
| `recall`              | R 非空时，`size(U intersect R) / size(R)`         |
| `mrr`                 | R 非空时，首个相关命中的 `1/r`，没有命中为 0                   |
| `ndcg`                | R 非空时，`DCG@k / IDCG@k`                        |
| `duplicateRate`       | n 大于 0 时，`(n-size(U))/n`；否则 null              |
| `emptyResultAccuracy` | 仅 expectedEmpty 样本适用，完整 hits 为空为 1，否则为 0；不带 k |

DCG 为各原始位置首次版本收益 `(2^grade-1)/log2(r+1)` 之和；IDCG 将全部不同版本的标注按 grade 降序排列，取前 k 个计算，grade
为 0 的收益为 0。R 非空保证 IDCG 大于 0。结果少于 k 时不会自动判错，其质量指标仍使用完整正相关集合及完整理想排序作为分母。

expectedEmpty 样本的 hit、recall、mrr、ndcg 为 null；有正相关目标但结果为空时四项均为 0。全部指标以适用样本为分母做宏平均，汇总包含
totalCases、eligibleCases；没有适用样本时值为 null。重复率与空结果符合率分别汇总，不以空结果的重复率 0 奖励没有召回的方案。

手算验收：A=3、B=1、C=2，排名 A、A、B、C。k=3 时 hit=1、recall=2/3、mrr=1、duplicateRate=1/3、DCG=7.5，IDCG=`7+3/log2(3)+1/2`
。再增加首项不相关、相关项刚好位于 k 内外、相同文档不同版本等独立夹具。

备选是先去重后取 k，或只判断 docId。两者都会改变实际预算或混淆不同版本的相关性。

### D5：同条件基线比较与分类聚合

CLI 的 `--baseline <runId>` 显式指定已加载的基线。未指定时只输出各运行指标，不默认将文件顺序当作基线；指定时至少还需一个候选运行。所有运行使用同一标注集和同一组
k，不提供静默缩小样本范围的选项。

每个标签独立形成分组，多标签样本进入多个组，但总体只计一次。汇总与逐样本比较均输出候选减基线的
rawDelta；质量指标正差为改善，duplicateRate 负差为改善，空值比较的差仍为 null。逐样本列表按 caseId 排序，并按指标列出退步用例标识。

latencyMs 仅作为附加记录统计，输出覆盖数、均值和 nearest-rank P95：排序后取 `ceil(0.95*N)` 位。无记录时为
null。报告明确其为文件声明的耗时，首版不据此提供吞吐、压测结论、时延门禁或退步判定。

备选是跨查询比较 RRF 原始分数或把离线执行用时当检索性能。这些值不能证明实际排序质量或检索延迟。

### D6：报告及输出发布

默认同时输出四个文件：`summary.json` 为完整机器可读报告，`summary.csv` 为总体及标签汇总，`cases.csv` 为逐样本指标及基线差值，
`report.md` 为人读汇总和退步用例。JSON 中包含
schemaVersion=1、metricDefinitionVersion=1、来源声明、身份与摘要、有效样本数、所有指标、差值、耗时覆盖及门禁结果。

数值使用 double 保存计算结果；JSON 保留足够精度，CSV/Markdown 展示固定 6 位小数，不从展示舍入值重新计算差值或门禁。JSON 的
null、CSV 空单元格、Markdown 的 N/A 都代表不适用，并同时展示 eligibleCases。运行按 runId、分类按 tag、用例按 caseId、k
按数值升序稳定输出；不插入当前时间、绝对路径或 CLI 执行耗时。

报告只从白名单评测 DTO 生成，不序列化原始 JSON 树。受控技术标识中的 Markdown 特殊字符需转义，CSV
使用标准字段转义；正文、标题、query、密码、token、模型连接信息和原始异常都不进入标准输出、错误输出或报告。

输出目录必须不存在。全部验证和计算完成后在其父目录建立本次独有的暂存目录，写完全部报告后将目录发布到目标名称；提交前再次检查目标不存在，不覆盖。失败时仅清理本次暂存目录，校验绝对目标后操作，不删除已有目标。返回
1 的门禁失败也发布完整报告；返回 2 的运行不产生最终目录。

备选是逐文件直接写入目标目录。暂存后发布避免 CI 把半套报告误认为已完成结果。

### D7：显式回归规则和退出码

CLI 支持 `--dataset`、可重复 `--run`、可选 `--baseline`、可选 `--k`、可选 `--thresholds`、必需 `--output` 和 `--help`。默认 k
为 5,10,20；用户值必须是无重复的正整数，重复或非法参数返回 2，合法值按升序计算。help 返回 0 且不读取评测文件或写报告。

阈值文件示例：

```json
{
  "schemaVersion": 1,
  "rules": [
    {"metric":"recall","k":10,"min":0.7,"maxRegression":0.02},
    {"metric":"duplicateRate","k":10,"max":0.4},
    {"metric":"emptyResultAccuracy","min":0.9}
  ]
}
```

首版只对总体宏平均设门禁。hit、recall、mrr、ndcg、emptyResultAccuracy 支持 min；duplicateRate 支持 max；所有指标支持
maxRegression，含义为按改善方向计算的最大绝对退步量，不是百分比：质量指标为 baseline-candidate，重复率为
candidate-baseline。每条规则至少有一个限制值，值须为 0..1 的有限数。质量指标不接受 max，重复率不接受 min；emptyResultAccuracy
不接受 k，其他规则必须引用本次计算的 k。重复 metric+k 规则、空规则列表、未知键或缺少基线的退步规则拒绝。

指定基线时对每个候选执行规则，基线仅作比较；未指定基线时绝对规则应用于所有运行。至少一项适用且正常计算的规则才有门禁意义；规则指向任何被评测运行或所需基线的
null 指标时返回配置错误 2。缺省不设置生产门槛。

退出语义固定：0 为有效评测且门禁通过或未配置，1 为有效评测且至少一个候选违反规则，2 为输入、配置或输出错误。限制边界相等时通过，double
比较使用至多 1e-12 的计算容差，禁止使用展示舍入值。规则结果输出实际值、参考值、限制值及 pass/fail，便于复核。

备选是固定项目质量门槛或以同一个非零码表示所有失败。目前缺少真实标注集，门槛应由实际评测建立；区分 1 和 2 可避免把工具错误当成质量退步。

## Risks / Trade-offs

- 标注不完整会低估未标注结果的相关性 → 明确未标注按 0 计算，以标注版本与摘要追踪变更，不声称标注真实性由工具证明。
- 文档版本命中可能只命中无答案的分块 → 报告明确粒度，后续精确分块评测另行扩展，不从当前指标推导答案质量。
- 输入可以错误声明方案或来源 → 保留声明、配置标识及摘要，首版只核验文件间一致性，不核验实际算法或权限执行。
- 同样结果在不同 k 下重复率和 Recall 变化 → 固定先截断后统计、原始位置不压缩，测试预算边界。
- 文件原本可能含敏感额外字段或损坏内容 → 流式跳过非评测字段，固定诊断，不输出原始行或异常消息。
- 全部运行结果载入会随数据集变大占用内存 → 标注按 caseId 索引，结果逐行读取；只保存所需指标、比较用身份和前 max(k)
  的记录，耗时单独累计。先按仓库预期的约 50 条查询规模验收，不承诺大规模压测能力。

## Migration Plan

1. 实现阶段新增 kb-eval 并注册 Maven 模块；既有模块无运行时依赖或配置迁移。
2. 用合成夹具完成参数解析、输入、指标、聚合、序列化及门禁组件的进程内单元测试，提供含实际 labelsSha256 的样例，并以跳过测试的
   Maven 构建检查交付 JAR；本次不安排完整 CLI 回放、独立进程烟测或集成测试。
3. 在 `docs/retrieval-evaluation.md` 记录格式、口径、标注责任及 PowerShell 操作；README 只增加评测入口与范围。
4. 日后由独立采集流程提供已有结果文件；启用质量门禁前根据真实标注选择阈值，本次不编写采集流程或上线配置。
5. 回退时停止使用该 CLI 并移除模块注册，无业务数据迁移；已生成报告保留供追踪，不影响线上服务。

实施验证限定为组件单元测试和跳过测试的编译打包；不运行外部组件，不对 README、使用文档、Markdown
文件内容或文档示例编写测试，也不要求执行文档示例作为验收。规格场景描述目标行为，不意味着必须增加集成或端到端测试。

预期实施后的 PowerShell 用法如下，仅用于交付使用说明，不作为本次实施的执行验收任务：

```powershell
mvn -pl kb-eval -am package '-DskipTests'
java -jar '.\kb-eval\target\kb-eval-0.1.0-SNAPSHOT.jar' `
  --dataset '.\kb-eval\examples\demo\dataset.json' `
  --run '.\kb-eval\examples\demo\bm25.run.json' `
  --run '.\kb-eval\examples\demo\rerank.run.json' `
  --baseline bm25 --k 5,10,20 `
  --output '.\target\retrieval-eval-demo'
```

合成样例的配置名仅表示演示方案，不代表已经运行真实 BM25 或 rerank。再次运行应使用新的输出目录。
