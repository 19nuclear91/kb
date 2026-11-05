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

## 独立结算采样与有效开关

app 与 AI 分别导出 `kb_monitoring_feature_enabled{feature="conversation"}` 和 `{feature="ai_observation"}`，读取各自已绑定的 `kb.conversation.enabled`、`kb.ai.observability.enabled`。关闭显式为 0，缺失不等于关闭；两服务值不一致时保留各自事实，不能取最大值伪装为一致开启。这些维度不包含用户、部门、会话、轮次或资源标识。

app 始终装配持久结算采样，即使两个开关都关闭也继续观察 `kb_conversation_settlement`：

- `kb_conversation_settlement_pending`：全部 PENDING，包括 `next_attempt_at` 在未来的退避记录和已被领取、有当前租约的记录。
- `kb_conversation_settlement_review`：全部 REVIEW。
- `kb_conversation_settlement_oldest_pending_age_seconds`：PENDING 最早 `finalized_at` 至采样时刻的秒数；空集为 0，未来时间下限为 0。
- `kb_monitoring_sample_available{source="settlement"}` 与 `kb_monitoring_sample_last_success_timestamp_seconds{source="settlement"}`：首次成功前为 0；超时或失败仅使 available=0，保留旧值及旧成功时间，不能据旧零值判断健康。

一次采样只执行单个只读、单行聚合，WHERE 仅允许 PENDING/REVIEW；正常 RESERVED 与历史 SETTLED 排除，不关联 AI 观测、用户或正文，不领取、不锁业务行、不扣费、不自动处理 REVIEW。默认启动 30 秒后首次采样，此后 fixed-delay 30 秒；`kb.monitoring.sample-interval-ms` 可设 1000–3600000，`kb.monitoring.query-timeout-seconds` 默认每条 3 秒、可设 1–30。专用 JDBC 模板不更改业务配置；抓取只读不可变快照，不触发 SQL。

AI 观测页面及 `kb.ai.observation.unresolved` 是有采集覆盖边界的辅助视图，可能混入正常 RUNNING/RESERVED，不是结算权威来源。关闭新生成不会清空旧账本；页面没有对应记录不能解释为没有积压。结算告警先检查 settlement 来源可用性及新鲜度，再按同库部署的有效 app 副本 max 去重，不能 sum 或受功能开关静默。

本轮仅进行进程内配置、JDBC Mock 映射、可控时钟与并发 registry 抓取验证，人工审阅 SQL 谓词；未连接数据库、执行真实 SQL、迁移或部署，不以 Mock 证明 SQL 性能或线上结算状态。
