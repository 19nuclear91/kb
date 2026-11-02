# Design

## Context

动机和用户已确定的估算边界见 [proposal.md](proposal.md)。当前没有正在实施的其他变更，本设计复用以下已观察到的结构：

- `ChatPromptRenderer` 生成 `PreparedChatRequest`；材料先编码为带 document/version/chunk/page 身份的 JSON，再成为独立 user 数据消息。预算必须包括这层真实内容包装。
- `ChatBudgetBinding` 仅接受匹配 `ChatModelIdentity` 的 `ChatTokenBudget`。`OpenAiMultiTurnModel` 和 `ConversationBudget` 都只识别可信上界。
- `ConversationBudget` 先淘汰完整历史问答，再从末尾按 Unicode code point 对半裁剪材料。`MultiTurnChatService` 当前把最终材料为空也解释为 `NO_RESULTS`，因此可能把预算失败误报成成功空检索。
- `ConversationEnvironmentValidator` 已要求 AI 预留与多轮预留一致。默认预留 12000、输出上限 2048、上下文字符保护 24000；不能另增一份脱离账本的预留值。
- `UsageSettlement` 已区分 NOT_INVOKED、UNKNOWN、KNOWN；KNOWN 不裁掉超预留的真实 usage，UNKNOWN 按预留结算。无需修改账本模式或数据库结构。

本变更跨越预算契约、配置、编排、发送复核和故障语义，具有兼容性及资源边界问题，因此需要本设计。

## Goals / Non-Goals

**Goals:**

- 显式启用估算后，内置多轮适配可以在没有供应商 tokenizer 的情况下按确定性本地规则控制输入规模。
- 预算类型、配置与发送内容保持一致，现有严格扩展继续可用，默认部署行为不变。
- 将参数的单位、默认值、取整、数值范围、裁剪和失败行为固定为可重复测试的契约。

**Non-Goals:**

- 不证明任何真实模型的 token 上界，不核验供应商窗口、隐藏模板、可变别名或 tokenizer。
- 不增加在线计数、厂商 SDK、模型发现、运行时热切换、动态预留或按估算量计费。
- 不改变现有普通文本流支持范围，不增加工具、思考过程、多模态、模型重试或自动删参。
- 不扩展前端页面、公开配置 API、持久化预算统计字段或平台级监控；测试使用独立对象及 Mock/内存替身，不在本变更执行真实服务联调。

## Decisions

### 1. 模式必须显式，默认继续 verified

新增 `kb.ai.chat-model.budget` 配置组，模式只接受小写 `verified`、`estimated`。默认 `verified`，沿用既有严格扩展选择；没有匹配扩展仍返回 503。`estimated` 只为内置 OpenAI-compatible 多轮桥接选择本地估算策略，不调用严格扩展的计数方法，也不根据其成功与否改变模式。`verified` 的缺失、重复、异常、身份不匹配不会触发估算回退。

既有自定义 `ChatModelClient` / `MultiTurnModel` 的独立优先级保持不变。预算配置约束内置实现，不把一个不支持预算的自定义多轮实现自动升级为可用。显式启用通用模型时仍校验其配置，即使已有自定义 Bean；模型适配保持关闭时不因本组配置开启任何调用。

不采用“先严格、失败再估算”：那会让同一部署在没有明确操作的情况下改变保证级别。

### 2. 配置采用有界整数，窗口必须由部署方提供

以下数值是本变更选定的初始产品默认值，不代表供应商 token 比率或经过真实模型校准的参数。单位统一为 token 或 UTF-8 字节。

| 属性（相对 budget） | 环境变量 | 默认与校验 |
| --- | --- | --- |
| `mode` | `KB_CHAT_BUDGET_MODE` | `verified`；只接受 verified/estimated |
| `context-window-tokens` | `KB_CHAT_BUDGET_CONTEXT_WINDOW_TOKENS` | 无已知窗口；用 0 表示未配置；estimated 下必须为 1..2147483647 |
| `tokens-per-kib` | `KB_CHAT_BUDGET_TOKENS_PER_KIB` | 1024；整数 1..65536，表示每 1024 字节估算多少 token |
| `per-message-tokens` | `KB_CHAT_BUDGET_PER_MESSAGE_TOKENS` | 16；整数 0..65536 |
| `request-overhead-tokens` | `KB_CHAT_BUDGET_REQUEST_OVERHEAD_TOKENS` | 64；整数 0..65536 |
| `safety-margin-tokens` | `KB_CHAT_BUDGET_SAFETY_MARGIN_TOKENS` | 512；整数 0..2147483647 |

启用 estimated 时，以上配置必须可解析、在范围内，并满足 `min(window, reservation) > maxOutput + margin`。字段绑定失败、未知 mode、窗口缺失及余量占满容量均在创建业务连接前给出固定配置错误，不打印值或原始异常。使用 `long` 检查加法，不能让 int 溢出通过校验。

verified 使用扩展提供的窗口及既有零额外余量；估算参数不参与其计算。任何模式都保留单请求预留一致性校验，`single-max-output-tokens` 仍只用于旧单轮；多轮输出上限仍来自 `kb.conversation.max-output-tokens`。

选择整数缩放系数是为了避免 NaN、Infinity、浮点舍入及隐含的语言平均 token 比率。窗口不提供猜测性默认值；其真实性由后续部署配置及联调负责。

### 3. 明确分离预算能力与计数结果，兼容旧扩展

在 `kb-ai` 内引入中性的预算值类型（具体命名可按现有包内风格调整）：

- `BudgetKind`：UNAVAILABLE、VERIFIED、ESTIMATED。
- `BudgetCapability`：类型、窗口及安全余量。不可用类型不携带可放行窗口；可用窗口必须为正，余量非负。
- `InputTokenCount`：类型及非负输入计数；预算结果类型必须与能力一致。
- 共用判定器负责类型/数值检查和容量判断，供裁剪与发送前复核调用。

`MultiTurnModel` 保留现有三个预算方法，新增带默认实现的能力及计数入口：旧实现声明支持可信上界时默认映射为 VERIFIED、零附加余量，并代理旧计数方法；不支持时映射为 UNAVAILABLE。这样已有严格实现无需新增方法。应用编排改用新入口。

内置估算实现覆盖新入口返回 ESTIMATED；其旧 `supportsTokenUpperBound()` 必须为 false，旧上界计数入口明确拒绝，避免旧调用方误认。`contextWindowTokens()` 可继续返回当前配置窗口，但窗口单独存在不代表就绪。

保留 `ChatTokenBudget` 作为严格扩展契约。新增内部 `ChatBudgetPolicy`，由严格绑定包装器或 `ConfiguredEstimatedTokenBudget` 提供；后者不实现 `ChatTokenBudget`。内置桥接共享渲染和传输，不复制模型调用链。

能力和估算参数在装配时形成不可变快照，与规范化 base URL、model 和 `kb-chat-text-v1` 关联；配置改变通过重启生效。该关联仅防止本地请求串用配置，不宣称识别了远端别名对应的真实权重。VERIFIED 表示既有扩展承诺，不是本次运行自动完成了模型核验。

不直接重命名或删除旧方法，也不让估算值沿着旧上界方法返回：前者会破坏外部实现，后者会混淆保证级别。

### 4. 估算只读取最终消息内容，计算为线性且有界

算法版本固定为 `utf8-budget-v1`。令：

```text
B = sum(UTF8_BYTES(message.role) + UTF8_BYTES(message.content))
N = count(messages)
P = tokens-per-kib
M = per-message-tokens
F = request-overhead-tokens
E = ceil(B * P / 1024) + N * M + F
```

取整针对全部消息的字节和执行一次，不逐消息重复向上取整。使用检查溢出的整数运算；计算或转换溢出立即以固定预算不可用错误拒绝，不饱和为可放行值，不把异常解释为零。

计数对象是 `PreparedChatRequest.messages()` 的 role 与 content。system、所有完整历史消息、材料说明、材料 JSON 身份字段及内容、原问题都已在该视图内。材料字符串内部的转义字节属于真实消息内容，必须计算；HTTP 外层 JSON 为传输再增加的转义、模型字段、认证头及 API key 不计为输入内容，未知供应商消息模板由 M/F 估算。

计数不得修改消息、读取网络、调用模型或持久化正文。以字符扫描计算 UTF-8 长度并复用 Unicode 合法性检查，避免为计数再构建无界字节副本；非法 Unicode/NUL 沿用拒绝规则。已有消息规模、字符、请求字节及流资源限制仍独立生效。

例如仅用于算法单测的两条消息 `user: A` 与 `assistant: 中😀`：B=21、N=2。P=512、M=2、F=3 时 E=18，能验证 UTF-8、角色字节、取整及开销。另用两条 `user: A` 验证总和取整：同参数下 B=10、E=12，逐消息取整会错误得到 13。

采用字节规模启发式而不是未知 tokenizer 的假计数，也不将 HTTP 请求字节直接称为模型 token。默认 P=1024 相当于每字节估算一个 token，可能明显牺牲可用上下文；参数可校准，但本算法不承诺真实上界。

### 5. 裁剪与发送前使用相同容量公式

```text
C = min(capability.windowTokens, reservationTokens)
accept iff count.tokens <= C - maxOutputTokens - capability.safetyMarginTokens
```

先验证 C、输出、余量及计数均合法，再以 checked long 运算进行减法，避免求和溢出。VERIFIED 的计数来自严格策略且余量为零；ESTIMATED 的 E 和配置余量分别表达，余量只扣一次。两处都检查结果类型与能力匹配。

`ConversationBudget.select` 在每次裁剪后重新渲染并计数，复用现有完整历史淘汰、来源依赖同步和 Unicode 材料裁剪。字符限制与来源上限继续是独立门禁。当前问题完整保留。`Plan` 保存本轮能力快照，发送前复核应使用同一冻结配置；内置策略不做运行时参数更新。

`OpenAiMultiTurnModel.generate` 渲染实际待发送的请求，复核同一公式，然后把这个请求对象直接交给 transport，不在复核后重新拼接不同的消息。预算计算不订阅 transport，不因超限执行生成重试。

默认预留 12000、输出 2048、估算余量 512 时，在配置窗口至少为 12000 的情况下，E 上限为 9440。调大窗口不会绕过部门预留；调低窗口同样约束调用。

### 6. 材料被预算裁空属于预算失败

区分以下事实：

| 情况 | 结果 |
| --- | --- |
| 本次授权检索合法返回空列表 | 保留固定 NO_RESULTS 成功流程，模型调用为零 |
| 非空检索结果全部缺少有效版本材料 | 保留 SEARCH_UNAVAILABLE 失败 |
| 初始有效版本材料非空，因预算或字符保护裁到没有可用材料 | INPUT_BUDGET_EXCEEDED，模型调用为零 |
| 必需指令及原问题仍容纳不下 | INPUT_BUDGET_EXCEEDED，原问题不截断 |
| 策略计算异常、无效类型或配置运行时不一致 | 预算能力不可用，固定错误，不伪装成预算不足或空检索 |

新增固定预算不足类别并沿用既有 error 事件。输入选择阶段的预算失败发生在 `app.dispatch` 之前；流已建立时携带 `code=INPUT_BUDGET_EXCEEDED` 与固定安全消息，使用既有 FAILED、NOT_INVOKED/零 token 结算及恢复路径；不写入成功的 NO_RESULTS 正文，不发布被移除材料的引用。若在响应提交前发现同类输入错误，则 HTTP 400 加相同固定 code。能力不可用在响应提交前仍为 503，流建立后沿用安全 error。

本期不新增前端专用文案，现有未知错误码处理仍显示通用失败并核对历史；API 可辨别预算原因。真正的空检索、检索故障、重放及已有严格模式也遵循上述修正。

### 7. 真实用量和请求执行规则继续独立

预算值只用于输入准入及裁剪，不填充 promptTokens/completionTokens，不传给 `UsageSettlement` 当作 usage，不新增账本列。KNOWN 的真实输入/输出即使超过估算或预留也完整保存和结算；UNKNOWN 仍为空并按原预留扣除，NOT_INVOKED 仍为真实和扣除均零。

保留现有 `app.dispatch` 持久化意图在 `model.generate` 之前的顺序。输入选择失败尚无意图，可结算为 NOT_INVOKED；如果已有意图而发送前最后复核或传输准备失败，即使本地观察到零 HTTP 请求，也不能新增旁路把账本改成未调用，仍按既有无确认 usage 的 UNKNOWN 结算。需要分别测试这两个时点，不通过本变更重新设计调用意图协议。

若估算不足导致上游拒绝或截断，沿用现有失败和结算流程，不自动扩大窗口、减少参数、切换模式或再次调用模型。已有轮次匹配重放继续优先于新生成能力检查，关闭或更改预算配置不影响其原状态核对。

启动诊断最多记录固定模式和算法版本，不记录模型地址、密钥、消息或原始异常。既有来源确认、权限、回调幂等和终态规则不受估算模式放宽。

### 8. 验证聚焦独立单元和配置兼容

新增算法单测使用可手算消息；覆盖中文、英文、emoji、材料 JSON 转义、所有消息角色、取整、参数边界、溢出、重复调用无副作用。配置单测直接绑定内存环境并调用校验器/工厂，不启动 Spring 容器或网络。

扩展现有预算、桥接、编排测试：旧严格扩展的默认方法兼容；estimated 不能从旧上界入口放行；默认 verified 仍 503；重复严格策略、严格异常不降级；模式选择与自定义模型优先；余量两处一致且只扣一次；材料裁空失败、合法空检索仍成功、原问题不截断、来源同步、取消/重放不新增调用。

KNOWN 超预留、UNKNOWN、NOT_INVOKED 复用既有用量计算测试及 Mock 编排协作验证。模拟供应商响应仅证明本地行为，不证明网络连接池、真实 tokenizer、窗口或生产费用上限。不编写文档文字断言或为本变更启动真实 PG/Redis/MinIO/模型。

## Risks / Trade-offs

- [实际 token 高于估算或配置窗口错误] → 显式 estimated、必填窗口、固定余量、完整实际 usage、清楚的部署说明；不声称该风险已被消除。
- [偏保守参数过度裁剪材料] → 将比例和开销显式配置，保留来源裁剪标记；裁空报预算不足，后续按实际模型校准。
- [旧扩展接口被误用或升级破坏] → 使用新增默认方法桥接严格扩展，估算不实现旧上界契约，增加兼容性单测。
- [选择阶段与发送阶段采用不同公式] → 共享类型与判定器，冻结策略配置，两处都覆盖恰好边界及差一 token 的测试。
- [估算放宽使超预留更常见] → 保留真实超额结算，不用预留上限裁掉 usage；部门限额仍是准入机制，不能据此承诺真实费用硬上限。

## Migration Plan

1. 实施时更新两份 delta spec 对应的实现与说明，通过定向单测和编译；本提案阶段不修改应用代码或主规范。
2. 发布时默认 verified，不设置 estimated，确认已有严格扩展及匹配重放行为保持兼容；无需 DDL、数据回填或前端版本联动。
3. 后续真实接入时，由部署方配置现有模型地址、密钥、名称、普通文本参数，显式设置 estimated 和已知模型窗口；启用既有会话配置并保持 app/AI 预留及内部密钥一致。
4. 使用真实样本核对兼容性、实际 usage 与估算偏差，再调整 P/M/F/余量。这是发布验收，不作为本次离线测试完成的事实。
5. 回退到 verified 并重启 AI；有匹配严格策略则按原模式继续，没有则新生成返回 503。也可关闭通用模型适配。保留历史、终态、审计和待结算意图，不自动重放生成。

## Open Questions

- 后续部署选择的实际供应商、model、真实窗口以及校准参数尚未确定；它们作为部署输入解决，不改变本次算法、接口或开发任务。
