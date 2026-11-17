# E-11 实现与离线验证记录

日期：2026-11-17。变更：`answer-quality-and-evidence-evaluation`，schema：spec-driven。五份增量规格共 36 项要求、86 个场景；原任务 43 项，核验后追加修复任务 5 项，合计 48/48 完成。已交付实现与离线证据，同步 5 份主规格并[归档](../openspec/changes/archive/2026-11-17-answer-quality-and-evidence-evaluation/archive-summary.md)，未部署。下表按规格要求归组定位实现场景及测试，不将场景数量等同于 JUnit 方法数量，也不把 mock 结果称为真实联调结论。

## 执行范围与结果

环境为 Windows / PowerShell 7、Temurin Java 21.0.4、Maven 3.9.8。Maven 使用 `-o`，仅访问已有本地依赖缓存。核验修复后新增模块白名单 66 项、kb-ai 白名单 118 项，合计 184 项测试通过，失败/错误/跳过均为 0。先执行完整白名单，随后针对期限竞争补充测试并重新运行工具模块全部测试及打包；撤权与采集故障的两种发生顺序补齐后再次运行 MultiTurnRevocationTest。重复子集不重复计数。开发过程中一个新测试的 1 毫秒真实等待曾触发调度抖动，已改为虚拟时钟推进超时并通过回归。

全模块 `test-compile` 成功（父模块和 9 个子模块），没有运行全项目测试。独立工具重新打包成功；用 .NET ZipFile 只读检查 JAR：入口 `com.zhishiku.kb.answereval.AnswerEvaluationCli`，1288 个条目，不含 Spring、业务 ai/app/common、PostgreSQL、Redis/Lettuce 或 BOOT-INF 条目。Java 21 入口 class major=65 已在初次打包时静态核对。普通 JAR 供 AI 复用契约，可执行依赖打入 `-cli.jar`。未启动任一 JAR。

归档前，`openspec validate answer-quality-and-evidence-evaluation --strict --json` 返回 valid=true、issues=[]，`git diff --check` 通过。修复后再次核验复跑 45 项离线测试，全部通过，不重复计入上述 184 项；核验结果为 0 CRITICAL、0 WARNING、0 SUGGESTION。

归档阶段新建 3 份、扩展 2 份主规格，逐项比对确认 36 项要求完整同步，保留原规格内容与新规格 Purpose。`openspec validate --specs --strict --json` 的 72 份主规格全部通过，0 问题。随后归档完整变更目录并保留 `.openspec.yaml`，`openspec list --json` 确认活动变更为空。本阶段同步规格、更新文档并固定评测夹具换行，没有修改业务代码或重复运行完整测试。

提交前发现 `core.autocrlf=true` 会在重新检出时改变 7 份 JSONL 夹具的原始字节，破坏数据集摘要。已在 `kb-answer-eval/examples/.gitattributes` 限定该目录使用 LF。通过 `git -c core.autocrlf=true checkout-index --prefix=<临时目录>/ -- <示例文件列表>` 将暂存内容检出到被忽略的 `target` 子目录：修复前 17 个文件中 7 个字节摘要发生变化；修复后连同属性文件共 18 个文件字节一致，合成集与业务模板的 6 项文件摘要均匹配清单。使用 PowerShell `Get-FileHash -Algorithm SHA256` 核对，没有启动 CLI 或外部服务。

实施中发现并修复 Windows fileKey 可为空导致发布校验失败、裁判视图方法名不匹配导致编译失败，以及显式 null 可能成为基本类型默认值的问题；对应发布、编译和严格解析回归均通过。构建仍有 Mockito/Byte Buddy 动态 agent、已有 unchecked/注解处理及 shade 重叠 LICENSE/NOTICE/module-info 提示；它们不是测试失败。打包阶段使用 `-DskipTests` 的跳过是有意构建设置，不是上述测试白名单中的 skip。

## 实际执行命令

以下为本次实际执行的终端命令记录；文档本身未被测试或回放。

```powershell
mvn -o -pl kb-answer-eval '-Dtest=AnswerEvaluationCliTest,StrictJsonTest,LocalFilesTest,DatasetReaderTest,OutputPublisherTest,BusinessClientTest,SseAnswersTest,CollectionRunnerTest,HttpExchangeTest,CollectionIntegrityTest,JudgingTest,JudgmentRulesMetricsTest,ReviewReportTest,BenchmarkFixturesTest,OfflineCommandsTest' test
mvn -o -pl kb-ai -am '-Dtest=EvaluationPropertiesTest,CaptureRegistryTest,CaptureSessionTest,ModelEvaluationCaptureTest,OpenAiMultiTurnModelTest,OpenAiChatTransportTest,ChatPromptRendererTest,ChatTokenBudgetTest,ConfiguredEstimatedTokenBudgetTest,ChatModelLoggingTest,MultiTurnChatServiceTest,MultiTurnControllerTest,MultiTurnReplayControllerTest,MultiTurnRevocationTest,MultiTurnBudgetCompatibilityTest,ConversationSettlementTest,ConversationRevocationProtocolTest,ConversationBudgetTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -o -pl kb-answer-eval '-Dtest=JudgingTest,ReviewReportTest,OfflineCommandsTest' test
mvn -o -pl kb-answer-eval '-Dtest=ReviewReportTest,OfflineCommandsTest' test
mvn -o '-DskipTests' test-compile
mvn -o -pl kb-answer-eval -am '-DskipTests' package
openspec validate answer-quality-and-evidence-evaluation --strict --json
git diff --check
```

产物静态检查实际使用 `[System.IO.Compression.ZipFile]::OpenRead` 打开 `kb-answer-eval/target/kb-answer-eval-0.1.0-SNAPSHOT-cli.jar`，读取 `META-INF/MANIFEST.MF` 和入口 class 的前 8 字节，核对上述入口/Java 版本，并遍历条目排查上述禁止依赖前缀；没有启动 JVM 执行该工具。测试 XML 位于各模块 `target/surefire-reports`，构建目录不提交。

## 场景与实现对应

核验发现的 5 项问题已修复，新增证据如下：

|问题|实现与回归|
|---|---|
|公共报告缺来源及调用范围|Reports 保留数据集逻辑标识、版本、sourceType 和三阶段 executionScope；ReviewReportTest 覆盖合成/审定脱敏的三格式数据、HTTP_UNVERIFIED、旧文件 UNKNOWN、NO_RESULTS 未调用及基线比较|
|断连被误记为普通失败|HttpExchange/SseAnswers 保留 SUBMISSION_UNKNOWN；CollectionRunner 输出 UNKNOWN；测试覆盖创建响应丢失、正文断连/EOF、响应头轮次保留、期限在工作线程/调用方触发、发送前超时及明确拒绝|
|失败清空已确认阶段|CollectionRunner 保留已核对业务、结算、usage、attempt 和 binding；CollectionRunnerTest 覆盖 COMPLETED + PENDING、采集缺失、摘要不一致，失败正文仍为空|
|部分写入失败后继续发布|OutputPublisher 失效会话拒绝 put/finish；OutputPublisherTest 和 CollectionRunnerTest 覆盖已捕获异常后继续提交，目标与残留正文均不发布|
|限流及采集故障与撤权缺直接覆盖|JudgingTest 用 429 stub 证明一次调用、计费未知和后续 NOT_EVALUATED；MultiTurnRevocationTest 用虚拟许可时钟及真实临时文件写入失败覆盖故障先发生和撤权先发生，均只订阅一次、不等待采集、不恢复输出|

本轮末次工具测试与打包命令（仅记录已执行操作，不作为文档测试回放）：

```powershell
mvn -o -pl kb-answer-eval -am '-Dtest=AnswerEvaluationCliTest,StrictJsonTest,LocalFilesTest,DatasetReaderTest,OutputPublisherTest,BusinessClientTest,SseAnswersTest,CollectionRunnerTest,HttpExchangeTest,CollectionIntegrityTest,JudgingTest,JudgmentRulesMetricsTest,ReviewReportTest,BenchmarkFixturesTest,OfflineCommandsTest' '-Dsurefire.failIfNoSpecifiedTests=false' package
mvn -o -pl kb-ai -am '-Dtest=MultiTurnRevocationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -o '-DskipTests' test-compile
```

工具实现位于 [answereval](../kb-answer-eval/src/main/java/com/zhishiku/kb/answereval)，工具测试位于 [answereval tests](../kb-answer-eval/src/test/java/com/zhishiku/kb/answereval)。AI 采集实现位于 [evaluation](../kb-ai/src/main/java/com/zhishiku/kb/ai/evaluation)，测试位于 [AI tests](../kb-ai/src/test/java/com/zhishiku/kb/ai)。表中类名均相对这些目录；既有模型和会话回归继续使用依赖 mock。

|规格：answer-evaluation-collection 场景族|实现|离线证据|
|---|---|---|
|同题不同身份、脱敏未审定/摘要变化、标准要点隔离|DataModel、DatasetReader、CollectionConfig|DatasetReaderTest、BusinessClientTest、BenchmarkFixturesTest|
|审定范围外召回、同三元组正文变化|DatasetReader.requireMaterials、CaptureSession.input、CollectionRunner.verify|DatasetReaderTest、CaptureSessionTest、CollectionIntegrityTest|
|后部非法用例零调用、预检后路径改变|StrictJson、LocalFiles、DatasetReader、OutputPublisher|StrictJsonTest、LocalFilesTest、OutputPublisherTest|
|普通请求、登记换会话、到期或一次执行重复绑定|EvaluationProperties、CaptureRegistry|EvaluationPropertiesTest、CaptureRegistryTest|
|身份不符/强制改密、运行中撤权/停用|BusinessClient.preflight、saved、CollectionRunner|BusinessClientTest、CollectionRunnerTest、MultiTurnRevocationTest|
|网络结果未知不重发、正常错误答案继续后续|CollectionRunner、HttpExchange|CollectionRunnerTest、HttpExchangeTest|
|最终裁剪前缀、输入已准备但请求失败、自定义模型不支持|OpenAiMultiTurnModel、OpenAiChatTransport、CaptureSession|ModelEvaluationCaptureTest、CaptureSessionTest|
|保存失败、客户端/持久正文不同、来源屏蔽或不一致|CollectionRunner.verify、CaptureSession、MultiTurnChatService|CollectionRunnerTest、CaptureSessionTest、MultiTurnChatServiceTest|
|合法 NO_RESULTS、预算/检索失败不能冒充拒答|SseAnswers、CollectionRunner|SseAnswersTest、CollectionRunnerTest、JudgingTest|
|NOT_RUN 完整分母、模型别名与真实输入区别|DataModel.Collection、CollectionReader、Reports.modelComparison|CollectionRunnerTest、JudgingTest、ReviewReportTest|
|磁盘/队列故障、容量上限、输出目标竞争|CaptureRegistry、OutputPublisher|CaptureRegistryTest、CaptureSessionTest、OutputPublisherTest|
|合成样例与真实质量、外部组件未就绪|examples/synthetic、deidentified-template、运行手册|BenchmarkFixturesTest 校验 9 场景和格式；外部依赖未就绪仅说明，不执行实际联调|

|规格：answer-quality-evaluation 场景族|实现|离线证据|
|---|---|---|
|跨轮/跨例证据、部分失败保留分母|CollectionReader、JudgmentReader、JudgeRunner|CollectionIntegrityTest、JudgingTest|
|裁判缺配置、同模型声明、目标不允许|JudgeConfig、JudgeRunner、JudgmentReader、Reports.modelComparison|JudgingTest、ReviewReportTest、BenchmarkFixturesTest；客户端构造及目标准入代码审阅|
|碰巧答对但无证据、正确内容被裁去|JudgeViews.messages、JudgmentRules、Metrics|JudgingTest、BenchmarkFixturesTest 的 unsupported-correct/trimmed|
|缺失/重复要点、UNCERTAIN、关键额外错误|JudgmentModel、JudgmentRules、Metrics|JudgmentRulesMetricsTest、ReviewReportTest|
|虚构区间/三元组/引文、跨片段联合证据|JudgmentRules.span/validate|JudgmentRulesMetricsTest，Unicode code point 半开区间精确断言|
|无可核验结论、2/3 支持率、未知覆盖|Metrics.calculate/aggregate|JudgmentRulesMetricsTest、ReviewReportTest|
|不当作答/拒答、资料要求裁判给满分|JudgeViews 固定数据/指令分离、Metrics|JudgingTest、BenchmarkFixturesTest；真实裁判抵御注入效果未验证|
|攻击被裁去/实际进入材料|Metrics.injection|JudgmentRulesMetricsTest、BenchmarkFixturesTest 的 injection/injection-trimmed|
|窗口/输出/累计估算预算、限流/未知计费不重试|JudgeClient、Deadline、HttpExchange|JudgingTest、HttpExchangeTest；网络请求以进程内 stub 模拟|
|离线报告、独立初评/规则/请求身份|JudgeRunner、JudgmentReader、EvaluationCommands|JudgingTest、OfflineCommandsTest；重复 judgeRunId 原判保留与报告零网络结构核对|

|规格：answer-evaluation-review 场景族|实现|离线证据|
|---|---|---|
|资料/原判改变、改判缺原因|ReviewModel、Reviews.merge|ReviewReportTest 模板绑定、旧摘要与空原因拒绝|
|人工补漏、同版本分叉、不能覆盖规则/执行错误|Reviews.merge/effective、JudgmentRules|ReviewReportTest 补漏改变分母、重复幂等、前驱/分叉/协议失败|
|通过抽查、策略变化、失败改通过集合不缩小|Reviews.required|ReviewReportTest 稳定排序、重叠标签、稀有类别、比例变化与改判集合|
|未抽中项 MODEL、标准答案错误另建数据集|Reviews.effective、Reports|ReviewReportTest、OfflineCommandsTest；LABEL_ERROR 保持待复核，原判保留|
|候选材料不同、更换裁判、完整样本集合|Reports.Identity、CollectionReader、JudgmentReader|ReviewReportTest 比较身份和候选摘要差异、CollectionIntegrityTest、JudgingTest 完整分母|
|分类退步、相同输入字节稳定|Reports.calculate/publish|ReviewReportTest 数值/容差/退步/多格式稳定；比较与序列化不是说明文档测试|
|高分缺复核、没阈值、满分但关键编造|Reports 状态优先级|ReviewReportTest 覆盖 REVIEW_REQUIRED/NOT_CONFIGURED/FAIL/INCOMPLETE/PASS|
|N/A 门槛、边界/容差、缺基线|ReportConfig、Reports|ReviewReportTest 非法规则、N/A、缺基线、1e-12 容差；CLI INVALID 路径|
|不在线补源、公共交付去正文|Reviews.template、EvaluationCommands、Reports|OfflineCommandsTest、ReviewReportTest：正文/问题/证据/密钥/URL 哨兵不进入诊断和公共汇总|

|规格：chat-model-adapter / multi-turn-chat 场景族|实现|离线证据|
|---|---|---|
|采集默认关闭、prod 拒绝、普通请求无采集|EvaluationProperties、EvaluationConfiguration、CaptureRegistry|EvaluationPropertiesTest、CaptureRegistryTest；未启动完整 Spring 服务|
|预算多次计数只捕获最终视图、正常输出、撤权取消|OpenAiMultiTurnModel、OpenAiChatTransport、CaptureSession|ModelEvaluationCaptureTest、CaptureSessionTest、模型/预算/撤权白名单|
|队列饱和、供应商错误含秘密|CaptureRegistry 异步序列化/写盘、CaptureSession 固定阶段|CaptureRegistryTest、CaptureSessionTest、ChatModelLoggingTest、OpenAiChatTransportTest|
|公开请求夹带评测字段、匹配重放、用户不符|MultiTurnController、MultiTurnChatService、CaptureRegistry|MultiTurnControllerTest、MultiTurnReplayControllerTest、CaptureRegistryTest|
|合法空检索、模型完成但结算待确认、采集失败与撤权|MultiTurnChatService、CaptureSession|MultiTurnChatServiceTest、ConversationSettlementTest、MultiTurnRevocationTest、CaptureSessionTest|

## 人工审阅和未验证边界

README、E-11 路线图、数据/采集/运行/裁判/复核/报告说明及 PowerShell 入口已人工审阅。未对这些说明文档做指定内容断言、快照测试或命令回放。业务 JSON/JSONL、配置模板、报告数值、序列化以及秘密不泄漏属于功能测试；本机文件系统符号链接用例没有跳过。

没有启动前端、完整业务应用、独立 CLI JAR、HTTP/SSE 测试服务器，也没有连接数据库、Redis、对象存储、解析服务、被测模型或裁判。测试 HTTP 均注入内存 transport/mock，期限使用虚拟时钟或进程内受控字节流。未执行文档导入/审核/索引、迁移、生产配置启用、真实撤权、计费或业务会话清理。

真实供应商协议、部署拓扑、共享目录权限、网络故障、真实模型效果、费用、人工业务审定和业务质量阈值仍须在后续使用环境验证。本次不以这些真实环境事项作为实施或归档的前置任务；合成测试和声明字符串均不是生产质量或权限冻结证明。`DECLARED_ONLY`、unknown usage、未审定业务模板及 REVIEW_REQUIRED/INCOMPLETE 状态继续如实保留。
