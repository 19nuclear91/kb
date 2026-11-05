# 解析任务恢复与观测

Worker 使用 PostgreSQL 任务表作为持久队列。`pending` 任务只有在 `next_attempt_at` 到达后才会被领取；租约过期的 `running`
任务可由其他 Worker 回收。领取时递增 `attempts`，因此进程崩溃也会消耗一次尝试并最终收敛。

失败摘要由固定分类和代码组成：`PERMANENT` 立即进入死信，`RETRYABLE` 和 `UNKNOWN` 使用指数退避（默认 30 秒起、15 分钟上限、20%
抖动）直到达到 `max_attempts`。租约失守是控制流，不写业务失败。文档版本在等待退避时显示 `pending`，死信时显示 `failed`
，并通过进度接口返回分类、摘要、执行周期和死信状态。

用户可以在拥有文档 `edit` 权限时调用 `POST /api/documents/{documentId}/retry`。该操作关闭当前未解决死信、递增
`execution_generation`、保留历史并创建新的待执行周期。

全局查询需要当前 `parse:deadletter:read`，重放需要 `parse:deadletter:replay`；`ROLE_ADMIN` 名称不隐含资格。`/api/admin/parse/dead-letters` 支持 `status`、`failureClass` 和时间范围筛选。重放调用
`POST /api/admin/parse/dead-letters/{id}/replay`，请求体提供受限 `reason`，并通过 `X-Request-Id`
实现幂等。响应只包含任务、版本、分类、尝试次数和脱敏摘要。

查询与重放均须 `X-Request-Id`，去除首尾空白后为 1–256 个 Unicode code point；重放原因独立限制为 1–2000 个 code point。空值、NUL 或超长输入返回 400，不再截断；300 字原因会完整保存。查询状态仅允许 OPEN/REPLAYED/RESOLVED，分类仅允许 PERMANENT/RETRYABLE/UNKNOWN，省略或空字符串表示不限。page 从 1 开始，size 为 1–100；带时区时间采用 `[from,to)`，两端都有值时必须 from < to，非法条件返回 400。

重放标识绑定操作者、死信 ID 及规范化原因；匹配重试返回原 REPLAYED 记录，内容不匹配返回 409，不返回原目标资料。未知或已经被其他请求处置的目标返回 404。历史记录只按保存的事实核对，旧版截断掉的原因无法还原，不能确认匹配时返回 409。

服务使用 READ_COMMITTED 事务，在读取绑定前按请求域取得 PostgreSQL 事务级 advisory lock，再沿用控制共享锁、任务行和死信行的顺序。锁等待与事务都有上限，审计异常向事务边界传播；任务事件和成功审计随首次重放一起提交，匹配重试不重复写入。重放只表示新周期已排队，Embedding 维护中仍等待正常领取，不表示解析成功。

部署兼容性说明：应排空旧版 app 的重放写入口后统一更新，避免旧实例绕过请求键锁。回退旧 app 会失去新校验及绑定保证，现有处置事实仍保留。本次仅通过 Mock 进程内单元测试验证输入、分支、调用顺序及 JSON 摘要，不包含数据库运行、实际互斥或事务回滚验证，也不交付烟测脚本。

## 发布事务持有的文档行锁

`ChunkPublisher.publish` 在写分块之前取**文档行锁**——`SELECT 1 FROM kb_document WHERE id = ? FOR UPDATE`，锁的范围是
`kb_document` 里该文档的那一行，持有时间从取锁一直到发布事务提交，覆盖 `kb_doc_chunk` 的整批 DELETE/INSERT。

这把锁不是为发布自己加的，是为了和**文档搬迁**（`PATCH /api/admin/documents/{documentId}/space`
）串行化。搬迁事务在写之前取同一把锁，于是两者只能一前一后：发布在后就读到新的 `space_id`，搬迁在后就按 `doc_id`
覆盖发布刚写下的那批分块。锁只取一侧不够——发布侧不取锁时，它的 `INSERT` 可以在搬迁的 `UPDATE` 之后提交，那批新分块会带着旧
`space_id` 落地，并继续按**原空间**参与检索过滤。

运维上要知道的两件事：

- **持有时长随分块数增长**。长文档的分块批量写入全程持锁，所以它是搬迁延迟与锁等待的主要来源。一篇文档正在解析发布时，对它的搬迁请求会阻塞到发布事务提交。
- **锁的粒度是单篇文档**。它不会影响其他文档的发布或搬迁，也不影响检索与读取（两者都不取这把锁）。看到搬迁接口响应变慢时，先确认该文档是否有解析任务在跑，而不是去查全局锁竞争。

部署顺序因此是**先 kb-worker（取锁）再 kb-app（搬迁接口）**。反过来会留一个"接口可用但竞态未收口"的窗口。

## 指标与事件

Actuator 在管理端口暴露 `/actuator/metrics` 与 `/actuator/prometheus`。生产须配置 `KB_MANAGEMENT_PORT` 和 `KB_MANAGEMENT_ADDRESS`，同步把健康探针迁移到管理端口；显式 dev 可沿用业务端口。Worker 注册以下低基数指标：

- `kb.parse.tasks.claimed`、`completed`、`retried`、`dead_lettered`、`lease_lost`
- `kb.parse.tasks.retried.by_class`、`kb.parse.tasks.dead_lettered.by_class`
- `kb.parse.alerts.failed`
- `kb.parse.task.duration`
- `kb.parse.queue.depth`、`kb.parse.dead_letter.depth`、`kb.parse.queue.oldest_age_seconds`

无标签重试/死信总量保持旧名称不变；只有 `.by_class` 分类计数携带 `failure_class`（`PERMANENT`、`RETRYABLE`、`UNKNOWN`）标签。原同名分类序列改用独立名称，避免 Prometheus registry 因标签键集合冲突丢失分类输出。原总量查询不变，分类查询迁移为 `kb_parse_tasks_retried_by_class_total{failure_class="RETRYABLE"}` 或 `kb_parse_tasks_dead_lettered_by_class_total{failure_class="PERMANENT"}`；不要把总量和分类相加。

标签不包含任务 ID、版本 ID、对象路径或用户输入。建议外部监控按队列年龄、死信深度、租约失守率和告警投递失败率配置阈值；Worker 不绑定通知平台。

`kb.parse.task.duration` 保持无业务标签，默认显式边界为 1s、5s、30s、60s、300s，并去重合入 `KB_TASK_TIMEOUT_SECONDS`（默认 900s）。`kb.telemetry.histograms.parse` 可覆盖基础边界，配置必须严格递增且为正；Prometheus 按秒导出 `_bucket`、`_count`、`_sum`。抓取与 OpenMetrics 配置见[遥测运维](telemetry-operations.md)。

### 来源快照与本地接入状态

`kb_monitoring_sample_available{source=~"parse|embedding"}` 和 `kb_monitoring_sample_last_success_timestamp_seconds{source}` 从启动即存在，首次完整采样前均为 0。Worker 默认延迟 30 秒首次采样，此后每次完成后等待 30 秒；`kb.worker.metrics-gauge-interval-ms`（既有 `KB_METRICS_GAUGE_INTERVAL_MS`）可设 1000–3600000 ms。监控专用 SQL 默认每条超时 3 秒，`kb.monitoring.query-timeout-seconds` 可设 1–30，不修改业务模板配置。

解析的三个读取全部成功才发布：`queue.depth` 是 `status='pending' AND attempts<max_attempts` 且 `next_attempt_at` 为空或已到时的可尝试队列；`queue.oldest_age_seconds` 保留所有未耗尽 pending 的 `min(COALESCE(next_attempt_at,created_at))` 谓词，并将未来时间导致的负年龄收敛为 0；`dead_letter.depth` 仅为 OPEN。退避中的 pending 可以进入年龄集合而不进入可尝试深度，不要把深度与年龄当作同一集合。

任一读取失败将来源 available 置 0，保留旧业务值和旧成功时间；不把异常当空库。Prometheus 抓取不查库，来源级导出一次捕获业务值与质量状态，避免并发发布时把部分新值当完整成功。Embedding 状态、错误、进度和持久事件一起发布，旧 `kb_embedding_metrics_available` 与新质量信号一致；`kb_embedding_events_persisted{operation}` 是数据库 Gauge，不是进程 Counter。

同库多 Worker 汇总应先按实例筛选 up、available 和有效成功时间（默认新鲜度 120 秒，拒绝未来时间），再按环境／部署取 max 去重，不能按副本 sum。全部失效必须显示采样不可用。`kb_parse_parser_configured` 为本地 `DocumentParser.isAvailable()` 零一值：缺适配器为 0，配置完成但远程故障仍可能为 1；读取不调用 parse、不访问原件、不联网、不领取任务，原健康与暂停规则不变。

采样仅只读观察，不重试任务、处置死信或调用钉钉。本轮数值映射、异常和并发行为通过进程内 registry/JDBC Mock 验证；SQL 仅人工审阅，不声称真实 PostgreSQL 执行或生产查询性能已验证。

死信转移和重试耗尽先写入 `kb_parse_task_event` 与 `kb_parse_alert_delivery`。现有 `TaskAlertOutbox.dispatch` 在事务中持有待投递行锁并调用本地 `TaskFailureAlert`；本次不向该持锁事务接入钉钉 HTTP。`dedupe_key` 约束重复巡检事件，失败保留待投递记录并增加指标；钉钉聚合通知由独立 Prometheus/Alertmanager 链路负责。

## 任务级追踪

追踪默认关闭；显式启用后，每次 `ParseTaskRunner.runOne` 执行生成独立根跨度 `kb.parse.task`，`kb.parse.document`、`kb.parse.embedding`、`kb.parse.publish` 分别覆盖解析、向量化与发布。重复领取、自动重试和人工重放后的执行使用新的 trace，以任务和版本 ID 关联历史；不把上传请求 trace 存进任务表或当作父跨度。

上下文在提交流水线前捕获，并在独立 executor 线程恢复和清理；任务执行日志携带 traceId/spanId，心跳与巡检线程保持独立。任务跨度属性仅允许 `kb.task.id`、`kb.version.id`、`kb.execution.generation` 和固定 `kb.failure.class`；不记录对象键、原件内容、解析器原始错误体或异常堆栈。解析器未就绪时不领取任务，也不生成任务跨度。

第三方解析与 embedding 客户端不传播内部追踪头或 baggage。任务关闭追踪后使用无操作 tracer，领取、重试、发布及指标继续按原流程运行。启用、采样和回退操作见[遥测运维](telemetry-operations.md)。
