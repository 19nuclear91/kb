# OpenAI-compatible 文本模型接入

## 范围与启用

通用适配器仅接入 Chat Completions 普通文本流，不绑定厂商 SDK。默认关闭；没有内置适配器及自定义模型时，旧单轮保留原占位兼容。显式启用但配置错误会在业务连接建立前拒绝启动，不能静默回退占位。

模型地址、密钥与名称只由服务端外部配置注入。生产地址必须是 HTTPS API 根地址，例如 `https://model.example.invalid/v1`，适配器追加 `/chat/completions`，不要填写完整 completions 地址。禁止 URL 用户信息、query、fragment 和路径回退；只有显式 dev 可使用 HTTP。不自动跟随重定向，不透传用户 satoken、Cookie 或内部签名。

| 环境变量 | 缺省 |
| --- | --- |
| `KB_CHAT_MODEL_ENABLED` | false |
| `KB_CHAT_MODEL_BASE_URL` / `KB_CHAT_MODEL_API_KEY` / `KB_CHAT_MODEL_NAME` | 无，启用时必填 |
| `KB_CHAT_MODEL_OUTPUT_TOKEN_FIELD` | max_tokens，可显式改为 max_completion_tokens |
| `KB_CHAT_MODEL_REQUEST_USAGE` | true |
| `KB_CHAT_MODEL_SINGLE_MAX_OUTPUT_TOKENS` | 2048，不超过现有单请求预留 |
| `KB_CHAT_MODEL_CONNECT_TIMEOUT_MS` / `KB_CHAT_MODEL_IDLE_TIMEOUT_MS` | 5000 / 30000 |
| `KB_CHAT_MODEL_MAX_REQUEST_BYTES` / `KB_CHAT_MODEL_MAX_EVENT_BYTES` | 1048576 / 1048576 |
| `KB_CHAT_MODEL_MAX_RESPONSE_BYTES` / `KB_CHAT_MODEL_MAX_ANSWER_CODEPOINTS` | 4194304 / 64000 |

总期限复用 `kb.ai.upstream-timeout`，默认 60 秒。配置不提供任意请求体/头部扩展、供应商推断或假预算就绪开关。不要将模型秘密放入 VITE 变量、URL、日志、文档示例或命令历史。

多轮默认使用 `verified`，没有匹配严格策略时新生成返回 503。显式设置 `estimated` 并提供窗口后，可使用通用本地估算；这表示部署方接受估算误差，不能证明真实 token 上界。具体供应商、生产 tokenizer 和真实服务联调不属于本次验证范围。

预算前缀为 `kb.ai.chat-model.budget`：

| 配置键 | 环境变量 | 默认与范围 |
| --- | --- | --- |
| `mode` | `KB_CHAT_BUDGET_MODE` | verified；仅小写 verified/estimated |
| `context-window-tokens` | `KB_CHAT_BUDGET_CONTEXT_WINDOW_TOKENS` | 0 表示未配置；estimated 必填 1–2147483647 token |
| `tokens-per-kib` | `KB_CHAT_BUDGET_TOKENS_PER_KIB` | 1024；1–65536 token / 1024 UTF-8 字节 |
| `per-message-tokens` | `KB_CHAT_BUDGET_PER_MESSAGE_TOKENS` | 16；0–65536 token / 消息 |
| `request-overhead-tokens` | `KB_CHAT_BUDGET_REQUEST_OVERHEAD_TOKENS` | 64；0–65536 token / 请求 |
| `safety-margin-tokens` | `KB_CHAT_BUDGET_SAFETY_MARGIN_TOKENS` | 512；0–2147483647 token |

estimated 要求 `min(window, reservation) > maxOutput + margin`，全部数值为整数；未知模式、字段、浮点或越界均以固定配置错误拒绝。多轮输出来自 `kb.conversation.max-output-tokens`，不使用旧 `single-max-output-tokens`。预留仍由会话与 AI 配额共同配置并保持一致，预算参数不另建账本额度。verified 使用严格策略的窗口和零附加余量，估算字段不参与其计算。

在已有协议和会话配置基础上，部署方可设置以下预算参数后重启 AI。16384 仅展示配置格式，必须替换为实际部署的已知窗口；不能据此推断任何模型容量：

```powershell
$env:KB_CHAT_BUDGET_MODE = 'estimated'
$env:KB_CHAT_BUDGET_CONTEXT_WINDOW_TOKENS = '16384'
$env:KB_CHAT_BUDGET_TOKENS_PER_KIB = '1024'
$env:KB_CHAT_BUDGET_PER_MESSAGE_TOKENS = '16'
$env:KB_CHAT_BUDGET_REQUEST_OVERHEAD_TOKENS = '64'
$env:KB_CHAT_BUDGET_SAFETY_MARGIN_TOKENS = '512'
```

回退设置 `$env:KB_CHAT_BUDGET_MODE = 'verified'` 后重启；没有匹配严格策略则新生成返回 503。不会因严格策略失败而自动启用估算。

## 消息与参数兼容

`kb-chat-text-v1` 先发送固定 system 指令，再保持历史 user/assistant 顺序，在最后原问题之前以 user 数据消息发送本轮授权材料。材料用 JSON 编码，只包含文档/版本/分块身份、可用页码及正文；未知版本保留 null。当前原问题仅出现一次，不在协议层重新裁剪、补查正文或推断最新版本。分隔符和固定说明不是访问控制，实际边界仍是授权检索和不执行工具。

请求固定 `stream=true`、`n=1`。仅发送已选择的一种输出限制字段，不接受任意厂商请求参数。`request-usage=false` 时完全省略 stream_options；如果上游拒绝某种参数，本次调用直接失败，不自动删除参数、切换模型或重发以试探兼容性。

## 流终结、截断与用量

仅接受 HTTP 200、UTF-8 text/event-stream、唯一索引 0 的普通文本候选。正常结束要求非空正文、finish_reason=stop 及完整后续 [DONE] 事件；不等待远程主动关闭 socket。length、content_filter、未知原因、异常 EOF、stop 后继续出字或缺失 [DONE] 均为失败，不向现有编排伪造正常完成。已展示或保存的前缀不等于完整回答，也不承诺从用户设备撤回。

工具/函数调用、非文本内容、非空拒绝和 reasoning_content/reasoning/thinking 等思考字段不在首版支持范围，遇到时安全终止，不把载荷转为答案或执行操作。

完整累计 prompt_tokens/completion_tokens 只在正常终结确认后输出一次，多个快照取最后值而不累加；后续正文会使旧候选统计失效。小数、负数、缺一项或超出接口表示范围均拒绝。明确 0/0 是 KNOWN；缺少尾部统计不产生用量事件，真实用量为 UNKNOWN，不用字符数或预留额度补成实际消耗。旧单轮 done 的整数字段继续兼容，缺省零不是实际零消耗证明，应以已启用采集返回的 usageStatus 为准。失败、取消和 UNKNOWN 的额度规则不因适配器被统一改写。

estimated 的计数只用于准入及裁剪，KNOWN 仍完整记录和结算供应商真实 usage，即使超过估算或预留。多轮 done 在终态与结算完成后读取确认用量。调用意图已保存但最终统计缺失时为 UNKNOWN、实际字段为 null、按预留结算；发送前复核失败也不凭本地未发 HTTP 将已保存意图改成 NOT_INVOKED。上游拒绝、截断或低估不触发自动重试。

## 超时、限额与故障操作

连接建立使用 connect-timeout-ms。收到语义事件前后均有 idle-timeout-ms，注释不续期；总期限固定为 upstream-timeout，从本次执行开始计时，持续小帧也不能延长。超时会取消上游订阅，不能通过普通流完成冒充成功。请求在序列化时限制字节；单 SSE 事件、解压后的累计响应（含注释及用量）、JSON 深度 32 和累计回答 code point 分别受限。超限立即失败，不自动截断后报告完成。

单事件字节限制对以 CR 结束的分隔空行保守预留 1 字节 LF，普通事件和 `[DONE]` 均在发布前检查。CR 已恰好顶满上限时立即报 `PAYLOAD_LIMIT`，即使实际没有后续 LF；预留充足时保持即时交付或终结。实际 LF 到达时只计一次。因此单 CR 结尾事件的实际字节数必须至少比上限少 1，LF/CRLF 结尾则可按完整实际字节数恰好达到上限。这是用户确认的兼容取舍，避免通过等待后续字节或 EOF 来判断可选 LF。

同一个生成流仅允许一次订阅；未订阅不发 HTTP。429、5xx、连接故障或协议错误不重试、不切换模型或供应商；元数据重试不触发生成。取消、失败和正常终结会清理本地订阅与缓冲，不能据此承诺供应商已停止计算或计费。

非成功 HTTP 和错误媒体类型不聚合远程错误体，也不为自动清理二次消费正文。异常仅保留固定类别，不记录问题、答案、材料、密钥或原始 cause。排障先核对服务端配置与所选协议开关，任何重新生成均遵循原业务请求规则，不通过底层自动重发掩盖故障。

有效材料因 token 或字符预算裁空、或必需输入放不下时，返回固定 `code=INPUT_BUDGET_EXCEEDED`：响应前 HTTP 400，响应后 SSE error；不调用模型、不封存 NO_RESULTS、不返回成功 done 或被裁材料引用。输入选择阶段尚无调用意图，按 FAILED、NOT_INVOKED/零 token 收尾；前端沿用通用失败提示。真实空检索仍成功，检索故障或缺少有效版本材料仍为 SEARCH_UNAVAILABLE。

限界验证使用内存传输替身、虚拟时间和缓冲引用计数，不创建服务、监听端口或连接数据库；不能证明实际连接池、网络链路或供应商取消行为。

## 预算扩展

部署方后续可实现 `com.zhishiku.kb.ai.model.ChatTokenBudget`：`identity()` 明确 API 根地址、模型名和消息格式，`contextWindowTokens()` 返回核验窗口，`inputTokenUpperBound(PreparedChatRequest)` 对同一发送视图计算完整输入上界。完整消息与材料包装可通过 `messages()` 读取，不能只统计正文，更不能改写消息。扩展方法只允许本地、有界、确定性计算。

verified 下没有匹配策略时多轮不可用；同一身份存在多个策略则配置拒绝。更换地址、模型或消息格式需要重新核验，不能把同名模型别名视为权重证明。estimated 使用冻结参数的 `utf8-budget-v1`，不实现 `ChatTokenBudget`，不解析或调用严格策略；计算说明见[多轮适配器与预算](conversation-model-budget.md)。自定义模型仍分别优先，估算配置不会补齐自定义多轮实现缺失的预算能力。单轮现有字符/字节保护和输出上限不是总 token 上界；实际用量、预算预留与额度扣除继续分别表达。

## 发布与回退

内置自动配置只在 `KB_CHAT_MODEL_ENABLED=true` 时生效，单轮和多轮分别通过缺失 Bean 条件退让已有自定义模型。启用配置仍独立校验，不因为已有自定义 Bean 就忽略错误配置。仅修改 kb-ai 的适配层与配置，没有数据库迁移或前端/网关协议变更。

后续发布时由部署环境注入确定的 API 根地址、模型密钥和名称，显式选择输出字段与 usage 选项，再启用适配。多轮同时需要匹配严格策略，或显式选择 estimated 并填写真实窗口；估算参数需要结合真实样本校准。本次没有部署或真实模型验收。现有授权检索为空或失败时不会调用模型，现有来源、终态保存和结算协议继续生效。

回退可关闭 `KB_CHAT_MODEL_ENABLED` 并重启 kb-ai，或恢复旧二进制；不删除会话、审计或账本，不自动重新生成。关闭后旧单轮在没有自定义模型时可能恢复占位回答，这不是“真实模型恢复”。多轮保留原有历史和请求重放核对能力。

也可将预算模式改回 verified 并重启；无匹配严格策略时拒绝新生成，不影响匹配旧轮次的状态核对。严格模式异常、缺失或身份不匹配绝不自动回退 estimated。

## 离线验证记录（2026-11-01）

以下为此前协议适配变更的历史记录，不包含后续通用估算功能；本次预算变更验证记录见[多轮适配器与预算](conversation-model-budget.md)。

环境为 Windows PowerShell、JDK 21.0.7、Maven 3.9.9。核验修复后的最终显式白名单共 19 个类、121 项独立单元测试通过（新增模型测试 54 项、既有定向回归 67 项），失败、错误、跳过均为 0。只使用 Mock、内存传输、受控缓冲和虚拟时间，未执行容器装配、集成、浏览器端到端、真实模型或任何文档类测试，未启动 DB、Redis、MinIO 或应用服务。

```powershell
$tests = @(
    'ChatModelPropertiesTest', 'ChatModelConfigurationTest', 'ChatPromptRendererTest',
    'OpenAiSseDecoderTest', 'OpenAiChatTransportTest', 'ChatModelLimitsTest',
    'ChatModelLoggingTest', 'ChatTokenBudgetTest', 'OpenAiChatModelClientTest',
    'OpenAiMultiTurnModelTest',
    'ChatServiceAdapterTest', 'ChatServiceLifecycleTest', 'ChatControllerTest',
    'ChatObservationTest', 'AiObservedChatTest', 'ConversationBudgetTest',
    'MultiTurnChatServiceTest', 'NoDatabaseAccessTest', 'ReactiveStackTest'
)
mvn -o -pl kb-ai -am test "-Dtest=$($tests -join ',')" '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
```

测试中曾发现并修复两处问题：WebClient 默认头部的二次合并会带回用户凭据，现同时隔离默认头、Cookie 与过滤器；总期限的伴随错误流没有取消实际主订阅，现使用固定截止时间约束每次 timeout。取消断言、累计字节限界与池化缓冲释放均在独立单元测试中覆盖。配置工厂单测不能证明实际自动装配顺序或真实网络连接池行为，生产预算策略也未在本期实现。

首轮实现核验修复了普通事件中 CRLF 的 LF 漏计：LF 计入原事件并延后重置计数，避免跨分包时漏计或归入下一事件；媒体类型由兼容匹配改为精确匹配 `text/event-stream` 的 type/subtype，拒绝 `text/*`、`*/*` 等通配符，继续接受合法参数和大小写形式。该轮新增 5 项独立单元测试，覆盖连续多行 CRLF 事件的所有双分包切点、恰好上限与越限、首个超限 LF 立即取消、单 CR 即时终结，以及错误媒体类型正文仅取消一次且不进入解码；当时 25 项定向测试和 118 项白名单回归通过。

再次核验发现 `[DONE]` 会在终止 CR 处提前确认，尚未计入可能的 LF。经用户选择，普通事件和终结事件统一在发布前保守预留 1 字节 LF；预留不足时立即失败，有余量时单 CR 仍可即时完成，实际 LF 不重复计数。新增 3 项单测在修复前均失败，分别观察到过早发布正文或最终 usage；修复后 28 项定向测试及上述 121 项白名单回归全部通过。新增覆盖 LF/CRLF/CR 终结帧的恰好限额与不足限额、所有双分包切点及逐字节分包、终止 CR 后不再发送字节时立即失败、候选 usage 不外发及池化缓冲归零。原复现中的 116 字节 CRLF 终结帧在上限 115 时拒绝，上限 116 时成功。

打包与 OpenSpec 严格校验命令：

```powershell
mvn -o -pl kb-ai -am package '-DskipTests' '-Dstyle.color=never'
openspec validate openai-compatible-chat-adapter --strict
```

核验修复后已重新打包，结果为 `BUILD SUCCESS`，产物为 `kb-ai/target/kb-ai-0.1.0-SNAPSHOT.jar`。此前只读核对确认适配类已打入 JAR，根目录 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 注册了 `com.zhishiku.kb.ai.model.ChatModelConfiguration`。`NoDatabaseAccessTest` 的 1 项和 `ReactiveStackTest` 的 2 项均已包含在上述 121 项测试中并通过。

核验修复后重新执行严格校验，退出码为 0，输出 `Change 'openai-compatible-chat-adapter' is valid`。此结果表示 OpenSpec 结构校验通过；规格与实现另按以下对应关系进行源码和独立单元测试核对：

| 规格场景组 | 实现与核对依据 |
| --- | --- |
| 显式启用、配置拒绝、自定义扩展与凭据隔离 | 配置校验器、两个桥接的独立缺失 Bean 条件；`ChatModelPropertiesTest`、`ChatModelConfigurationTest`、`OpenAiChatTransportTest` |
| 授权材料、历史角色和显式请求参数 | `ChatPromptRenderer`、`PreparedChatRequest`；`ChatPromptRendererTest` |
| SSE 分包、严格终结、不支持输出和最终累计 usage | `OpenAiSseDecoder` 及两个模型桥接；`OpenAiSseDecoderTest`、`OpenAiChatModelClientTest`、`OpenAiMultiTurnModelTest` |
| 单次订阅、取消、期限、资源上限和安全异常 | `OpenAiChatTransport`、`ModelStreams`；`OpenAiChatTransportTest`、`ChatModelLimitsTest`、`ChatModelLoggingTest` |
| 预算身份匹配、无策略拒绝及多轮受理门禁 | `ChatBudgetBinding`、`OpenAiMultiTurnModel` 及既有预算/编排；`ChatTokenBudgetTest`、`OpenAiMultiTurnModelTest`、`ConversationBudgetTest`、`MultiTurnChatServiceTest` |
| 原授权检索、重放、失败收尾和用量结算兼容 | 白名单中的既有单轮编排、控制器、观测及多轮编排测试，各自使用协作替身 |

源码与变更范围核对确认没有注册生产预算实现，没有新增数据库迁移、前端/网关协议变更或厂商运行时依赖；模型密钥保留外部环境变量注入，未写入真实密钥或发送真实模型请求。上述结果不构成供应商兼容、容器自动装配或生产多轮验收。
