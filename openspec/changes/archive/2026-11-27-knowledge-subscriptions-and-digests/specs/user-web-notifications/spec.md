# user-web-notifications Spec Delta

## MODIFIED Requirements

### Requirement: 通知跳转必须保留固定业务目标

页面 SHALL 只解析白名单业务目标，在受保护文档／审核页面核对原版本与轮次，在知识问题详情核对原 gapId，或在本人更新/周报页面核对原 updateId/digestId 及当前资格。不能采用任意外链、令牌参数、“最新版本”、另一问题或其他周期代替；目标失效时清理旧内容并提示核对，通知已读状态不绕过新授权，也不代表提问者确认解决。

#### Scenario: 打开旧版回复通知
- **WHEN** 用户打开针对 V1 的回复，而文档当前为 V2
- **THEN** 进入 V1 的受保护版本上下文，不把 V2 的评论当成该回复

#### Scenario: 打开问题通知
- **WHEN** 用户点击 KNOWLEDGE_GAP 目标
- **THEN** 只打开相同 gapId 的受保护详情，重新读取当前状态，不自动标记已读、提交方案或确认解决

#### Scenario: 点击前被改派
- **WHEN** 原负责人读取通知后问题已改派，且不再有其他问题访问资格
- **THEN** 打开目标被拒绝并清理旧问题信息，不从通知恢复正文或回退其他问题

#### Scenario: 打开订阅更新或周报
- **WHEN** 用户点击 KNOWLEDGE_UPDATE 或 KNOWLEDGE_DIGEST
- **THEN** 只打开相同本人更新或周报，固定发布版本及周期重新读取，不自动已读或改为展示当前最新内容

### Requirement: 新通知界面必须一致声明支持问题单通知

支持订阅的新版用户端 SHALL 在通知列表及可见未读数请求中同时发送 X-Kb-Notification-Contract: KNOWLEDGE_SUBSCRIPTIONS_V1，该契约包含已有问题单通知；只支持问题的旧版继续使用 KNOWLEDGE_GAPS_V1。每个客户端使用同契约游标，不因故障去掉或降级能力头，不混用不同契约的计数与列表。账号或契约改变时清空游标、列表和计数，沿用至少 30 秒轮询、单在途和失败待确认规则。

#### Scenario: 新列表与未读数
- **WHEN** 支持订阅的新页面及导航读取通知
- **THEN** 两者一致声明 KNOWLEDGE_SUBSCRIPTIONS_V1 并消费协作、问题和订阅来源，周报只算一条通知，缺少可靠响应显示待确认

#### Scenario: 读取失败或重新登录
- **WHEN** 能力头请求被拒绝、依赖失败或当前身份改变
- **THEN** 不切换成裸请求掩盖失败，不沿用旧游标或计数填充新身份

#### Scenario: 旧问题客户端继续运行
- **WHEN** 已部署的旧问题客户端使用 KNOWLEDGE_GAPS_V1
- **THEN** 保持已有问题及协作提醒，不接收不能解析的订阅目标，未读数也只计其支持类型

## ADDED Requirements

### Requirement: 订阅通知必须严格解析最小目标与固定提示

用户端 SHALL 将 SUBSCRIBED_VERSION_PUBLISHED 精确匹配 kind=KNOWLEDGE_UPDATE、updateId，将 KNOWLEDGE_WEEKLY_DIGEST 精确匹配 kind=KNOWLEDGE_DIGEST、digestId，二者 ID 为规范 UUID。只使用本地固定提示；未知类型、额外字段、非法 ID、类型与目标不匹配均作为协议故障，不猜测目标或丢弃部分项冒充完整成功。

#### Scenario: 目标夹带摘要或任意链接
- **WHEN** 更新或周报目标含 title、summary、returnUrl、documentId 等非白名单字段
- **THEN** 不渲染或导航该载荷，显示安全协议故障，不补查这些字段指向的资源

#### Scenario: 混合类型和失败
- **WHEN** 一页包含审核、评论、问题、发布及周报，其中一个目标结构非法
- **THEN** 各类型按各自合同解析，非法项使本页按协议故障处理，不靠过滤未知项伪造空页或错误未读数
