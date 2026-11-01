# Proposal

## Why

`kb-ai` 已具备授权检索、流式编排、会话保存和额度结算，但没有真实聊天模型实现：旧单轮入口可返回占位回答，多轮入口因缺少模型能力返回 503。供应商尚未确定，先交付独立于厂商 SDK 的 OpenAI-compatible 文本聊天适配层，并保留可信 token 预算门禁，避免为了接通接口而虚构生产多轮能力。

## What Changes

- 新增服务端显式启用的 Chat Completions 配置与 WebClient 流式客户端，支持模型地址、密钥、模型名称、输出限制及有界资源配置；默认关闭，不选择默认供应商。
- 同一协议层适配 `ChatModelClient` 与 `MultiTurnModel`，复用现有授权材料、历史角色和客户端 SSE 协议，不新增外部聊天入口。
- 严格区分正常结束、输出截断、拒绝、不支持的输出与异常断流；已启用适配器的调用失败不得退回占位回答，不自动重试或切换供应商。
- 只使用经流终结确认的累计 usage，缺失时沿用 UNKNOWN 语义；不改变旧单轮与多轮各自的额度结算规则。
- 独立提供可绑定具体模型与消息格式的 token 预算扩展接口。本期不实现任何生产 tokenizer 或字符估算兜底；没有经核验预算策略时，多轮新生成继续在受理前返回 503，不创建轮次或调用模型。
- 补充仅依赖进程内模拟传输、受控数据流和虚拟时间的配置、消息、SSE、取消、用量及编排兼容测试，交付配置和回退说明。

## Capabilities

### New Capabilities

- `chat-model-adapter`：服务端受控的 OpenAI-compatible 普通文本生成协议、消息构造、流式生命周期、用量转换及模型预算扩展契约。

### Modified Capabilities

无。既有 `multi-turn-chat` 的角色、来源、预算、幂等和 503 门禁，以及 `ai-usage-audit` 的真实用量与结算分离要求保持不变；新规格只补充具体适配层应如何满足这些契约。

## Impact

- 主要实现位置为 `kb-ai/src/main/java/com/zhishiku/kb/ai/model/`（拟新增），桥接既有 `chat/` 和 `conversation/` 接口；配置位于 `kb-ai/src/main/resources/application.yml`，测试位于 `kb-ai/src/test/java/`。
- 实施阶段更新模型接入与多轮预算说明；现有前端、网关路由、数据库表、迁移、授权检索和结算协议不改动。
- 复用现有 WebFlux、Reactor Netty、Jackson 和测试依赖，不引入厂商 SDK、模型框架、数据库访问或 Servlet 栈。
- 首期不包含供应商选型、生产预算策略、真实服务联调、部署、工具调用、多模态、思考过程输出、模型故障转移或生成自动重试。
- 通用协议适配完成不等于生产多轮可用；实际模型的协议兼容、上下文窗口、token 预算和取消行为仍须在后续选型与接入中单独核验。
