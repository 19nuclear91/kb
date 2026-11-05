# OpenSpec 核验报告：collect-retrieval-evaluation-results

核验日期：2026-11-05。Schema：spec-driven。首次核验未修改实现；随后按用户要求修复 W1，修复复核见文末。
任务勾选和归档状态未变更。
依据为本变更 proposal、design、tasks、retrieval-result-collection delta spec，以及当前工作区实现和测试。

## 核验结果

| 维度 | 结果 |
| --- | --- |
| 完整性 | 28/28 任务已勾选；15/15 新增需求找到实现，无缺失需求或不可读材料 |
| 正确性 | 15 条需求完成实现映射；39 个规格场景找到代码和组件测试证据；额外边界问题 W1 已修复并回归通过 |
| 一致性 | 8 项设计决策已对照；网络前预检的 W1 偏差已消除；代码组织和约定未见显著偏离 |

当前未解决问题：CRITICAL：0。WARNING：0（首次核验的 W1 已关闭）。SUGGESTION：0。
适用检查未跳过；没有 REMOVED 或 RENAMED 需求。
真实网络、服务、模型及 ACL 快照验收被设计明确排除，不作为本次核验或归档的新增前提。

## 已解决的 WARNING

### W1：不可创建的输出目录名未在网络前拒绝（已修复）

首次核验位置：`kb-eval-collector/src/main/java/com/zhishiku/kb/eval/collector/OutputPublisher.java:21`，
尤其是第 24、33 行的 `Files.exists`；调用顺序见 `Collector.java:32`。

修复前 `preflight` 检查目标是否存在、父目录及可写性，但没有核验最终目录名的文件系统限制。
`Files.exists` 在无法确定存在性时也返回 false，因此过长名称会被当作尚不存在的合法目标。
暂存目录使用另一个短名称，创建能够成功，随后身份和搜索调用会照常进行，最后 `Files.move` 才失败。

本机 Windows 上以正常合成输入、一个场景和 300 个 ASCII 字符组成的输出目录名执行进程内复现，结果为：

```text
invalidOutputNameLength=300 exit=2 remoteCalls=2
COLLECT_ERROR OUTPUT role=output line=0 field=file
```

这里的 remoteCalls 是内存传输替身记录的调用数，分别为 `/api/auth/me` 和 `/api/search`；没有实际网络连接。
预期为退出 2 且调用次数为 0。虽然最终没有发布错误运行，但无效输出路径仍会消耗实际部署的搜索/模型资源。

这偏离了“用例必须完整绑定标注场景”需求中的“全部可本地判定的错误 MUST 在任何网络调用前拒绝”，
以及 design 第 2 节的网络前输出路径校验约定，关联任务 5.1、6.2。

修复要求（已落实）：在 `preflight` 校验目标名称和路径的文件系统约束，并区分“目标不存在”与“属性探测失败”；
补充非法/过长输出路径的进程内回归测试，断言客户端或传输调用次数为 0，既有输入与目标不变。
首次核验仅记录问题；后续修复已修改实现及测试，详见“W1 修复复核”。

复现代码留在被 Git 忽略的 `target/retrieval-collection-verification/review-probes/ReviewProbe.java`，
仅使用已有 Fixtures 和 FakeTransport。该诊断复现不计入下述 276 项正式测试。

## 需求与实现映射

以下实现文件均位于 `kb-eval-collector/src/main/java/com/zhishiku/kb/eval/collector/`，
测试类位于该模块对应的 `src/test/java` 包；既有评测器的测试另外注明。

| 新增需求 | 主要实现证据 | 场景测试证据与结论 |
| --- | --- | --- |
| 独立 CLI 与既有授权接口 | CollectionCli.java:14、32；Protocol.java:58、73；模块 pom.xml | CollectionCliTest、CollectorTest 的正常输出与帮助测试；2 个场景有证据 |
| 完整绑定标注场景 | Inputs.java:108、165、207；Collector.java:32 | InputsTest 标注对照/覆盖校验、CollectorTest 零调用和多身份测试；3 个列明场景有证据，额外边界 W1 已修复 |
| 有界本地输入与冻结字节 | JsonSupport.java:68、81、105、136；Inputs.java:108 | InputsTest 的实际读取/行/数量/深度/UTF-8/大整数测试及 CollectorTest 文件变化测试；3 个场景有证据 |
| 令牌绑定与预检 | Collector.java:58；Protocol.java:58 | CollectorTest 全身份先于搜索、第二身份失败、凭据冻结；ProtocolTest 身份标志；3 个场景有证据 |
| 端点与凭据传播 | Endpoint.java:13；JdkTransport.java:29；Protocol.java:93 | ProtocolTest 前缀/不安全 URL/重定向/客户端隔离/头字段；3 个场景有证据 |
| 串行、数量与筛选 | Inputs.java:129、207、244；Collector.java:42 | InputsTest 数量/Unicode/筛选/编码限制，ProtocolTest 请求构造，CollectorTest 顺序；3 个场景有证据 |
| 期限与响应读取限界 | Deadline.java:12；JdkTransport.java:60；Protocol.java:102；JsonSupport.java:81 | ProtocolTest 慢正文/无长度超限/中断/解析期限，JdkTransportTest Future 取消与交接；3 个场景有证据 |
| 完整成功协议 | Protocol.java:102、159；JsonSupport.java:105 | ProtocolTest 空数组/坏包装/编码/筛选版本头；3 个场景有证据 |
| 身份与原始排名保真 | Protocol.java:73、178 | ProtocolTest 精确 long、同版本不同分块、反向 score、重复三元组/缺失版本；3 个场景有证据 |
| 首错停止且不重试 | Collector.java:42；Protocol.java:102；JdkTransport.java:29 | CollectorTest 中间场景失败，ProtocolTest 3xx/401/403/429/503/断连；2 个场景有证据 |
| 兼容离线格式 | OutputPublisher.java:116、129 | CollectorTest 直接使用未修改 InputReader，InputsTest CRLF 原字节摘要；2 个场景有证据 |
| 元数据声明边界 | Collector.java:72；Inputs.java:312 | CollectorTest 固定观测标志、摘要变化和敏感内容排除；3 个场景有证据 |
| 客户端搜索耗时 | Protocol.java:73、102；Deadline.java:30 | CollectorTest.latencyIncludesBodyButExcludesPreflightAndWriting；2 个场景由同一可控时钟测试覆盖 |
| 完整发布且不覆盖 | OutputPublisher.java:23、75、80、129 | OutputPublisherTest 已有目标、发布竞争、写盘、清理和取消；2 个列明场景有证据；新增 W1 零客户端/零请求回归通过 |
| 退出码与脱敏诊断 | CollectionCli.java:32；CollectionException.java:5；既有 EvaluationCli.java:38 | CollectionCliTest、CollectorTest、ProtocolTest 脱敏/错误分类；既有 QualityGateTest 验证质量失败为 1，采集成功仍为 0；2 个场景有证据 |

场景证据按组件组合核对，未将其表述为 39 次真实服务回放。所有标为 FINAL_RESPONSE、UNOBSERVED、DECLARED_ONLY 的事实边界均保持不变。

## 设计与项目一致性

逐项核对八项设计决定：独立 Java 模块与文件协议、四个 CLI 参数及严格输入、受控端点和全身份预检、
绝对期限/串行/无重试、完整协议与排序、兼容输出和旁路元数据、失败停止及新目录发布、仅进程内验证。
W1 曾是网络前预检的边界缺口，现已修复；没有新增算法阶段接口、在线评测模式、业务存储访问或业务服务运行时依赖。

HTTP 传输、环境变量、时钟与输出发布均可替换。Maven/包命名、具体类 import、多行控制流与大括号符合现有项目模式。
文档和合成示例按字段契约人工核对，没有对文档内容写断言。

## 首次核验执行与范围

重新执行实施记录中的明确白名单 Maven test：17 类、276 项全部通过，失败/错误/跳过均为 0。
其中采集器 155 项，既有评测器 121 项。日志：`target/retrieval-collection-verification/review-tests.log`。
再次运行两个现有独立 JAR 的 --help，均返回 0；`git diff --check` 和本变更 OpenSpec 严格校验通过。
独立打包与运行时依赖检查沿用同一实现的实施记录；本次未重复打包。

没有执行不限定范围的 Maven verify，没有启动 HTTP 监听端口、前端、应用、数据库、Redis、MinIO、Docker 或模型服务。
真实网络、底层客户端部署兼容性、模型效果和权限快照不在本次验收范围内。

## 结论

W1 已修复并复核，当前无未解决的 CRITICAL、WARNING 或 SUGGESTION。适用检查已完成，可归档。
尚未执行归档；真实部署验收边界保持不变。

## W1 修复复核

2026-11-05：`OutputPublisher.java:23` 的预检及发布前检查改用 `requireAbsent`，通过
`Files.readAttributes(..., NOFOLLOW_LINKS)` 探测目标，只有明确的 `NoSuchFileException` 允许继续。
名称超限、权限拒绝和其他探测异常均转为固定的本地输出错误，不创建客户端。父目录创建并解析真实路径后再次检查目标，
避免缺失父目录掩盖最终名称错误。检查使用实际文件系统行为，没有添加统一的目录名长度上限。

新增 6 项进程内回归：256/300 字符名称、缺失父目录下的超长名称、AccessDeniedException、一般 IOException，
以及合法 255 字符目录名的完整发布。失败用例同时检查客户端创建数和传输调用数为 0、诊断不泄密、原始输入及既有目录保持不变。

定向执行 `com.zhishiku.kb.eval.collector.OutputPublisherTest` 和 `com.zhishiku.kb.eval.collector.CollectorTest`：
35 项全部通过（14 + 21），失败/错误/跳过均为 0。未重复执行无关测试。
重新打包通过，新采集器 JAR 的 --help 返回 0；差异检查和 OpenSpec 严格校验通过。

原 300 字符复现代码再次执行的结果已变为：

```text
invalidOutputNameLength=300 exit=2 remoteCalls=0
COLLECT_ERROR OUTPUT role=output line=0 field=file
```

日志位于 `target/retrieval-collection-verification/` 的 w1-tests.log、w1-package.log、w1-help.log、w1-reproduction.log。
没有启动任何服务或编写文档内容断言测试。
