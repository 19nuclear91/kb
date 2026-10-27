# 多轮配置与部署顺序

所有服务默认 `kb.conversation.enabled=false`。app 与 AI 使用相同的 `kb.conversation.secret`（至少 32 UTF-8 字节），由环境注入；生产拒绝含
dev/example 的开发密钥。不把密钥写入数据库、日志或公共响应。

公共配置前缀为 `kb.conversation`，可使用对应的 `KB_CONVERSATION_*`
环境变量。默认值：signature-window-seconds=60、nonce-ttl-seconds=180、lease-seconds=120、renewal-ms=30000、history-pairs=8、max-sources=256、batch-codepoints=1024、batch-ms=1000、max-answer-codepoints=64000、max-question-codepoints=8000、max-context-codepoints=24000、max-output-tokens=2048、reservation-tokens=12000、settlement-retry-seconds=3600、settlement-dedup-seconds=86400、work-batch-size=20、cleanup-timeout-ms=5000。

AI 的 reservation-tokens 必须等于既有 kb.ai.max-tokens-per-request；历史与材料输入及输出上界共同占用这份预留。续期小于执行租约的一半；nonce
TTL 覆盖整个时间窗口；结算去重期长于重试期并保留余量。启用时 app/AI 在建立连接前校验，关闭时不额外要求密钥。

生产顺序：保持关闭，发布 app 与 V18 迁移，再发布 AI/网关并配置匹配密钥、TLS 和支持多轮及 token
上界估计的模型适配器，最后显式启用。此处仅交付部署说明，本次未执行迁移、部署或真实组件验证。

