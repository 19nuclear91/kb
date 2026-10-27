# Proposal

## Why

当前 `GET /api/search?q=...` 使检索词进入请求 URL；AI 服务也以该方式调用检索，并在失败时记录原始异常文本。URL
和异常内容可能被应用、网关或代理日志保存，与项目对查询词保密的要求不一致。

## What Changes

- **BREAKING** 将 `/api/search` 改为仅接受 POST JSON；停用 GET，返回 HTTP 405。检索词、候选数和结果数从请求体传入，保留现有服务端身份解析、授权检索及响应字段语义。
- AI 服务改用 POST 请求体调用同一授权检索入口，不将检索词放入 URL；会话令牌透传、召回失败时的既有对外行为及引用契约保持一致。
- 检索和问答请求相关的异常日志改为只记录固定阶段与安全的错误类别或状态，不输出原始异常文本、堆栈、查询词或上游响应体。
- 更新接口文档、示例与回归测试，并明确核查网关及前置代理访问日志的部署要求。

## Capabilities

### New Capabilities

- `search-query-confidentiality`: 规定检索请求的无查询词 URL 契约、旧 GET 的拒绝行为，以及检索和问答异常日志中的敏感信息边界。

### Modified Capabilities

无。现有 `citation-provenance` 的响应字段及引用语义不变。

## Impact

- API：`GET /api/search` 的调用方必须改用 `POST /api/search`；HTTP 方法发生不兼容变更，响应 JSON 保持原有结构。
- 代码：`kb-app` 搜索控制器及相关异常处理、`kb-ai` 召回客户端和问答异常日志；相关单元与接口测试。
- 文档与部署：OpenAPI、README、引用说明中的搜索调用示例，以及网关和前置代理的访问日志核查清单。
