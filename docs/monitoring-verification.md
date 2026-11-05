# 监控与钉钉交付验证记录

日期：2026-11-05。变更：`deliver-monitoring-alerts-and-dingtalk`。

## 范围与结果

仅执行进程内 Java 测试、Mock/内存传输、合成时序、Maven 编译打包、本地 CLI 静态配置检查。没有启动完整应用、HTTP 监听替身、前端、数据库、Redis、监控组件、容器或浏览器，没有真实钉钉发送。没有新增文档、README、Markdown、注释内容断言测试。

下表和“实际命令”保留首次交付时的历史结果；首事件 P95 核验问题的修复与本轮定向复测见后文，不与历史测试数重复累计。

| 检查 | 实际结果 |
| --- | --- |
| 通知模块独立 package | 158 tests，0 failures，0 errors，0 skipped；可执行 JAR 生成成功 |
| 指标及相关既有白名单回归 | 111 tests，0 failures，0 errors，0 skipped；common 2、app 37、Worker 41、AI 31 |
| 新增指标最初定向回归 | 36 tests 通过，已包含于上项 111，不重复累计 |
| 统一监控静态检查 | 55 步通过，0 失败，0 未执行 |
| 配置生成/边界/provisioning | 154 项行为检查通过，生成 14 个配置文件 |
| promtool 合成时序 | 52+1 组通过；20 类告警各有等待、触发、解除、不触发场景；38 个实际面板查询与正向状态/去重场景 |
| amtool 离线路由 | 目录允许的 39 组告警/服务组合通过；不冒充运行时抑制测试 |
| Compose 解析与语义 | 102 项模型检查、13 项非法变体拒绝、9 项配置联接检查通过 |
| 缺工具回归 | 16 项通过；子验证按预期 exit=1（4 步通过、3 失败、48 未执行），分别保留 127/126 |
| 静态镜像 fixture 发布拒绝 | 未带 AllowStaticImageFixture 时预期 exit=1，拒绝测试占位镜像 |
| 通知依赖树 | 无业务模块、数据库/JDBC、Redis、Sa-Token、Spring Cloud、Swagger 运行依赖 |
| OpenSpec 严格校验 | `openspec validate deliver-monitoring-alerts-and-dingtalk --strict --json`：valid=true、issues=[] |

Java 合计 **269 项**，不含重复执行次数。工具版本：Temurin JDK 21.0.7、Maven 3.9.9、PowerShell 7、promtool 3.5.0、amtool 0.28.1、Compose CLI 2.39.2。CLI 执行前按 `ops/monitoring/versions.json` 校验可执行文件 SHA-256；镜像版本与官方 manifest 摘要见同文件。

## 实际命令

从仓库根目录运行，PowerShell 7；本地 Maven 缓存须已有依赖，检查脚本不会联网安装工具。

```powershell
mvn -o -pl kb-alert-notifier package
mvn -o -pl kb-alert-notifier dependency:tree "-DoutputFile=$env:PI_SCRATCH_DIR\notifier-dependency-tree.txt"

$tests = 'MonitoringSampleTest,ParseTaskMetricsExportTest,ParseTaskMetricsTest,EmbeddingMetricsTest,EmbeddingMetricsReaderTest,ParserConfiguredMetricsTest,MonitoringSnapshotConcurrencyTest,MonitoringSamplingConfigurationTest,MonitoringFeatureMetricsTest,SettlementMetricsReaderTest,SettlementMetricsTest,SearchMetricsTest,ModelTelemetryTest,StreamTelemetryTest,TelemetryConfigurationTest,ManagementConfigurationTest,HistogramConfigurationTest,TelemetrySpanFilterTest,HttpMetricsCardinalityTest'
mvn -o -pl kb-worker,kb-app,kb-ai -am test "-Dtest=$tests" '-Dsurefire.failIfNoSpecifiedTests=false'

pwsh -NoProfile -File .\ops\monitoring\scripts\verify.ps1 -ToolDirectory (Join-Path $env:PI_SCRATCH_DIR 'monitoring-tools') -ScratchDirectory $env:PI_SCRATCH_DIR
pwsh -NoProfile -File .\ops\monitoring\tests\deployment.tests.ps1 -TestMissingTools -ScratchDirectory $env:PI_SCRATCH_DIR
openspec validate deliver-monitoring-alerts-and-dingtalk --strict --json
git diff --check
```

`failIfNoSpecifiedTests=false` 只允许依赖模块没有同名白名单类，不跳过已选测试；实际所有已选测试 skipped=0。未运行全量测试，以免带入既有外部组件/监听型用例。通知模块全部测试为进程内，因此独立 package 不用 `-am` 或 `skipTests`。

统一入口逐步执行：工具完整性和版本、配置行为、渲染、时序生成、`promtool check config/check rules/test rules`（发现全部 `*.test.json`）、`amtool check-config/config routes test`、`compose config --format json`、模型语义检查。任一失败或未执行都会返回非零，不以 Mock 回执或静态输出声称可部署。

本次原始日志留在会话 scratch：`notifier-package-final.log`、`notifier-dependency-tree.txt`、`monitoring-regression-final.log`；最后统一报告为 `monitoring-verify-b8af93d6993742fdbbcff8b11e6aa703/verification-report.json`，包含全部步骤、退出码与对应日志名。缺工具报告位于 `missing-tools-dbbc75dbc577453fb39dac3d4e73eaee/monitoring-verify-3a63099fda514696b97afba026a091bd/verification-report.json`。scratch 是临时证据，不作为部署输入或长期构建制品。

## 实现与人工核对索引

- 1.1—1.5：`kb-alert-notifier/pom.xml`、NotifierProperties/Settings/Bootstrap、AlertCatalog、ConfigurationTest/CatalogTest；`docs/alert-notifier-operations.md`。依赖树和专用配置不继承业务 Redis 检查；网关未增加通知路由。
- 2.1—2.5：Worker ParseTaskMetrics/EmbeddingMetrics/EmbeddingMetricsReader/DocumentParserConfiguration、MonitoringJdbc/MonitoringSchedule/MonitoringSnapshotExporter；对应测试及 `docs/parse-task-recovery-operations.md`、`docs/telemetry-operations.md`。既有 Counter/Timer 名称与标签未更改，数据库事件仍为 Gauge。
- 3.1—3.4：app conversation/monitoring、app/AI MonitoringFeatureMetrics；SettlementMetricsReaderTest/SettlementMetricsTest/MonitoringFeatureMetricsTest/MonitoringSnapshotConcurrencyTest；`docs/conversation-configuration.md`。单聚合 SQL 字段与 V18 表结构人工核对：PENDING/REVIEW，排除 RESERVED/SETTLED，无 next_attempt_at/claimed_until 过滤，不查询用户正文，不锁行或写业务状态。
- 4.1—4.8：IngressFilter、BoundedJson、Webhook、Markdown、DingTalkProtocol、DeliveryService、JdkTransport、BoundedBody、Admission、Deadline、NotifierMetrics 与九个测试类。固定结果枚举与规则对齐为小写 `success`、`rate/concurrency`；每次最多一次发送，不持久去重，取消后等待网络任务实际退出才回收槽位。
- 5.1—5.8：`ops/monitoring/alert-catalog.json`、settings、Monitoring.Config/Rules、config.tests/build-rule-tests、告警运维说明。按实例质量筛选再 max；Counter 先 increase 后 sum，桶合并再算 P95；所有条件保留部署隔离。采样根因用来源级门控，避免服务级抑制掩盖其他有效副本/来源；Alertmanager 抑制仅同 environment/deployment/service 和必要 family。
- 6.1—6.5：Compose、versions、notifier.Dockerfile、render-config/verify、deployment.tests 与部署运维说明。未构建自有镜像，不能编造 digest；基础镜像固定，最终自有 tag+digest 作为部署必填输入，静态 fixture 明确不可发布。
- 7.1—7.4：Monitoring.Dashboards 与生成的 datasource/provider，三组固定 UID、38 个面板。即时状态区分未部署/失联/不可用/关闭/无流量，查询不填绿色零；管理链接与既有 AdminGuard 路由核对，保留登录和权限检查。

所有说明均人工核对，没有以文档字符串存在作为实现证据。一次可选独立代码复核因工具会话 API 错误终止，未得到结论，不计为复核通过；主代理完成了必要的代码、契约和差异核对。
## 官方协议静态依据

已读取 https://open.dingtalk.com/document/robots/customize-robot-security-settings.md 的签名公式、毫秒 timestamp 和 access_token/timestamp/sign 参数。2026-11-05T12:30:29Z 获取的原始正文 SHA-256 为 `56653ec491c1ec2c0ef6080c4b0820fa2af783c2a3173409f8cc3b0647b28288`。实现的 HMAC-SHA256 key/message、Base64、URL 编码及同一 timestamp 与示例一致。

Markdown 依据 https://open.dingtalk.com/document/orgapp/custom-bot-send-message-type.md ，原始正文 SHA-256 为 `5d17e47290b4dc620a12ce1d7cf84f924579e99dc7fec7417b9f98b96d250204`。已核对固定 msgtype 和嵌套 title/text；未验证客户端渲染。

https://open.dingtalk.com/document/orgapp/custom-robots-send-group-messages.md 内部存在 errcode 参数表 Number/返回示例字符串的差异。结合已读阿里云官方整数 0 示例，保持本变更批准的严格整数零成功条件，字符串零不确认；不据静态文档推断线上类型。正文、元数据与完整报告保存在 scratch 的 `dingtalk-official-contract`，没有调用真实机器人 endpoint。


## 首事件 P95 核验问题修复复测

核验发现任务 7.3 的模型首字、SSE 首内容 P95 面板查询 `_bucket`，但原 Timer 只导出 count/sum/max。现已在 AI 的 HistogramConfiguration 为两个 Timer 增加专用 `kb.telemetry.histograms.first-event` 分桶，默认 100ms、500ms、1s、2s、5s、10s、30s、60s；不改原指标名、面板查询或模型整次调用分桶。配置和语义见 `docs/telemetry-operations.md`。

- 先在旧实现运行新增 FirstEventHistogramTest：10 项中 9 项预期失败、0 errors，证实导出及配置回归能检测缺口。
- 修复后定向 Java 回归：35 项通过，0 failures、0 errors、0 skipped；其中新增 10 项覆盖 Prometheus/OpenMetrics 实际桶值、count/sum、标量/列表配置、非法边界、无内容不造样本，以及 citation 先于 delta 时仅记录首次内容。
- 统一 CLI 55 步全部通过，0 失败、0 未执行；合成时序增至 53+1 组。新增双副本场景直接执行两条实际面板表达式，分别得到模型首字 P95=1.5 秒、SSE 首内容 P95=0.3 秒，保留环境/部署标签且排除其他环境和部署的高延迟样本。
- 使用实际 ModelTelemetry、StreamTelemetry 和进程内 PrometheusMeterRegistry 重跑原探针，两个指标均为 `count=true bucket=true`。这不是 Grafana 渲染或部署运行验证。

本轮实际命令（PowerShell 7）：

```powershell
mvn -o -pl kb-ai -am test '-Dtest=FirstEventHistogramTest,HistogramConfigurationTest,ModelTelemetryTest,StreamTelemetryTest,MultiTurnStreamTelemetryTest,TelemetryConfigurationTest' '-Dsurefire.failIfNoSpecifiedTests=false'
pwsh -NoProfile -File .\ops\monitoring\scripts\verify.ps1 -ToolDirectory (Join-Path $env:PI_SCRATCH_DIR 'monitoring-tools') -ScratchDirectory $env:PI_SCRATCH_DIR
```

原始日志保存在本轮 scratch 的 `first-event-histogram-before-fix.log`、`first-event-histogram-regression.log`、`first-event-monitoring-cli.log`；统一报告为 `monitoring-verify-195b7ee8129d4fd6918bbaa226ef043c/verification-report.json`。本轮没有启动服务、数据库、容器、浏览器或真实发送通知，没有增加文档内容断言测试。

## 明确未验证的内容

- JDBC/Mapper Mock 不能证明真实 PostgreSQL SQL 已执行、执行计划或生产数据规模性能。
- Compose config 不连接 Docker daemon，不验证镜像运行、宿主网络、secret ACL 到容器 UID 的实际映射、持久卷恢复或 Grafana 渲染。
- 未构建/推送自有 notifier 镜像，未伪造其 digest；运行环境仍需提供真实固定镜像引用、管理地址与 secret 文件。
- 没有对真实机器人发送请求，签名向量与内存响应不证明真实送达。单套监控及同一故障通知链路不保证自报整体失效。
- 不执行部署、归档或扩大到 HA、自动恢复、逐任务通知等范围。
