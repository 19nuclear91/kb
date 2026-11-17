# 第一轮回答采集

`collect` 逐例创建本人会话并提交第一轮，使用现有身份、会话、SSE 和本人轮次读取接口。每例使用新请求标识，不自动登录、提权、重发生成或删除会话。模型给出错误答案但执行完整时继续后续用例；执行、采集或核对失败后，剩余用例全部保留为 NOT_RUN。

## 配置

`collection-config.json` 使用 schemaVersion=1，并提供 runId、targetId、instanceId、artifactId、configurationId、model、aclSnapshotId、baseUrl、allowHttpLoopback、captureDirectory、identities，以及可选 connectTimeoutMs、caseTimeoutMs。

- targetId 必须在数据集允许的被测目标列表中，instanceId 必须匹配 AI 配置。
- artifactId、configurationId、model、语料与 ACL 标识是操作者声明；实际模型名称、消息和材料摘要另见采集快照。同名标识不证明模型权重或环境冻结。
- baseUrl 是固定 HTTPS 根地址，可含路径前缀；拒绝用户信息、查询串、片段及重定向。显式 allowHttpLoopback=true 才允许 localhost、127.0.0.1、[::1] 的 HTTP。
- captureDirectory 是 AI 与运行器共享的受控本地目录。工具向新随机 datasets 子目录复制本次冻结字节，保留文件摘要与审批绑定，再发布逐例登记。
- identities 是身份别名到 tokenEnv、expectedUserId 的映射。令牌只从指定环境变量读取一次；预检所有被引用身份的真实 userId、启用和强制改密状态，全部通过后才创建业务会话。
- connectTimeoutMs 默认 5,000，上限 60,000；caseTimeoutMs 默认 180,000，上限 600,000，覆盖新建、生成、本人读取和等待采集完成。

使用方式（仅作为运行手册交付，本次未执行）：

```powershell
java -jar .\kb-answer-eval\target\kb-answer-eval-0.1.0-SNAPSHOT-cli.jar validate-dataset --dataset D:\eval-data\dataset.json
java -jar .\kb-answer-eval\target\kb-answer-eval-0.1.0-SNAPSHOT-cli.jar collect --dataset D:\eval-data\dataset.json --config D:\eval-config\collection-config.json --output D:\eval-output\candidate-001
```

先通过运行环境的秘密注入机制提供 tokenEnv 对应变量，不把令牌写入命令参数、配置文件、示例或仓库。

## 完成条件

成功的 MODEL 样本必须同时满足：原问题和轮次一致，SSE 恰好一次合法 done，客户端完整正文与本人可见 COMPLETED 持久回答相同，来源身份与裁剪标志一致，当前结算为 SETTLED，实际输入属于审定语料，模型解码输出摘要一致，采集业务完成及结算确认均齐备。

SSE EOF、重复 done、error、JSON 重放、部分正文、来源屏蔽或读取失败不当作完整成功。重放只记录既有轮次身份和状态，不再生成，也不以持久文本冒充本次客户端流。模型正常结束不等于业务保存或客户端交付成功。

NO_RESULTS 只有业务正常完成、固定无结果回答一致、来源为空且采集声明未调用模型时才是有效拒答样本。INPUT_BUDGET_EXCEEDED、SEARCH_UNAVAILABLE、身份失败和超时均为执行失败，不参与正确拒答收益。

## 产物和清理

输出到显式新目录，包含 collection.json、answers.jsonl、成功用例的 inputs 文件及完整清单。失败用例只写安全状态和已确认的 attempt 身份；未核对正文不落入运行包。输出目录已存在或发布发生竞争时明确失败，不覆盖或合并旧产物。

发送 POST 后断连、响应正文丢失、SSE 未得到完整终态或调用期限耗尽，记录 UNKNOWN / SUBMISSION_UNKNOWN，不重复提交。尚未发送前已过期、明确业务拒绝、后续只读核对或等待采集超时仍记录相应失败。attempt 同时保留创建请求的 createRequestId、生成请求的 requestId，以及已确认的 conversationId、turnId；仅收到合法响应头后正文中断，也保留轮次 ID。

已通过本人读取并核对问题/轮次的业务状态、结算阶段和 usage 在后续失败时继续保留；如 COMPLETED + PENDING，不能重置成两者均未确认。已校验的采集 binding 也保留，未通过核对的正文、来源和正文摘要仍不进入失败包。

任一文件部分写入失败后，整个暂存会话失效，禁止继续追加或发布完成标记，只清理本次创建且身份未变化的文件。此类输出故障返回 2，不能将残留文件作为完成运行。collection.json 的 invocationOrigin 记录调用范围，含义见[报告说明](answer-evaluation-report.md)。

退出码 0 表示采集完整，3 表示运行不完整，2 表示输入、配置或输出失败。采集成功不代表回答质量通过，后续还需初评、人工复核及报告。

测试会话清理依据 answers.jsonl 的 attempt.conversationId、turnId，登记与采集清理依据 runId、attemptId、artifactKey；只处理操作者确认的本次目标。删除业务会话仍使用既有本人接口或界面，工具不会自动删除用户、空间、会话、原件或历史产物。

本次仅运行进程内 transport stub、虚拟时钟及临时文件测试，未执行真实授权检索、模型或裁判联调。
