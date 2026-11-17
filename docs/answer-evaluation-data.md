# 回答评测数据格式

回答评测使用独立的 `kb-answer-eval` 模块，版本为 `schemaVersion: 1`，与检索评测的输入格式不通用。所有文件使用 UTF-8；JSONL 每行一个对象，不接受空行、重复属性、未知属性、NUL、非法 Unicode 或身份数值的隐式转换。

## 数据集

`dataset.json` 的字段：

| 字段 | 含义 |
| --- | --- |
| schemaVersion | 固定整数 1 |
| datasetId / datasetVersion | 数据集逻辑标识与版本 |
| corpusSnapshotId | 人工声明的语料版本，不代表已冻结全库 |
| sourceType | SYNTHETIC 或 HUMAN_APPROVED_DEIDENTIFIED |
| cases / labels / corpus | 分别为包含 file、sha256 的文件引用 |
| approval | 合成资料可为 null；脱敏业务资料必须有人工作出的审定记录 |
| allowedTestTargets / allowedJudgeTargets | 允许外发的被测、裁判目标逻辑标识列表 |

文件引用必须指向清单目录内的本地文件。摘要是实际文件原始字节的 SHA-256 小写十六进制字符串，换行改变也会改变摘要。读取后使用冻结的同一份字节，不在运行中重新读取已改变的输入。拒绝 URI、网络共享、通配符、路径越界和链接跳转。

审定记录含 `reviewerId`（逻辑标识）、`version`、`contentSha256`。内容摘要按 `DatasetReader.approvalDigest` 计算：对 datasetId、datasetVersion、corpusSnapshotId、sourceType、三个文件引用、两个允许目标列表进行递归键排序和紧凑 UTF-8 JSON 序列化，再取 SHA-256。数组顺序保留。审定须覆盖全部正文、标准答案及允许目标；工具只校验声明绑定，不自动脱敏或认证审定人员。内容或目标变化后重新审定。

## 用例、标注与语料

`cases.jsonl` 每项包含 `caseId`、`identityAlias`、`question`、非空 `tags` 和可空 `injectionId`。同一问题用于不同身份时必须采用不同 caseId。原问题不裁剪、不归一化；禁止客户端 system、history、role 或来源注入字段。

`labels.jsonl` 每项包含：

- `caseId`、`expected`（ANSWER 或 ABSTAIN）。
- `facts`：由 factId、text 组成的数组。ANSWER 至少一个要点；ABSTAIN 必须为空。
- `forbiddenAssertions`、`criticalErrorCriteria`：人工禁止断言和关键错误判据文本数组，可为空。
- `abstentionCondition`：拒答依据；ABSTAIN 必填，其他用例可为 null。
- `injection`：普通用例为 null；注入用例含 injectionId、attackText 和非空 behaviors。

每项注入行为含 kind、marker。kind 支持 MUST_ABSTAIN（marker 为 null）、MUST_CONTAIN 和 MUST_NOT_CONTAIN（marker 为受控合成文本）。必须核对 attackText 实际进入本轮模型材料；未暴露为 NOT_EXERCISED。不能使用真实秘密作为标记。用例和标注的 caseId 集合必须完全相同，拒绝重复或缺失项。

`corpus.jsonl` 每项包含 `source`（docId、versionId、chunkId）、`text`、`sha256`。三个 ID 必须是正 64 位整数；不能用版本编号或当前版本代替固定身份。正文摘要按 text 的 UTF-8 字节计算。

采集材料使用同一 source，额外记录 contextTruncated 和本次实际文本摘要。未裁剪时正文必须完全相同；裁剪时必须是审定原文的真实前缀。范围外资料或正文不匹配不能落入正文评测包，也不能发给裁判。标准答案只进入评审，不作为被测问答输入或 grounding 的材料。

## 运行文件

`collection.json` 记录 runId、datasetSha256、toolVersion、artifactId、configurationSha256、model、corpusSnapshotId、corpusState、aclSnapshotId、aclState、sourceType、完整 caseIds、identityBindings、answers 文件引用、inputs 文件引用映射及 complete。corpusState 与 aclState 首期仅接受 DECLARED_ONLY。

`answers.jsonl` 每例保留 caseId、status、attempt、binding、answerKind、answer、persistedAnswerSha256、sources、businessStage、settlementStage、usage、error。attempt 记录 requestId、attemptId 及已确认的 conversationId/turnId；提交未知时也保留已知身份以便人工核对。状态包括 COMPLETE、FAILED、CANCELLED、UNKNOWN、NOT_RUN；失败状态不冒充正确拒答。

输入快照包含 schemaVersion、binding、instanceId、model、promptFormat、messages、materials、requestSha256、modelStage、answerSha256、answerCodePoints、usage、answerKind、businessStage、settlementStage、sealedSources。

binding 固定 runId、caseId、attemptId、datasetSha256、userId、authEpoch、conversationId、turnId、questionSha256。attemptId、turnId 使用规范小写 UUID；用户和会话 ID 精确保留 64 位整数，不经浮点转换。

模型阶段包括 NOT_CALLED、INPUT_READY、REQUEST_SUBSCRIBED、MODEL_COMPLETED、MODEL_FAILED、CANCELLED、UNSUPPORTED。业务阶段和结算阶段独立记录。usage 的 promptTokens、completionTokens 同时有值或同时为 null，未知不补零。

产物写入新目录，完整清单 `complete.json` 最后随目录发布；旧目录和并发创建的目标不覆盖。临时目录及缺少完整清单的残留不代表完成。目录访问权限由操作者管理；正文包属于独立副本，不承诺随线上撤权自动删除。

## 准备与限制

固定语料先通过项目既有上传、解析、审核发布流程准备；操作者取得固定版本/分块清单并审定正文。新原件可检索仍依赖解析服务 E-01。不得为了评测伪造索引、改写业务数据库或以完整原文补齐当轮裁去的材料。业务资料和真实产物保存在仓库之外。

首期上限：1,000 个用例；清单 1 MiB；每个用例/标注/语料文件 64 MiB；JSON 深度 32；JSONL 每行 512 KiB；问题 8,000 code point；单例采集 8 MiB；单运行产物 2 GiB。业务服务可有更紧的限制，超限明确失败。

本次开发验证使用离线组件和合成资料，真实部署、供应商兼容性及业务质量未验证。此说明只作交付文档人工审阅，不执行文档内容断言或示例命令回放。
