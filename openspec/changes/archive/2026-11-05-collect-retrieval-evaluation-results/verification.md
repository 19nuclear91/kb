# 实施验证记录

2026-11-05，Windows 11 / PowerShell 7，Temurin JDK 21.0.4、Maven 3.9.8。

## 测试白名单与结果

仅执行进程内单元/组件测试，使用合成内存数据、替身传输、响应流、可控时钟和临时文件。
`JdkTransportTest` 的 Future 也是内存对象，没有建立网络连接。

```powershell
mvn -o -pl kb-eval-collector -am test '-Dtest=CollectionCliTest,InputsTest,ProtocolTest,CollectorTest,OutputPublisherTest,JdkTransportTest,CliOptionsTest,DiagnosticFormatterTest,InputReaderTest,MetricCalculatorTest,AggregatorTest,ComparisonCalculatorTest,ThresholdReaderTest,QualityGateTest,JsonReportWriterTest,CsvReportWriterTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

`OutputPublisherTest` 在两个模块分别存在，均在白名单中。reactor 仅包含根 POM、kb-eval、kb-eval-collector。
实际执行 17 类、276 项，失败 0、错误 0、跳过 0；未使用忽略失败选项。各类结果如下：

| 模块 | 测试类 | 用例数 |
| --- | --- | ---: |
| kb-eval-collector | CollectionCliTest | 10 |
| kb-eval-collector | InputsTest | 58 |
| kb-eval-collector | ProtocolTest | 53 |
| kb-eval-collector | CollectorTest | 21 |
| kb-eval-collector | OutputPublisherTest | 8 |
| kb-eval-collector | JdkTransportTest | 5 |
| kb-eval | CliOptionsTest | 12 |
| kb-eval | DiagnosticFormatterTest | 2 |
| kb-eval | InputReaderTest | 54 |
| kb-eval | MetricCalculatorTest | 7 |
| kb-eval | AggregatorTest | 9 |
| kb-eval | ComparisonCalculatorTest | 3 |
| kb-eval | ThresholdReaderTest | 18 |
| kb-eval | QualityGateTest | 5 |
| kb-eval | JsonReportWriterTest | 3 |
| kb-eval | CsvReportWriterTest | 3 |
| kb-eval | OutputPublisherTest | 5 |

覆盖有界输入、标注语义对照、精确 64 位 ID、全部本地错误先于网络、身份冻结与全量预检、无重试、筛选协议、
损坏响应与合法空结果、逐块正文限界、解析期限、取消及响应交接竞争、串行失败停止、耗时、场景摘要、脱敏、
完整发布、竞争创建、写盘/清理故障。生成产物直接交给未修改的 kb-eval InputReader 校验。

## 构建、依赖与进程烟测

```powershell
mvn -o -pl kb-eval-collector -am package '-DskipTests'
java -jar '.\kb-eval-collector\target\kb-eval-collector-0.1.0-SNAPSHOT.jar' --help
java -jar '.\kb-eval\target\kb-eval-0.1.0-SNAPSHOT.jar' --help
```

打包成功。两个 --help 在无输入文件的空工作目录执行，均退出 0，工作目录新增文件数为 0；未提供凭据或执行采集。
采集器运行时依赖树仅含 Jackson databind/core/annotations 2.17.2；检查打包内容没有 Spring、业务服务模块、
kb-common 或 kb-eval 的输入/指标实现。kb-eval 仅为测试范围依赖，其源代码和 POM 均未修改。
Shade 输出了 Jackson 许可证、manifest 与 module-info 重叠的常规警告，打包及帮助入口均成功。

## 人工复核与边界

新增 Java 使用具体类 import、多行方法及带大括号的控制流；record 补充中文 Javadoc 与组件说明。
人工核对合成示例字段、PowerShell 参数、凭据注入说明、两套退出码、原始标注保留及新输出目录要求。
不对文档、注释、源码格式或文档示例编写内容断言测试。

`git diff --check` 与 `openspec validate collect-retrieval-evaluation-results --type change --strict` 通过。
本次没有未解决的白名单测试失败；其他业务模块测试未运行。

未执行无范围限制的 verify、完整真实采集、前端/业务应用、HTTP 监听服务、数据库、Redis、MinIO、Docker 或模型服务。
替身验证不证明真实网络和底层客户端在部署中的行为、实际模型效果、冻结的语料/ACL 或线上权限验收。
配置与快照仍是声明，算法降级状态仍为 UNOBSERVED。

本地详细日志保存在被 Git 忽略的 `target/retrieval-collection-verification/`：
tests.log、test-results.json、package.log、collector-help.log、evaluator-help.log。

## W1 修复验证（2026-11-05）

修复输出路径预检将属性探测错误误认为“不存在”的问题。只有 NoSuchFileException 允许继续，其他探测错误在创建客户端前返回 2。
新增 6 项回归覆盖名称超限、缺失父目录、属性探测故障与合法 255 字符名称；失败场景验证零客户端、零传输调用和文件不变。

```powershell
mvn -o -pl kb-eval-collector -am test '-Dtest=com.zhishiku.kb.eval.collector.OutputPublisherTest,com.zhishiku.kb.eval.collector.CollectorTest' '-Dsurefire.failIfNoSpecifiedTests=false'
mvn -o -pl kb-eval-collector -am package '-DskipTests'
java -jar '.\kb-eval-collector\target\kb-eval-collector-0.1.0-SNAPSHOT.jar' --help
```

本轮定向测试 35 项全部通过（OutputPublisherTest 14 项，CollectorTest 21 项），失败/错误/跳过为 0。
打包、帮助入口及严格校验通过；原 300 字符目录名复现从 2 次替身调用变为 0 次，仍返回固定 OUTPUT 诊断和退出码 2。
本轮未重跑完整 276 项历史白名单，不将累计测试数当作一次执行结果。详细关闭记录见 [review.md](review.md)。
