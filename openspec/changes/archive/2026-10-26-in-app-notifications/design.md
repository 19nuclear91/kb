# Design

## Context

动机与范围见 proposal.md。本变更必须在 `versioned-document-review` 实施并同步规格后执行：复用其无正文 `kb_collaboration_event`、收件人意图，以及版本访问和审核资格的同源 SQL 规则。当前 `TaskAlertOutbox` 的 payload、生命周期及日志实现面向解析告警，不能保存用户已读状态；其 dispatch 还在事务中调用适配器，不将该写法复制到未来外部渠道。

现有 `Session` 管理身份 epoch、聚焦恢复及请求取消；收藏提供本人归属、有界游标、权限过滤前分页、no-store 的参考模式。网关当前没有 `/api/notifications/**` 业务路由。

## Goals / Non-Goals

**Goals:** 在同库内可靠派生本人提醒，将不可变业务事实、消费进度与用户已读事实分开；列表和计数使用一致的当前可见性。

**Non-Goals:** 不引入消息中间件、邮件／IM、WebSocket、通知删除／订阅／全部已读、通用消息发送接口；不改变 Worker 告警、业务决定或审批权限。

## Decisions

### D1. 源事件与通知各自拥有生命周期

新增 `kb_notification_projection` 保存 event_id、状态、lease_owner/until、单调 claim_token、recipient_cursor、attempts、next_attempt_at 与固定错误分类；`kb_user_notification` 保存单调内部 ID、UUID notification_id、event_id、recipient_user_id、type、created_at、read_at，唯一 `(event_id, recipient_user_id)`。不复制标题、正文、原因或令牌。

从源事件 NOT EXISTS projection 的反连接中有界发现工作，不仅以全局 last_id 扫描：序列 ID 分配顺序不等于提交顺序，单调游标可能漏掉晚提交事件。发现使用 ON CONFLICT DO NOTHING，多实例重复发现无副作用。源事件不加已发送标记，也不因派生被删除。

替代方案：在原审批事务同步推送会耦合通知可用性；复用审计表作通知队列会丢失收件人、污染计数，均不采用。

### D2. 领取、分批提交与故障恢复

默认每 5 秒扫描，每轮发现／领取最多 50 个事件，每事件分批处理最多 100 个收件人；单批使用短事务、SKIP LOCKED 或租约 token 检查，只执行数据库写入，不调用 HTTP。默认租约 60 秒、失败退避 30 秒起并封顶 15 分钟，单轮处理量有上限，失败保留后续重试。

每批在同一事务写通知及 recipient_cursor，唯一键冲突不刷新通知时间或 read_at。到末页才完成 projection；进程重启从已提交 cursor 续建，过期接管推进 token，旧执行不能推进新进度。租约时间使用数据库时钟。仅通知派生失败不改源审核或评论；事件保存失败仍回滚源业务事务，二者不是同一种故障。

必要低基数指标：待处理事件数、最老年龄、派生失败、接管次数、发现查询耗时，以及读取侧 SQL 与 Java 复核不一致次数；标签只有固定类型／阶段，不能带用户／文档／事件 ID。

### D3. 固定收件人意图与逐次资源授权

REVIEW_SUBMITTED 的收件人是送审时源事务记录的合格池，APPROVED/REJECTED 是本轮送审者；WITHDRAWN/CANCELLED/PUBLISHED 不独立重复通知。排除事件操作者自身，上传者／owner 不替代送审者。

不在派生时按当前角色重算历史收件人；否则断点续建可能改变名单。新加入池者依靠实时审核工作台，不追发旧提醒。通知是否显示再按当前授权核对：

- 待审核：仍是同一 IN_REVIEW、空间未迁移且当前具备 view/review/密级与非自审条件。
- 审核结果：本人为本轮送审者，当前可读对应版本和私有审核状态；edit-only 者仍可从本人工作台读取最小状态，但通知不借此泄露文档或意见。
- 评论回复：待评论源上线后，以有发布证据的版本、当前 view/密级及仍存在的可读回复作为目标。

在 notification/service 定义固定 `NotificationTargetResolver`。通知模块的 Java 代码不引用 document/review/comment 的 Mapper 或写服务，由架构测试约束；授权规则通过源模块公开的同源 SQL 片段取得，由通知 Mapper XML 以 include 引用。结果提醒引用版本 INTERACTIVE 访问片段；待审提醒与审核工作台待办共用同一个 `pendingReviewer` 片段，规则只写一处。

列表与未读 count 共同使用集合式、可下推的 SQL 访问谓词；不能先取 limit 行再逐项丢弃，也不能把全部收件箱载入内存算 count。列表读取时解析器按 SQL 投影出的事实独立复核，与 SQL 结论不一致时安全失败为 503，并计入低基数指标；未读 count 只执行 SQL，不做该复核。放宽共享片段时须同步修改解析器。映射测试检查真实参数、投影行为，以及两处确实引用同一片段。

### D4. 评论生产者晚于消费者上线

预留 COMMENT_REPLIED 类型、event_id/recipient 合约及目标解析扩展点，不在本变更创建评论表或虚构评论。解析器尚不存在的类型保持 WAITING_SOURCE，读取时不可见，不触发失败热循环；评论变更安装真实解析器后恢复消费。不在任务中要求未实现的评论模型才能编译或运行审核通知。

### D5. 本人 API 与一致性

| 方法与路径 | 契约 |
| --- | --- |
| GET `/api/notifications` | 仅 limit/cursor，R.data.items/hasMore/nextCursor；固定 ID 位置倒序 |
| GET `/api/notifications/unread-count` | 无参数，精确十进制字符串 count + asOf |
| PUT `/api/notifications/{uuid}/read` | 无载荷，R.data.notificationId/readAt，首次已读时间不变 |

列表项提供 type、时间、readAt、固定中文提示与结构化 `{kind, documentId, versionId, reviewId?, commentId?}`，仅当前可见时输出目标；不包含原始事件载荷或任意可导航字符串。打开仍按源业务鉴权，审核和评论定位在页面内存传递，刷新后重新核对固定目标，不猜最新版。

cursor 绑定本人、查询类型和上一页内部 ID；撤权后列表与 count 同时隐藏，恢复后可从首页重新发现。列表与单独 count 各有 asOf，不承诺两次请求冻结一致。已读只需当前有效本人归属；即使目标隐藏，也可用本人旧通知 UUID 标记，不输出目标。未知和他人 UUID 同为 404。

使用安全固定错误，参数／JSON 严格有界，服务失败为 503，所有响应 no-store；读取不会递归生成管理审计或通知。

### D6. 页面和网关

`kb-web/src/notifications` 放 API、状态 hook 和测试；`/notifications` 页面展示固定提示而非源正文，导航显示独立状态。默认 60 秒轮询 count，最低配置间隔 30 秒；同一标签页共享一个订阅和在途请求，后台、离线或身份未确认停止。

用户显式“标记已读”调用 PUT，打开目标不自动改已读，更不提交审批。未知结果保留待确认并允许同目标幂等重试，成功后刷新列表和计数。使用 Session epoch、AbortController 和请求代次隔离迟到结果，不引入 localStorage/service worker；公开配置不可放通知或身份凭据。

Gateway 的 prod/dev 业务路径增加 `/api/notifications/**`；成功、错误及 Sa-Token 提前拒绝均 no-store，不改变匿名白名单或 AI SSE 路由。SPA 和登录安全返回增加固定 `/notifications`，拒绝附带 userId、查询或站外地址。

## Risks / Trade-offs

- [权限过滤增加 count 成本] -> 本人／未读／event 外键索引及集合式 SQL，不用未经授权的原始计数降级；本变更不实测负载或执行计划，不宣称实际查询性能已验证。
- [通知不等于任务系统] -> 待办仍以实时审核工作台为准；已处理提醒隐藏，计数明确不是待办量。
- [无自动保留期可能增长] -> 首期只保留最小元数据并监控体量；清理需独立定义覆盖、幂等墓碑和用户预期，不能在本次暗删。
- [只读授权变化无法撤回已送达内容] -> 不保存内容快照，每次查询重新过滤，客户端恢复时清理；不承诺瞬时撤回。
- [晚提交序列洞] -> 反连接发现而非单个全局高水位；分支和恢复逻辑用内存仓储替身单测，真实提交顺序及多实例互斥保留为未验证限制，不列入本变更待办。
- [反连接扫描随事件总数增长] -> projection 不删除，发现每轮扫描全部事件；以发现耗时指标观测，必要时先调大扫描间隔。安全水位优化需要证明滞后大于最长源事务与时钟偏差，另行立项，本变更不实测执行计划。
- [共享片段与 Java 复核漂移] -> 放宽 `pendingReviewer` 而未同步解析器时，列表读取持续 503，未读 count 仍按放宽后的 SQL 返回，角标与列表不一致；以不一致计数观测，不放行未经确认的目标。

## Migration Plan

1. 先完成审核变更的事件、收件人意图、版本门禁及主规格同步，再追加通知迁移，不改源事件 schema 语义。
2. 新 app 可从已有可信审核事件派生，保留原 eventOccurredAt 供目标核对，通知 createdAt 为实际生成时刻；不能从旧日志／未提交状态补造提醒。
3. 部署顺序为网关业务路由与 app 先于前端；评论解析器尚无时仅接入审核来源，未知类型安全等待。本条只说明部署边界，不安排真实环境联调。
4. 回退撤下前端并停止派生调度，保留源事实、通知及已读数据；源审批不依赖消费者运行，恢复后按原事件身份续建。

本变更验证仅限独立内存单测、无连接映射、架构检查、路由规则／过滤器单测、前端组件／状态单测、虚拟时间、类型检查和静态构建；来源 service、认证和下游客户端全部使用 Mock／stub。不得启动完整应用、网关或真实下游，不运行集成测试、跨服务联调、浏览器端到端验收，不启动或连接数据库、Redis、对象存储、消息中间件或第三方 API，不使用 Testcontainers／内嵌服务替代。迁移任务只编写脚本，不实际执行；说明文档作为交付物，不对 README／Markdown／文案或 SQL 文本做断言。真实数据库原子性、网络、提交顺序、性能和多实例行为均保留为未验证限制，不列入本变更待办或完成前置条件，也不得据离线检查宣称生产可用。
