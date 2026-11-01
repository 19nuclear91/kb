# Tasks

本清单实施用户确认的 A 方案。供应商与生产 tokenizer 均不在本期任务内；完成本清单不能被解释为生产多轮已启用。测试范围仅限独立单元测试：被测对象的协作依赖使用 Mock 或内存替身，模拟传输不监听端口，不串联多个真实业务组件。

不编写或执行集成测试、Spring 容器装配测试、跨组件联合测试或端到端测试；不启动应用、HTTP 服务、DB、Redis、MinIO、Docker、浏览器或真实模型。保留文档编写和验证结果记录，但不为 README、Markdown、注释或文档示例编写内容、格式、文案及示例执行测试。

## 1. 服务端配置与安全校验

- [x] 1.1 在 `kb-ai/.../model/` 定义 `ChatModelProperties`，实现默认关闭、必填字段、两个输出字段枚举、usage 选项及设计中的资源参数；以 `ChatModelPropertiesTest` 覆盖默认值、非法值、单轮输出与预留关系、连接/空闲与总期限关系，关闭配置不要求模型凭据。
- [x] 1.2 实现 API 根地址规范化、路径追加和启用时的生产校验，避免配置类型或错误打印秘密；以配置单测验证 `/v1/` 路径保留、重复接口路径拒绝、生产 HTTP 拒绝、显式 dev HTTP 允许、userinfo/query/fragment/控制字符拒绝及错误中无原始敏感值，不进行网络探测。
- [x] 1.3 在 `kb-ai/src/main/resources/application.yml` 加入外部注入且默认关闭的模型配置，并创建 `docs/chat-model-adapter.md` 的配置、范围和安全章节；通过内存环境下的独立属性绑定测试及直接调用校验器验证 prod/dev 参数与失败行为，不创建 Spring 容器。文档交付说明无默认供应商、密钥不进前端、本期多轮仍需可信预算，不编写文档内容或文案断言。

## 2. 同源消息渲染与有界请求

- [x] 2.1 实现 `ChatPromptRenderer` 和可供预算与传输共用的 `PreparedChatRequest` 视图，固定消息格式版本；以 `ChatPromptRendererTest` 验证 system 来源、历史角色与顺序、本轮原问题仅一次、材料作为数据消息且版本未知不补全、未新增或二次裁剪材料。
- [x] 2.2 实现单候选流式请求、显式输出限制字段及可省略的 usage 选项，并使用有界序列化限制请求字节；以渲染单测覆盖中文、补充字符、引号和伪造角色文本、未知字段拒绝、仅发送一种 token 字段、usage=false 时省略 stream_options，以及首个超限写入即失败且不发送 HTTP。
- [x] 2.3 补充 `docs/chat-model-adapter.md` 的消息格式和参数兼容章节；以本组渲染测试固定消息与请求的结构契约，说明提示词分隔并非访问控制、不能配置任意供应商参数、不能通过删参重试探测兼容性，不增加文档内容测试。

## 3. SSE 解码、终结确认与累计用量

- [x] 3.1 实现有状态的有界 SSE 解码和严格 JSON 解析；以 `OpenAiSseDecoderTest` 覆盖跨包 UTF-8、一个分包多事件、LF/CRLF/CR、多行 data、注释、空增量、非法 UTF-8、孤立代理项、NUL、重复键、尾随文档、深度及单事件越限；在 CR 事件分隔空行处统一保守预留 1 字节 LF，验证首段正文无需等待完整回答即可交付。
- [x] 3.2 实现 `STREAMING -> STOP_SEEN -> DONE` 正常终结和异常转换；以解码单测验证正文与 stop 同帧、stop 后 usage、stop 与 [DONE] 缺一、未结束帧 EOF、stop 后继续出字、空回答及 length/content_filter/未知原因，只有受支持且通过终结帧字节限额与 CR 保守预留检查的完整终结链正常完成。
- [x] 3.3 实现唯一候选及支持输出类型校验；以解码单测覆盖多个候选、非零索引、非 assistant 角色、非文本 content、拒绝、tool_calls/function_call、非空 reasoning_content/reasoning/thinking，确认不会把不支持载荷作为正文交付。
- [x] 3.4 实现最终累计用量候选、正文后候选失效和 [DONE] 后单次确认；以解码单测覆盖累计快照不累加、0/0、缺省/null usage、缺一项、小数、负数、Long 溢出、中途统计后继续出字，以及终结帧越限、异常或取消前不提前发送 FinalUsage。
- [x] 3.5 补充 `docs/chat-model-adapter.md` 的流终结、截断与 UNKNOWN 说明；以本组测试明确严格 stop/[DONE] 兼容边界、部分回答不等于完整回答、无 usage 不代表实际为零，并记录不支持的生成形态，不运行真实模型请求。

## 4. 单次出站传输、期限与资源释放

- [x] 4.1 实现隔离的 `OpenAiChatTransport`，配置 Bearer 认证、明确 HTTP 200/SSE 判定及禁止重定向和连接自动重试；以 `OpenAiChatTransportTest` 捕获请求验证地址和头部，模拟 3xx/401/429/5xx/错误媒体类型，确认不透传 satoken、Cookie、内部签名，且不重发或换参数。
- [x] 4.2 实现延迟发送与同一执行流的单次订阅保护；以受控传输验证未订阅零请求、首次订阅一次、二次或并发重复订阅被拒、取消向上游传播，以及心跳或清理不导致额外订阅，不以 cache/replay 保存正文。
- [x] 4.3 实现连接等待、语义空闲与复用 upstream-timeout 的绝对总期限；以 `ChatModelLimitsTest` 和虚拟时间验证无首段、stop 后等待 [DONE]、持续注释、持续慢速正文、正常完成与期限竞争，期限触发必须是错误且清理定时器，不伪装正常 EOF。
- [x] 4.4 实现累计响应和回答上限、非成功正文取消或有界释放、固定安全异常；以限界与 `ChatModelLoggingTest` 覆盖大量小帧、超大错误体、传输异常含秘密、取消/错误/成功的缓冲引用计数归零及不二次消费正文，验证日志和异常不含密钥、正文、完整 URL 或远程错误体。
- [x] 4.5 补充 `docs/chat-model-adapter.md` 的超时、限额与故障操作章节；通过本组模拟传输及虚拟时间用例核对设计中的限制分别生效，说明不自动重试、断连不证明远端停止计费、当前测试不能证明实际网络连接池与供应商行为，不测试说明文案。

## 5. 可信预算扩展接口与缺省拒绝

- [x] 5.1 定义 `ChatModelIdentity` 与 `ChatTokenBudget` 的模型/格式匹配、核验窗口和输入上界契约，共用第 2 组消息视图；以 `ChatTokenBudgetTest` 使用仅在测试目录的替身验证指令、历史、材料、问题和包装全部纳入、重复计算不发网络请求且不改变发送消息。
- [x] 5.2 实现预算策略匹配与无匹配/多匹配处理，不提供生产估算器、虚假就绪开关或 dev 假策略；以单测验证模型地址、模型名称、消息格式变化不复用旧策略，多匹配拒绝，不匹配不能靠任意窗口值启用，异常或负上界仍被拒绝。
- [x] 5.3 更新 `docs/conversation-model-budget.md` 并补充模型接入文档的扩展边界，说明未来策略需核验部署、tokenizer 和消息包装，以及单轮字符/字节/输出限制不等于总 token 预算；以本组测试确认替身只存在于测试装配，不将供应商选型或生产预算实现列为本变更完成前置。

## 6. 单轮、多轮桥接与条件装配

- [x] 6.1 实现 `OpenAiChatModelClient` 的 stream/streamEvents 桥接，保留已选材料且两种方法不重复调用生成；以 `OpenAiChatModelClientTest` 验证文本与尾部 usage 次序、Integer 精确转换、溢出失败、无 usage 不合成零值，以及真实协议失败不变为正常完成。
- [x] 6.2 实现 `OpenAiMultiTurnModel` 的消息、Long usage 和预算委托；以 `OpenAiMultiTurnModelTest` 验证无策略时 supportsTokenUpperBound=false、窗口为 0、直接 generate 也不请求模型，并使用匹配的测试预算替身验证多轮上限与最终事件映射，不装配任何生产预算实现。
- [x] 6.3 完成 `ChatModelConfiguration` 的启用条件、两个接口分别退让自定义 Bean 以及预算扩展绑定；通过编译及 `ChatModelConfigurationTest` 对配置校验器和工厂方法的直接调用验证构造参数、返回类型及非法配置分支，协作依赖全部使用 Mock 或内存替身。不启动 Spring 容器、不串联真实桥接与传输，也不将这些单测表述为容器自动装配已验收。
- [x] 6.4 更新 `docs/chat-model-adapter.md`、`docs/conversation-model-budget.md` 和 README 中直接相关的当前状态、启用及回退说明，交付“可配置真实单轮、无生产预算多轮仍 503”的说明；保留原公共 SSE/用量字段和两类结算规则，明确关闭后可能恢复旧单轮占位而不声称真实模型恢复。此项仅交付文档，不编写文档类测试。

## 7. 独立单元测试回归与交付核对

- [x] 7.1 仅执行下方显式白名单中的独立单元测试，分别覆盖配置、消息、解码、传输、预算、桥接及既有编排和架构约束；依赖均采用 Mock 或内存替身，不做跨组件联合验证、不启动 Spring 容器、不监听端口、不使用全量测试自动发现。记录实际用例数、失败/跳过和异常，不运行需要外部组件或针对文档的测试。
- [x] 7.2 执行 `mvn -pl kb-ai -am package '-DskipTests' '-Dstyle.color=never'`，检查 kb-ai 可打包且 NoDatabaseAccessTest、ReactiveStackTest 已在前项通过；核对无新增数据库迁移、前端/网关协议变更或厂商运行时依赖，发现越界变更时先停止而非扩展本期范围。
- [x] 7.3 执行 `openspec validate openai-compatible-chat-adapter --strict`，在模型接入文档记录白名单命令、结果及明确未验证范围；核对任务实现与规格场景对应、无生产预算替身、无真实请求或密钥落盘，不把离线通过写成供应商兼容或生产多轮验收完成。

第 7.1 项计划在项目根目录使用以下 PowerShell 命令；新增测试类名按本清单约定，如实施中合理改名须同步精确白名单与记录：

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
mvn -pl kb-ai -am test "-Dtest=$($tests -join ',')" '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'
```

## Workflow follow-up

- 用户另行发起实施后才执行上述编码与测试；本次提案创建只生成规划文件。
- 具体供应商兼容验证、真实模型 tokenizer/预算扩展、真实网络取消和生产部署应独立排期，不作为本变更中假装完成的任务。
- 实施完成后由用户另行发起实现核验与归档，不在任务进度中自动包含归档操作。
