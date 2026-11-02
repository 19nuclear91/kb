# Proposal

## Why

四个部署单元目前没有任何请求级关联标识：日志里没有 traceId，kb-gateway 连 actuator 都没有，kb-ai 零指标，已有的 `kb.search.*`、`kb.parse.*`、`kb.embedding.*` 指标也没有任何导出器可供 Prometheus 抓取。架构决策记录第 9 章把"traceId 贯穿网关→业务→AI→模型"和"任务积压、索引失败、模型耗时/用量、检索延迟监控"列为生产底线，README 第六节对应项仍未勾选；第三方解析、模型供应商与 PG/MinIO/Redis 的真实联调即将开始，缺少这些观测就无法定位跨单元故障。监控平台已确定为 Prometheus + Grafana，trace 存储（Tempo 或 OTel Collector）尚未部署，因此本次只交付埋点、传播和导出配置，不交付平台。

## What Changes

- 在 kb-gateway 为每个入站请求生成 trace（忽略并丢弃客户端自带的 `traceparent`/`tracestate`），以 W3C 头在 gateway→app/ai、ai→app、app→ai 的内部 HTTP 调用间传播；MVC 入口及搜索工作线程、Reactor 与 Worker 独立执行线程显式桥接 MDC，日志统一携带 `traceId`/`spanId`，存在 `X-Request-Id` 时另记 `requestId`。匹配重放复用 requestId，但每次请求都是新的 trace。
- 所有发往本系统之外的 HTTP 调用（模型、embedding、rerank、第三方解析、SSO 供应商）不得携带 trace 传播头或 baggage：app/worker 四处使用注入 builder 的 embedding/rerank 客户端对齐 `OpenAiChatTransport` 的隔离方式，并以守卫测试钉住。
- span 属性与指标标签遵守既有日志红线：不记录查询、正文、令牌、授权码/state、请求头与 URI 查询串；服务端观测只记录路由模板。
- tracing 默认关闭；显式启用时 OTLP 地址必填、采样率有界、baggage 关闭，配置不全在建立业务连接前拒绝启动。
- 四个单元统一接入 Prometheus 导出（kb-gateway 新增 actuator），端点只在管理端口暴露；生产要求显式管理端口与绑定地址，禁止通配暴露。**BREAKING**：生产环境 `/actuator/health` 随显式管理端口迁移，部署健康检查地址需同步调整；开发态可继续使用业务端口。
- 按既有预算为耗时直方图显式分桶（检索 2s/3s/1.5s/8s、模型 60s），启用 exemplar 关联 trace，约束 Boot 自动 `http.server.requests` 的 URI 标签基数；既有指标名与标签保持不变，唯一例外是解析任务重试/死信的 `failure_class` 分类序列改用 `.by_class` 后缀，保留旧无标签总量名称，避免 Prometheus 同名指标标签集合冲突。
- 补齐 kb-ai 指标：模型调用结果、上游耗时与首 token 延迟、流终结类别、最终用量已知/未知计数、配额拒绝与检索失败分类；Worker 每次任务执行开启任务级 trace，覆盖第三方解析、embedding 与发布。
- 交付 `docs/telemetry-operations.md`（启用、端口、抓取配置、OpenMetrics 协商与回退）并更新 README 第六节与第八节；不交付 Prometheus/Grafana/Tempo 的部署、看板或告警规则。
- 不做：浏览器可见 traceId、SSE error 携带 traceId、`kb_parse_task`/`kb_ai_call` 的 trace 列、跨请求权限缓存。

## Capabilities

### New Capabilities

- `trace-propagation`：trace 上下文的生成边界、内部传播、对外隔离、日志关联、属性保密与启用/校验规则。
- `metrics-export`：指标导出端点、管理端口与暴露面校验、直方图分桶、exemplar 关联、自动 HTTP 指标基数约束及流式响应生命周期指标。

### Modified Capabilities

- `search-resilience`：观测要求延伸为可经导出端点抓取并按预算分桶，增加 AI 检索失败分类指标；查询 embedding/rerank 出站调用不得携带内部追踪上下文。
- `document-parsing`：任务指标要求延伸为可导出；每次任务执行必须开启可关联的任务级 trace，Worker 执行日志携带该标识；第三方解析调用不传播追踪上下文。
- `chat-model-adapter`：出站模型请求禁止项增加 trace 传播头与 baggage；新增模型调用低基数观测要求（结果、上游耗时、首 token、终结类别、用量已知/未知）。
- `search-query-confidentiality`：日志保密要求扩展到 trace 属性、指标标签与 exemplar 等遥测输出。
- `enterprise-sso`：认证日志最小化要求扩展到追踪属性，回调 URI 参数不得进入 span。

## Impact

- 依赖：`micrometer-registry-prometheus`、`micrometer-tracing-bridge-otel`、`opentelemetry-exporter-otlp` 及 Reactor 上下文传播加入 kb-app/kb-ai/kb-worker/kb-gateway 各自 POM；kb-gateway 新增 `spring-boot-starter-actuator`；测试作用域引入 tracing/OTel 内存导出工具。kb-common 保持无 Spring 运行时依赖，不承载追踪代码。
- `kb-gateway`：新增 trace 根过滤器并与既有响应策略、Sa-Token 拒绝路径对齐；`application.yml` 管理端口与导出配置；`ProductionEnvironmentCheck`。
- `kb-app`：`OpenAiCompatibleQueryEmbeddingClient`、`search/embedding/EmbeddingProfilesConfiguration` 改为隔离客户端；MDC `requestId` 注入；`kb-common/config/ProductionSettings` 新增校验规则；`application.yml`。
- `kb-ai`：新增与既有 `ai.observation`（AI 用量观测）区分命名的遥测包；模型与流指标接入 `OpenAiChatTransport`、`ChatController`、`MultiTurnChatService`、配额与检索客户端；Reactor 上下文桥接；配置与校验。
- `kb-worker`：`ParseTaskRunner`/`ParsePipeline` 任务级 trace 与独立线程 MDC 桥接；`embedding/EmbeddingProfilesConfiguration`、`OpenAiCompatibleEmbeddingClient` 改为隔离客户端；`application.yml`。
- 测试：各单元新增守卫测试（供应商请求无传播头、span 无查询/令牌/回调参数、管理端口与暴露校验），以及 `SimpleMeterRegistry`/内存 span 导出器单测；不启动服务、数据库、Redis、MinIO、容器或第三方服务。
- 文档：`docs/telemetry-operations.md`、README 第六/八节、`docs/search-resilience.md`、`docs/parse-task-recovery-operations.md`、`docs/chat-model-adapter.md` 的指标与配置章节。无数据库迁移，无前端或网关对外协议变更。
