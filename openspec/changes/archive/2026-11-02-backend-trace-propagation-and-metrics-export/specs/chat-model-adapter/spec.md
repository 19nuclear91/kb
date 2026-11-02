# Spec Delta

## MODIFIED Requirements

### Requirement: 出站模型调用必须仅使用服务端受控地址与凭据

系统 MUST 仅从服务端配置取得模型 base URL、模型名称和 API Key，在保留 base URL 路径前缀的基础上调用 `POST /chat/completions`，使用 Bearer 认证。生产 MUST 使用 HTTPS，并拒绝地址中的用户信息、查询参数和片段。出站请求不得携带用户会话令牌、Cookie、内部服务签名、追踪传播头或 baggage，也不得自动跟随重定向。

#### Scenario: base URL 含版本路径
- **WHEN** 已配置的 base URL 路径为 `/v1/`
- **THEN** 请求路径为 `/v1/chat/completions`，不丢失版本前缀、不重复追加接口路径

#### Scenario: 上游要求重定向
- **WHEN** 配置地址返回重定向响应
- **THEN** 本次生成安全失败，不向重定向目标发送凭据或重发生成请求

#### Scenario: 身份凭据隔离
- **WHEN** 已认证用户经知识库触发模型生成
- **THEN** 模型请求只使用服务端模型密钥，不出现入站 satoken、Cookie 或内部签名

#### Scenario: 不安全生产地址
- **WHEN** 生产启用了 HTTP 地址或地址夹带凭据、查询参数、片段
- **THEN** 本地配置校验拒绝启动，不通过日志或异常回显原始地址与凭据

#### Scenario: 追踪上下文隔离
- **WHEN** 追踪已启用且一次生成触发模型调用
- **THEN** 模型请求不含 `traceparent`、`tracestate` 或 baggage
- **AND** 本地仍可记录该调用的耗时与结果类别

## ADDED Requirements

### Requirement: 模型调用必须提供低基数观测

系统 SHALL 为每次模型调用记录结果类别（完成、上游错误、协议错误、限额、超时、取消）、首个文本事件延迟、按模型总期限显式分桶的整次调用耗时、终结类别以及最终用量已知与未知的计数。标签 MUST 为固定枚举，MUST NOT 含模型密钥、问题、答案、材料或供应商原始错误。

#### Scenario: 正常完成
- **WHEN** 模型流正常终结并给出完整最终用量
- **THEN** 完成计数与用量已知计数各增加一次，并记录首个文本事件延迟与整次耗时

#### Scenario: 缺少最终用量
- **WHEN** 模型流正常终结但没有完整最终用量
- **THEN** 完成计数增加而用量未知计数增加，不记录伪造的零用量

#### Scenario: 限额或协议错误
- **WHEN** 调用因限额、协议损坏或超时安全失败
- **THEN** 对应固定类别计数增加，不含供应商错误体、问题或答案
