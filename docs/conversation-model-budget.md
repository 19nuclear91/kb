# 多轮适配器与预算

新入口需要 MultiTurnModel，实现有序角色消息、固定系统指令、本轮新材料、maxOutputTokens、明确类型的预算能力
和带明确 finalUsage 的流事件。仅实现旧 ChatModelClient 不能启用多轮；缺少能力返回 503，不能用占位回答写入正常多轮历史。当前工程不交付具体生产模型供应商。旧
POST /api/ai/chat 保留既有单轮与占位兼容。

通用 OpenAI-compatible 协议接入及配置见 [聊天模型适配](chat-model-adapter.md)。协议就绪与可信预算能力分别判断：`ChatTokenBudget` 扩展按规范化 API 根地址、模型名称和 `kb-chat-text-v1` 消息格式精确绑定，返回核验窗口和完整 `PreparedChatRequest` 的输入上界。计算必须纯本地、确定性且有界，包含角色、指令、历史、材料数据消息、原问题和供应商包装成本；不能发网络计数请求或修改输入。

预算能力通过不可变 `BudgetCapability` 声明 UNAVAILABLE、VERIFIED 或 ESTIMATED、窗口和余量；`InputTokenCount` 的类型必须与能力一致。不可用能力没有窗口，严格能力附加余量为零。`MultiTurnModel.budgetCapability()` 和 `countInputTokens()` 的默认方法兼容原严格扩展，仍代理原来的三个预算方法，原实现无需新增方法。内置估算实现覆盖新入口，其 `supportsTokenUpperBound()` 为 false，直接调用旧 `inputTokenUpperBound()` 会拒绝。

模式默认 `verified`，由 `ChatBudgetBinding` 包装部署方 `ChatTokenBudget`。没有匹配策略时，多轮新生成在创建轮次、检索和配额领取前返回 503；已有匹配重放保留原状态核对能力。多个策略同时匹配拒绝装配，模型或格式改变不能复用旧策略，异常不回退估算。显式 `estimated` 使用 `ConfiguredEstimatedTokenBudget`，它不实现严格上界契约，也不调用严格策略。参数、身份和窗口在装配时冻结，修改配置需要重启；VERIFIED 表示扩展声明，不代表系统自动核验了远端模型。

## 通用估算算法

`utf8-budget-v1` 只读取 `PreparedChatRequest.messages()` 的 role 和 content。令 B 为全部这些字符串的 UTF-8 字节总数、N 为消息数、P 为每 1024 字节 token 系数、M 为每消息开销、F 为请求开销：

```text
E = ceil(B * P / 1024) + N * M + F
```

仅对总和取整一次。默认 P=1024、M=16、F=64，相当于每字节估算一个 token 再加固定开销；安全余量另行扣减，不包含在 E 中。字节扫描不复制整个正文，非法 Unicode/NUL、负数或 checked long 溢出均拒绝。计算没有网络、模型或持久化副作用。

`user: A` 与 `assistant: 中😀` 的 B=21、N=2；P=512、M=2、F=3 时 E=18。两条 `user: A` 的 B=10、N=2，同参数 E=12，逐消息取整会错误得到 13。所有 system 指令、历史、材料说明、材料 JSON 身份字段及其转义内容、原问题均参与；HTTP 外层再次转义、model 参数、认证头及密钥不参与。

这是字节启发式，不能证明真实 token 上界。真实模型接入后，部署方需核对窗口、消息模板、兼容性和真实 usage，用样本调整 P/M/F/余量。当前默认值及离线替身不能证明真实服务已校准，实际 token 可能超过估算或预留。

app 只提供当前可见且 COMPLETED 的完整历史及完整来源依赖。AI 默认最多 8 对历史，按序保留 user/assistant
角色，不提炼摘要。查询组合最近最多两条合格原用户问题及本轮原问题，以换行分隔，限 8000 code
point；如“任务租约”后问“它过期后怎么办”。组合只用于召回，存储与最后 user 消息仍是原问题。无历史只用当前问题，不再调用模型改写。合法成功空列表使用固定 NO_RESULTS；召回拒绝、故障或非空但全部缺少有效版本材料时以 SEARCH_UNAVAILABLE 失败，不封存 NO_RESULTS，不用旧答案绕过检索。

输入选择与发送前复核共用 `BudgetGuard`，对本轮能力快照采用同一容量公式：

```text
count <= min(window, reservation) - maxOutput - safetyMargin
```

默认预留 12000、输出上限 2048、估算余量 512 时，窗口至少 12000 才有 9440 的估算输入容量：9440 可通过，9441 必须裁剪或拒绝。窗口 10000 时容量为 7440；调大窗口不能突破预留。verified 的附加余量为零，输入来自严格策略；estimated 使用字节估算 E。类型不一致、配置变化或无效计算均拒绝，不能解释为零输入。

另有默认 24000 code point 输入保护，以及独立请求字节限制。预算优先整对移除最旧历史及其全部依赖，再从末尾按 Unicode code point 对半裁剪新材料；每次裁剪后重新渲染并计数，原问题不截断。裁剪后正文为纯空白的材料会被移除；仍有有效材料时继续预算检查，全部失效时按预算不足拒绝。`Plan` 保存能力快照，生成前检查一致性，内置适配把复核过的同一 `PreparedChatRequest` 对象直接交给传输。来源默认最多 256 个，不能删来源后保留对应历史正文。

有效材料被 token 预算或字符保护裁空，或原问题与必要指令无法容纳时，报告固定 `INPUT_BUDGET_EXCEEDED`。响应前为 HTTP 400，流已建立时为 error 事件和固定安全消息；不封存 NO_RESULTS，不保存错误文案为助手正文，不发布被裁材料引用，也不调用模型。真实空检索仍是 NO_RESULTS 成功，非空但缺少有效版本材料仍是 SEARCH_UNAVAILABLE。现有前端对新增错误码按通用失败展示并核对历史。

source seal 使用最终 Plan 的父轮与新材料，app 再验证并保存实际来源传递并集。SSE citation 只列本轮实际新材料，保留固定
document/version/chunk 及裁剪标记，不把继承来源声明为逐句证据。无 versionId 的旧材料不传模型，也不推断当前版本。

最终累计 usage 只取明确确认的最后值，不求和。模型调用意图先持久化；无可靠最终统计为 UNKNOWN，实际 token 字段
null，chargedTokens 独立为预留。尚未保存调用意图为 NOT_INVOKED、真实及计费均零；KNOWN 保留真实超估算、超预留统计，不截断隐藏费用。估算值不填入 promptTokens/completionTokens，不作为费用硬上限证明。

输入选择失败发生在 `app.dispatch` 之前，沿用 FAILED、NOT_INVOKED/零 token 结算与并发释放，不返还请求次数。调用意图已保存后，即使最后发送复核失败且本地未发送 HTTP，也继续按 UNKNOWN 和预留结算，不能改成 NOT_INVOKED。上游拒绝或截断按原失败规则处理，不自动删参数、切模式、扩大窗口或重新生成。收尾暂时失败、取消竞争继续使用既有固定结算意图、重试与过期恢复机制。

回退到 `KB_CHAT_BUDGET_MODE=verified` 并重启后，有匹配严格策略则继续生成，无策略则新生成 503；也可关闭模型适配。已有匹配重放优先于预算就绪检查，不重新领取配额、检索或生成。历史和待结算意图保持可核对。

检索独立使用 `kb.ai.retrieval.timeout-ms=10000`、`max-response-bytes=4194304`；模型继续使用原 `kb.ai.upstream-timeout`。`retrieve-top-k` 必须为 1–200，出站候选数封顶 200。检索失败不调度 Chat 模型，多轮复用 FAILED 与 NOT_INVOKED/零 token 结算；不退还请求次数窗口，且不代表 Embedding/rerank 供应商未收费。FAILED 与结算 PENDING/REVIEW 是两个独立事实。

## 离线验证记录（2026-11-02）

Windows PowerShell 下执行以下显式白名单，30 个类、189 项独立单元测试通过：kb-ai 186 项、kb-common 3 项，失败、错误、跳过均为 0。

```powershell
$tests = @(
    'BudgetValueTest', 'BudgetGuardTest', 'MultiTurnBudgetCompatibilityTest',
    'ChatBudgetConfigurationTest', 'ConfiguredEstimatedTokenBudgetTest',
    'ChatModelPropertiesTest', 'ChatModelConfigurationTest', 'ChatPromptRendererTest',
    'ChatTokenBudgetTest', 'OpenAiMultiTurnModelTest', 'OpenAiSseDecoderTest',
    'OpenAiChatTransportTest', 'ChatModelLimitsTest', 'ChatModelLoggingTest',
    'OpenAiChatModelClientTest', 'ConversationBudgetTest', 'MultiTurnChatServiceTest',
    'MultiTurnControllerTest', 'MultiTurnReplayControllerTest', 'ConversationSettlementTest',
    'ConversationAppClientTest', 'UsageSettlementTest', 'ConversationSettingsTest',
    'ChatServiceAdapterTest', 'ChatServiceLifecycleTest', 'ChatControllerTest',
    'ChatObservationTest', 'AiObservedChatTest', 'NoDatabaseAccessTest', 'ReactiveStackTest'
)
mvn -o -pl kb-ai -am test "-Dtest=$($tests -join ',')" '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
mvn -o -pl kb-ai -am test-compile '-DskipTests' '-Dstyle.color=never'
openspec validate configurable-multi-turn-token-budget --strict
```

白名单执行和 test-compile 均为 `BUILD SUCCESS`；严格校验输出 `Change 'configurable-multi-turn-token-budget' is valid`。本地日志为 `kb-ai/target/multi-turn-budget-tests.log` 和 `kb-ai/target/multi-turn-budget-compile.log`。首次离线执行因本地缺少 Knife4j WebFlux 4.5.0 及其 Springdoc 依赖而未执行测试，联网补齐既有构建依赖后，上述最终命令均已离线通过，POM 未修改。

覆盖值类型与旧接口兼容、模式隔离、完整配置绑定（含 YAML 数字类型、驼峰别名和环境占位符不能截断浮点）、UTF-8 手算、材料包装与转义、溢出、冻结参数、9440/9441 边界、两处复核、历史成对淘汰、Unicode 裁剪、材料裁空及取消/收尾竞争。真实估算器和渲染器在纯内存对象链路中验证：9440 保留，9441 裁剪后按相同请求复核，传输使用 Mock。

新增用量回归发现并修正 done 数据提前生成的问题：现在在终态与结算完成后生成事件，KNOWN 超预留统计完整返回，UNKNOWN 的 null token 字段不再因自动拆箱而转成 error。输入选择失败为 NOT_INVOKED/零 token；意图已保存后复核失败为 UNKNOWN/预留，匹配重放不重新执行。

核验发现材料裁剪后可能仅剩空白却仍被保留，已在裁剪时移除这类材料。新增 10 项回归覆盖 VERIFIED/ESTIMATED 的 token 与字符限制、Unicode 空白、剩余有效材料及来源身份保留、真实估算器的 407/402 边界，以及无封存/引用/生成和零 token 结算。修复前这 10 项均失败；修复后三个相关测试类的 53 项及上述完整白名单的 189 项全部通过。复现与修复后的定向测试日志分别为 `kb-ai/target/multi-turn-budget-whitespace-before.log`、`kb-ai/target/multi-turn-budget-whitespace-after.log`。

人工对照 proposal、两份 delta spec、design、配置及单测结果确认默认 verified、六项参数范围、INPUT_BUDGET_EXCEEDED 与实际 usage 口径一致；未编写或运行文档内容断言。源码范围核对未新增供应商/tokenizer 依赖、数据库迁移或公开历史/账本字段。测试没有启动 Spring 容器、前端、监听服务、数据库、Redis、MinIO 或真实模型；配置工厂及条件声明测试不证明实际容器自动装配。本次完成估算功能及离线验证，真实供应商兼容、窗口真实性、参数校准和生产验收仍需后续接入时完成。
