# Design

## Context

动机与已确认的 A 方案见 `proposal.md`。本设计涉及外部协议、敏感数据、流生命周期和预算门禁，符合需要单独设计文档的条件。

已核对的实现约束：

- `kb-ai/.../chat/ChatService.java` 通过 `ObjectProvider<ChatModelClient>` 取得可选模型；没有 Bean 才返回占位回答。材料先按 code point 裁剪，再同时用于引用事件和模型输入。
- `kb-ai/.../conversation/MultiTurnChatService.java` 的 `prepare` 先核对请求是否重放，再调用 `ConversationBudget.ready`，最后才创建轮次和领取配额。必须保留此顺序，不能因为普通模型已配置而越过预算门禁。
- 同一服务的 `commands` 会把上游 Flux 正常完成转换为 `COMPLETED`。适配器必须先核验完整终结链，不能让普通 EOF 或 `length` 走正常完成。
- `ConversationBudget` 已负责整对移除历史、裁剪材料及来源集合；适配层不得另做一次隐蔽裁剪，否则模型输入与来源确认不一致。
- `ChatModelClient.StreamEvent` 的用量为 Integer，`MultiTurnModel.Event` 为 Long，桥接必须精确转换。旧单轮 done 的原始整数字段不表达 UNKNOWN；真实用量状态由既有采集和多轮协议独立表达，本次不改该兼容边界。
- `AiObservedChat` 仅在模型事件流正常完成时确认尾部 usage；未正常终结的候选统计不能提前发布。原失败、取消及 UNKNOWN 的账本策略不在本次统一。
- `kb-ai/pom.xml` 已含 WebFlux、Jackson、Reactor Netty 和 reactor-test；`NoDatabaseAccessTest`、`ReactiveStackTest` 守住类路径边界，无需引入新运行时依赖。

## Goals / Non-Goals

**Goals:**

- 把上游兼容性限制集中在协议层，既有单轮和多轮仅消费原有模型事件。
- 一次执行使用同一消息格式生成上游请求，并为将来的可信预算扩展提供同源的消息与包装信息。
- 使未订阅、正常完成、失败、取消和超时都有可测且有界的资源生命周期。
- 本期提供可接真实服务的单轮实现及可验证的多轮桥接，但不宣称生产多轮已经可启用。

**Non-Goals:**

- 不引入 Spring AI、LangChain4j 或厂商 SDK，不修改数据库、前端、网关或公共响应协议。
- 不实现生产 tokenizer、通用字符/token 换算、强制开启预算能力的配置项或模型自动发现。
- 不支持 Responses API、工具、多模态、思考过程、任意厂商参数映射、自动降级或切换服务。
- 不改造现有单轮结算策略，也不以本次模拟传输测试替代真实供应商、浏览器或中间件验收。

## Decisions

### 1. 在 kb-ai 内分离协议、消息和两个桥接

拟在 `com.zhishiku.kb.ai.model` 新增以下职责，具体 DTO 可保持为包内类型，避免不必要的公共 API：

| 组件 | 职责 |
| --- | --- |
| `ChatModelProperties` / `ChatModelConfiguration` | 本地配置校验、独立客户端构造、条件装配 |
| `ChatPromptRenderer` / `PreparedChatRequest` | 确定性的消息构造、格式版本、最终请求参数及有界编码 |
| `OpenAiChatTransport` | 一次性出站请求、HTTP 状态和资源生命周期 |
| `OpenAiSseDecoder` / `ModelEvent` | 有界 SSE 解码、普通文本和最终累计用量 |
| `OpenAiChatModelClient` | 桥接旧 `ChatModelClient`，精确转换 Integer usage |
| `OpenAiMultiTurnModel` | 桥接 `MultiTurnModel`，委托可信预算扩展 |
| `ChatTokenBudget` / `ChatModelIdentity` | 纯本地、模型和格式绑定的预算扩展边界 |

```text
ChatService ----------> OpenAiChatModelClient ---+
                                               |
MultiTurnChatService -> OpenAiMultiTurnModel ----+--> ChatPromptRenderer
                              |                |         |
                              v                +--> OpenAiChatTransport
                        ChatTokenBudget                  |
                                                   OpenAiSseDecoder
```

沿用现有 ObjectProvider 扩展方式，内置单轮、多轮桥接分别对对应接口使用缺失 Bean 条件，部署方自定义实现优先。启用配置的校验与有无自定义桥接分开，不允许配置错误因条件装配被静默忽略。

**替代方案：**在 ChatService 直接加入 HTTP 调用会把协议错误与业务编排耦合；一个大类同时承接 HTTP、预算和两类模型容易使无预算能力被误判为多轮就绪；厂商框架会增加当前不需要的协议和依赖范围。

### 2. 配置固定在服务端，兼容差异采用小型显式开关

新增前缀 `kb.ai.chat-model`，不复用 Embedding 或 rerank 的地址、密钥与模型。规划的配置与缺省如下：

| 配置 | 缺省 / 校验 |
| --- | --- |
| `enabled` | false；关闭时不创建内置传输和模型桥接 |
| `base-url`、`api-key`、`model` | 无默认；启用时必填；模型值和密钥禁止控制字符 |
| `output-token-field` | `max_tokens`；仅另允许 `max_completion_tokens` |
| `request-usage` | true；控制是否发送 `stream_options.include_usage=true`，false 时完全省略 stream_options |
| `single-max-output-tokens` | 2048；正整数，且不超过现有单请求预留值；只适用于旧单轮 |
| `connect-timeout-ms` / `idle-timeout-ms` | 5000 / 30000；必须为正且不超过总期限 |
| `max-request-bytes` / `max-event-bytes` / `max-response-bytes` | 1048576 / 1048576 / 4194304；正整数，单事件不超过累计响应限制 |
| `max-answer-codepoints` | 64000；正整数；作为适配层安全上限，与既有多轮保存上限分别生效 |

整次调用的绝对期限复用 `kb.ai.upstream-timeout`，启用模型时额外确认该值为正；不能只依赖现有 `.timeout` 的逐事件空闲语义。JSON 最大深度固定为 32。配置中不提供任意请求体或头部扩展映射，也不提供可伪造预算就绪的布尔开关。

base URL 定义为 API 根地址，例如 `https://model.example.invalid/v1`，只去除末尾斜杠后追加 `/chat/completions`，不使用会丢弃 `/v1` 的根相对 URI 合并。拒绝已经指向 completions 接口的根地址、用户信息、query、fragment 和非法 scheme；生产只允许 HTTPS，显式开发环境允许 HTTP。密钥仅由外部配置注入，不进入 VITE 变量或文档命令历史，配置类型不得通过自动生成的 toString 打印秘密。

`stream=true`、`n=1` 固定；每次仅发送选定的一个输出限制字段。首版不支持必须依靠额外 thinking 参数才能关闭推理的模型，部署方需选用符合普通文本契约的服务配置。

**替代方案：**自动尝试两种 token 字段、删去 usage 后重发或按模型名推断厂商都会引入重复计费及不可复核行为，故不采用。具体供应商未知不阻碍固定这些小型协议选择。

### 3. 消息渲染保持角色，材料放在不可信的数据消息中

共享 renderer 输出格式版本 `kb-chat-text-v1`：

1. 第一条为服务端固定 system 指令。多轮使用传入的既有固定指令；旧单轮使用相同安全原则，不从用户或配置接收任意 system 文本。
2. 按传入顺序输出历史 user/assistant 消息，校验仅有支持的角色且最后一条是本轮 user。历史正文不升级角色。
3. 在最后的原问题之前插入一条 user 数据消息，用固定说明与 JSON 序列化的材料列表承载 `docId`、可空 `versionId`、`chunkId`、可用页码和正文；正文经结构化编码，不通过手写分隔符拼接新消息。只外发回答需要的字段，不外发对象路径、检索分数、权限主体或存储定位凭据。
4. 最后一条 user 消息保持原问题原文。单轮没有历史，只由问题和传入材料构造相同结构；旧来源缺版本仍为 null，不查找或补成新版本。

renderer 不增删实际材料，不重新裁剪，不请求外部 URL。请求字节超限直接失败，而不是悄悄压缩资料使已确认来源失真。单轮的材料字符裁剪继续由 ChatService 负责，多轮继续由 ConversationBudget 负责。

**替代方案：**把材料拼入 system 会扩大不可信文本的角色；把历史扁平化成字符串会丢失真实角色。单靠分隔符不能构成提示词注入防护，服务端授权与禁用工具才是实际访问边界。

### 4. SSE 采用独立终结状态机，不依赖 EOF 宣告成功

传输只接受 HTTP 200 与 UTF-8 `text/event-stream`。解码按事件组织，不把每个 DataBuffer 当一条 JSON；支持 CRLF/LF/CR、注释、多行 data 和跨包字符。严格拒绝无效 UTF-8、孤立代理项、NUL、重复 JSON 键、尾随文档及过深结构。必须在单事件累积过程中检查限制，不能先构造大字符串再检查。

```text
NEW --> STREAMING --> STOP_SEEN --> DONE
          |             |
          +-------------+--> FAILED

NEW / STREAMING / STOP_SEEN --> CANCELLED
```

- 普通候选只接受索引 0。允许初始 assistant 角色、空 delta 和无害元数据；usage 独立帧允许 choices 为空。
- 非空正文立即产生 Delta，不等待 finish。拒绝不支持的角色、多个候选、工具/函数调用、非文本内容、非空拒绝载荷及已识别的 reasoning_content/reasoning/thinking 思考字段。
- 一条帧同时携带正文和 finish 时，先验证该完整帧，再按正文、stop 的顺序推进。stop 后只允许无正文的元数据和累计 usage，再等待 `[DONE]`。
- 正常完成要求已收到非空正文、stop 与完整 `[DONE]` 事件。遇到 `[DONE]` 后停止读取上游并完成一次，不为等待远程关闭连接继续占用资源。
- CR 结束事件分隔空行时，在发布普通事件或确认 `[DONE]` 前统一检查还可容纳 1 字节 LF。此为用户确认的保守预留：若 CR 已恰好顶满上限，则立即失败，即使实际使用单 CR；有余量时保持即时交付和终结。预留只作准入检查，不增加已读取字节计数，后续实际 LF 仍只计一次。不依据网络分包、延迟或 EOF 推测是否会有 LF。
- `length`、`content_filter`、工具结束原因、未知结束原因、stop 后继续出字或缺少终结链的 EOF 均转为固定错误。首版不新增“成功但截断”的公共状态，不修改多轮终态枚举。

这些错误不能通过 `onErrorResume(Flux.empty())` 变成正常 Flux 完成，否则既有编排会错误保存 COMPLETED。部分已交付文本由现有失败收尾保留，不把错误文案写入助手正文。

**替代方案：**只看 `[DONE]`、只看 finish 或依靠 socket EOF 更宽松，但无法可靠区分截断与成功。首版接受与某些宽松兼容服务不兼容的代价，不为它们自动放宽成功标准。

### 5. usage 是等待终结确认的候选值，不是每帧增量

解码器只暂存最后一个完整非负整数 prompt_tokens/completion_tokens 候选；新的文本到来使旧候选失效。不得把 JSON Number 通用转换为 long 后截断小数或大数。未知的可选明细不当作额外扣费，total_tokens 不用于反推缺失的组成项。

仅在正常终结链确认后输出一次 FinalUsage，再正常完成。缺省或 null usage 表示未提供；显式 usage 对象缺一项、格式非法或超出表示范围为协议错误。多轮映射为 `finalUsage=true` 的纯用量事件，单轮用精确 Integer 转换后映射为 `StreamEvent.usage`；溢出失败，不能饱和或取模。

缺少最终统计时不输出 usage，沿既有流程区分 UNKNOWN 与显式 KNOWN=0/0。异常、取消或 `length` 的提前终止不等待尾部统计来延迟失败，也不把已经收到但未经确认的中途候选用量冒充最终值。

旧单轮 done 仍保留当前整数兼容字段；没有 usage 时这些缺省字段不代表真实零消耗。启用采集后由 AiObservedChat 表达 UNKNOWN，本次不把单轮 UNKNOWN 改成多轮的预留额度结算，也不重写失败/取消账本。

**替代方案：**累计求和、按字数计费或强制所有入口采用同一 UNKNOWN 扣费策略均违背既有契约，故不采用。

### 6. 一次订阅、无重试、可取消且有总期限

transport 返回按调用创建的延迟、单次消费流：未订阅不发送 HTTP；同一返回流的再次订阅在发送前拒绝，不使用 cache/replay 缓存正文。桥接方法每次创建执行对象，既有控制器继续负责只订阅一次；多轮匹配重放根本不进入生成。

使用隔离的 WebClient，避免继承内部服务认证头、Cookie 或非预期过滤器。Reactor Netty 显式关闭重定向和底层连接故障自动重试，业务层不添加 retry/retryWhen，不因 usage 不支持、429、5xx 或连接失败换参数重发。

连接等待、语义事件空闲和绝对总期限分别计时。注释不延长语义事件空闲计时，任何帧也不延长总期限；总期限到达产生错误，不能通过普通 takeUntil 完成冒充成功。终结、错误和取消均清理定时器、上游订阅、连接资源以及已分配缓冲；迟到回调不能重新发布终态。

请求采用有界序列化，在超过 max-request-bytes 的首个写入位置失败；累计响应字节在解压后的 DataBuffer 层计数，包含正文以外的 SSE 框架、注释和 usage。单事件字节限制、JSON 限制和回答 code point 限制分别执行，不以小帧规避累计限制。

单事件限制在 CR 分隔空行处包含上述可选 LF 的保守预留；因此单 CR 结尾事件的实际字节数必须至少比配置上限少 1。LF 与 CRLF 结尾可按完整实际字节数恰好达到上限。终结状态及最终 usage 的发布晚于这一限额检查，避免 `[DONE]` 提前退出读取循环而跳过最后一个可能的 LF。

非成功状态和错误媒体类型取消或有界释放正文，不调用会聚合原始错误体的通用异常构造，不为清理再次订阅上游。客户端取消表示取消本地 I/O，不保证供应商已停止计算或收费。

**替代方案：**按事件 timeout 不能限制持续慢流；聚合完整响应破坏流式和内存边界；失败重试无法证明供应商未受理，故都不采用。

### 7. 预算扩展只定义契约，本期不装配生产实现

`ChatTokenBudget` 提供对 `ChatModelIdentity` 的明确匹配、已核验上下文窗口以及对同一 renderer 产物的输入 token 上界计算。identity 至少包含规范化 API 根地址、配置模型名称及消息格式版本；后续真实扩展还必须核验这些声明对应的实际模型部署与 tokenizer，不把同名别名视为权重证明。

预算计算为纯本地、确定性调用，不发模型或计数 HTTP 请求，不占用数据库事务。计数对象包含 system、历史、材料、当前问题和完整消息包装成本，不只统计正文。renderer 的预算视图与发送视图来自同一构造路径，格式版本变化使旧匹配失效。

没有匹配策略时，内置多轮适配的 `supportsTokenUpperBound()` 返回 false、`contextWindowTokens()` 返回 0；直接调用生成同样在请求前拒绝。不提供默认字符倍数或 `verified=true` 等开关。若多个扩展同时匹配同一身份，则装配拒绝而非随机选一个。即使以后有匹配策略，预算计算异常、负数或窗口不合法也不能绕过原 ConversationBudget。

保留 `prepare` 中先检查匹配重放、再 ready、再 begin/acquire 的次序：已有轮次的匹配重放不因缺少新生成能力而重新生成或失去核对能力；新生成在无预算时 503，不创建轮次、不检索、不占配额。本期仅在测试目录提供确定性替身，分别验证预算、桥接和编排的独立逻辑，协作依赖使用 Mock 或内存替身，不串联多个真实组件；替身不能注册进默认或 dev 生产代码路径。

单轮继续使用其既有字符限制、请求字节保护与显式输出上限；这些保护不是输入 token 上界，也不承诺一定落在预留内。真实累计 usage 仍按既有规则传递，不能为了掩盖超预留费用截断统计。

**替代方案：**通用 byte/字符换算无法在未知 tokenizer 和消息模板上证明上界；自动装配一个估算器会违背用户选择的 A 方案，故只交付扩展点。

### 8. 错误仅保留安全枚举，验证限定于独立单元测试

新增错误可使用 HTTP_REJECTED、CONNECT_FAILED、TIMEOUT、PROTOCOL_INVALID、INCOMPLETE_STREAM、OUTPUT_TRUNCATED、UNSUPPORTED_OUTPUT、PAYLOAD_LIMIT、BUDGET_UNAVAILABLE 等固定类别。不携带上游响应、完整 URL、原异常消息或请求载荷作为可打印属性；原始 cause 不应落入既有通用日志。客户端仍由原编排输出通用 error，不新增公开供应商状态码或原始错误消息。

实施测试只针对独立被测对象，协作依赖使用 Mock 或内存替身。传输测试使用模拟 ExchangeFunction/ClientResponse，不监听端口；数据流使用受控 DataBuffer、Reactor TestPublisher/StepVerifier 和虚拟时间；配置测试直接调用属性绑定、校验器及工厂方法，不创建 Spring 容器。用内存日志捕获确认密钥和正文不外泄，模拟缓冲引用计数确认释放，计数确认同一执行没有二次订阅或请求。

现有 `ChatServiceAdapterTest`、`ChatServiceLifecycleTest`、`ChatControllerTest`、`ChatObservationTest`、`AiObservedChatTest`、`ConversationBudgetTest`、`MultiTurnChatServiceTest`、`NoDatabaseAccessTest`、`ReactiveStackTest` 仅作为独立单元测试的定向回归，各被测对象的模型、检索、配额、存储与观测依赖维持 Mock 或内存替身。不新增真实桥接、传输及业务编排串联的生命周期联合测试，也不以单元测试替代容器装配或真实链路验收。

本期不编写或执行集成测试、容器装配测试、跨组件联合测试或端到端测试，不启动应用、HTTP 服务、DB、Redis、MinIO、Docker、浏览器或真实模型。文档编写和记录结果保留，但不对 README、Markdown、注释或文档示例编写内容、格式、文案断言或示例执行测试。

## Risks / Trade-offs

- [兼容服务不支持严格 stop/[DONE]、usage 或输出字段] → 只提供小型显式配置，默认严格失败；后续经确认的新协议变体再单独扩展，不自动重试探测。
- [本期多轮在真实环境仍不可用] → 这是已确认的 A 方案，文档明确“协议已实现”与“生产预算就绪”不同，交付验收包含持续 503 而非伪造可用性。
- [模型名称或地址背后是可变别名] → 后续预算扩展必须核验部署身份和 tokenizer；仅配置模型名不产生预算证明。
- [单轮缺 usage 时旧 done 仍呈现缺省整数] → 保留公共兼容协议，在说明中强调真实用量以采集状态为准，不把旧字段当作 KNOWN。
- [取消后供应商仍计算或计费] → 不宣传远端取消保证，不自动重试；后续真实服务验收单独确认其取消行为。
- [部分答案在异常前已展示或保存] → 不伪造撤回，使用现有失败/取消状态和已确认前缀；未完整回答不进入合格后续上下文。
- [模拟传输不能证明实际 HTTP 连接池、驱动和供应商行为] → 本期只声明离线验证；真实服务兼容、安全和流式效果留待选型后的独立验收。
- [未执行容器装配和跨组件联合测试] → 只声明独立单元测试已覆盖的分支，不将配置工厂测试或单个编排测试表述为自动装配和完整调用链已验收。

## Migration Plan

1. 实施时只新增 kb-ai 内适配层、配置和测试，更新模型接入说明及多轮预算说明，不新增数据库迁移或公开 API。
2. 发布默认仍为关闭，不在 dev 自动启用假模型或假预算；所有既有部署方自定义扩展保持优先。
3. 后续接入真实服务时先外部注入单轮所需地址、密钥、模型和协议选择；不在本次规划或离线验收中发送真实请求。
4. 即使服务已配置，本期没有预算扩展时多轮仍 503；不得仅打开前后端会话开关宣称已支持真实多轮。具体生产预算实现另行规划、核验后接入。
5. 回退可关闭内置适配并重启或回退 kb-ai 二进制，不删除会话、审计或账本。关闭后旧单轮可能恢复其原占位兼容，不应把它当成真实模型已恢复；多轮不可用时保留现有历史核对路径。

## Open Questions

- 后续实际供应商、模型部署和 API 根地址尚未确定；它们不改变本期固定的 Chat Completions 文本契约和任务拆分。
- 真实模型采用何种 tokenizer 与部署身份核验方案留待独立预算扩展变更决定；本次只实现接口及无策略时的拒绝行为，不将该未知项包装为本期已实现能力。
