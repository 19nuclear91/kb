# 监控告警与面板运维

本说明对应 `ops/monitoring` 的固定告警目录、规则和三组面板。部署、版本、secret、升级及回退步骤见 `docs/monitoring-deployment-operations.md`；通知接口和安全边界见 `docs/alert-notifier-operations.md`。本变更只做离线检查，不证明真实 SQL、部署网络、页面渲染或钉钉送达已经验证。

## 身份、采样与聚合

- `environment`、`deployment`、`service` 来自受校验设置；一个 deployment 只代表一套业务数据库。相同数据库的多个副本共用 deployment，不同数据库必须分开。所有查询保留环境和部署，不把跨库数据混合。
- 四个应用都必须显式声明 `expected`；不部署的服务设 `false` 并使用空 targets。预期清单是独立 recording rule，目标文件被误删仍能告警，不以 `up` 缺失推断未部署。
- `kb_monitoring_sample_available{source}` 和 `kb_monitoring_sample_last_success_timestamp_seconds{source}` 从启动即有值；source 为 parse、embedding、settlement。尚未成功时均为 0；失败保留旧业务值和成功时间、available 变 0。抓取不查库。默认采样 30 秒、语句超时 3 秒。
- 业务数据库 Gauge 先在同实例筛选 available=1、成功时间大于 0 且不在未来、距今不超过 `freshnessSeconds=120`、up=1，然后同部署取 `max`。全部无效时显示不可用而非零；单副本异常仍有采样告警，其他可信副本继续提供业务判断。采样抑制由来源级 PromQL 门控完成，不能用一个服务级 Alertmanager 采样抑制掩盖其他健康副本或来源。
- 解析 `queue.depth` 只计到达可尝试时间的 pending；`oldest_age` 仍依据所有 pending 的 `COALESCE(next_attempt_at, created_at)`，负年龄归零。两者不是同一个过滤集合。configured 是 `DocumentParser.isAvailable()` 的本地配置事实，不是第三方探活。
- Embedding mode 是 one-hot，副本短暂不一致时保守显示多个状态；不可相加。`events.persisted` 是数据库 Gauge，不是进程 Counter，不对它做 rate 或累加副本。
- Counter 逐序列 `increase/rate` 后再 sum，先处理各自重置；直方图先按 le 合桶再算分位数，不能平均实例 P95。
- settlement 直接只读聚合 `kb_conversation_settlement`，不看 AI 观测副本。PENDING 包含退避和已领取记录，REVIEW 单列；RESERVED、SETTLED 不计入。年龄是当前 PENDING 最早 finalized_at 距采样时刻的非负秒数。新会话和观测均关闭也继续采样历史账本。

## 首期 20 类规则

以下为建议初值，不是经生产基线验证的 SLO。`waitSeconds` 控制连续满足条件的时长；5 分钟／15 分钟是查询窗口，不等于等待期。所有规则仅对对应的预期服务生效。

入口缩写：**总览** `/d/kb-overview`，**解析** `/d/kb-parse-index`，**检索 AI** `/d/kb-search-ai`，均相对于配置的 Grafana HTTPS 根地址。管理页面使用独立 adminRoot。

| 告警 | 数据来源、条件及开关 | 等级 / 等待 | 入口与定位 |
| --- | --- | --- | --- |
| KbServiceUnavailable | 预期清单与 up；没有存活目标，包括目标文件被删；不受功能开关影响 | critical / 120s | 总览；检查发现配置、端口、管理绑定、防火墙 |
| KbServiceReplicaUnavailable | up 中至少一副本为 0 且至少一副本为 1 | warning / 300s | 总览；按 instance 检查故障副本 |
| KbMonitoringContractMissing | 存活实例缺固定采样、configured、feature、search inflight 或 notifier inflight 契约 | warning / 120s | 总览；核对应用版本与导出，不以缺失补零 |
| KbMetricSampleUnavailable | 任一来源/副本失败、未采样、未来或超过 120s | warning / 120s | 总览；查看 source、成功时间、SQL 超时和调度，检查时钟同步 |
| KbParserNotConfigured | 存活 Worker configured 全为 0，且 parserExpected=true | critical / 300s | 解析；检查本地解析器接入，不触发探活或任务 |
| KbParseQueueAging | 可信 pending 最老等待 >900s，configured=1 且仅 ACTIVE 索引 | warning / 300s | 解析；结合可尝试队列和退避原因，不将维护排队当普通故障 |
| KbParseDeadLetters | 可信 OPEN 死信 >0；维护或 parserExpected=false 不清空状态 | warning / 300s | `/admin/parse/dead-letters`；授权人员核对，不自动重放 |
| KbParseLeaseLoss | `kb_parse_tasks_lease_lost_total` 15m 增量 >=3 | warning / 300s | 解析；检查耗时、租约续期及实例稳定性 |
| KbEmbeddingBlocked | 可信 mode=BLOCKED | critical / 120s | `/admin/embedding`；核对错误类别和人工处置条件 |
| KbEmbeddingMaintenanceLong | 可信 UNINITIALIZED/QUIESCING/REBUILDING/VERIFYING，且 parserExpected=true | warning / 3600s | `/admin/embedding`；查看重建进度，不能无限延长维护而隐去状态 |
| KbSearchFailures | requests 的 FAILED/(SUCCESS+EMPTY+FAILED)，5m 分母 >=20 且比例 >0.1，契约健康 | critical / 300s | 检索 AI；合法 EMPTY、REJECTED、CANCELLED 不计失败 |
| KbSearchRetrievalSlow | stage=RETRIEVAL 的桶合并 P95 >2.5s，5m count >=20，契约健康 | warning / 300s | 检索 AI；只代表召回阶段，不是总搜索延迟 |
| KbSearchDegradation | 同阶段 degradation 增量/有效搜索量 >0.2，5m 搜索 >=20，契约健康 | warning / 600s | 检索 AI；区分 EMBEDDING/RETRIEVAL/RERANK 降级，不当作上游模型失败 |
| KbModelFailures | TIMEOUT/PROTOCOL_ERROR/UPSTREAM_ERROR 占非 CANCELLED 模型调用 >0.2，5m 调用 >=10，契约健康 | critical / 300s | 检索 AI；LIMIT 不计失败，usage UNKNOWN 仅显示覆盖状态 |
| KbChatSystemErrors | TIMEOUT 或非 INPUT_BUDGET_EXCEEDED 的 ERROR 占非 CANCELLED 流 >0.2，5m 流 >=10，契约健康 | critical / 300s | 检索 AI；HTTP 200 后的流内系统错误仍计入，UNKNOWN 不猜测供应商原因 |
| KbSettlementPendingOld | 可信 PENDING>0 且 finalized_at 最老年龄 >300s；不受 conversation/ai_observation 门控 | warning / 300s | 检索 AI；区分退避与长期未处理，不重放生成或触发扣费 |
| KbSettlementReview | 可信 REVIEW>0；功能关闭仍监控 | critical / 120s | 检索 AI；人工核对持久事实，AI 审计仅为可选辅助覆盖 |
| KbConversationConfigurationMismatch | 同环境部署中存活 app 与 AI 的 conversation 有效开关不一致 | warning / 300s | 检索 AI；核对逐实例配置，不自动合并成已开启 |
| KbNotificationDeliveryFailures | 5m Alertmanager webhook 失败或 notifier outcome!=success 或 reason=rate/concurrency 增量 >0 | warning / 300s | 总览；核对凭据、时间、出口和限流，不从失败返回体泄露正文 |
| KbRuleEvaluationFailures | `prometheus_rule_evaluation_failures_total` 5m 增量 >0 | critical / 120s | 总览；核对规则语法、执行资源，业务告警可能缺失 |

阈值键分别位于 `thresholds`：freshnessSeconds、queueAgeSeconds、leaseLossCount、searchMinRequests、searchFailureRatio、retrievalP95Seconds、degradationRatio、modelMinRequests、modelFailureRatio、chatMinRequests、chatErrorRatio、settlementAgeSeconds。修改设置后重新渲染并执行统一离线验证，不直接修改生成规则，不尝试在 PromQL 中使用环境变量替换。调整流量门槛和比率要一起评估，避免少量失败被放大；不通过填零掩盖契约错误。

## 分组、抑制、静默与解除

Alertmanager 按 environment/deployment/service/alertname 分组，等待 30s，更新间隔 5m，重复 4h；发送 resolved。数值、时间、instance、业务标识不作为通知身份。适配器不会把 `TaskAlertOutbox` 的逐任务事件发到钉钉，也不在持锁事务内调用 HTTP。

抑制规则只在 environment、deployment、service 相等时成立：服务整体失联抑制相关下游；critical 抑制同 family 的 warning。单副本失联不会静默其他健康副本，采样门控仅排除无效来源。Embedding 维护门控普通队列，独立维护超时和已有死信继续保留。

维护时使用 Alertmanager 自身 silence，必须精确限定环境、部署、服务和告警名，给出操作者、原因、短有效期；不要把整套环境、REVIEW 或死信顺手静默。以下仅是运维操作示例，本变更未执行，地址由受控管理网络提供：

```powershell
$amtool = 'C:\tools\amtool.exe'
$adminUrl = 'http://127.0.0.1:9093'
& $amtool --alertmanager.url=$adminUrl silence add 'environment=staging' 'deployment=kb-main' 'service=worker' 'alertname=KbEmbeddingMaintenanceLong' --duration=1h --author=operator --comment='批准的索引维护窗口'
& $amtool --alertmanager.url=$adminUrl silence query
# 完成维护后，使用查询结果中的确切 ID 提前结束静默：
# & $amtool --alertmanager.url=$adminUrl silence expire <silence-id>
```

静默应保留在 Alertmanager 持久卷中；重启不是清除静默的方法。旧告警因规则关闭、目标删除或采样丢失也可能结束，因此统一解除文案为“告警条件已解除，请核对采样和当前状态”，不能承诺端到端业务恢复。

## 三组面板如何阅读

- **总览**：服务 1=存活、0=失联、-1=明确未部署；固定契约 0=完整、1=缺失；采样 0=不可用、1=有效。状态使用即时查询，不沿用历史绿色。时间差负值代表未来时间；规则失败、通知尝试、容量拒绝及并发独立展示。
- **解析索引**：有效队列、pending 年龄、OPEN 死信、15m 租约增量；one-hot mode、重建进度、持久事件和错误类别。所有数据库值来自可信且去重的快照；采样失败显示缺口而非零队列，维护和采样来源分开显示。
- **检索 AI**：搜索业务结果、召回阶段 P95、降级及拒绝；模型首字 `kb_model_first_text_delay_seconds` 和 SSE 首内容 `kb_chat_first_event_delay_seconds` 独立，后者可能是 citation。feature 按实例显示，0=明确关闭，缺失=不可用；在服务和契约健康时请求序列未注册才可解释为无流量。持久结算与 AI 观测覆盖不同，不因功能关闭清空历史账本。
- 三组固定 UID 分别为 kb-overview、kb-parse-index、kb-search-ai；数据源 UID 为 kb-prometheus。单选 environment/deployment 变量控制所有查询；链接保留变量，不能把多个部署混作同一数据库。空分位数不补绿色零。

## 页面权限和通知边界

`kb-web/src/routing/routes.ts` 登记的管理路由继续由 `kb-web/src/admin/AdminGuard.vue` 保护；`/admin/parse/dead-letters`、`/admin/embedding`、`/admin/ai/audits` 分别需要死信读取、索引读取和 AI 审计读取资格。通知只构造固定路径，不携带用户、文档、轮次、对象键、会话令牌或分享凭据；点击不会获得额外权限。AI 观测关闭或覆盖不足时，审计页面没有记录不代表没有结算积压，优先查看独立结算面板。

钉钉收到通知不意味着处理完成；本地健康不意味着可送达。适配器对外失败返回 503，Alertmanager 重试；回执丢失可能重复。整套监控停止、Prometheus 无法求值、适配器或钉钉出口整体故障时，同一链路无法保证报告自身故障。独立外部探活、通知 HA 和真实群验收不在本次交付范围。
