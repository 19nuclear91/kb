# 本地人工复核

`review-template`、`review-import` 和报告均不联网、不读取密钥。它们完整核对数据集、采集和初评绑定。模板目录中的 `details/<caseId的SHA-256>.json` 保存必要问题、答案、实际材料、模型原判、绑定和是否必需；`reviews.jsonl` 保存待填写条目；`policy.json` 保存抽查策略与必需集合摘要。这些是受控正文产物，不能当作公开报告发送。

报告配置必须显式给出 `reviewPolicy.version="stratified-sha256-v1"` 及 `(0,1]` 的 `sampleProportion`。必需集合始终基于不可变初评：所有未知、未满足预期、关键错误、规则/语义矛盾项，加上通过项的分类抽查。每标签取 `max(1,ceil(p*n))`，按 `SHA-256(datasetDigest + "|" + rubricVersion + "|" + caseId)`、caseId 排序；重叠标签取并集。改判后集合不会缩小。策略或比例变化重新计算覆盖；已完成的合法修订可复用，不能假定新集合已全部复核。

填写 `reviews.jsonl`：用逻辑标识替换 `REPLACE_REVIEWER`，将 `decision` 从 PENDING 改为 CONFIRMED，填写 reason，再逐项核对 correctness 与 grounding。复核人可以改变语义判断、补漏结论、修正关键错误，但引用三元组和 Unicode 半开区间仍必须精确匹配实际答案与材料。导入每条确认都要求非空原因。不能靠复核修复执行失败、协议错误、缺失输入或 NO_RESULTS 的确定性判断；这类情况需要补数据或新建初评运行。

发现人工标注错误时使用 `LABEL_ERROR` 并说明原因；报告保持 REVIEW_REQUIRED。应另建数据集版本，重新审定与采集/初评，不修改旧标签或伪装成评分改判。人工仍填 UNCERTAIN 时也继续待复核。

首次修订为 revision=1、previousRevisionSha256=null。再次修改需使用新的 reviewId、revision=2，previousRevisionSha256 填上一条修订的 SHA-256。摘要针对键递归排序、UTF-8 紧凑 JSON 的完整上一条对象（不含 JSONL 换行）；导入结果 `revisions.jsonl` 使用同一规范。提交新修订时用 `--history` 指向旧 `review.json`，用 `--reviews` 指向新增 JSONL。保留全部修订；同 reviewId 同内容幂等，同 ID 异内容、同例同序号分叉、缺前驱或过期绑定均拒绝，不以时间戳覆盖冲突。

每条修订绑定数据集、采集、用例、run、答案、实际输入、规则、裁判配置、judgeRunId 和原判摘要。`review-import` 在新目录发布 `review.json`、`revisions.jsonl`、`complete.json`；不修改模型原判。有效结果使用最新确认修订，逐例/逐结论标记 HUMAN；未复核项保留 MODEL，合法 NO_RESULTS 标记 RULE。报告并列初评与有效结果，人工补漏会改变有效结论分母，初评分母仍保留。

逻辑复核人和本地摘要只用于操作追踪，不提供企业身份认证、数字签名或防管理员篡改保证。目录权限、访问审计、正文保留期、备份与删除责任由运行方管理。业务撤权不会自动删除离线正文。详见[操作手册](answer-evaluation.md)和[报告说明](answer-evaluation-report.md)。本次文档人工审阅，未回放使用命令。
