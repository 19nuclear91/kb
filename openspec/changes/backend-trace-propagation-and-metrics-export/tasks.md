# Tasks

验证范围限定为进程内单元测试、内存 Mock/Stub、`SimpleMeterRegistry`、内存 tracer/跨度导出器、编译与静态构建。受测组件的数据库、Redis、HTTP、模型、对象存储及其他业务依赖均使用替身；不编写或执行集成、端到端测试，不启动前端或应用服务、不监听端口，不运行或连接 PostgreSQL、Redis、MinIO、容器、Prometheus、Tempo 及第三方服务。测试命令必须显式列出允许的单元测试类，避免全量、通配执行带入既有集成测试。

文档任务以对应文件和章节的交付为完成依据；不编写或执行针对 README、运维文档、Markdown、措辞、格式或文档示例的测试，也不以源码、配置文本扫描替代行为单测。

## 1. 依赖、配置面与生产校验

- [ ] 1.1 在 kb-app、kb-ai、kb-worker、kb-gateway 的 POM 加入 Prometheus registry、tracing 桥接与 OTLP 导出器（kb-gateway 另加 actuator），测试作用域加入内存 tracer/跨度导出器；kb-common 不变。验证：各模块 `mvn -o -pl <模块> -am test-compile '-DskipTests'` 通过，且 `NoDatabaseAccessTest`、`ReactiveStackTest`、`ModuleBoundaryTest` 仍通过（新增依赖未把 servlet 或数据库能力带进 kb-ai）。
- [ ] 1.2 实现 `kb.telemetry.tracing.*` 配置绑定及其到 `management.tracing.*` / OTLP 导出配置的映射：关闭即 `management.tracing.enabled=false`，启用时 OTLP 地址必填、采样率 0<p≤1、baggage remote-fields 固定为空。验证：配置绑定单测覆盖默认关闭、启用缺地址、采样率越界、地址夹带用户信息/查询/片段的拒绝，以及错误信息不含地址。
- [ ] 1.3 为 `ProductionSettings` 新增规则（生产管理端口与绑定地址必填且不得为 `0.0.0.0`/空白、暴露清单禁止通配、追踪启用则地址必填、分桶非递增或非正拒绝），并让四个 `ProductionEnvironmentCheck` 采集 `management.` 前缀。验证：`ProductionSettings` 参数化单测覆盖上述每条规则的拒绝与通过、dev 不触发、错误只含配置名。
- [ ] 1.4 新增 `docs/telemetry-operations.md` 的开关、配置键与生产校验章节；完成依据为交付对应章节。

## 2. 网关 trace 根与入站处理

- [ ] 2.1 将网关观测配置为只产生、不消费追踪上下文，并增加静默剥离 `traceparent`/`tracestate`/`baggage` 的 WebFilter（顺序先于观测与 Sa-Token 过滤）。验证：`MockServerWebExchange` 单测断言客户端自带 traceparent 不成为父上下文、转发到下游的请求头只含网关生成的 traceparent、剥离不记 WARN、401 拒绝路径处于跨度内、响应头无追踪标识。
- [ ] 2.2 确认管理端点不经网关路由，CORS 暴露清单不变。验证：路由单测断言不存在匹配 `/actuator/**` 的路由；CORS 配置单测断言 exposedHeaders 未新增追踪头。
- [ ] 2.3 更新网关 `application.yml` 的管理端口、绑定地址与暴露配置（dev 留空沿用业务端口）及 `ProductionEnvironmentCheck`。验证：配置加载单测覆盖 dev 同端口与 prod 必填。

## 3. 内部传播与对外隔离

- [ ] 3.1 确认 kb-ai 的 `RetrievalClient`、`QuotaPrincipalClient`、`ConversationAppClient`、`AiObservationClient` 与 kb-app `SettlementDeliveryClient` 在追踪启用的内存装配下携带 W3C 头。验证：替身捕获请求头单测断言 traceparent 存在；`RequestSignature` 单测新增带 traceparent 的用例断言签名结果不变。
- [ ] 3.2 将 kb-app 的 `OpenAiCompatibleQueryEmbeddingClient` 与 `search/embedding/EmbeddingProfilesConfiguration` 改为自建隔离 builder，保留现有 JDK 请求工厂与超时。验证：单测断言预算路径与默认路径的请求头均只含 Authorization 与正文，不含 traceparent/tracestate/baggage/satoken。
- [ ] 3.3 将 kb-worker 的 `OpenAiCompatibleEmbeddingClient` 与 `embedding/EmbeddingProfilesConfiguration` 改为自建隔离 builder。验证：同 3.2 的 worker 侧单测。
- [ ] 3.4 为每个单元新增供应商传播守卫测试：在追踪启用下断言全部供应商客户端（含既有 `OpenAiChatTransport`、`SsoHttp`、`SearchHttpTransport`）出站不含传播头，且供应商响应中的追踪头不改写本地上下文。验证：守卫测试通过。
- [ ] 3.5 在 `docs/conversation-internal-protocol.md` 增加"追踪头不参与签名、不作身份"说明，在 `docs/third-party-document-parsing.md` 增补适配器不得复用注入 builder、不得向解析服务传播追踪头；完成依据为交付对应段落。

## 4. 日志关联与上下文桥接

- [ ] 4.1 为 kb-app 增加 `X-Request-Id` 到 MDC 的过滤器（进入写入、finally 清理）。验证：standalone MockMvc 与日志捕获单测断言请求期间 MDC 含 requestId、请求后清理、无该头时无字段、重放请求 requestId 相同而 traceId 不同。
- [ ] 4.2 在 kb-gateway 与 kb-ai 开启 Reactor 自动上下文传播，以本地 baggage 字段关联 requestId 到 MDC 且 remote-fields 为空。验证：`StepVerifier` 与多线程调度器单测断言 SSE 流各阶段日志 MDC 含 traceId；出站内部请求无 baggage 头。
- [ ] 4.3 在 `ParseTaskRunner` 提交流水线前捕获上下文快照并在 `pipelineExecutor` 线程恢复、finally 清理。验证：受控 executor 与 latch 单测断言流水线线程 MDC 含 trace 标识、执行后清理、心跳与巡检线程不继承任务上下文。
- [ ] 4.4 确认各单元默认日志模式包含 Boot 自动关联字段且不新增 logback 文件。验证：输出捕获单测断言启用追踪时一条日志含 traceId/spanId，关闭时字段为空而不报错。

## 5. 属性红线

- [ ] 5.1 为 MVC 与 WebFlux 实现本项目的服务端观测约定（只记录路由模板、方法、状态、结果与异常类名）及客户端约定（固定目标类别），替换默认约定。验证：内存跨度导出器单测以搜索 POST、带 code/state 的 SSO 回调、带 satoken 的请求断言跨度属性无 `http.url`、无请求头、无查询串，含 `http.route`。
- [ ] 5.2 增加导出前属性过滤，删除 `http.url`、`url.full`、`http.request.header.*`、`net.*`。验证：对人工注入上述属性的跨度，导出后属性被删除且其余属性保留。
- [ ] 5.3 定义 Worker 跨度属性白名单（任务、版本、执行代次、失败分类），禁止对象路径、原件内容与解析器错误体。验证：单测断言失败路径跨度不含错误摘要全文或对象键。
- [ ] 5.4 更新 `docs/search-resilience.md` 的日志保密说明与 `docs/enterprise-sso-configuration.md` 的日志最小化说明，覆盖追踪属性与指标标签；完成依据为交付对应段落。

## 6. 指标导出、端口、分桶与基数

- [ ] 6.1 更新 kb-app、kb-ai、kb-worker 的 `application.yml`：暴露 `health,info,prometheus`（worker 另含 `metrics`），管理端口与绑定地址由环境变量注入、dev 留空。验证：配置加载单测覆盖 dev 同端口与 prod 必填（与 1.3 规则联动）。
- [ ] 6.2 实现 `kb.telemetry.histograms.*` 分桶配置到 Micrometer 分布配置的映射，默认覆盖 `kb.search.stage.duration`/`kb.search.options.stage.duration`（1.5s/2s/3s/8s）、`kb.model.call.duration`（1s/5s/15s/30s/60s）、`kb.parse.task.duration`（1s/5s/30s/60s/300s 及任务超时）与 `http.server.requests`。验证：`SimpleMeterRegistry` 单测断言直方图含指定边界桶；配置单测拒绝非递增或非正值。
- [ ] 6.3 实现 `http.server.requests` 基数守卫：沿用 URI 模板与 `max-uri-tags`，`MeterFilter` 拒绝管理端点自身序列，未匹配路径归入固定值，网关以路由 id 作标签。验证：单测用随机路径与多文档 ID 路径断言序列数不随路径增长且不含原始路径。
- [ ] 6.4 确认追踪启用时 Prometheus registry 的 exemplar 自动配置生效、关闭时导出正常。验证：内存装配单测分别在启用/关闭下断言 exemplar 采样器存在/不存在，并在进程内调用注册表 scrape 输出断言关闭时不含 exemplar（不启动 Prometheus）。
- [ ] 6.5 完善 `docs/telemetry-operations.md` 的抓取配置（OpenMetrics 协商）、管理端口迁移与健康检查探针改法、分桶与回退章节；更新 README 第六节对应勾选项与第八节；完成依据为交付对应章节。
- [ ] 6.6 更新 `docs/search-resilience.md` 指标章节，补充导出端点、分桶与 AI 检索失败分类；完成依据为交付对应章节。

## 7. kb-ai 指标

- [ ] 7.1 新建 `com.zhishiku.kb.ai.telemetry` 包并接入模型调用指标 `kb.model.calls`、`kb.model.first_text.delay`、`kb.model.call.duration`、`kb.model.usage` 到 `OpenAiChatTransport`。验证：内存流单测覆盖正常完成、缺少最终 usage、限额、协议错误、超时与取消的类别计数与首文本延迟记录，标签不含问题或答案。
- [ ] 7.2 接入流生命周期指标 `kb.chat.streams` 与 `kb.chat.first_event.delay` 到 `ChatController` 与多轮 SSE 收尾。验证：`StepVerifier` 单测覆盖 COMPLETED、带固定 SEARCH_UNAVAILABLE 码的 ERROR、CANCELLED 与 TIMEOUT 计数。
- [ ] 7.3 接入 `kb.ai.quota.rejections` 与 `kb.ai.retrieval.failures` 指标。验证：`RetrievalClientTest` 扩展断言失败原因分类计数；配额拒绝处理单测断言 reason 固定枚举。
- [ ] 7.4 更新 `docs/chat-model-adapter.md` 的指标与追踪隔离章节；完成依据为交付对应章节。

## 8. Worker 任务级 trace 与指标导出

- [ ] 8.1 在 `ParseTaskRunner.runOne` 开启根跨度 `kb.parse.task`（属性为任务、版本、执行代次与失败分类），解析、向量化与发布为子跨度；追踪关闭时为无操作。验证：内存导出器单测断言一次执行一条根跨度、重试为独立 trace、关闭时零跨度且领取/发布调用顺序不变、解析不可用时不产生跨度。
- [ ] 8.2 为 `kb.parse.task.duration` 应用显式分桶并保持既有指标名与标签不变。验证：`ParseTaskMetrics` 单测扩展断言名称集合与分桶。
- [ ] 8.3 更新 `docs/parse-task-recovery-operations.md` 的指标导出与任务追踪章节；完成依据为交付对应章节。

## 9. 收尾核对与打包

- [ ] 9.1 按显式测试类名单分别运行各单元新增及受影响单测，并以 `mvn -o -pl <模块> -am package '-DskipTests'` 完成打包。验证：记录实际命令与结果，零失败、零错误、零跳过；需外部环境的既有烟测不运行。
- [ ] 9.2 运行 `openspec validate backend-trace-propagation-and-metrics-export --strict`，并在 `docs/telemetry-operations.md` 的验证记录章节写入本次离线验证范围与实际执行的测试命令。验证：校验退出码为 0，且记录中的命令与 9.1 实际执行一致；不以文档内容作为断言对象。

## Workflow follow-up

- 实现完成并核验后归档变更，归档时将两个新能力与五个 delta 同步到主规格。
- 追踪的启用在 Tempo 或 Collector 部署后由运维按 `docs/telemetry-operations.md` 配置，不属于本变更的实施任务。
