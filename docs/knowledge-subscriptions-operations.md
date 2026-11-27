# 知识订阅与周报运维

本功能已实现并完成独立离线检查，2026-11-27同步8份主规格并[归档](../openspec/changes/archive/2026-11-27-knowledge-subscriptions-and-digests/archive-record.md)，见[验证记录](../openspec/changes/archive/2026-11-27-knowledge-subscriptions-and-digests/verification.md)。V45迁移已交付，尚未执行；测试范围为进程内、内存替身及无连接映射检查，真实数据库约束、锁、执行计划、联调和部署未验证。

## 数据与生命周期

| 记录 | 作用 |
| --- | --- |
| `kb_knowledge_order` | 单行事务顺序及非递减数据库时间，协议版本固定为1 |
| `kb_knowledge_subscription` | 本人与文档、空间或标签的稳定关系 |
| `kb_knowledge_subscription_period` | 每次订阅代次的生效和取消位置；重新订阅产生新代次 |
| `kb_knowledge_preference` / `_revision` | 当前统一提醒频率和不可改修订历史 |
| `kb_knowledge_command_receipt` | 本人请求绑定和最小回执，状态与回执同事务提交 |
| `kb_knowledge_publication` | 真实发布当时的空间、标签、固定版本、位置和业务周 |
| `kb_knowledge_match_projection` | 发布匹配的领取、分批游标、退避和完成进度 |
| `kb_knowledge_update` / `_match` | 本人发布记录及全部原匹配订阅代次 |
| `kb_knowledge_week` | 固定周封口、匹配完整性和收件人发现进度 |
| `kb_knowledge_delivery` | 逐次/每周提醒工作及 NOTIFIED 或 NO_ELIGIBLE 终态 |
| `kb_user_notification.knowledge_delivery_id` | 共享收件箱的第三来源，保留原协作与问题来源 |

通知不复制标题、说明正文或数量。匹配只保存订阅意图，不是权限快照；每次读取重新判断当前可读范围。取消保留历史，已生成通知的首次已读时间不可重置。首期不自动清理这些表，不能手工删除进度来重新投递。

## 发布顺序与锁

`kb_knowledge_next_order()` 在原业务事务内锁定唯一控制行，递增位置并取不早于上次的数据库时间。行锁保持至事务提交，回滚不保留本次位置。发布时间、原发布事件时间和订阅上下文时间来自同一次取号；周归属固定使用 `Asia/Shanghai`。

锁顺序为请求标识互斥、既有资源锁、knowledge order、新关系或工作行。持有 order 后不得再申请资源行锁、调用外部服务或执行订阅者扇出。发布捕获函数只读取已被原业务锁定的文档归属，保存最多20个标签ID。

生效区间按位置判定：发布位置大于开始位置，且小于取消位置（未取消时没有上界）。频率取发布位置之前最后一条偏好修订，无显式修订为 WEEKLY。进程时间、序列号分配时间和扫描游标都不能替代这套生效关系。

发布证据、协作事件和订阅上下文在提交时共同校验。缺少上下文的旧发布写入者会失败；发布消费者关闭不能停止源上下文保存。新协议没有为历史事件补造空间、标签或收件人。

## 迁移与兼容

V45 新增订阅相关结构、函数、索引和提交时约束，并扩展收件箱第三来源。原通知 UUID、内部顺序、创建时间、首次已读、协作与问题来源唯一键和外键保留。历史空间ID不建立阻止合法空空间删除的外键。

部署应先排空旧 app/Worker 写入者，由 app 执行迁移，再升级全部 app 和 Worker，随后更新 gateway 与 web。Worker 不执行 Flyway。回退必须使用仍支持该发布协议的兼容后端，保留新数据和回执，不删除迁移历史或重开旧 DONE projection。

以上为部署说明，本轮不运行数据库迁移或部署命令。实现进度和离线执行记录以变更 tasks 与后续验证记录为准。

## 派生状态与恢复

发现使用发布上下文对独立projection的反连接，晚提交/晚发现不会因全局扫描水位被跳过。每类领取限制50项，使用数据库租约、递增claimToken及SKIP LOCKED；每个收件人批次独立事务，默认100人，最大500人。个人update、全部原命中代次、逐次工作登记与游标一起提交，来源整批完成后才允许逐次投递。

匹配PENDING可推进；来源证据/协议不完整进入WAITING_SOURCE并退避，固定错误SOURCE_NOT_READY；其他失败为DERIVATION_FAILED。租约过期可接管，旧token不能推进或覆写新失败。DONE不重新打开，也不触碰旧协作projection。

周任务在下周一北京时间09:00到期，持有order登记可信closePosition；WAITING_MATCHES必须确认原周全部发布匹配DONE才开始收件人游标。READY分批登记每人每周唯一delivery，DONE只表示登记结束，不等于通知已生成。空周可完成登记而不产生收件箱行。

提醒终结取得order后检查全部原代次的取消位置和当前可读范围。至少一个合格项目才原子写NOTIFIED及收件箱；无合格项目写NO_ELIGIBLE，二者都是终态。数据库或权限依赖故障必须退避，不能伪装NO_ELIGIBLE。周报保存dispatchPosition，后续取消不追溯移除已发成员；后来重新订阅不救活旧待提醒项。

## 配置

配置前缀 `kb.knowledge-subscriptions`，环境变量前缀 `KB_KNOWLEDGE_SUBSCRIPTIONS_`，后缀大写并用下划线替代短横线。

| 配置 | 默认 | 允许范围 |
| --- | --- | --- |
| derivation-enabled | true | 与kb.notification.derivation-enabled合取 |
| scan-delay-ms | 5000 | 1000–3600000毫秒 |
| discover-limit / claim-limit | 50 | 1–500 |
| recipient-batch-size | 100 | 1–500 |
| max-batches-per-event | 10 | 1–50 |
| lease-seconds | 60 | 30–3600秒，至少三倍10秒事务预算 |
| backoff-initial-seconds | 30 | 至少1秒 |
| backoff-max-seconds | 900 | 不小于初始值，最多86400秒 |
| catchup-weeks | 20 | 1–52 |
| metrics-delay-ms | 30000 | 1000–3600000毫秒 |

非法值在初始化时拒绝。每阶段独立领取及轮次预算，一个来源故障不消耗旧通知循环预算。关闭派生不影响订阅管理、读取和三个发布入口的捕获，恢复也不改原频率。

## 观测

Micrometer名称：`kb.knowledge.subscriptions.backlog`（stage=match/weekly/delivery）、`oldest.age.seconds`、`snapshot.available`、`snapshot.timestamp`，均以 `kb.knowledge.subscriptions.` 为前缀；计数器 `work` 仅使用固定stage/outcome。Prometheus按注册器惯例转为下划线名称。

指标采样读取完整积压，包括缺projection的发布及未终结delivery；NOTIFIED/NO_ELIGIBLE不算积压。抓取只读内存快照，不查库；采样失败保留上次值且available=0，首次失败值为NaN。先看快照可用性和时间，再解释积压及年龄。指标和固定错误不包含用户、文档、空间、标签、请求标识、标题、说明或底层异常。订阅Mapper日志关闭，个人查询条件不进入URL。

匹配意图、当前授权、提醒生成、首次已读是四个独立事实。个人历史存在不等于已发通知；有通知不代表当前仍可读；已读不表示业务处理或确认知识准确。使用规则见[用户说明](knowledge-subscriptions.md)，发布/回退见[协调发布](knowledge-subscriptions-rollout.md)。
