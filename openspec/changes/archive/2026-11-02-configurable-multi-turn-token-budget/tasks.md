# Tasks

实施依据为本变更的 `design.md`、`specs/chat-model-adapter/spec.md` 和 `specs/multi-turn-chat/spec.md`。本清单交付显式通用估算模式；实际供应商、真实窗口与校准参数是后续部署输入，不阻塞下面的离线开发，也不被视为本清单已验证。

## 1. 预算类型与旧扩展兼容

- [x] 1.1 在 `kb-ai` 定义预算类型、能力快照和带类型的输入计数，明确 UNAVAILABLE / VERIFIED / ESTIMATED、窗口及余量约束；用独立值类型测试验证非法类型组合、负数、零窗口和不可变性。
- [x] 1.2 为 `MultiTurnModel` 新增兼容默认入口，保留原严格预算方法并把旧实现映射为 VERIFIED 或不可用；新增 `MultiTurnBudgetCompatibilityTest`，验证原严格实现无需新增方法、零额外余量、不支持时不计算、估算实现不能从旧上界接口放行。
- [x] 1.3 引入内部 `ChatBudgetPolicy`，让既有 `ChatBudgetBinding` 提供严格包装，保留 `ChatTokenBudget` 外部契约；扩展 `ChatTokenBudgetTest` 验证身份不匹配、重复策略、异常及负数仍拒绝，任何失败均不调用估算策略。
- [x] 1.4 更新 `docs/conversation-model-budget.md` 的能力类型和旧扩展兼容说明；交付与本组接口一致的说明，结合本组单测核对，不新增文档文字断言。

## 2. 估算配置与启动校验

- [x] 2.1 在 `ChatModelProperties` 及 `application.yml` 增加 `kb.ai.chat-model.budget` 和设计中的六个环境变量，固定 verified 默认、窗口未配置状态及 P/M/F/余量默认值；以属性绑定单测验证默认值、显式覆盖和未知字段/模式。
- [x] 2.2 扩展 `ChatModelEnvironmentValidator` 与相关配置校验，检查 estimated 参数整数范围、窗口必填、输出加余量容量、long 算术及既有预留一致性；用内存 Environment 单测覆盖边界、差一单位、浮点/超范围值及错误脱敏，不创建业务连接。
- [x] 2.3 在模型装配路径按显式 mode 选择策略，保留自定义单轮/多轮模型的独立优先及显式配置校验；扩展 `ChatModelConfigurationTest`，验证默认严格模式无估算器、estimated 不调用严格计数、严格失败无回退、自定义模型未被覆盖和适配关闭零调用。
- [x] 2.4 更新 `docs/chat-model-adapter.md` 与 `docs/conversation-configuration.md` 的配置表、单位和启用/回退示例；交付全部六个配置键及其范围说明，明确模型窗口需由部署方填写、多轮输出不使用旧单轮输出配置，以本组绑定用例核对示例参数。

## 3. 确定性 UTF-8 估算器

- [x] 3.1 实现 `ConfiguredEstimatedTokenBudget`，从 `PreparedChatRequest.messages()` 读取 role/content，按总 UTF-8 字节及 `ceil(B*P/1024)+N*M+F` 返回 ESTIMATED；新增可手算单测，覆盖英文、中文、emoji、所有消息角色、N=2/B=21/E=18 示例及跨消息只取整一次。
- [x] 3.2 实现线性 UTF-8 长度计算、非法 Unicode/NUL 拒绝和 checked long 运算，冻结配置及本地身份关联；通过独立单测验证参数最大边界、乘加取整溢出路径、重复调用一致、输入未修改、诊断无正文或密钥，且不调用网络或持久化协作者。
- [x] 3.3 扩展 `ChatPromptRendererTest` 与估算器测试，分别确认实际材料说明、JSON 身份字段和内容转义参与计数，HTTP 外层传输转义和凭据不参与；验证同一准备消息的结构和手算结果，避免仅断言与实现相同的计算过程。
- [x] 3.4 更新 `docs/conversation-model-budget.md` 的算法、单位、手算示例和校准说明；交付明确区分字节启发式与真实 token 上界的文档，以 3.1 的固定期望值复核示例，不运行真实模型。

## 4. 裁剪与发送前预算复核

- [x] 4.1 实现共享预算判定器，校验类型一致性、合法计数及 `count <= min(window,reservation)-output-margin`；新增边界测试，覆盖 9440/9441、窗口小于预留、余量只扣一次、Long 极值及非法能力。
- [x] 4.2 调整 `ConversationBudget.ready/select` 使用新预算入口与本轮能力快照，在每次完整历史淘汰或 Unicode 材料裁剪后重新计算；扩展 `ConversationBudgetTest` 验证两种模式、原问题不截断、来源同步、字符限制及旧严格扩展行为。
- [x] 4.3 调整 `OpenAiMultiTurnModel` 的内置策略桥接和发送前复核，复用共享判定并把复核过的请求直接交给 transport；扩展 `OpenAiMultiTurnModelTest`，验证类型与配置一致、精确边界、超限零请求、未订阅零请求、重复订阅拒绝及发送内容未被再次改写。
- [x] 4.4 更新 `docs/conversation-model-budget.md` 的预算公式、默认 9440 估算输入容量条件及两处复核规则；交付与 4.1–4.3 边界测试一致的说明，并保留现有独立字符/字节限制。

## 5. 预算不足错误与结算语义

- [x] 5.1 增加固定预算不足类别，在有效材料因预算或字符保护被裁空、或必需输入无法容纳时拒绝；扩展 `ConversationBudgetTest` 验证不会返回可封存 NO_RESULTS 的空材料 Plan，真实空检索和版本无效由原路径区分。
- [x] 5.2 调整 `MultiTurnChatService`、`ConversationExceptionHandler` 的错误映射：响应前 400 或流内 error，固定 `code=INPUT_BUDGET_EXCEEDED`；以控制器/编排独立单测验证错误无敏感内容、无成功正文/done/被裁材料引用、无模型调用，真实 NO_RESULTS 与 SEARCH_UNAVAILABLE 行为不变。
- [x] 5.3 扩展 `MultiTurnChatServiceTest` 与重放测试，以 Mock 协作者核对输入选择阶段预算失败尚无调用意图，进入 FAILED、NOT_INVOKED/零 token 结算，收尾故障与取消竞争沿用恢复机制；验证预算配置不可用时匹配重放仍优先且不再次检索、领取或生成。
- [x] 5.4 为估算模式补齐真实 usage 超估算/超预留、缺失最终 usage 及上游拒绝的独立回归，复用 `UsageSettlement` 和现有结算契约；验证 KNOWN 完整保留、UNKNOWN 按预留、无调用意图的 NOT_INVOKED 为零，并覆盖意图已保存后复核失败仍为 UNKNOWN 的情况；无自动删参、切模式或生成重试，不修改数据库结构和账本字段。
- [x] 5.5 更新 README 当前状态及 `docs/chat-model-adapter.md`、`docs/conversation-model-budget.md` 的错误、实际用量、回退和验证边界；交付 INPUT_BUDGET_EXCEEDED 说明，注明现有前端按通用失败展示，真实模型兼容与校准尚未完成，不编写文档内容测试。

## 6. 跨任务回归与交付核对

- [x] 6.1 执行本变更新增及受影响的独立单测白名单，包含预算类型/兼容/估算/判定器、新旧配置、渲染、`ChatTokenBudgetTest`、`OpenAiMultiTurnModelTest`、`ConversationBudgetTest`、多轮编排/控制器/重放、结算及日志测试；记录实际命令和结果，所有新增场景须通过，不以文档断言或真实外部服务代替单测。
- [x] 6.2 执行 `mvn -o -pl kb-ai -am test-compile '-DskipTests' '-Dstyle.color=never'` 验证模块编译，并核对本次不引入供应商/tokenizer 运行时依赖、不变更数据库迁移或公开历史/账本结构；如离线依赖缺失，记录具体阻塞，不能把未执行写为通过。
- [x] 6.3 执行 `openspec validate configurable-multi-turn-token-budget --strict`，对照 proposal、两份 delta spec、design、已完成测试与 docs 核对模式默认值、参数范围、错误码和真实用量口径一致；记录真实执行范围，不把估算功能完成宣称为真实模型生产验收。
