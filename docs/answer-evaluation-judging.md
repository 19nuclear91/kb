# 回答质量裁判初评

`kb-answer-eval judge` 先完整读取并验证数据集、collection、answers、实际输入与完成清单，再创建裁判客户端。任何跨用例、跨轮、摘要或审定范围错误都会拒绝整批，不会先上传合法前缀。合法但不完整的采集仍保留全部用例，执行失败项标记 `EXECUTION_INCOMPLETE`。

独立裁判配置见 [judge.json](../kb-answer-eval/examples/config/judge.json)。`targetId` 必须在数据集 `allowedJudgeTargets` 内；`apiKeyEnv` 只读取该环境变量，无业务令牌回退。地址为固定 HTTPS 前缀，只有显式允许的 loopback 可用 HTTP。禁用代理、重定向和自动重试，不继承 Cookie。配置摘要包含端点、模型、预算和凭据变量名，不包含密钥值。

每个 MODEL 样本最多两次普通 `chat/completions` 请求。固定 `answer-quality-v1` 规则提供两个视图：correctness 包含问题、答案、人工要点和禁断言；grounding 包含问题、答案、实际材料及注入行为。标准要点不进入 grounding，候选名称不进入评分资料。材料正文来自最终实际输入，不能用完整原件补齐被裁去内容。

仅接受单个 assistant 文本候选、`finish_reason=stop` 和严格 JSON 内容。工具调用、拒绝字段、思考字段、截断、多候选及未知结构都失败；不尝试去 Markdown、修复 JSON 或自动重发。供应商必须支持 `temperature=0`、`n=1`、`stream=false` 及明确配置的 `max_tokens` 或 `max_completion_tokens`。未做真实供应商兼容验证。

裁判返回逐要点、逐结论和逐材料的枚举判断；证据必须绑定 docId/versionId/chunkId，区间以 Unicode code point 计数，采用 `[start,end)`，quote 严格等于对应子串。允许多个片段共同支持一个结论。程序独立计算比率，不接收裁判总分。`UNCERTAIN` 保持未知。位置存在不证明语义正确，仍需人工复核。

每次请求和响应各限 2 MiB；连接默认 5 秒，单次完整请求默认 120 秒，整批默认 600 秒，使用单调期限，超时取消且不重发。必填窗口、最大输出、安全余量、输入字节/token 比例、累计输入估算及最大请求数。准入估算是 `ceil(完整消息 UTF-8 字节数 / inputBytesPerToken) + 16 × 消息数`；与输出上限和余量之和不得超过窗口，边界相等允许。估算标识为 ESTIMATED，不是费用硬上限。usage 未提供时保持 null；已知 usage 即使内容协议错误也保留。

首个请求、协议或预算错误停止后续初评；剩余样本为 `NOT_EVALUATED`。每行初评文件限 512 KiB，单文件 64 MiB，容量不足同样停止。NO_RESULTS 无裁判请求：预期 ANSWER 为缺失全部要点且拒答错误；预期 ABSTAIN 为正确拒答，要点/结论/材料不适用。应进入实际材料的攻击正文缺失时，注入维度为 NOT_EXERCISED，不能完整验收。

每次显式 judge 创建独立 judgeRunId，并写 `judgment.json`、`judgments.jsonl`、`configuration.json`、`complete.json`。配置文件保留逻辑配置标识、端点、模型、参数与预算，属于受控产物，不含密钥值。保存数据集/采集/答案/输入/规则/配置摘要、每次请求摘要、调用阶段、估算与真实 usage。重复初评必须使用新输出目录，原判不覆盖。完成退出 0；合法但不完整退出 3；输入/配置无效退出 2。初评完成不表示质量通过。

使用入口见[操作手册](answer-evaluation.md)，人工过程见[复核说明](answer-evaluation-review.md)。本次仅用进程内 transport stub、临时文件和虚拟时钟验证，未发送真实裁判请求。
