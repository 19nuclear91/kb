# Spec Delta

## MODIFIED Requirements

### Requirement: 检索仅通过 POST 请求体接收查询

系统 SHALL 仅通过 `POST /api/search` 的 JSON 请求体接收检索词 query、候选数 candidates、结果数 topK 及 search-filtering 定义的可选组合条件。成功响应 SHALL 保持既有 JSON 字段、授权过滤和排序语义；缺省数量与现有接口一致。URL 查询参数 MUST NOT 成为检索输入。未知字段、重复字段和尾随 JSON 必须返回 400，不静默忽略条件。请求须遵守 search-resilience 的字符、正文、数量、时间及容量限制；输入或容量被拒时不得以成功空列表替代错误。

#### Scenario: 合法检索请求
- **WHEN** 已登录且完成必需改密的用户以 POST JSON 提交非空 query、合法数量和可选筛选
- **THEN** 系统按该用户当前服务端身份和完整条件执行授权检索，并以既有响应结构返回结果
- **AND** 请求 URL 不含检索词、数量或筛选参数

#### Scenario: 缺省数量
- **WHEN** POST 请求体只包含合法的 query
- **THEN** 每路候选数默认为 50，topK 默认为候选数

#### Scenario: 无效请求体或 URL 参数
- **WHEN** POST 请求体缺少或提供空白 query、数量超出现有 1–200 范围、筛选非法，或 `/api/search` 的 URL 携带查询参数
- **THEN** 系统返回固定且不回显输入值的 HTTP 400 响应，不执行检索

#### Scenario: 调用方伪造身份或向量
- **WHEN** 请求体包含 userId、角色、密级、queryVector 或 profile 声明
- **THEN** 系统将其作为未知字段返回 400，不执行检索，也不将这些值作为身份、授权范围或查询向量

#### Scenario: 请求触发新增限界
- **WHEN** 查询字符、实际请求体或搜索并发超过配置上限
- **THEN** 分别返回约定的 400、413、429 或 503，不回显输入，不执行被拒请求的召回

### Requirement: 查询相关日志不得记录原始敏感内容

系统控制的业务应用、AI 服务及网关日志 SHALL 不记录检索词、问答问题、筛选值、候选名称查询、原始查询字符串、请求或响应正文、会话令牌，以及可能包含这些内容的原始异常文本或堆栈。此规则 MUST 同时覆盖 `/api/search`、`/api/search/categories` 和 `/api/search/tags` 的成功与失败。失败诊断 SHALL 使用固定处理阶段和安全的错误类别或状态。前置代理的访问日志规则 SHALL 在上线前核查，不得记录原始查询字符串或请求体。

#### Scenario: 异常内容回显检索词
- **WHEN** 召回、检索、模型或配额流程抛出消息、原因链或上游响应体含有检索词的异常
- **THEN** 应用异常日志只包含固定阶段与安全类别或状态，不包含该词或原始异常内容
- **AND** 对外错误仍遵循既有通用文案与授权边界

#### Scenario: 旧 GET 到达受控网关
- **WHEN** 旧客户端向受控网关发送带 q 的 GET 请求并被拒绝
- **THEN** 系统控制的访问日志不记录原始查询字符串或其值

#### Scenario: 发布前核查前置代理
- **WHEN** 将新检索入口部署到带前置代理的环境
- **THEN** 上线核查确认该代理的访问日志不会保存查询字符串或请求体；未完成核查时不得宣称端到端日志保密已验证

#### Scenario: 搜索候选异常携带名称
- **WHEN** 分类或标签候选读取、分页解码或依赖调用抛出包含目录名称、筛选值或游标内容的异常
- **THEN** 日志不输出这些值及原始堆栈，响应使用固定安全错误，候选子路径不退回通用异常明细日志

## ADDED Requirements

### Requirement: 搜索候选查询必须使用受保护请求体并禁止缓存

搜索分类、标签候选 SHALL 通过各自固定 POST 路径接收 JSON 条件，拒绝 URL 查询参数；名称查询不进入 URL、浏览器持久存储或遥测。搜索及其候选端点的成功、参数拒绝、认证拒绝、权限拒绝和服务故障响应 MUST 带 `Cache-Control: no-store`，网关不得丢失或覆写；公开路径不得转发到 AI 或改写为静态页面。

#### Scenario: 标签名称查询
- **WHEN** 用户输入名称并查询搜索标签候选
- **THEN** nameQuery 只通过 `/api/search/tags` 的 JSON 请求体发送，响应禁止缓存，地址栏不出现名称

#### Scenario: 网关提前拒绝候选请求
- **WHEN** 网关因登录状态失效在业务应用前拒绝 `/api/search/categories` 或 `/api/search/tags`
- **THEN** 拒绝响应仍带 no-store，不暴露查询体或返回可被缓存的候选数据
