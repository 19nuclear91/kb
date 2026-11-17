# 回答质量与引用证据评测

E-11 首期用于研发回归：固定问题、人工要点和审定语料，比较模型、提示词或检索配置。独立 Java 21 CLI `kb-answer-eval` 提供 validate-dataset → collect → judge → review-template → review-import → report。AI 采集默认关闭，仅支持专用实例、白名单用户、新会话第一轮；不新增管理页面、线上采样或数据库结构。

首期 48/48 项任务已完成，已同步 5 份主规格并[归档](../openspec/changes/archive/2026-11-17-answer-quality-and-evidence-evaluation/archive-summary.md)，未部署。正式行为约束见[运行与采集](../openspec/specs/answer-evaluation-collection/spec.md)、[质量初评](../openspec/specs/answer-quality-evaluation/spec.md)和[人工复核与报告](../openspec/specs/answer-evaluation-review/spec.md)；模型适配与会话采集约束见归档记录中的完整同步清单。

阅读顺序：[数据格式](answer-evaluation-data.md)、[AI 采集配置](answer-evaluation-capture.md)、[问答运行器](answer-evaluation-collection.md)、[裁判初评](answer-evaluation-judging.md)、[人工复核](answer-evaluation-review.md)、[报告门禁](answer-evaluation-report.md)。实现及离线测试证据见[验证记录](answer-evaluation-verification.md)。

## 数据与环境准备

[合成基准](../kb-answer-eval/examples/synthetic)包含 3 份纯文本原件、9 个问题/标注场景及可手算正反例。`expected.jsonl` 是功能测试夹具，不是实际模型输出，也不作为采集输入。覆盖正确答案、错误金额、漏审批条件、无证据但答案正确、正确/错误拒答、裁剪、注入暴露及攻击被裁去。

合成三元组只用于离线格式与数值验证。真实使用时通过既有文档上传/审核发布流程导入原件，记录实际 docId/versionId/chunkId，核对分块正文，创建新数据集版本并更新文件摘要；运行器不会上传、审核、建立索引或修改 ACL。须固定测试语料、权限、解析/Embedding/rerank/模型配置，设置明确的语料与 ACL 声明；工具不证明环境已冻结。

[脱敏业务模板](../kb-answer-eval/examples/deidentified-template)使用明显的合成示例，尚无人工审定。`dataset.template.json` 的 approval=null，不能采集或外发；`approval.template.json` 是待填格式。运行方替换问题、人工要点与已脱敏语料，填写允许的被测/裁判目标和真实逻辑审定人，按数据说明计算文件与审定摘要，再另存正式 dataset.json。没有真实业务资料或审定时，不应将模板改称已审定业务集。

运行 collect 前需已部署专用 AI 测试实例、配置采集目录/白名单/实例标识，准备有效测试会话令牌和对应用户 ID，保证 CLI 与 AI 看到同一受控目录。凭据由运行方通过指定环境变量安全注入。示例 `https://replace-*.invalid`、REPLACE_*、用户 1、模型/预算/ACL 及目录均须核对替换。裁判凭据与业务令牌独立；裁判会收到问题、回答和相应评审资料。

## PowerShell 入口

以下是运行手册示例，本次开发未执行这些 CLI 或真实环境命令。先复制配置到受控目录，修改占位项；每阶段输出目录必须不存在。

```powershell
mvn -pl kb-answer-eval -am package '-DskipTests'
$answerEvalJar = '.\kb-answer-eval\target\kb-answer-eval-0.1.0-SNAPSHOT-cli.jar'
$answerEvalDataset = 'D:\answer-eval\dataset\dataset.json'
$answerEvalConfig = 'D:\answer-eval\config'
$answerEvalRun = 'D:\answer-eval\runs\candidate-001'

java -jar $answerEvalJar validate-dataset --dataset $answerEvalDataset
java -jar $answerEvalJar collect --dataset $answerEvalDataset --config "$answerEvalConfig\collect.json" --output "$answerEvalRun\collection"
java -jar $answerEvalJar judge --dataset $answerEvalDataset --collection "$answerEvalRun\collection\collection.json" --config "$answerEvalConfig\judge.json" --output "$answerEvalRun\judge"
java -jar $answerEvalJar review-template --dataset $answerEvalDataset --collection "$answerEvalRun\collection\collection.json" --judgments "$answerEvalRun\judge\judgment.json" --config "$answerEvalConfig\report.json" --output "$answerEvalRun\review-template"
```

完成模板人工核对并填写 reason、逻辑复核人和 CONFIRMED 后，导入新修订文件。PENDING 条目不能导入；不完整初评应重新建立 judge 运行，不能由人工补成通过。

```powershell
java -jar $answerEvalJar review-import --dataset $answerEvalDataset --collection "$answerEvalRun\collection\collection.json" --judgments "$answerEvalRun\judge\judgment.json" --reviews "$answerEvalRun\review-template\reviews.jsonl" --config "$answerEvalConfig\report.json" --output "$answerEvalRun\review-001"
java -jar $answerEvalJar report --dataset $answerEvalDataset --collection "$answerEvalRun\collection\collection.json" --judgments "$answerEvalRun\judge\judgment.json" --reviews "$answerEvalRun\review-001\review.json" --config "$answerEvalConfig\report.json" --output "$answerEvalRun\report"
```

后续修订使用新 reviewId、连续 revision 和前驱摘要，指向原 history；正式比较还需相同数据集、身份、语料/ACL、裁判规则和复核策略。示例门槛不是业务承诺。

```powershell
java -jar $answerEvalJar review-import --dataset $answerEvalDataset --collection "$answerEvalRun\collection\collection.json" --judgments "$answerEvalRun\judge\judgment.json" --reviews "$answerEvalRun\revision-002.jsonl" --history "$answerEvalRun\review-001\review.json" --config "$answerEvalConfig\report.json" --output "$answerEvalRun\review-002"
java -jar $answerEvalJar report --dataset $answerEvalDataset --collection "$answerEvalRun\collection\collection.json" --judgments "$answerEvalRun\judge\judgment.json" --reviews "$answerEvalRun\review-002\review.json" --baseline 'D:\answer-eval\runs\baseline\report\report.json' --config "$answerEvalConfig\comparison.json" --output "$answerEvalRun\comparison"
```

退出码 0 为 PASS 或 NOT_CONFIGURED，1 为 FAIL，2 为 INVALID，3 为 INCOMPLETE，4 为 REVIEW_REQUIRED。应同时检查报告状态；采集/初评命令退出 0 只表示该阶段完整。每次生成/裁判无自动重试，提交未知时保留状态和逻辑 ID。清理测试会话及离线正文须按清单由运行方显式执行；本工具不隐式删除业务数据。

## 本次验证边界

实现验证限定为进程内单元/组件、内存 transport stub、虚拟时钟、临时文件、编译和 JAR 清单静态检查。未启动前端、完整应用、独立 CLI JAR、HTTP 测试服务器或数据库/Redis/对象存储；未连接被测模型或裁判。未验证部署联调、真实供应商兼容性、费用或业务质量。文档仅人工审阅，不做内容断言、快照或示例命令回放。
