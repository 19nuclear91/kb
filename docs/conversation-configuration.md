# 多轮配置与部署顺序

所有服务默认 `kb.conversation.enabled=false`。app 与 AI 使用相同的 `kb.conversation.secret`（至少 32 UTF-8 字节），由环境注入；生产拒绝含
dev/example 的开发密钥。不把密钥写入数据库、日志或公共响应。

公共配置前缀为 `kb.conversation`，可使用对应的 `KB_CONVERSATION_*`
环境变量。默认值：signature-window-seconds=60、nonce-ttl-seconds=180、lease-seconds=120、renewal-ms=30000、history-pairs=8、max-sources=256、batch-codepoints=1024、batch-ms=1000、max-answer-codepoints=64000、max-question-codepoints=8000、max-context-codepoints=24000、max-output-tokens=2048、reservation-tokens=12000、settlement-retry-seconds=3600、settlement-dedup-seconds=86400、work-batch-size=20、cleanup-timeout-ms=5000。

AI 的 reservation-tokens 必须等于既有 kb.ai.max-tokens-per-request；历史与材料输入、输出上限及适用安全余量共同占用这份预留。续期小于执行租约的一半；nonce
TTL 覆盖整个时间窗口；结算去重期长于重试期并保留余量。启用时 app/AI 在建立连接前校验，关闭时不额外要求密钥。

内置 OpenAI-compatible 模型的预算前缀为 `kb.ai.chat-model.budget`：

| 属性 | 默认 | estimated 范围 / 单位 |
| --- | --- | --- |
| `mode` | verified | 仅 verified、estimated，小写 |
| `context-window-tokens` | 0（未配置） | 必填 1–2147483647 token |
| `tokens-per-kib` | 1024 | 1–65536 token / 1024 UTF-8 字节 |
| `per-message-tokens` | 16 | 0–65536 token / 消息 |
| `request-overhead-tokens` | 64 | 0–65536 token / 请求 |
| `safety-margin-tokens` | 512 | 0–2147483647 token |

所有数值使用整数，且 `min(window, reservation) > maxOutput + margin`。多轮 maxOutput 来自 `kb.conversation.max-output-tokens`，与旧单轮 `kb.ai.chat-model.single-max-output-tokens` 独立。配置窗口必须由部署方按真实模型填写，估算能力不证明真实 token 一定小于该窗口或预留。

六个 `KB_CHAT_BUDGET_*` 环境变量及完整启用示例见[聊天模型接入](chat-model-adapter.md)。先保留默认 verified；需要估算时显式设 `KB_CHAT_BUDGET_MODE=estimated` 和实际窗口，再重启 AI。回退设 `KB_CHAT_BUDGET_MODE=verified` 并重启，有严格策略时按原行为继续，无策略时新生成返回 503；已存在轮次可继续核对，不重新领取或生成。

生产顺序：保持关闭，发布 app 与 V18 迁移，再发布 AI/网关并配置匹配密钥、TLS 和支持多轮的模型适配器；选择严格预算或显式估算并核对其参数后，最后启用。此处仅交付部署说明，本次未执行迁移、部署或真实组件验证。

