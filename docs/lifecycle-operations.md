# 员工生命周期运维手册

本期以钉钉企业内部应用为权威来源，自动处理离职停权；纳管、退出、复职与应急轮换由具备精确权限的管理员确认。受管账号包括本地密码登录员工，SSO 绑定不构成员工映射。完整接口见 [管理 API](lifecycle-management-api.md)，上线顺序见 [协调发布方案](lifecycle-rollout.md)。

## 配置与接入前核对

默认 `kb.identity.lifecycle.dingtalk.enabled=false`。先按 [接入配置示例](dingtalk-stream-adapter.md) 配置企业 ID、独立内部应用凭据、事件统一应用 ID 和连接别名。`clientId` 与 `eventUnifiedAppId` 是不同协议字段，必须从同一应用的可信配置信息核对，不能直接假定相等。内部认证签名另用 `KB_AUTH_INTERNAL_SECRET`，不复用钉钉、SSO、Office 或会话秘密。

平台应用需要当前成员详情读取能力、覆盖拟纳管员工的通讯录可见范围，并订阅 `user_leave_org`。管理员应在平台控制台核对授权与订阅范围，再用准确员工标识执行成员核验。成员不可见、离开企业、未激活、响应损坏或调用失败均不构成在职证明；本系统不会自动扩大平台权限、补建账号或强制恢复。

固定官方 Stream Java SDK 为 `com.dingtalk.open:app-stream-api:1.3.12`，Java 21。协议和 ACK 使用 SDK 类型，连接由本项目有界 JDK 适配器管理。出站 HTTP 仅使用 `api.dingtalk.com`、`oapi.dingtalk.com` 的固定 HTTPS 接口；WSS 默认白名单为 `wss-open-connection.dingtalk.com:443`。需要增加主机时，只添加经过平台配置核对的精确主机，不能使用通配域名或关闭 TLS 校验。DNS、代理和防火墙应允许这些固定来源，不允许重定向到任意目标。

单次 HTTP、握手及 ACK 发送最多两秒。成员查询在必要时先取得应用 token，再读取成员，可能包含两次独立调用；不把这个串行耗时冒充单次两秒。成员核验不在离职接收事务内执行。Stream 消息和员工数量、线程池及队列有固定边界，具体配置范围见接入说明。不得在日志中打开原始 SDK 消息、JDK HTTP 正文或含 accessToken/ticket 的完整 URL。

## 应急与覆盖核对

1. 准备独立的非受管本地账号，启用账号和主部门，完成初始改密，分配 [应急保障要求](lifecycle-emergency.md) 中的完整权限。保管凭据时与受管员工身份区分。
2. 候选本人通过真实本地密码登录，在“员工生命周期 → 独立应急保障”再次验证本人密码并填写原因登记。SSO、开发会话和代他人输入密码不满足要求。
3. 查看服务端应急摘要的当前资格。纳管前及接收器启用/重连前都会复核；只有角色名为管理员不代表合格。
4. 从受控权威员工目录建立内部账号与准确员工标识的人工核对清单，逐个核验并纳管。本地密码账号同样纳入；不得按姓名、手机号或 SSO 绑定推断。核对内部账号覆盖、离职前仍未纳管的账号和长期未匹配事件。全局受管数量只能辅助核对，不能证明目录全覆盖。

页面不会导出原始员工标识或完整目录，也没有批量自动匹配功能。需要目录差异核对时，由获得授权的运维在受控环境读取映射事实，与权威清单按准确标识比较；只向工单记录必要的内部账号引用和结论，不把原始目录写入通知、浏览器 URL 或普通日志。

轮换时新候选本人选择已有保障账号，先登记本人、再退役旧保障，二者为同一事务。最后一名合格保障的停用、改密要求、部门失效、角色解除和权限收缩会被拒绝。不能通过关订阅或把保障账号纳管来绕过；先完成合格替换，再处理原账号。

## 事件与复职处置

| 现象 | 核对与处理 |
| --- | --- |
| 订阅启用但未连接 | 核对固定出站域名、应用身份/统一应用 ID、凭据和应急资格；接收器有界重连。未收到事件不表示没有员工离职。 |
| `UNMAPPED` | 找到权威目录中的准确员工和内部账号，执行核验并补映射；提交映射时会核对历史未解决事件，再读取状态。不得直接把未匹配记录标作成功。 |
| `REVIEW` | 检查迟到事件、同键内容冲突或保障异常；保持限制。当前成员、部门及角色事实都确认后，按人工复职流程处置。 |
| `FAILED` | 核对数据库和依赖状态；后台执行有界退避，也可显式“重试原事件”。不能替换原载荷或改写首份事件依据。 |
| 会话清理积压 | 检查 Redis 和清理调度。数据库代次门禁继续拒绝旧 token；只重试原撤销代次清理，禁止用户全量 logout 误伤复职新会话。 |
| 写入响应未知 | 当前页可按原内容、原因、版本、证明和请求标识显式重试；不要自动换新标识。页面已关闭则先重读事实，不能把超时当作未执行。 |
| 普通启用 409 | 生命周期限制必须通过人工复职核对解除；普通启用不替代复职。 |
| 成员查询 503 | 无法确认成员资格，不执行纳管/恢复。核对平台可见范围及依赖，再显式核验；不能根据错误文本推断已离职或已复职。 |

复职先展示当前成员、部门、角色、密级、人工状态，取得五分钟一次性证明；确认时再次查询平台并在事务中复核事实和事件边界。事实变化或凭据过期须重新核对。仅解除生命周期限制，人工停用继续生效；旧 token 永久无效。新有效离职再次停权，已处理事件重投不重复停权；此前未见过的早期事件进入 REVIEW，不能因时间戳较早直接忽略。

可靠接收以事务提交为界，成功 ACK 晚于 Inbox、逐员工结果、已匹配账号限制、认证代次、审计及清理意图的提交。事务前或提交失败不能记作接收成功；平台尚未投递的时间不属于本系统接收后 30 秒目标。未匹配和隔离记录的持久接收也不表示完成账号停权。

## 监控语义与排障顺序

先看 `up`、指标契约和 `kb_monitoring_sample_available{source="lifecycle"}`，再看 `kb_monitoring_sample_last_success_timestamp_seconds`。未采样为 available=0、last_success=0；失败保留上次业务值但 available=0。未来时间、超过新鲜度阈值的样本或失联实例均不能用于业务判断。只有有效采样且积压确认为空，才可解释积压和最老年龄的零值。

| Prometheus 指标 | 解释 |
| --- | --- |
| `kb_lifecycle_subscription_enabled`、`kb_lifecycle_connected` | 当前节点订阅配置与连接状态，随来源快照采样；不是“所有离职事件均已送达”的证明。 |
| `kb_lifecycle_last_connected_timestamp_seconds` | 本节点最后成功连接时刻，零表示本进程尚无连接记录；仍须结合来源采样有效性。 |
| `kb_lifecycle_emergency_required`、`kb_lifecycle_emergency_ready` | 持久保障要求及当前合格保障；关闭接收仍可能 required=1。 |
| `kb_lifecycle_backlog{status}` | 固定 UNMAPPED/REVIEW/FAILED/CLEANUP 的真实未解决项数量；同库副本以有效样本最大值聚合，不相加。 |
| `kb_lifecycle_oldest_record_age_seconds{queue}` | ITEM/CLEANUP 中最老未解决记录的年龄，从数据库记录时间计算；不是可靠接收后的停权耗时。 |
| `kb_lifecycle_reception_total{source,outcome}`、`kb_lifecycle_items_total{source,outcome}` | 接收提交、重复、冲突、失败以及逐项处置的独立观测。 |
| `kb_lifecycle_input_rejections_total{source,reason}` | 固定 SCOPE_MISMATCH/INVALID_EVENT/UNSUPPORTED_EVENT/MALFORMED_MESSAGE/MESSAGE_LIMIT/EMPLOYEE_LIMIT/UNSUPPORTED_COMMAND/QUEUE_FULL 类别；含网络分片超限，不记录原文。 |
| `kb_lifecycle_last_received_timestamp_seconds{source}` | 本进程最后一次观察到接收提交的时间；重启后的零表示暂无本进程观测，不表示数据库没有事件。 |
| `kb_lifecycle_access_barrier_delay_seconds_*` | 只在已匹配 OFFBOARDED 与可靠接收同一事务提交时产生零延迟样本；不包括 AI 停止或 Redis 清理。延后补映射缺少原可靠接收时刻，不补造延迟样本。 |
| `kb_lifecycle_dependency_failures_total{operation}`、`kb_identity_session_cleanup_total{outcome}` | 固定 SAMPLE/MEMBER/EVIDENCE 依赖失败和独立清理结果，不包含员工、事件、用户、token 或异常文本标签。 |
| `kb_lifecycle_output_stop_samples{quality}` | 最近 15 分钟上报且精确关联原撤销代次的 CONFIRMED/UNCERTAIN 数量；补映射和未关联记录不计入首次已映射员工的 SLA 样本。单次最多读取 1001 项，超过 1000 时采样失败，不截断后伪装完整。 |
| `kb_lifecycle_output_stop_delay_seconds{bound}`、`kb_lifecycle_output_stop_delay_available` | CONFIRMED 记录的 lower/upper 最大耗时界限；须同时 available=1、CONFIRMED>0 且 lifecycle 采样有效。没有证据时 available=0，界限的占位零不表示零延迟成功。 |
| `kb_ai_output_stop_reports_total{outcome}` | REPORTED/FAILED/DROPPED/MISSING_ANCHOR 固定结果；未上报成功不能视为已获得停止证据。失败与队列满接入依赖告警。 |
| `kb_ai_output_permit_active`、`kb_ai_output_permit_overdue_seconds` | 当前进程许可数量及仍存活许可超过本地硬截止的最大秒数。无活跃许可的零不证明任何撤销成功。 |
| `kb_ai_output_permit_stops_total{reason}`、`kb_ai_output_permit_acquisition_failures_total{reason}` | 已观察到的本地停止与初次取得许可失败分别计数；依赖未知不同于 ACCOUNT_REVOKED。 |
| `kb_ai_output_permit_observed_age_seconds_*`、`kb_ai_output_permit_stop_overdue_seconds_*` | 从本次执行首次请求开始到本地停止的年龄，以及超过最近硬截止的时间；执行年龄可能很长，不是离职时延。正常完成或没有停止证据不产生此样本。 |
| `kb_ai_output_permit_deadline_misses_total` | 观察到停止晚于许可截止的次数；排障还需查看超出量，短暂调度误差与明显暂停分别判断。 |
| `kb_ai_output_permit_revocation_stop_upper_bound_seconds_*` | 仅在取得过有效许可并因 ACCOUNT_REVOKED 停止后记录，从最近一次成功身份检查的请求开始至本地停止的保守上界。主库撤销发生于此前成功检查之后，所以不需要比较两台机器的时钟；依赖未知、正常完成和暂无停止均不造样本。上界超过 30 秒只能表示目标不可确认，不能当作实际离职耗时超过 30 秒的证明。 |

每个输出许可独立绑定当前用户、代次和执行，硬上限 10 秒、默认每 3 秒核对、单次最多 2 秒，从单调请求发起时间计。过期或依赖未知先停止输出再收尾；每次 citation/delta/done 写出前仍检查。ACCOUNT_REVOKED 收尾为 CANCELLED，依赖未知为 FAILED，确认前缀及 NOT_INVOKED/UNKNOWN 结算语义保留。若 AI 取消落库失败，执行租约恢复会根据持久认证代次收尾，不重新生成。

新增固定告警为 `KbLifecycleDisconnected`、`KbLifecycleBacklogOld`、`KbLifecycleEmergencyUnavailable`、`KbOutputPermitOverdue`、`KbLifecycleDependencyFailures`、`KbRevocationTargetUnconfirmed`。处置/清理最老年龄超过 300 秒持续 300 秒告警；本地许可超出硬截止 2 秒持续 30 秒告警，既读取活跃超出量，也读取停止后短窗口最大值。撤销停止上界超过 30 秒，或许可超过硬截止 20 秒仍存活，持续 30 秒触发独立的目标不可确认告警。这些是运维发现规则，不是放宽许可或 30 秒目标。连接和应急异常持续 60 秒、依赖错误在五分钟窗口持续 60 秒告警；采样异常由既有来源新鲜度告警单独提示。

另外提供 `KbLifecycleInputRejected`、`KbLifecycleEventConflict` 和 `KbLifecycleRevocationSlow`：输入拒绝/内容冲突在 5 分钟窗口有新增并持续 30 秒告警，冲突规则不依赖 REVIEW 或其他积压；关联耗时下界超过 30 秒持续 30 秒表示已确认超时。`KbRevocationTargetUnconfirmed` 同时覆盖 app 的关联上界越界或 UNCERTAIN，以及 AI 的原保守上界/许可逾期，不能把它当作已确认超时。

接收与输出停止通过 `sys_identity_output_stop_evidence` 受控数据库视图关联，原始依据保留在事件、sys_identity_revocation_evidence 和 sys_identity_output_stop 中。只关联相同用户和被撤销的原认证代次，复职不改写历史。接收真实提交位于主库 commit_before/commit_after 之间；主库 checkedAt 加上 AI 单调停止时刻减去核对响应/请求时刻，给出停止下界/上界。两区间相减得到耗时区间；不使用 AI 墙上时钟，也不把上报网络延迟当成输出延迟。未观察提交、时间顺序异常或区间重叠不能确认因果时，保留 UNCERTAIN。数据库时钟自身的异常仍须排查。

通知只输出固定中文摘要、环境/部署/服务/严重度、受控时间和 `/admin/identity/lifecycle` 管理链接；不会带员工或事件标识，也不会执行账号操作。已交付证据关联代码和进程内验证，但未执行实际部署的跨实例测量。没有报告、丢失证据或暂无事件都不能显示为达标；实际效果须以运行环境产生的证据为准。

## 保留与审计

本期长期保留来源作用域、原始业务事件身份、规范摘要、逐员工处置、映射历史、恢复边界、管理回执和审计。原事件内容冲突不能覆盖首份依据。关闭订阅、换密钥、重连、退出纳管或复职均不能删除去重依据，不能用按天清表代替容量治理。

数据库备份须覆盖这些事实与用户认证代次；恢复备份需按发布方案隔离访问并重新核对丢失期间的离职事件。原始外部员工标识只用于受控持久映射和处置，限制数据库读取权限；普通管理读取与通知只给最小摘要。一次性证明虽五分钟失效，已消费状态和回执仍有幂等意义，不以清除证明替代审计保留。

本文只交付配置与故障处置说明。验证使用进程内替身、jsdom、编译/构建、配置解析和 promtool 合成时序；未连接真实钉钉、启动第三方组件、执行迁移或部署演练。

## 本次收尾验证记录

2026-11-06，管理页面四个相关测试文件共 86 项通过；最后的生命周期组件改动重跑 21 项通过，`npm run typecheck` 和 `npm run build` 通过。后端最后一轮指标、输出许可、单轮/多轮撤销与通知回归共 91 项通过。此前各任务中的身份、应急、映射、接收、复职、Office 与内部协议验证使用各自的进程内测试白名单，未运行包含外部环境或文档断言的全量测试。

监控配置行为检查 160 项通过；固定版本 promtool 3.5.0 解析 71 条生成规则，通过 66 组主合成时序及 1 组 AI 明确不部署场景，包含 26 类目录告警和 38 个既有面板查询。新增六类生命周期告警覆盖等待、触发、恢复、关闭订阅后保留积压/应急要求、无事件、失效/陈旧采样、停止后窗口值及 AI 刷新依赖故障。所有规则均离线计算，没有启动 Prometheus、Alertmanager 或通知服务。

收尾后端检查命令如下；通知消息生成使用替身，不发送外部消息：

```powershell
mvn -pl kb-app,kb-ai,kb-alert-notifier -am '-Dtest=LifecycleMetricsTest,LifecycleObservationsTest,OutputPermitMetricsTest,OutputPermitServiceTest,ChatRevocationTest,MultiTurnRevocationTest,LifecycleAlertTest,CatalogTest,MarkdownTest,WebhookTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```
