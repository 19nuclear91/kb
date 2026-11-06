# 钉钉生命周期接入

接入配置独立于 SSO，命名空间为 `kb.identity.lifecycle.dingtalk`，默认关闭。先在钉钉同一企业的内部应用中配置通讯录可见范围、成员详情权限及 `user_leave_org` 事件订阅，再在本系统登记应急管理员和明确的员工映射。SSO 开关不控制此接入，关闭接收也不关闭成员核验、持久限制或恢复任务。

无秘密的配置示例：

```yaml
kb:
  identity:
    lifecycle:
      dingtalk:
        enabled: false
        enterprise-id: ${KB_LIFECYCLE_DINGTALK_ENTERPRISE_ID:}
        client-id: ${KB_LIFECYCLE_DINGTALK_CLIENT_ID:}
        client-secret: ${KB_LIFECYCLE_DINGTALK_CLIENT_SECRET:}
        unified-app-id: ${KB_LIFECYCLE_DINGTALK_UNIFIED_APP_ID:}
        connection-id: dingtalk-internal
        application-type: INTERNAL
        max-message-bytes: 1048576
        max-employees: 1000
        workers: 2
        queue-capacity: 16
        timeout-millis: 2000
        stream-hosts: [wss-open-connection.dingtalk.com]
```

企业 ID、客户端凭据和统一应用 ID 必须属于同一企业内部应用。`client-id` 是连接鉴权凭据中的应用标识；`unified-app-id` 必须填写该应用在事件信封中的 `eventUnifiedAppId`，不得直接复制 OAuth clientId 并假定相同。配置者从平台应用信息及可信订阅配置确认对应关系；接收端分别严格比较企业和统一应用标识，不匹配返回 LATER，不能通过宽松匹配放行。连接别名只用于内部展示，不参与业务去重；业务作用域绑定来源、企业和统一应用，换密钥或连接不清空去重事实。

SDK 固定为官方 Java Stream 的 `com.dingtalk.open:app-stream-api:1.3.12`。其公开事件执行器、请求/响应类型和 ACK 枚举用于协议适配。官方默认构造器的线程队列和握手等待不提供本项目所需的配置边界，因此未使用默认连接构造器，而由隔离的 JDK HTTP/WebSocket 适配器控制连接。排除未使用的官方 WebSocket 实现及传递 JUnit；依赖树中协议各组件均为 1.3.12，Netty 由本项目管理为 4.1.114.Final。已完成 Java 21 编译与依赖树检查；这不等于企业实例联调。

连接申请只访问 `https://api.dingtalk.com/v1.0/gateway/connections/open`，当前成员查询只访问 `api.dingtalk.com`、`oapi.dingtalk.com` 的固定接口。不跟随重定向，不接收客户端指定的供应商 URL，不传播用户令牌、内部认证签名、Cookie 或追踪头。返回的连接地址还须通过精确 WSS 主机白名单、443 端口及无用户信息/查询/片段检查，再附加平台 ticket。ticket 和平台 accessToken 只在内存内使用。

单次 HTTP/握手/ACK 发送上限两秒，接收只有一个连接，每五秒检查是否需要重连。每次重新申请连接前重新核对应急就绪。完整消息上限一 MiB、单事件输入列表上限 1000 人，超限拒绝整条，不截断。SDK 转换前检查 JSON 深度、重复键和载荷结构；列表在检查输入数量后去重排序。业务处理线程默认两个、队列 16；网络执行器各两个线程、队列 32。每条消息等待处理和 ACK 发送完成才请求下一条，队列满或响应失败关闭当前连接，由来源重投。停止接收后中止连接，最多等待五秒在途处理，未成功确认的投递可重试。

官方 SDK 原始日志关闭，适配器只输出固定的连接可用/不可用文本；不记录异常原文、连接 ticket、凭据、完整员工标识或事件载荷。不得在生产打开 JDK HTTP 请求/内容调试、SDK 原始日志或代理的带查询串访问日志。

成员核验仅接受准确员工 ID，使用独立内部应用 token 调用成员详情接口，校验返回 ID 一致且 `active` 为明确的布尔真值。该字段表示已激活钉钉；查询到授权企业内的当前成员加上这一检查才构成本项目的核验证据，不能单凭客户端声明在职。成员不可见、接口错误、非激活、字段损坏或超时都不能用于纳管/复职。应用 token 有界缓存并提前过期；成员资格不作正向缓存，每次核验均查询。不写 SSO 绑定，也不按姓名或手机号匹配账号。

ACK 使用传输 `messageId` 回应当前投递，业务去重使用持久来源作用域与 `eventId`。SDK 回调仅在事件接收服务正常返回后产生 SUCCESS；该服务必须完成第 6 组接收事务的提交，包括 Inbox、逐员工结果、已匹配账号限制、认证代次、审计和清理意图。事务失败为 LATER。未映射或隔离事件的持久接收不能计为已完成停权。入职和其他明确支持的非离职通知只记忽略，不建号或自动复职。

协议核对来源：[官方 SDK](https://github.com/open-dingtalk/dingtalk-stream-sdk-java)、[1.3.12 官方发布源码包](https://repo.maven.apache.org/maven2/com/dingtalk/open/app-stream-api/1.3.12/app-stream-api-1.3.12-sources.jar)、[Stream 协议](https://open-dingtalk.github.io/developerpedia/docs/learn/stream/protocol/)、[通讯录离职事件](https://open.dingtalk.com/document/orgapp/event-v3user-leave-org.md)、[成员详情](https://open.dingtalk.com/document/orgapp/query-user-details.md)。测试均使用进程内替身，没有连接企业平台或启动数据库、Redis、前端服务，也未据此宣称真实环境达到三十秒停权目标。
