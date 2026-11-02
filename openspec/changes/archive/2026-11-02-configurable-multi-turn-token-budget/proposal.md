# Proposal

## Why

现有 OpenAI-compatible 文本协议已完成，但多轮生成仅接受经具体模型核验的 token 上界；当前没有生产预算策略，配置模型接口后仍返回 503。用户已选择先交付供应商无关的通用估算模式，真实模型后续接入，并明确接受实际 token 可能超过估算值，因此需要让这一选择在配置、能力声明、裁剪和用量处理上保持一致。

## What Changes

- 为内置 OpenAI-compatible 多轮适配新增显式 `estimated` 预算模式，按最终消息的 UTF-8 字节数、可配置换算比例、消息开销和安全余量进行纯本地估算；模型窗口由部署方填写，不猜测供应商或 tokenizer。
- 默认继续使用 `verified` 模式及既有 `ChatTokenBudget` 扩展。未提供匹配严格策略时保持不可用；严格策略异常或不匹配不会自动降级到估算。
- 在多轮契约中明确区分 `ESTIMATED`、`VERIFIED` 和不可用状态，兼容已有严格预算自定义实现；估算值不得通过上界接口伪装为已验证值，也不得写成实际 usage。
- 输入选择与发送前复核共用预算判定：输入计数加输出上限及适用余量，不超过配置窗口与单轮预留的较小值。保留字符/字节限制、完整问答对淘汰、Unicode 安全裁剪和原问题不截断。
- 修正非空有效检索材料被预算裁空后的处理：以固定 `INPUT_BUDGET_EXCEEDED` 错误失败，不调用模型、不封存 `NO_RESULTS`；真正的空检索结果沿用原行为。
- 复用现有真实用量与额度结算：KNOWN 完整记录供应商最终 usage，即使超过预留；已有调用意图但无确认统计时保持 UNKNOWN 并按原规则结算预留；输入选择阶段拒绝且未形成调用意图时为零 token 结算。
- 更新配置、操作说明及独立单元测试，明确估算模式的启用与回退、兼容范围和未联调事实。

## Capabilities

### New Capabilities

无；预算策略属于既有模型适配与多轮编排能力。

### Modified Capabilities

- `chat-model-adapter`：新增显式估算模式、确定性计数契约、配置校验与模式隔离，修订目前仅允许可信上界启用多轮的要求。
- `multi-turn-chat`：让受理及裁剪识别预算类型，统一输入/输出/余量判定，明确材料因预算耗尽而清空的失败语义及旧扩展兼容。

## Impact

- 主要代码范围为 `kb-ai` 的 `model`、`conversation`、配置校验与相关测试，复用 `PreparedChatRequest`、`ChatPromptRenderer`、`ConversationBudget` 和现有结算逻辑。
- 文档范围为 README、`docs/chat-model-adapter.md`、`docs/conversation-model-budget.md`、`docs/conversation-configuration.md`；主规范在后续同步或归档时更新。
- 不新增公开模型代理、客户端模型选择或预算配置 API，不变更 SSE 事件类型、历史及账本字段，不新增数据库迁移或前端页面。
- 不增加供应商 SDK、tokenizer 依赖或在线计数请求；不接入真实供应商，不把协议兼容声明当作所有模型均可用或预算已核验的证明。
- 本次只规划和实现估算功能及离线验证，真实模型接入、参数校准、供应商兼容和生产端到端验收另行开展。
