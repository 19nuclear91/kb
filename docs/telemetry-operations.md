# 后端追踪与指标运维

## 开关与启用顺序

四个部署单元使用 Micrometer、OpenTelemetry 和 Prometheus registry。追踪默认关闭，指标导出独立于追踪开关。启用追踪前准备 OTLP HTTP 接收端，然后在需要启用的单元设置以下环境变量并重启：

```powershell
$env:KB_TELEMETRY_TRACING_ENABLED = 'true'
$env:KB_TELEMETRY_OTLP_ENDPOINT = 'https://collector.example/v1/traces'
$env:KB_TELEMETRY_SAMPLING_PROBABILITY = '0.1'
```

地址需包含 OTLP HTTP 的接收路径。生产可以使用 HTTPS，或以内部服务名、Kubernetes Service DNS、私有 IPv4/回环地址提供的 HTTP 接收端。应用不通过 DNS 探测地址是否内网。

## 配置键

| 配置键 | 环境变量 | 默认值 / 约束 |
| --- | --- | --- |
| `kb.telemetry.tracing.enabled` | `KB_TELEMETRY_TRACING_ENABLED` | `false`；唯一启停开关 |
| `kb.telemetry.tracing.otlp-endpoint` | `KB_TELEMETRY_OTLP_ENDPOINT` | 空；启用时必填 |
| `kb.telemetry.tracing.sampling-probability` | `KB_TELEMETRY_SAMPLING_PROBABILITY` | `1.0`；启用时 `0 < p ≤ 1` |
| `management.server.port` | `KB_MANAGEMENT_PORT` | 开发可留空；生产必须是独立有效端口 |
| `management.server.address` | `KB_MANAGEMENT_ADDRESS` | 开发可留空；生产必须显式绑定 |
| `management.endpoints.web.exposure.include` | 配置文件 | `health,info,prometheus`；Worker 另含 `metrics`；生产禁止通配 |
| `management.tracing.baggage.enabled` | 固定 | `false`；配置为 `true` 拒绝启动 |
| `management.tracing.baggage.remote-fields` | 固定 | 空；配置远程字段拒绝启动 |

环境后处理器在自动配置与业务连接建立前进行校验，再映射到 `management.tracing.enabled`、`management.tracing.sampling.probability` 和 `management.otlp.tracing.endpoint`。Boot 原生开关必须与项目开关一致；原生地址和采样率也接受校验。

Spring Boot 3.3.5 的 OTel 实现中，仅将 remote-fields 置空不能关闭 W3C baggage 传播，因此明确禁用 baggage 网络传播。requestId 的本地 MDC 关联使用显式监听器，不使用该版本不支持的 local-fields 配置。

该版本的 `management.tracing.enabled=false` 也不能独立关闭本地跨度创建。项目在自动配置导入阶段排除 OTel tracing、OTLP exporter 与 exemplar 配置，由 Boot 提供无操作 tracer；指标仍正常记录和导出。

## 生产校验

默认 profile 是 `prod`，只有明确的 `dev` 才跳过生产暴露面校验，二者不得同时启用。

- 管理端口必须是 `1–65535` 内的整数，且与业务端口按数值比较不同（如 `08082` 与 `8082` 仍是同一端口）。管理绑定地址不得为空或为 `0.0.0.0`、IPv6 未指定地址、通配地址；数字地址规范化后检查，压缩/括号写法如 `0:0::0` 也会拒绝，校验不进行 DNS 探测。
- 管理端点暴露清单不得含 `*`，包括索引形式的列表配置。
- 启用追踪必须提供地址；地址不得包含用户信息、查询参数或片段。采样率必须有限且处于 `(0, 1]`。
- `kb.telemetry.histograms.*` 的边界必须为有限正值并严格递增。支持秒数、`ms/s/m/h` 后缀和 ISO-8601 Duration；非法值不会静默回落默认配置。
- 类型转换与地址校验失败使用只含配置名的安全错误，不保留含配置值的异常原因链。

## 管理端口迁移与健康探针

生产发布前，为每个进程设置 `KB_MANAGEMENT_PORT` 与 `KB_MANAGEMENT_ADDRESS`。例如业务端口为 8081 的 app 可使用管理端口 9081；同机的 gateway、AI、Worker 分别选择不冲突的独立端口。绑定地址应是监控网络可达的本机地址；`127.0.0.1` 仅适合同机抓取或代理。

将健康探针从 `http://<主机>:<业务端口>/actuator/health` 改为 `http://<管理绑定地址>:<管理端口>/actuator/health`，同步修改容器编排的 probe 端口、服务发现和抓取目标。管理端口不提供业务 API；网关不转发下游管理端点。端点没有应用内认证，部署方须限制管理网络的访问范围。Worker 未配置解析器时健康状态仍为 `OUT_OF_SERVICE`，不能因端口迁移而忽略该状态。

显式 `dev` 下不设置这两个变量时，继续在业务端口访问管理端点。生产不得通过清空变量回退到共用端口。

## Prometheus 抓取与 OpenMetrics

抓取每个部署单元的 `/actuator/prometheus`，不要通过公开网关路由。支持 `scrape_protocols` 的 Prometheus 可参考以下片段，替换示例地址和端口：

```yaml
scrape_configs:
  - job_name: kb-backend
    metrics_path: /actuator/prometheus
    scrape_protocols: [OpenMetricsText1.0.0, OpenMetricsText0.0.1, PrometheusText0.0.4]
    static_configs:
      - targets: [gateway-monitor:9080, app-monitor:9081, ai-monitor:9082, worker-monitor:9083]
```

OpenMetrics 协商媒体类型为 `application/openmetrics-text;version=1.0.0;charset=utf-8`。只有在采样中的活跃跨度内记录样本，才有机会附带 `trace_id`/`span_id` exemplar；普通 Prometheus 文本格式不显示 exemplar。平台还需启用 exemplar 存储，并在 Grafana 配置对应 trace 数据源链接。无 exemplar 时先核对追踪开关、采样率、样本记录时的上下文、抓取协商与平台存储能力；它不影响普通指标的计数。

Micrometer 的点分名称在 Prometheus 文本中按协议转换，例如 `kb.model.call.duration` 导出为 `kb_model_call_duration_seconds_*`；Counter 带 `_total`，这不是业务指标重命名。标签仍只含固定类别，不含 requestId、用户、问题、资源 ID 或 URL。

Worker 重试/死信分类有一项明确的名称迁移：保留无标签总量 `kb_parse_tasks_retried_total`、`kb_parse_tasks_dead_lettered_total`；原同名 `failure_class` 分类改为 `kb_parse_tasks_retried_by_class_total`、`kb_parse_tasks_dead_lettered_by_class_total`，标签和值域不变。Prometheus registry 要求同名指标的标签键集合一致，不能混用无标签总量与分类。旧总量查询无需调整，分类查询须换新名称，不要将总量与分类相加。其余业务指标名称与标签不变。

## 显式分桶与基数

| 配置键 | 指标 | 默认边界 |
| --- | --- | --- |
| `kb.telemetry.histograms.search` | `kb.search.stage.duration`、`kb.search.options.stage.duration` | 1.5s、2s、3s、8s |
| `kb.telemetry.histograms.model` | `kb.model.call.duration` | 1s、5s、15s、30s、60s |
| `kb.telemetry.histograms.parse` | `kb.parse.task.duration` | 1s、5s、30s、60s、300s，并合入 `KB_TASK_TIMEOUT_SECONDS` |
| `kb.telemetry.histograms.http` | `http.server.requests` | 100ms、500ms、2s、10s |

可在外部配置文件中用逗号分隔的 Duration 或列表覆盖边界；修改预算后应同步审视对应分桶。配置必须严格递增且为正值，Worker 的任务超时边界会去重后合入。按名称应用 SLO 桶，避免全局开启百分位直方图。

HTTP 服务端按路径模板聚合，未知路径使用固定类别，网关以路由 ID 聚合；`management.metrics.web.server.max-uri-tags` 默认 100。管理请求在路由匹配前投影为固定 `MANAGEMENT` 类别，由过滤器拒绝计量，覆盖 `.active` 与结束时的耗时指标；原始路径不会写入标签。网关附加指标移除原始路径和目标 URI 标签。

导出时删除完整 URL、请求头、网络地址及异常消息/堆栈属性。OTel 异常事件重新构建为仅含合法异常类名的事件；跨度状态保留 ERROR/OK/UNSET，但清空描述，避免原始异常消息绕过属性过滤。排障使用 trace、路由、固定失败类别和任务标识，不依赖远程原始错误体。

app 的搜索主工作与可选阶段显式恢复 Observation、span 和本地 requestId，确保跨线程的降级日志仍关联入口 trace；作用域只覆盖实际执行，成功、异常或取消后的真实退出都会清理，不复制其他 MDC 或认证/事务字段。app/AI 接收端的内存测试同时核对已知 W3C 父上下文、服务端日志和后续内部调用沿用同一 trace，不仅断言出站头存在。

## 回退

将 `KB_TELEMETRY_TRACING_ENABLED=false` 并重启可停止跨度创建、传播和导出，指标端点继续可用。生产管理端口仍须保留。若回退到旧二进制，需同步恢复它对应的健康探针和抓取配置；本变更不涉及业务数据迁移。OTLP 接收端就绪后可重新设置地址与采样率并启用追踪。

## 验证记录

2026-11-02，获准联网补齐本地 Maven 依赖后，恢复离线验证。以下命令已执行成功：

```powershell
mvn -o -pl kb-app -am test-compile '-DskipTests'
mvn -o -pl kb-ai -am test-compile '-DskipTests'
mvn -o -pl kb-worker -am test-compile '-DskipTests'
mvn -o -pl kb-gateway -am test-compile '-DskipTests'
mvn -o -pl kb-app,kb-ai -am test '-Dtest=NoDatabaseAccessTest,ReactiveStackTest,ModuleBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false'
mvn -o -pl kb-app,kb-ai,kb-worker,kb-gateway -am test '-Dtest=TelemetryConfigurationTest' '-Dsurefire.failIfNoSpecifiedTests=false'
mvn -o -pl kb-common test '-Dtest=ProductionSettingsTest'
```

架构边界测试 12 项、追踪配置测试 60 项、生产配置测试 34 项，均零失败、零错误、零跳过。验证使用进程内配置绑定与替身，没有启动业务服务或连接业务依赖。

### 最终定向回归与构建（2026-11-02）

完成启停兼容、模型/流/依赖指标、Worker 任务跨度与异常事件过滤后，使用以下显式名单完成最终回归。共 86 个测试类、415 项单元测试，失败、错误和跳过均为 0：app 48、AI 237、Worker 55、gateway 38、common 37。每类使用独立 JVM（`reuseForks=false`），避免不同内存 SDK 与 Boot 测试之间的 OTel 全局 ContextStorage 初始化干扰。

```powershell
$tests = @('TelemetryConfigurationTest','HistogramConfigurationTest','HttpMetricsCardinalityTest','HttpObservationPrivacyTest','TelemetrySpanFilterTest','TelemetryLoggingTest','ManagementConfigurationTest','PrometheusExemplarConfigurationTest','InternalPropagationTest','SupplierPropagationTest','RequestIdFilterTest','EmbeddingSupplierIsolationTest','EmbeddingProfilesPropertiesTest','EmbeddingEnvironmentValidatorTest','EnvironmentIsolationTest','ModuleBoundaryTest')
mvn -o -pl kb-app -am test "-Dtest=$($tests -join ',')" '-Dsurefire.failIfNoSpecifiedTests=false' '-DreuseForks=false'

$tests = @('TelemetryConfigurationTest','HistogramConfigurationTest','HttpMetricsCardinalityTest','HttpObservationPrivacyTest','TelemetrySpanFilterTest','TelemetryLoggingTest','ManagementConfigurationTest','PrometheusExemplarConfigurationTest','InternalPropagationTest','ReactiveRequestContextTest','ModelSupplierPropagationTest','ModelTelemetryTest','StreamTelemetryTest','MultiTurnStreamTelemetryTest','OpenAiChatTransportTest','ChatModelConfigurationTest','ChatModelLimitsTest','ChatModelLoggingTest','OpenAiSseDecoderTest','OpenAiChatModelClientTest','OpenAiMultiTurnModelTest','ChatControllerTest','ChatServiceAdapterTest','ChatServiceLifecycleTest','ChatObservationTest','AiObservedChatTest','AiObservationClientTest','MultiTurnChatServiceTest','MultiTurnBudgetCompatibilityTest','ConversationAppClientTest','RetrievalClientTest','ChatQuotaAuditServiceTest','SafeChatLoggingTest','SearchMigrationChatContractTest','NoDatabaseAccessTest','ReactiveStackTest')
mvn -o -pl kb-ai -am test "-Dtest=$($tests -join ',')" '-Dsurefire.failIfNoSpecifiedTests=false' '-DreuseForks=false'

$tests = @('TelemetryConfigurationTest','HistogramConfigurationTest','HttpMetricsCardinalityTest','HttpObservationPrivacyTest','TelemetrySpanFilterTest','TelemetryLoggingTest','ManagementConfigurationTest','PrometheusExemplarConfigurationTest','TaskTelemetryPrivacyTest','EmbeddingSupplierIsolationTest','EmbeddingProfilesPropertiesTest','EmbeddingEnvironmentValidatorTest','EmbeddingMetricsTest','DefaultParsePipelineTest','ParseTaskClaimServiceTest','ParseTaskTracingTest','ParseTaskRunnerRecoveryTest','ParseTaskMetricsTest','TaskContextPropagationTest')
mvn -o -pl kb-worker -am test "-Dtest=$($tests -join ',')" '-Dsurefire.failIfNoSpecifiedTests=false' '-DreuseForks=false'

$tests = @('TelemetryConfigurationTest','HistogramConfigurationTest','HttpMetricsCardinalityTest','TelemetrySpanFilterTest','TelemetryLoggingTest','ManagementConfigurationTest','PrometheusExemplarConfigurationTest','ReactiveRequestContextTest','TraceBoundaryTest','CorsConfigurationTest','SearchRouteTest','SaTokenGatewayConfigTest','InboundHeaderSanitizeFilterTest')
mvn -o -pl kb-gateway -am test "-Dtest=$($tests -join ',')" '-Dsurefire.failIfNoSpecifiedTests=false' '-DreuseForks=false'

mvn -o -pl kb-common test '-Dtest=ProductionSettingsTest,RequestSignatureTest' '-DreuseForks=false'

foreach ($module in @('kb-app', 'kb-ai', 'kb-worker', 'kb-gateway')) {
    mvn -o -pl $module -am package '-DskipTests'
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}
```

四次打包均为 `BUILD SUCCESS`，产物位于各模块 `target/<模块>-0.1.0-SNAPSHOT.jar`。打包阶段按命令跳过测试执行，上述 415 项是此前独立 test 阶段的实际结果。所有受测业务依赖均为 Mock/Stub，跨度仅在内存中导出，Prometheus 仅调用注册表 `scrape()`；未启动应用服务或监听端口，未运行既有外部环境烟测或连接数据库、Redis、对象存储、模型和监控平台。Java 变更的 Git 空白检查通过。

本次守卫曾复现并修复：Boot 3.3.5 关闭开关仍创建本地跨度/exemplar，以及异常事件和状态描述携带原始异常消息。修复后的回归验证了无操作 tracer、供应商隔离、内部传播、MDC、固定指标标签、分桶、属性/事件过滤和任务执行顺序。

最终执行以下严格校验，退出码为 0，输出 `Change 'backend-trace-propagation-and-metrics-export' is valid`：

```powershell
openspec validate backend-trace-propagation-and-metrics-export --strict
```

校验确认 OpenSpec 变更结构有效；文档没有作为测试断言对象，真实部署、采集链路与供应商联调不属于本次离线验证。

### 归档前核验问题修复（2026-11-02）

上述 415 项是修复前的历史结果，不作为本轮修复通过的证据。核验发现并修复了以下实际装配与生命周期问题：

- `ParseTaskMetrics` 明确注入构造，避免 Spring 选择无参测试构造、使用私有 registry 且跳过队列刷新。新增 `ParseTaskMetricsExportTest` 用内存 Spring、Mock JDBC 与 Prometheus registry 验证实际导出。
- 无标签重试/死信总量保持不变；按已确认的兼容方案将分类序列拆为 `.by_class`，同时验证 Prometheus 文本和 OpenMetrics 输出中的总量、分类、队列值与任务直方图。
- 四模块在路由模板出现前识别固定管理类别，过滤 `.active`。MockMvc/WebFlux 生命周期测试覆盖默认/自定义管理前缀、应用 context path，并确认相似业务前缀不被误过滤。
- 生产端口按数值比较，数字 IP 地址规范化后拒绝通配绑定；覆盖前导零、正号、IPv6 压缩/括号与 IPv4 等价写法，错误不含原始值或原因链。

先执行 `mvn -o -pl kb-common test '-Dtest=ProductionSettingsTest' '-DreuseForks=false'`，55 项通过；随后以下定向回归 98 项通过（common 55、app 10、gateway 11、Worker 13、AI 9），失败、错误、跳过均为 0：

```powershell
mvn -o -pl kb-app,kb-ai,kb-worker,kb-gateway -am test '-Dtest=ProductionSettingsTest,HttpMetricsCardinalityTest,HttpObservationPrivacyTest,ParseTaskMetricsTest,ParseTaskMetricsExportTest,ManagementConfigurationTest,HistogramConfigurationTest,PrometheusExemplarConfigurationTest,TraceBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DreuseForks=false'
```

本轮最终定向回归与打包使用以下显式名单；首次定向编译遇到测试误用 `SimpleMeterRegistry` 的 try-with-resources，已改为 finally 关闭后重跑通过：

执行记录：首轮长命令在 app 与 AI 通过后，于 Worker 回归期间触及工具的 120 秒执行时限而终止；随后取消工具时限，重新执行完整 Worker 名单，并继续 gateway、common 与四模块打包。未把中断轮次计为完整通过，也未重复累计测试项。

```powershell
$tests = @('TelemetryConfigurationTest','HistogramConfigurationTest','HttpMetricsCardinalityTest','HttpObservationPrivacyTest','TelemetrySpanFilterTest','TelemetryLoggingTest','ManagementConfigurationTest','PrometheusExemplarConfigurationTest','InternalPropagationTest','SupplierPropagationTest','RequestIdFilterTest','EmbeddingSupplierIsolationTest','EmbeddingProfilesPropertiesTest','EmbeddingEnvironmentValidatorTest','EnvironmentIsolationTest','ModuleBoundaryTest')
mvn -o -pl kb-app -am test "-Dtest=$($tests -join ',')" '-Dsurefire.failIfNoSpecifiedTests=false' '-DreuseForks=false'

$tests = @('TelemetryConfigurationTest','HistogramConfigurationTest','HttpMetricsCardinalityTest','HttpObservationPrivacyTest','TelemetrySpanFilterTest','TelemetryLoggingTest','ManagementConfigurationTest','PrometheusExemplarConfigurationTest','InternalPropagationTest','ReactiveRequestContextTest','ModelSupplierPropagationTest','ModelTelemetryTest','StreamTelemetryTest','MultiTurnStreamTelemetryTest','OpenAiChatTransportTest','ChatModelConfigurationTest','ChatModelLimitsTest','ChatModelLoggingTest','OpenAiSseDecoderTest','OpenAiChatModelClientTest','OpenAiMultiTurnModelTest','ChatControllerTest','ChatServiceAdapterTest','ChatServiceLifecycleTest','ChatObservationTest','AiObservedChatTest','AiObservationClientTest','MultiTurnChatServiceTest','MultiTurnBudgetCompatibilityTest','ConversationAppClientTest','RetrievalClientTest','ChatQuotaAuditServiceTest','SafeChatLoggingTest','SearchMigrationChatContractTest','NoDatabaseAccessTest','ReactiveStackTest')
mvn -o -pl kb-ai -am test "-Dtest=$($tests -join ',')" '-Dsurefire.failIfNoSpecifiedTests=false' '-DreuseForks=false'

$tests = @('TelemetryConfigurationTest','HistogramConfigurationTest','HttpMetricsCardinalityTest','HttpObservationPrivacyTest','TelemetrySpanFilterTest','TelemetryLoggingTest','ManagementConfigurationTest','PrometheusExemplarConfigurationTest','TaskTelemetryPrivacyTest','EmbeddingSupplierIsolationTest','EmbeddingProfilesPropertiesTest','EmbeddingEnvironmentValidatorTest','EmbeddingMetricsTest','DefaultParsePipelineTest','ParseTaskClaimServiceTest','ParseTaskTracingTest','ParseTaskRunnerRecoveryTest','ParseTaskMetricsTest','ParseTaskMetricsExportTest','TaskContextPropagationTest')
mvn -o -pl kb-worker -am test "-Dtest=$($tests -join ',')" '-Dsurefire.failIfNoSpecifiedTests=false' '-DreuseForks=false'

$tests = @('TelemetryConfigurationTest','HistogramConfigurationTest','HttpMetricsCardinalityTest','TelemetrySpanFilterTest','TelemetryLoggingTest','ManagementConfigurationTest','PrometheusExemplarConfigurationTest','ReactiveRequestContextTest','TraceBoundaryTest','CorsConfigurationTest','SearchRouteTest','SaTokenGatewayConfigTest','InboundHeaderSanitizeFilterTest')
mvn -o -pl kb-gateway -am test "-Dtest=$($tests -join ',')" '-Dsurefire.failIfNoSpecifiedTests=false' '-DreuseForks=false'

mvn -o -pl kb-common test '-Dtest=ProductionSettingsTest,RequestSignatureTest' '-DreuseForks=false'

foreach ($module in @('kb-app', 'kb-ai', 'kb-worker', 'kb-gateway')) {
    mvn -o -pl $module -am package '-DskipTests'
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}
```

最终结果：87 个测试类、446 项测试通过（app 50、AI 239、Worker 59、gateway 40、common 58），失败、错误、跳过均为 0；四模块离线打包全部为 `BUILD SUCCESS`。打包阶段的 `-DskipTests` 不计入这 446 项单独 test 阶段结果。Java Git 空白检查通过。

修复后再次执行 `openspec validate backend-trace-propagation-and-metrics-export --strict`，退出码为 0，返回 `Change 'backend-trace-propagation-and-metrics-export' is valid`。

所有验证仍限于进程内 Mock/Stub、内存注册表和静态构建，未启动应用服务或监听端口，未连接业务基础设施。前置代理上线日志核查与真实采集链路仍待部署阶段验证，不能据此宣称端到端日志保密已验证。

### 搜索线程关联与内部接收端补强（2026-11-02）

前一轮 446 项为历史结果。本轮补齐 `SearchContextPropagation`，用模块内白名单快照为搜索主工作和可选阶段恢复 Observation、独立 span 与 requestId；保留直接构造测试入口，生产注入路径另有行为断言。真实 SearchService 降级测试同时核对双线程上下文、不同请求复用线程、异常与取消后真实退出清理，关闭追踪时仍保留 requestId 且无跨度；其他 MDC canary 不会被复制。

app 的 `InternalPropagationTest` 与 AI 的 `InternalReceptionTest` 分别处理已知 W3C 父上下文，断言服务端跨度、日志和后续真实内部客户端调用沿用该 trace，并确认 requestId 不使用入站 baggage、不作为远程 baggage 传播。AI 接收测试单独成类配合 `reuseForks=false`，避免同 JVM 先前 OTel SDK 初始化干扰 MDC 监听器；保留全部断言，不通过调整方法顺序消除干扰。Reactor 调度线程屏障用于等待上游 doFinally 完成，内存 SDK forceFlush 后再检查导出结果。

执行过程曾发现测试在批量导出器 forceFlush 前读取结果，以及同一测试类多次初始化 OTel 的干扰，均修正后重新运行。修正后的定向命令如下，11 项通过（common 3、app 6、AI 2），失败、错误、跳过均为 0：

```powershell
mvn -o -pl kb-app,kb-ai -am test '-Dtest=SearchContextPropagationTest,InternalPropagationTest,InternalReceptionTest,RequestSignatureTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DreuseForks=false'
```

搜索执行器的既有并发、预算、降级和候选路径使用以下显式名单补充回归；该轮 app 51 项、common 3 项通过，AI 接收测试在拆为独立测试类前失败，不将该轮视为整体通过：

```powershell
mvn -o -pl kb-app,kb-ai -am test '-Dtest=SearchContextPropagationTest,InternalPropagationTest,RequestSignatureTest,SearchExecutorTest,SearchExecutorRaceTest,SearchBudgetTest,SearchAdmissionTest,SearchMetricsTest,SearchFallbackTest,SearchFacadeTest,SearchOptionExecutionTest,SearchPropertiesTest,SearchServiceTest,SearchOptionServiceTest,SearchFilterServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DreuseForks=false'
```

本轮最终 app/AI 回归命令如下；Worker、gateway、common 以及四模块 `package '-DskipTests'` 均重新执行上一节的相同显式命令（不执行全量或通配测试）：

```powershell
$tests = @('TelemetryConfigurationTest','HistogramConfigurationTest','HttpMetricsCardinalityTest','HttpObservationPrivacyTest','TelemetrySpanFilterTest','TelemetryLoggingTest','ManagementConfigurationTest','PrometheusExemplarConfigurationTest','InternalPropagationTest','SupplierPropagationTest','RequestIdFilterTest','EmbeddingSupplierIsolationTest','EmbeddingProfilesPropertiesTest','EmbeddingEnvironmentValidatorTest','EnvironmentIsolationTest','ModuleBoundaryTest','SearchContextPropagationTest','SearchExecutorTest','SearchExecutorRaceTest','SearchBudgetTest','SearchAdmissionTest','SearchMetricsTest','SearchFallbackTest','SearchFacadeTest','SearchOptionExecutionTest','SearchPropertiesTest','SearchServiceTest','SearchOptionServiceTest','SearchFilterServiceTest')
mvn -o -pl kb-app -am test "-Dtest=$($tests -join ',')" '-Dsurefire.failIfNoSpecifiedTests=false' '-DreuseForks=false'

$tests = @('TelemetryConfigurationTest','HistogramConfigurationTest','HttpMetricsCardinalityTest','HttpObservationPrivacyTest','TelemetrySpanFilterTest','TelemetryLoggingTest','ManagementConfigurationTest','PrometheusExemplarConfigurationTest','InternalPropagationTest','InternalReceptionTest','ReactiveRequestContextTest','ModelSupplierPropagationTest','ModelTelemetryTest','StreamTelemetryTest','MultiTurnStreamTelemetryTest','OpenAiChatTransportTest','ChatModelConfigurationTest','ChatModelLimitsTest','ChatModelLoggingTest','OpenAiSseDecoderTest','OpenAiChatModelClientTest','OpenAiMultiTurnModelTest','ChatControllerTest','ChatServiceAdapterTest','ChatServiceLifecycleTest','ChatObservationTest','AiObservedChatTest','AiObservationClientTest','MultiTurnChatServiceTest','MultiTurnBudgetCompatibilityTest','ConversationAppClientTest','RetrievalClientTest','ChatQuotaAuditServiceTest','SafeChatLoggingTest','SearchMigrationChatContractTest','NoDatabaseAccessTest','ReactiveStackTest')
mvn -o -pl kb-ai -am test "-Dtest=$($tests -join ',')" '-Dsurefire.failIfNoSpecifiedTests=false' '-DreuseForks=false'
```

本轮最终完整显式回归共 101 个测试类、497 项测试通过：app 29 类/100 项、AI 37 类/240 项、Worker 20 类/59 项、gateway 13 类/40 项、common 2 类/58 项，失败、错误、跳过均为 0。统计仅包含本轮各模块独立 test 阶段，不重复累计前述定向验证、失败诊断或历史轮次。

四模块均重新执行 `mvn -o -pl <模块> -am package '-DskipTests'` 并返回 `BUILD SUCCESS`；打包阶段显式跳过测试，不计入上述 497 项测试结果。所有测试与构建仍限于进程内 Mock/Stub、内存注册表/导出器和静态构建，未启动应用服务、监听端口或连接业务基础设施。

收尾再次执行 `openspec validate backend-trace-propagation-and-metrics-export --strict`，退出码为 0，返回 `Change 'backend-trace-propagation-and-metrics-export' is valid`；`git -c core.safecrlf=false diff --check -- '*.java'` 同样退出码为 0。文档不作为测试断言对象。

前置代理上线日志核查仍需要部署环境证据；本轮不执行部署，不将其标为已验证。
