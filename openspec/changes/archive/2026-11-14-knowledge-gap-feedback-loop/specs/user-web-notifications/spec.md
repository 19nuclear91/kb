# Spec Delta

## MODIFIED Requirements

### Requirement: 通知跳转必须保留固定业务目标

页面 SHALL 只解析白名单业务目标，在受保护文档／审核页面核对原版本与轮次，或在知识问题详情核对原 gapId 及当前资格和状态。不能采用任意外链、令牌参数、“最新版本”或另一问题代替；目标失效时清理旧内容并提示核对，通知已读状态不绕过新授权，也不代表提问者确认解决。

#### Scenario: 打开旧版回复通知
- **WHEN** 用户打开针对 V1 的回复，而文档当前为 V2
- **THEN** 进入 V1 的受保护版本上下文，不把 V2 的评论当成该回复

#### Scenario: 打开问题通知
- **WHEN** 用户点击 KNOWLEDGE_GAP 目标
- **THEN** 只打开相同 gapId 的受保护详情，重新读取当前状态，不自动标记已读、提交方案或确认解决

#### Scenario: 点击前被改派
- **WHEN** 原负责人读取通知后问题已改派，且不再有其他问题访问资格
- **THEN** 打开目标被拒绝并清理旧问题信息，不从通知恢复正文或回退其他问题

## ADDED Requirements

### Requirement: 新通知界面必须一致声明支持问题单通知

新用户端 SHALL 在通知列表及可见未读数请求中同时发送 `X-Kb-Notification-Contract: KNOWLEDGE_GAPS_V1`，使用同契约游标。不因故障自动去掉能力头重试或混合旧计数与新列表；账号或契约作用域改变时清理原游标与计数，沿用已有至少 30 秒轮询、单在途和失败待确认规则。

#### Scenario: 新列表与未读数
- **WHEN** 新页面及导航读取通知
- **THEN** 两者声明相同能力并消费两类来源结果，通知数不是问题待办数，缺少可靠响应显示待确认

#### Scenario: 读取失败或重新登录
- **WHEN** 能力头请求被拒绝、依赖失败或当前身份改变
- **THEN** 不切换成裸请求掩盖失败，不沿用旧游标或计数填充新身份

### Requirement: 问题单通知必须严格解析最小目标与固定提示

问题单目标 SHALL 只接受 kind=KNOWLEDGE_GAP 和精确 gapId，通知类型须为服务端定义的 GAP_CREATED、GAP_RETURNED、GAP_ASSIGNED、GAP_SOLUTION_SUBMITTED、GAP_SOLUTION_REJECTED、GAP_RESOLVED、GAP_WITHDRAWN、GAP_CLOSED 之一。页面使用本地固定提示，不接收问题标题、描述、方案或任意 URL 作为提示内容。未知类型、额外目标字段和类型/目标不一致均按协议故障处理；既有文档目标结构不变。

#### Scenario: 问题通知含额外正文或外链
- **WHEN** 响应目标包含 description、documentId、returnUrl 或其他非白名单字段
- **THEN** 不渲染或导航该载荷，显示安全协议故障，不尝试宽松猜测目标

#### Scenario: 既有通知混合显示
- **WHEN** 同一页含审核、回复和问题通知
- **THEN** 每种类型分别严格匹配对应目标结构，保持原固定版本跳转和已读行为，不把问题目标误识别为审核目标
