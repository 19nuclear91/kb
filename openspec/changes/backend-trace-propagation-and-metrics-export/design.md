# Design

## Context

动机见 [proposal.md](proposal.md)。本设计依据当前实现的以下边界：

- 四个部署单元均无 trace、MDC 字段或 logback 配置；日志使用 Spring Boot 默认模式。kb-gateway 没有 actuator；kb-app 有 actuator 但未配置暴露清单（仅默认 health）；kb-ai 暴露 `health,info` 且没有任何 Micrometer 用户；kb-worker 暴露 `health,info,metrics`。没有任何导出 registry，没有 spring-security，管理端点与业务端口共用。
- 既有指标全部使用 Micrometer 且低基数：`SearchMetrics`、`NotificationMetrics`、`AiUsageMaintenance`、`AiObservationResponseFilter`（app），`ParseTaskMetrics`、`EmbeddingMetrics`（worker）。`ParseTaskMetrics` 已有 `SimpleMeterRegistry` 测试构造；指标名已在运维文档中承诺。
- 出站 HTTP 客户端分两类。使用注入 `WebClient.Builder` / `RestClient.Builder` 的：kb-ai 的 `RetrievalClient`、`QuotaPrincipalClient`、`ConversationAppClient`、`AiObservationClient`（目标 kb-app）；kb-app 的 `SettlementDeliveryClient`（目标 kb-ai）；以及四处**面向供应商**的 embedding/rerank 客户端——app 的 `OpenAiCompatibleQueryEmbeddingClient`、`search/embedding/EmbeddingProfilesConfiguration` 的 `builder.clone()` 路径，worker 的 `OpenAiCompatibleEmbeddingClient`、`embedding/EmbeddingProfilesConfiguration`。自建隔离客户端的：kb-ai `OpenAiChatTransport.isolatedClient`、kb-app `SsoHttp`、`SearchHttpTransport`（纯 JDK `HttpClient`）。
- 内部签名协议的规范串只含调用方、方法、路径、时间、nonce、正文摘要与令牌摘要，不含其他请求头。`X-Request-Id` 由前端 `crypto.randomUUID()` 生成并绑定操作者与载荷，匹配重放必须复用。
- kb-gateway 与 kb-ai 运行于 Reactor；Worker 的解析流水线在 `ParseTaskRunner` 的独立单线程 executor 上执行，心跳与巡检在调度线程。
- 各单元 `ProductionEnvironmentCheck` 只采集 `spring.`、`kb.`、`logging.level.`、`sa-token.` 前缀及少量显式键，不采集 `management.*`；`ProductionSettings` 以固定错误拒绝生产非法配置。
- `com.zhishiku.kb.ai.observation` 已用于 AI 用量观测（业务语义），不能再用来命名 Micrometer Observation 相关代码。
- Spring Boot 3.3.5 与 Spring Cloud 2023.0.3 提供 Micrometer Tracing、Observation API、Reactor 上下文传播与网关观测支持；Boot 在检测到 tracing 桥接时自动为默认日志模式追加 `[应用名,traceId,spanId]`。

## Goals / Non-Goals

**Goals:**

- 让"关闭"成为真正的零行为：未启用追踪时不产生跨度、不注入头、不改变任何出站请求。
- 让对外隔离可被单元测试证伪，而不是靠约定：每个单元都有断言供应商请求不含传播头的守卫测试。
- 既有指标名、标签与测试构造方式不变；新增指标沿用"固定枚举标签、ID 不入标签"的既有规则。
- 生产暴露面由 `ProductionSettings` 硬校验，与现有 dev/prod 边界一致。
- 全部验证限定为进程内单元测试、内存导出器与静态构建。

**Non-Goals:**

- 浏览器可见 traceId、SSE error 携带 traceId、`kb_parse_task` / `kb_ai_call` 的 trace 列。
- Prometheus、Grafana、Tempo 或 Collector 的部署、看板、告警规则与 compose profile。
- Java agent 方案；JDBC、Redis、对象存储的自动跨度（本次不开启，避免 SQL/键名进入属性与噪声）。
- 跨请求权限缓存、答案缓存及任何检索算法变化。
- 集成、端到端测试，启动服务、数据库、Redis、MinIO、容器、真实 Prometheus/Tempo 或第三方服务。
- 针对 README、运维文档或配置文本的测试；文档以交付对应章节为完成依据。

## Decisions

### D1. 代码内 Micrometer Tracing，而不是 agent

采用 Micrometer Tracing + OpenTelemetry 桥接 + OTLP 导出器，在各单元 POM 内声明；kb-common 不引入任何追踪依赖。

**取舍：** agent 方案零代码，但默认采集 SQL 文本、完整 URL 与请求头，与本项目"不记录查询词与凭据"的红线直接冲突，且无法用进程内单元测试证伪。代码内方案能复用 Boot 对注入 builder 的自动传播、自动日志关联，并把每条红线落成测试。

### D2. 配置面、默认值与生产校验

| 配置键 | 环境变量 | 默认 | 说明 |
| --- | --- | --- | --- |
| `kb.telemetry.tracing.enabled` | `KB_TELEMETRY_TRACING_ENABLED` | false | 关闭时映射为 `management.tracing.enabled=false`，不装配传播与导出 |
| `kb.telemetry.tracing.otlp-endpoint` | `KB_TELEMETRY_OTLP_ENDPOINT` | 空 | 启用时必填；生产要求 https 或内网 http 且无用户信息/查询/片段 |
| `kb.telemetry.tracing.sampling-probability` | `KB_TELEMETRY_SAMPLING_PROBABILITY` | 1.0 | 启用时必须 0 < p ≤ 1 |
| `management.server.port` | `KB_MANAGEMENT_PORT` | 空 | 生产必填；dev 留空沿用业务端口 |
| `management.server.address` | `KB_MANAGEMENT_ADDRESS` | 空 | 生产必填且不得为 `0.0.0.0`/空白 |
| `management.endpoints.web.exposure.include` | 固定 | `health,info,prometheus`（worker 另含 `metrics`） | 生产禁止含 `*` |
| `management.tracing.baggage.remote-fields` | 固定 | 空 | baggage 不出站；本地可用于 requestId 关联 |

四个 `ProductionEnvironmentCheck` 增加 `management.` 前缀采集（`kb.telemetry.` 已由既有 `kb.` 前缀覆盖）；`ProductionSettings` 新增规则：追踪启用则地址必填且采样率有界；生产管理端口与绑定地址必填、暴露清单无通配；分桶配置非递增或非正拒绝。错误只含配置名。

**取舍：** 以 `kb.telemetry.*` 作为唯一开关并映射到 `management.tracing.*`，避免部署方直接设置 Boot 原生键绕过校验；原生键仍可用但受同一校验约束。

### D3. 网关为 trace 根，不消费入站上下文

网关使用 Boot 的 WebFlux 服务端观测作为 trace 根；传播配置为**只产生、不消费**（`consume` 为空），因此客户端自带的 `traceparent` 永不成为父上下文。另在现有入站净化之外增加一个静默剥离步骤，去掉 `traceparent`、`tracestate`、`baggage` 后再路由，防止它们作为普通头被转发到下游；该剥离不记 WARN（不是身份攻击面）。Sa-Token 的 401 拒绝发生在观测过滤器之内，因而同样带 trace。

响应不回写任何追踪头；CORS `exposedHeaders` 不变。

**取舍：** 另一种做法是把三个头加入 `InboundHeaderSanitizeFilter.STRIPPED`，但那会把每个带 traceparent 的浏览器请求（某些企业代理会注入）记成越权探测。分开实现、分开记录。

### D4. 内部传播依赖注入 builder，外部调用一律隔离

- 内部：kb-ai 四个客户端与 kb-app `SettlementDeliveryClient` 已使用注入 builder，Boot 自动注入 W3C 头；网关到下游由网关观测支持注入。实现时逐一核对，不改动签名协议；`conversation-internal-protocol.md` 增加一句"追踪头不参与签名、不作身份"。
- 外部：四处面向供应商的 embedding/rerank 客户端改为与 `OpenAiChatTransport.isolatedClient` 同构的自建 builder（保留现有 JDK 请求工厂与超时），不再从注入 builder `clone()`。`SsoHttp`、`SearchHttpTransport` 维持纯 JDK 客户端。
- 守卫：每个单元一个测试类，用捕获请求头的 `ExchangeFunction` / `ClientHttpRequestFactory` 替身，在追踪**启用**的内存配置下断言供应商请求不含 `traceparent`、`tracestate`、`baggage`、`satoken`，内部请求含 `traceparent`。

**取舍：** 也可保留注入 builder 并为供应商客户端移除观测过滤器，但那依赖过滤器顺序与 Boot 内部实现；自建 builder 与项目已有的模型隔离做法一致，更稳。

### D5. 日志关联与三处桥接

- 日志模式沿用 Boot 自动关联字段，不新增 logback 文件；各单元 `spring.application.name` 已存在。
- `requestId`：kb-app 以 Servlet 过滤器在请求进入时读取 `X-Request-Id` 写入 MDC 并在 finally 清理；kb-gateway、kb-ai 以本地 baggage 字段关联到 MDC（`remote-fields` 为空保证不出站）。worker 的任务事件已持有 `request_id`，日志按既有方式输出。
- Reactor：开启 Boot 的自动上下文传播，使 WebFlux 处理链与 SSE 流在各线程上保持 MDC；SSE 流全程处于一个服务端跨度，流结束时关闭。
- Worker：`ParseTaskRunner` 在提交流水线前捕获当前上下文快照，并在 `pipelineExecutor` 线程内恢复后执行，finally 清理；心跳与巡检线程不继承任务上下文。

**取舍：** 不把 `requestId` 做成远程 baggage——那会随内部调用流转并增加误传到供应商的面。kb-ai 到 kb-app 的内部请求本身携带的 `requestId` 已在各自入口写入 MDC。

### D6. 属性红线由自定义约定与导出过滤共同兑现

- MVC 与 WebFlux 的服务端观测约定替换为本项目实现：只记录路由模板、方法、状态、结果与异常类名；不记录 `http.url`、任何请求头、远端地址。客户端观定同样只记录目标类别（`internal-app`、`internal-ai`、`vendor-model`、`vendor-embedding`、`vendor-rerank`、`vendor-parser`、`sso`）、方法、状态与结果。
- 导出前再加一道属性过滤：删除任何名为 `http.url`、`url.full`、`http.request.header.*`、`net.*` 的属性，作为 Boot 默认约定变化时的兜底。
- Worker 跨度允许 `kb.task.id`、`kb.version.id`、`kb.failure.class` 等低敏属性（跨度属性不是指标标签，不受基数规则约束），但不允许对象路径、原件内容或解析器错误体。
- 守卫测试用内存跨度导出器断言：搜索请求、SSO 回调请求、带 `satoken` 的请求导出的跨度属性不含查询文本、授权码、state、令牌或查询串。

**取舍：** 只依赖 Boot 默认约定会把 WebFlux 的完整 URI（含 SSO 回调参数）写入属性；自定义约定与导出过滤双重保障，成本是多一处要同步维护的属性清单。

### D7. 指标导出、管理端口与暴露面

- 四个单元加入 `micrometer-registry-prometheus`；kb-gateway 同时加入 actuator，并以单测断言不存在匹配 `/actuator/**` 的路由（现有路由只覆盖 `/api/**`）。
- 生产通过独立管理端口与显式绑定地址提供 `/actuator/prometheus`、`/actuator/health`；dev 留空沿用业务端口，README 的本机验证命令不变。生产健康检查探针地址需随之调整，列入发布说明（proposal 已标 BREAKING）。
- 既有指标名不变；`ParseTaskMetrics`、`EmbeddingMetrics` 等现有类不改接口。

**取舍：** 以 push（OTLP metrics）可避免匿名端点，但 Prometheus 已确定为抓取模型；因此以端口与绑定校验收紧暴露面，不在应用内再做指标端点认证（项目无 spring-security，不为此引入）。

### D8. 分桶、exemplar 与自动 HTTP 指标基数

- 使用 Micrometer 的 SLO 分桶按指标名显式配置：`kb.search.stage.duration` 与 `kb.search.options.stage.duration` 为 1.5s/2s/3s/8s，`kb.model.call.duration` 为 1s/5s/15s/30s/60s，`kb.parse.task.duration` 为 1s/5s/30s/60s/300s 并包含 `KB_TASK_TIMEOUT_SECONDS`，`http.server.requests` 为 100ms/500ms/2s/10s。分桶由 `kb.telemetry.histograms.*` 承载并校验递增正值，再映射到 Micrometer 分布配置。
- exemplar 依赖 Boot 在追踪启用时的自动配置；运维文档要求 Prometheus 抓取协商 OpenMetrics。实现时核对 Boot 3.3 的 exemplar 自动配置条件与协商媒体类型；追踪关闭时端点必须仍正常。
- `http.server.requests`：保留 Boot 的 URI 模板标签与 `max-uri-tags` 上限，增加 `MeterFilter` 拒绝管理端点自身的序列，未匹配路径归入固定值；网关以路由 id 作为标签，不使用原始路径。单测用随机路径与多 ID 路径断言序列数不增长。

**取舍：** 全局百分位直方图开销大且边界与预算无关；按指标名显式 SLO 桶更省、更能回答"是否逼近预算"。

### D9. kb-ai 指标清单与接入点

| 指标 | 标签（固定枚举） | 接入点 |
| --- | --- | --- |
| `kb.model.calls` | `outcome=COMPLETED/UPSTREAM_ERROR/PROTOCOL_ERROR/LIMIT/TIMEOUT/CANCELLED` | `OpenAiChatTransport` 流终结 |
| `kb.model.first_text.delay` | 无 | 首个文本增量到达 |
| `kb.model.call.duration` | `outcome` | 整次调用 |
| `kb.model.usage` | `status=KNOWN/UNKNOWN` | 最终 usage 确认 |
| `kb.chat.streams` | `outcome=COMPLETED/ERROR/CANCELLED/TIMEOUT`，`error_code` 固定枚举 | `ChatController`、多轮 SSE 收尾 |
| `kb.chat.first_event.delay` | 无 | 首个内容事件 |
| `kb.ai.quota.rejections` | `reason` 固定枚举 | 配额拒绝处理 |
| `kb.ai.retrieval.failures` | `reason=REJECTED/TIMEOUT/UNAVAILABLE/PROTOCOL` | `RetrievalClient` 失败映射 |

新代码位于 `com.zhishiku.kb.ai.telemetry`，与 `ai.observation` 明确区分。所有标签不含问题、答案、会话或轮次标识。

### D10. Worker 任务级 trace

`ParseTaskRunner.runOne` 为每次执行开启根跨度 `kb.parse.task`，属性为任务、版本、执行代次与失败分类；第三方解析、向量化与发布作为子跨度。上下文随 D5 的快照进入流水线线程。追踪关闭时该包装退化为无操作，不改变领取、重试与发布的既有调用顺序。`docs/third-party-document-parsing.md` 增补：适配器不得复用注入 builder，不得向解析服务传播追踪头。

**取舍：** 不把上传请求的 traceId 写入任务表（proposal 已排除），日志仍以任务与版本 ID 与上传侧关联。

### D11. 验证策略

- 指标：`SimpleMeterRegistry` 断言名称、标签集合与计数；分桶配置单测断言映射结果与非法值拒绝。
- 追踪：Micrometer 的内存 tracer / OTel 内存跨度导出器；配置类单测分别在关闭与启用两种装配下运行。
- 头部：替身捕获出站请求头；网关用 `MockServerWebExchange` 断言剥离、无响应追踪头与 401 路径处于跨度内。
- 桥接：Worker 以受控 executor 与 latch 断言流水线线程 MDC 含 trace 标识且 finally 清理；kb-ai 用 `StepVerifier` 与虚拟时间断言 SSE 跨度随流关闭。
- 生产校验：`ProductionSettings` 参数化测试覆盖新增规则。
- 测试命令显式列出类名，不执行全量或通配；文档不测试。

## Risks / Trade-offs

- [Boot 默认观测约定或自动配置在升级后变化] → 自定义约定 + 导出过滤双保险，守卫测试在升级时先失败。
- [Reactor 上下文在某个算子处丢失，日志无 traceId] → 用虚拟时间与多线程调度器的单测覆盖 SSE 全程；缺失只影响关联，不影响业务。
- [生产管理端口迁移破坏健康检查] → BREAKING 标注、发布说明给出探针改法；dev 不变。
- [Prometheus 端点匿名可读] → 独立端口 + 显式绑定 + 禁止通配；网络策略由部署方负责，文档明示本应用不做端点认证。
- [exemplar 未按预期出现] → 不影响指标正确性；文档说明协商要求，排障从抓取协议入手。
- [未来新增对外客户端误用注入 builder] → 文档约定 + 客户端目标类别约定要求显式声明；守卫测试覆盖现有全部供应商客户端。
- [追踪开启后的开销] → 采样率可配置；OTLP 导出异步且目标不可达时丢弃，不阻塞业务（此行为为导出器约定，本次不做真实网络验证）。
- [自动 HTTP 指标基数] → `max-uri-tags`、管理端点拒绝过滤与未匹配归并，单测断言序列不随路径增长。

## Migration Plan

以下用于运行说明的交付；本变更的实施任务不启动服务或执行部署。

1. 随代码交付 `docs/telemetry-operations.md`：开关、端口、抓取配置（OpenMetrics 协商）、分桶、回退；更新 README 第六节勾选项与第八节，以及检索、解析、模型三份文档的指标章节。
2. 四个单元可按任意顺序发布：追踪默认关闭，指标导出在 dev 不改端口。生产发布前先配置 `KB_MANAGEMENT_PORT`、`KB_MANAGEMENT_ADDRESS`，同步更新健康检查探针与 Prometheus 抓取目标。
3. 追踪在 Tempo 或 Collector 就位后再启用：设置 OTLP 地址与采样率并重启；无需代码变更。
4. 回退：关闭 `KB_TELEMETRY_TRACING_ENABLED` 并重启即恢复零行为；指标端口回退需同步恢复探针与抓取配置；恢复旧二进制不影响任何业务数据，无迁移。

## Open Questions

- 生产管理端口与绑定地址的具体取值由部署环境决定，不影响规格与任务。
- trace 存储选 Tempo 还是经 Collector 转发，只改变 OTLP 地址，不影响代码。
