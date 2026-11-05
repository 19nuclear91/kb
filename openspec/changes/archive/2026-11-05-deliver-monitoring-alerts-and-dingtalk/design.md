# Design

## Context

动机与范围见 [proposal.md](proposal.md)。用户已确认独立监控组件、钉钉群机器人、聚合状态通知，以及项目内维护的小型 Java 适配器。

本设计基于以下现有实现：

| 位置 | 当前事实及设计影响 |
| --- | --- |
| `kb-worker/.../parse/ParseTaskMetrics.java` | 每个实例查询同一数据库的队列、死信与年龄；缺少采样失败／新鲜度信号，多副本不能求和 |
| `kb-worker/.../embedding/EmbeddingMetrics.java` | 已有模式、错误、进度及 available；持久事件数量是数据库 Gauge，不是可跨实例累加的进程 Counter |
| `kb-worker/.../parse/TaskAlertOutbox.java` | 日志投递发生在带行锁的事务中；本次不将钉钉 HTTP 接到该事务，也不新增逐任务通知 |
| `kb-app/.../aiusage/AiUsageMaintenance.java` 与 `AiUsageMapper.xml` | unresolved 混合 RUNNING、RESERVED、PENDING、REVIEW，且观测数据有覆盖边界，不适合作为结算权威指标 |
| `kb_conversation_settlement` | 已有 PENDING、REVIEW、finalized_at 等字段，可只读采样；新会话关闭不等于旧账本消失 |
| `SearchMetrics`、`ModelTelemetry`、`StreamTelemetry` | 已有业务结果、阶段耗时、模型调用和 SSE 结果；首个内容事件包含 citation，不可标成模型首字延迟 |
| `ProductionSettings` 与遥测配置 | 原四个服务生产管理端口必须独立且显式绑定，拒绝通配地址；通用生产检查还对非 Worker 要求 Redis，通知模块不能直接套用 |
| `ops/` | 只有 Office 的专项部署配置；没有监控部署、规则或面板 |

需要改动多个模块并接入外部通知，因此本次包含设计文档。已有主规格的授权、索引发布、额度结算和指标兼容约束保持适用。

## Goals / Non-Goals

**Goals:**

- 交付一套能独立部署、定位异常并进行聚合通知的监控闭环。
- 新规则只使用可信数据，区分缺失、采样失败、功能关闭、维护和业务失败。
- 监控与通知的故障不参与业务事务；协议及规则可在无真实群凭据时验证。

**Non-Goals:**

- 首期不提供 HA、跨重启持久通知去重、告警事件归档系统或自动故障修复。
- 不改造任务告警 Outbox 为 HTTP 队列，不通过通知读取业务正文、重试任务或处理结算 REVIEW。
- 不新增 Loki、Tempo、数据库／主机 exporter；数据库和对象存储底层容量告警属于后续专项。

## Decisions

### 1. 四个监控组件与单一通知路径

```text
app / worker / ai / gateway
             |
             v
         Prometheus ----> Grafana
             |
             v
        Alertmanager
             |
             v
     kb-alert-notifier ----> DingTalk
```

在 `ops/monitoring` 提供独立 Compose，四个组件均单实例。Prometheus 保留期初始 15 天并可配置；Prometheus、Alertmanager 和 Grafana 使用独立持久卷。镜像使用明确 tag 和 digest，不使用 latest；具体版本在实现时核对并写入版本清单，按对应版本检查配置契约。本变更不运行容器构建或部署联调，不将静态核对记为镜像运行验证。首次依赖校验不得使用未确认版本的字段。

应用目标由 `settings.json` 声明环境、部署 ID、预期服务及实际管理端点，生成 file_sd 文件。每个部署只对应一套业务数据库；同库应用共用部署 ID，独立数据库使用不同 ID。保留独立的预期服务记录规则，即使 file_sd 被误删仍能识别缺失。可选服务必须显式设为不预期；不能依据当前 up 是否存在决定它是不是必需服务。

监控 UI 默认只发布到宿主机回环地址，远程访问由受控管理网络或已认证代理提供。容器访问宿主机时使用明确的可达目标；现有应用配置仍须提供具体管理绑定地址，不能为方便改成 0.0.0.0。适配器 HTTP 与管理端口只在隔离的监控网络暴露，Compose 不发布公网端口。适配器需要钉钉出站网络，其配置和令牌以只读 secret 文件挂载。

选择独立 Compose 是为了适配当前仓库交付方式；不依赖公司已有监控，也不把所有业务服务迁入本次 Compose。采用 Prometheus 规则加 Alertmanager，不同时开启 Grafana 的第二套重复告警。

### 2. 指标契约与只读采样

以下名称为 Micrometer 名称；导出名称须由 Prometheus registry 测试固定，点转换为下划线，Counter／Timer 保留标准后缀。

| 指标 | 单元 | 含义 |
| --- | --- | --- |
| `kb.monitoring.sample.available{source}` | Worker / app | source=parse、embedding、settlement；最近一次来源采样是否完整成功 |
| `kb.monitoring.sample.last_success_timestamp_seconds{source}` | Worker / app | 最后完整成功的 UTC Unix 秒；从未成功为 0 |
| `kb.parse.parser.configured` | Worker | `DocumentParser.isAvailable()` 的本地配置事实；不进行远程探活 |
| `kb.conversation.settlement.pending` | app | 全部 PENDING，包括退避中或已被领取的记录 |
| `kb.conversation.settlement.review` | app | 全部 REVIEW |
| `kb.conversation.settlement.oldest_pending_age_seconds` | app | 当前 PENDING 的最老 finalized_at 年龄，空集为 0，时钟偏差向下限 0 收敛 |
| `kb.monitoring.feature.enabled{feature}` | app / AI | feature=conversation、ai_observation 的本地有效开关 |

采样默认每 30 秒，数据库语句超时初始 3 秒，不在 Prometheus 抓取时查询数据库。每个来源先读入临时结果，全部验证成功后发布不可变快照；失败发布不可用状态并保留上次成功时间。新增质量指标从启动即注册，避免“尚未采样”与“契约不存在”混淆。既有 `kb.embedding.metrics.available` 与新增 embedding 来源可用性保持一致。

同一来源导出应捕获完整快照，防止部分读取失败被标为新成功；如实现需要跨多个 gauge 读取，须通过来源级导出快照保证跨发布边界的观察可被识别为无效。具体适配现有 registry 的方式由实现确定，并以并发采样／抓取行为测试验证，不仅断言字段赋值顺序。

解析维持现有谓词：queue.depth 只计已到可尝试时间的 pending，oldest_age 继续使用现有 pending 的 COALESCE(next_attempt_at, created_at)。面板分别解释“可尝试队列深度”与“pending 最老等待”，规则不能把两者当成同一过滤集合。Embedding 的状态和持久事件总数保留原有语义。

结算使用一个只读聚合语句读取 `kb_conversation_settlement` 的 PENDING、REVIEW 及最早 finalized_at，不关联用户、会话正文或 AI 观测表。采样组件在 app 内始终装配，不受会话或观测开关控制。观测功能开关只解释页面覆盖，不门控此查询。初期不新增迁移；查询超时产生采样故障，不通过锁业务行或自动改索引掩盖性能问题。

替代方案是直接用 `kb.ai.observation.unresolved` 告警；由于它混入正常请求且可以没有完整采集，本次不采用。

### 3. 聚合、缺失与告警身份

静态抓取标签使用 `environment`、`deployment`、`service`，instance 仅用于内部采样／抓取诊断，不进入钉钉正文。标签值来自受验证部署配置。预期服务与固定质量 gauge 是契约检查对象；业务 Counter 可能尚未注册，只有服务和相应契约健康时才可将尚无事件解释为无流量。

数据库汇总先与同一实例的 available=1、有效且不在未来的成功时间、`time()-last_success<=120`、up=1 连接，再按环境和部署取 max 去重。各状态的 one-hot Gauge 也只从可信样本判断；短暂副本采样差异保守展示，不能把多副本 mode 值求和。任何单副本采样异常可见；全部副本不可用时停止业务数值断言并告警。跨机器时钟需要同步，未来时间不解释为无限新鲜。

Counter 先逐序列计算 rate/increase 再按服务聚合，避免进程重置被其他实例掩盖；直方图按 le 合并后计算分位数，不能平均各实例 P95。独立部署之间不做总和，查询和规则必须保留环境与部署标签。

Alertmanager 以 `environment, deployment, service, alertname` 分组。severity 作为稳定分类供路由／抑制使用，当前数值、时间戳、实例地址不进入告警身份标签。模板消费固定告警目录；目录与规则名称由同一配置来源生成，未知名称拒绝。

### 4. 首期规则目录和建议初值

以下是可调整的启动值，不是已验证的生产 SLO。数值和持续时间统一放入设置文件并生成规则，避免以不存在的 PromQL 环境变量替换能力实现配置。

| 告警名 | 条件口径 | 初始等级／等待 |
| --- | --- | --- |
| `KbServiceUnavailable` | 必需服务没有存活目标，包含目标列表消失 | critical / 2 分钟 |
| `KbServiceReplicaUnavailable` | 部分目标失联但至少一个存活，聚合副本数 | warning / 5 分钟 |
| `KbMonitoringContractMissing` | 存活目标缺少固定质量／开关契约序列 | warning / 2 分钟 |
| `KbMetricSampleUnavailable` | 任一来源失败、未采样或成功时间超过 120 秒 | warning / 2 分钟 |
| `KbParserNotConfigured` | 预期解析开启，存活 Worker 的本地 configured 全为 0 | critical / 5 分钟 |
| `KbParseQueueAging` | 可信最老等待超过 900 秒；解析已配置且 Embedding ACTIVE | warning / 5 分钟 |
| `KbParseDeadLetters` | 可信 OPEN 死信数量大于零 | warning / 5 分钟 |
| `KbParseLeaseLoss` | 15 分钟 lease_lost 增量至少 3 | warning / 5 分钟 |
| `KbEmbeddingBlocked` | 可信 BLOCKED 状态 | critical / 2 分钟 |
| `KbEmbeddingMaintenanceLong` | 可信 UNINITIALIZED 或 QUIESCING／REBUILDING／VERIFYING 持续，预期解析开启 | warning / 60 分钟 |
| `KbSearchFailures` | FAILED / (SUCCESS+EMPTY+FAILED)，5 分钟分母至少 20，比例超过 10% | critical / 5 分钟 |
| `KbSearchRetrievalSlow` | RETRIEVAL 阶段 5 分钟至少 20 样本，P95 超过 2.5 秒 | warning / 5 分钟 |
| `KbSearchDegradation` | Embedding 或 rerank 降级持续；5 分钟至少 20 个搜索且同阶段降级超过 20% | warning / 10 分钟 |
| `KbModelFailures` | TIMEOUT、PROTOCOL_ERROR、UPSTREAM_ERROR / 非 CANCELLED 调用，5 分钟至少 10，比例超过 20% | critical / 5 分钟 |
| `KbChatSystemErrors` | TIMEOUT 或非 INPUT_BUDGET_EXCEEDED 的 ERROR / 非 CANCELLED 流，5 分钟至少 10，比例超过 20% | critical / 5 分钟 |
| `KbSettlementPendingOld` | 可信 PENDING>0 且最老终结年龄超过 300 秒 | warning / 5 分钟 |
| `KbSettlementReview` | 可信 REVIEW>0 | critical / 2 分钟 |
| `KbConversationConfigurationMismatch` | 存活 app／AI 的 conversation 开关不一致 | warning / 5 分钟 |
| `KbNotificationDeliveryFailures` | Alertmanager 或适配器持续发送失败，及持续限流 | warning / 5 分钟 |
| `KbRuleEvaluationFailures` | Prometheus 规则求值失败持续出现 | critical / 2 分钟 |

规则中的 warning／critical 可按目录分为同一根因 family 进行抑制；同环境、部署、相关服务的失联／采样根因抑制依赖它的业务通知，critical 抑制同 family 的 warning。死信和遗留结算不是“新能力关闭”的下游症状，不能被功能开关静默。普通队列告警门控维护状态，维护时长规则始终单独保留。

RETRIEVAL 延迟只表示召回阶段，不能标成总搜索耗时；SSE first_event 与模型 first_text 分开显示。HTTP 5xx、取消、拒绝、模型 LIMIT 与 UNKNOWN usage 提供面板趋势，不在首期一律作为 critical。既有错误分类不足以细分的 UNKNOWN 仅作为保守系统错误显示，不能猜成具体供应商故障。

通知初值为 group_wait=30s、group_interval=5m、repeat_interval=4h，发送 resolved。规则被关闭、目标删除或数据缺失也可能令告警结束，因此解除模板固定提示“告警条件已解除，请核对采样和当前状态”，避免把消失当作确认恢复。

### 5. Java 适配器的部署与协议边界

新增 `kb-alert-notifier` Maven 模块，Java 21、沿用 Spring Boot BOM，使用 Web／Actuator／Prometheus 和 JDK HTTP 客户端，不依赖 `kb-common`、Spring Cloud、Sa-Token、DB 或 Redis。采用专用启动配置校验，避免通用 ProductionSettings 隐式要求 Redis。HTTP 仅提供通知入口，管理端口提供 health／prometheus；不会注册用户业务路由或 API 文档。

接口为 `POST /internal/alerts/dingtalk`，使用至少 32 UTF-8 字节的独立 Bearer secret，常量时间比较。入站凭据、钉钉 token／secret 和 Grafana 密码都从只读 secret 文件加载；不在命令行、镜像或生成的普通配置中携带实际值。

标准 Webhook version=4 载荷采用有限解析：总正文默认 256 KiB、alerts 最多 100、嵌套深度最多 16、普通字符串最多 4096 code point；禁止压缩请求、重复属性和尾随数据。认证先于正文解析；不合规返回 400、过大返回 413、不支持媒体类型返回 415。合法上游扩展字段只在资源限制内忽略。

只允许本部署的告警目录、服务与级别。固定目录生成摘要及管理路径，忽略原始 annotations、externalURL 和 generatorURL。环境／部署值与本地配置精确核对。通知以单条 Markdown 输出，正文初始预算 3500 UTF-8 字节，标题最多 80 code point；同时计算安全转义后的长度。保留组状态、接收到的 firing／resolved 数量、上游 truncatedAlerts 与本地省略数量，省略文本不能拆断 Unicode；不把部分条目误认为全部恢复。

固定链接使用配置的 HTTPS 根地址和 `/admin/parse/dead-letters`、`/admin/embedding`、`/admin/ai/audits` 以及三组 Grafana dashboard 路径。结算的主要入口是指标面板；AI 审计只是启用采集后的辅助入口，模板提示其覆盖差异。链接不带凭据，不生成按用户／文档定位的外部分享地址。

钉钉出站固定为 `https://oapi.dingtalk.com/robot/send`，由 token 文件构造 access_token 参数，并根据时间戳和 secret 做 HMAC-SHA256／Base64／URL 编码签名。生产禁止任意目标和重定向；测试通过注入进程内的内存传输替身支持协议验证，不启动 HTTP 替身服务，不增加生产可切换任意 URL 的后门。独立客户端不继承入站身份头和追踪传播，也不记录含 token 的 URI。

钉钉 HTTP 2xx、严格有界 JSON 且整数 errcode=0 才返回 204。外部 HTTP 错误、非零业务码、损坏响应、网络超时都映射为固定 503 和低基数原因；不输出供应商 errmsg。每个入站请求最多一次出站调用；取消和超时释放资源必须等待该尝试实际退出，不派生脱离请求的发送任务。

初始连接超时 2 秒、单次总时长 8 秒、响应上限 16 KiB；Alertmanager webhook timeout 设置为 15 秒并小于 group_interval。适配器并发上限 2、滑动 60 秒最多 10 次实际尝试，失败也消耗额度；容量不足立即 503，无内部重试和无界排队。首期同一机器人只部署一个适配器，内存窗口重启会重置，需要在运维说明中标识。

Alertmanager 负责通知状态、静默、聚合和重试。适配器不按 groupKey 永久去重，否则会吞掉合法重复提醒及解除消息；发送成功而回执丢失时允许重复通知。选择自维护 Java 模块是用户确认的技术路线，不再引入现成钉钉转换器作为生产运行依赖。

新增指标建议为 `kb.alert.notifier.requests{outcome}`、`kb.alert.notifier.deliveries{outcome}`、`kb.alert.notifier.delivery.duration{outcome}`、`kb.alert.notifier.rejections{reason}` 和 `kb.alert.notifier.inflight`。outcome／reason 使用有限枚举；机器人、groupKey、alert fingerprint、URL、服务凭据均不能作为标签。默认日志与 HTTP 指标同样不得泄露原始路径、报文或异常体。

### 6. 配置、面板与验证交付

建议文件组织：

```text
ops/monitoring/
  compose.yml
  versions.env
  settings.example.json
  templates/
  rules/
  tests/
  grafana/provisioning/
  grafana/dashboards/
  scripts/render-config.ps1
  scripts/verify.ps1
kb-alert-notifier/
docs/monitoring-alerts-operations.md
```

设置渲染验证唯一目标、服务枚举、阈值范围、时间单位和根地址，输出至明确的生成目录，禁止解释执行配置文本。通知目录和 Prometheus 规则使用同一告警名称映射，验证目录完整性，生成配置不复制 secret 内容。Compose 和检查命令均使用 PowerShell 说明。

Grafana 配置自动导入三组固定 UID 面板；服务总览包括 up、采样状态、通知链路和规则求值；解析索引包括有效队列、死信、租约、维护及重建；检索 AI 包括业务结果、阶段延迟、降级、模型和 SSE、功能开关以及独立结算。未知／过期显示为不可用，不填零或绿色。节点未部署、功能关闭、健康无流量是不同状态。

验证分层（均不启动前端或外部组件）：

1. 进程内 Java 行为与 registry 导出测试：时间、失败、恢复、并发、关闭开关下的历史结算；延续已有指标兼容及隐私测试。使用 Mapper／JDBC Mock 验证结果映射、年龄计算及错误处理，人工审阅 SQL 谓词，不提供真实 PostgreSQL 测试库用例，不以 Mock 声称 SQL 已运行。配置绑定使用不启动 Web 服务的进程内上下文。
2. 本地固定版本 `promtool check config`、`check rules`、`test rules`：合成序列覆盖每条规则的等待和解除，及多副本、重启、缺失、无流量和维护。使用本地 amtool 检查配置和离线路由匹配，人工审阅抑制条件；不运行 Prometheus 或 Alertmanager 服务，不通过容器调用检查工具。
3. 适配器单测和进程内协议组件测试：已知签名向量、认证、UTF-8 字节界限、不同状态／截断、非零 errcode、重定向、响应丢失、失败后再次提交和并发准入。使用内存传输替身、可控时钟和并发屏障模拟网络结果及取消，不启动 HTTP 监听服务，不访问真实钉钉。
4. Maven 编译打包、JSON／YAML 运行配置解析和本地 Compose CLI 静态配置检查；Grafana 查询用合成时序离线验证，面板展示映射人工审阅。检查不连接 Docker daemon，不启动容器、完整应用、数据库、Redis、Grafana 或浏览器。

本变更不交付集成／端到端／烟测或真实群验收入口，也不将它们作为完成条件。文档编写与人工审阅保留，不增加针对文档、README、Markdown、注释或示例文本的内容存在、字符串或快照断言测试；运行时配置的语义校验仍保留。回归命令明确选择离线测试清单，避免带入现有外部依赖测试。工具不可用时保留未执行记录，不将跳过记为通过；离线结果不能证明真实 SQL、部署网络、界面渲染或消息送达已经验证。

### 7. 协议依据

- [Prometheus 告警架构](https://prometheus.io/docs/alerting/latest/overview/)：规则、告警管理与通知职责。
- [Alertmanager Webhook 配置](https://prometheus.io/docs/alerting/latest/configuration/#webhook_config)：标准 version=4 载荷、send_resolved、max_alerts 和 timeout。
- [Alertmanager Webhook 实现](https://github.com/prometheus/alertmanager/blob/main/notify/webhook/webhook.go)：2xx 确认与 5xx 可恢复错误边界；实现时同时核对选定固定版本。
- [规则测试](https://prometheus.io/docs/prometheus/latest/configuration/unit_testing_rules/)：规则时序验证能力。
- [钉钉官方接入](https://open.dingtalk.com/document/robots/custom-robot-access)、[安全设置](https://open.dingtalk.com/document/robots/customize-robot-security-settings)：实现时核对签名与载荷契约；本次浏览页面正文不可直接读取，不据页面标题声称完成协议联调。
- [阿里云官方钉钉机器人说明](https://help.aliyun.com/zh/iot-studio/user-guide/dingtalk-chatbot)：可读的 errcode=0 成功示例与每机器人每分钟 20 条限制；本设计将本地初值设为 10 次／60 秒，离线测试不证明实际机器人的运行配额。

## Risks / Trade-offs

- [监控或钉钉出口整体故障] → 同一链路不能保证通知自身；提供本地面板和排障入口，独立外部探活属于后续部署选项。
- [回执丢失造成重复] → 只确认明确成功，重复提醒由 Alertmanager 管理，接受不确定发送可能重复的边界。
- [多副本、时钟漂移与旧快照] → 按实例做新鲜度检查，同库快照去重，样本全失效时告警；不承诺跨实例强一致读。
- [低流量与默认阈值不匹配] → 比率和分位数都有样本门槛，配置化启动值，试运行后依据真实基线调整。
- [单实例适配器限流及重启丢窗口] → 限定每机器人单实例、保守速率、Alertmanager 聚合退避；HA 需要未来独立设计共享限流。
- [凭据和任意注解泄露] → secret 文件、固定消息目录、出站隔离及负向日志测试；不转发原始注解和异常体。
- [旧应用未导出新质量契约] → 产生契约缺失告警，按升级步骤启用新规则，不将缺失解释为零。
- [本次不增加结算表索引] → 固定超时和周期采样限制开销；目标数据量执行计划若暴露问题，记录为具体性能调整，不静默引入迁移。

## Migration Plan

以下为运维文档描述的部署顺序，不属于本变更执行的集成测试或验收任务。

1. 构建新适配器，完成固定版本工具检查、配置渲染、规则测试及进程内通知协议测试；准备网络目标和 secret 文件。
2. 部署新增指标的 app、Worker、AI，确认旧指标仍兼容、新质量 gauge 可见；不修改现有任务和额度开关。
3. 按部署配置启动独立监控，加载目标、规则和三组面板。
4. 配置管理网络、持久卷、固定管理链接和接收群，按运行基线调整阈值。
5. 回退监控时停止对应通知路由和组件即可；不改变业务数据。若回退应用到旧指标版本，先停用依赖新契约的规则，保留通用 up 监控，避免将不兼容误作业务恢复。

## Open Questions

- 部署期实际地址、服务是否必需、机器人 token／secret、Grafana 入口及监控磁盘容量由运行环境提供，均已有配置边界，不影响实现结构。
- 具体组件固定版本与镜像 digest 在实施依赖核对时填入版本清单；阈值和保留期采用本文初值，实际容量由试运行校准。
