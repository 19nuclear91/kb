# 独立钉钉告警适配器运维说明

## 范围和部署边界

`kb-alert-notifier` 使用 Java 21、Spring Boot 3.3.5、Web/Actuator/Prometheus 与 JDK HTTP 客户端。它不依赖 `kb-common`、业务模块、数据库、Redis、Spring Cloud、Sa-Token 或 Swagger；不查询任务、文档、用户或结算表，也不消费业务 Outbox。

每个机器人只能部署 **一个实例**。Alertmanager 负责分组、静默、抑制、重复提醒及失败重试；适配器没有内部重试、持久队列或永久 `groupKey` 去重。不要通过扩容绕开单实例额度。

接收和管理端口必须独立。默认只绑定回环；部署到容器时，为适配器分配固定的隔离监控网络 IP，将两个地址设置为该 IP，不使用容器自身回环供其他容器访问。地址只接受明确的 RFC1918 IPv4、IPv6 私网/ULA 或回环字面值，不接受主机名、公网、组播、`0.0.0.0`、`::`。本模块所有环境都执行相同校验，不存在开发配置绕过生产限制的开关。

- 接收端口仅供 Alertmanager 访问；不发布公网端口，不通过业务网关转发。
- 管理端口仅供受控监控网络抓取，只有 `GET /actuator/health` 和 `GET /actuator/prometheus`。
- 网关没有新增通知路由。原业务服务的管理绑定限制不因本模块改变。
- 网络策略仍需阻止不可信主机访问内网；地址校验不能代替防火墙和网络隔离。
- 本地 health 的 UP 只表示配置装配和进程本地可用，不代表钉钉可达、群机器人有效或真实送达；健康读取和抓取不会发送消息。

## 配置契约

所有模块配置前缀均为 `kb.notifier`。下表环境变量有显式别名绑定，环境变量存在时优先于同名属性。空字符串不是缺省值。`NotifierBootstrap` 在服务器创建前校验配置，并固定底层端口、路由、访问日志和 HTTP 遥测边界；不要通过 `SERVER_PORT`、`MANAGEMENT_SERVER_PORT` 或通用 Actuator 属性覆盖本表。

| 环境变量 | 属性后缀 | 必需/缺省及限制 |
| --- | --- | --- |
| `KB_NOTIFIER_ENVIRONMENT` | `environment` | 必需；`[a-z0-9][a-z0-9_-]{0,47}` |
| `KB_NOTIFIER_DEPLOYMENT` | `deployment` | 必需；同上，必须与监控标签完全一致 |
| `KB_NOTIFIER_CATALOG_FILE` | `catalog-file` | 必需；可读普通 JSON 文件，最大 64 KiB |
| `KB_NOTIFIER_BEARER_SECRET_FILE` | `bearer-secret-file` | 必需；入站独立 secret 文件，32—512 UTF-8 字节 |
| `KB_NOTIFIER_DINGTALK_TOKEN_FILE` | `dingtalk-token-file` | 必需；机器人 access token 文件，20—256 字节，ASCII 字母/数字/`_`/`-` |
| `KB_NOTIFIER_DINGTALK_SECRET_FILE` | `dingtalk-secret-file` | 必需；机器人加签 secret 文件，16—256 字节，同上字符范围 |
| `KB_NOTIFIER_GRAFANA_ROOT` | `grafana-root` | 必需；明确 HTTPS 来源，最多 200 ASCII 字符 |
| `KB_NOTIFIER_ADMIN_ROOT` | `admin-root` | 必需；同上 |
| `KB_NOTIFIER_ADDRESS` | `address` | 默认 `127.0.0.1`；明确的内网/回环地址 |
| `KB_NOTIFIER_PORT` | `port` | 默认 `8090`；1024—65535 |
| `KB_NOTIFIER_MANAGEMENT_ADDRESS` | `management-address` | 默认 `127.0.0.1`；明确的内网/回环地址 |
| `KB_NOTIFIER_MANAGEMENT_PORT` | `management-port` | 默认 `9090`；1024—65535，必须与接收端口不同 |

HTTPS 根地址只允许来源（可以有端口及一个末尾 `/`），不允许用户名、密码、查询参数、fragment 或路径前缀。使用真实受控管理来源，不能使用 `example` 等示例值。管理链接不会携带用户令牌；原应用继续执行登录和权限检查。

三个 secret 文件只保存值本身，不写 `Bearer ` 前缀或完整 webhook URL。使用 UTF-8 无 BOM，允许末尾单个 LF 或 CRLF；不允许其他空白、控制字符、空值和明显占位符。入站 secret 建议由密码管理器生成至少 32 字节随机值。目录及 secret 文件只以 READ 打开，部署必须以只读挂载及最小文件 ACL 交付，禁止写入镜像、普通生成配置、仓库或命令行。文件名也不应包含 secret。

启动失败只报告固定配置类别/属性名，不输出内容或底层文件异常。凭据及目录均启动时读入，**轮换/修改后必须重启**；不会热加载。先更新受控 secret，再协调适配器与 Alertmanager 重启，避免新旧入站 token 暂时不一致。机器人 token/secret 轮换也要同步完成，不通过临时取消签名来过渡。

## 固定告警目录

运行时加载 `ops/monitoring/alert-catalog.json` 对应的只读挂载文件；实际路径通过 `KB_NOTIFIER_CATALOG_FILE` 指定。目录根为数组，必须恰好包含设计首期 20 个告警名，名称不能重复或增加。每项只有：

```json
{
  "name": "KbParseDeadLetters",
  "services": ["worker"],
  "severity": "warning",
  "family": "parse",
  "summary": "存在未处置解析死信，请核对采样和任务状态",
  "link": {"root": "admin", "path": "/admin/parse/dead-letters"}
}
```

上述单项仅说明格式，不是可启动的完整目录。生产目录与监控规则共用配置来源；测试在内存构造完整 fixture，不依赖生产目录文件。

- service 枚举：`app,worker,ai,gateway,notifier,prometheus,alertmanager,grafana`，每项允许多个不同服务，不能空数组。
- severity 仅 `warning/critical`；family 为 `[a-z][a-z0-9_-]{0,47}`；固定摘要最多 200 code point，不允许控制字符或 URL（含 `://`、`www.`）。
- `root=admin` 只允许 `/admin/parse/dead-letters`、`/admin/embedding`、`/admin/ai/audits`。
- `root=grafana` 只允许 `/d/kb-overview`、`/d/kb-parse-index`、`/d/kb-search-ai`。
- 目录摘要按 Markdown 安全转义；URL、路径遍历及不匹配的 root/path 被拒绝。没有自定义 webhook URL、任意模板或动态跳转配置。
- 结算主要从指标面板定位。AI 审计只覆盖启用采集后的记录，页面没有对应记录不能解释为无积压。

## 入站接口和状态语义

`POST /internal/alerts/dingtalk`，`Authorization: Bearer <独立文件值>`，`Content-Type: application/json` 或 `application/json; charset=utf-8`。没有业务用户 token、Cookie 或 satoken 替代入口。所有响应 `Cache-Control: no-store`、空正文；不回显路径、载荷或错误。带查询参数、非固定路径或错端口返回 404。

认证在读取正文以及媒体类型、正文长度判断之前执行。多个 Authorization 头也拒绝。支持 Alertmanager 标准 version 字符串 `"4"`，必须包含 receiver、groupKey、groupLabels、commonLabels、commonAnnotations 和非空 alerts；每项要求 labels、annotations、status、startsAt、endsAt。groupLabels 包含 environment/deployment/service/alertname；每项另外包含 severity。环境、部署、组身份、目录服务/级别必须一致；提供 family 时也核对目录。

时间接受带时区的 RFC3339，开始时间不能为零时间；firing 可使用 `0001-01-01T00:00:00Z` 结束时间，resolved 的结束时间不能早于开始。resolved 组不能夹带 firing；firing 组允许混合状态。`truncatedAlerts` 可省略，缺省 0；提供时必须是非负 int64 整数。

| 边界/结果 | 行为 |
| --- | --- |
| 未认证/错误凭据 | 401，不读正文，不发送 |
| 未知路由/错方法 | 404/405，不发送 |
| 非 JSON 媒体类型、其他 charset 声明、任何 Content-Encoding | 415，不发送 |
| JSON 重复属性、尾随数据、身份/时间/结构错误 | 400，不发送任何部分 |
| 正文 >256 KiB、alerts >100、嵌套 >16、字符串/属性名 >4096 code point | 413，不发送 |
| 有界扩展字段 | 可忽略，但仍受字符串、深度、正文限制 |
| 外部明确业务成功 | 204 |
| 限流、并发满、超时、取消或外部失败 | 固定 503，交由 Alertmanager 重试 |

损坏 JSON、UTF-8 损坏字节或孤立 surrogate 属于损坏正文，返回 400；媒体类型声明使用其他 charset 则为 415。数字词法长度另限 128 字符。

单条中文 Markdown 只投影固定摘要、受控身份、时间及数量，不使用 annotations、externalURL、generatorURL、fingerprint 或完整标签。正文在转义后最多 3500 UTF-8 字节，标题最多 80 code point；保留组状态、实际接收 firing/resolved 数量、上游 truncatedAlerts、本地省略详情数量以及固定管理入口。超预算只省略完整详情行，不拆断 Unicode、不拆成多条。解除固定提示“告警条件已解除，请核对采样和当前状态”，不把规则消失或缺失数据解释为全部业务恢复。

## 出站与容量

生产目标固定 `https://oapi.dingtalk.com/robot/send`。签名使用 UTF-8 `timestamp + "\n" + secret`，以 secret 作 HMAC-SHA256 key，结果 Base64 后 URL 编码；URL 只由本地 token、毫秒时钟及签名生成。独立 JDK 客户端不用代理、Cookie/Authenticator，不跟随重定向，不复制 Authorization、Cookie、satoken、traceparent、tracestate 或 baggage。不得给该进程安装自动采集 HTTP URL/正文的外部追踪代理或开启 JDK HTTP 调试日志。

- 连接超时 2 秒；一次出站尝试的总期限 8 秒，覆盖签名、连接、响应头、完整正文及业务确认，不是仅响应头超时。
- 响应最多 16 KiB，严格 UTF-8、有界 JSON、拒绝重复属性及尾随数据；仅 HTTP 2xx 且 `errcode` 为**整数零**时确认。字符串 `"0"`、`0.0`、`0e0`、布尔值及截断整数均不能冒充成功。
- HTTP 3xx/4xx/5xx、非零业务码、无效响应、连接失败、响应丢失、超时或调用线程取消均失败；从不输出供应商 errmsg。
- 同时最多 2 次实际尝试；单调时钟滑动 60 秒窗口最多 10 次，失败同样占用额度，恰好满 60 秒的尝试移出窗口。拒绝不排队，不额外发送。
- 每个请求只调用一次传输。JDK 自动连接重试被禁用；取消/期限触发后取消网络请求并关闭该客户端，**等待实际终止后**才返回并释放槽位。8 秒是确认/取消期限，不以提前返回来伪造资源已退出；若底层终止延迟，槽位和同步请求继续保留，后续容量请求被拒绝。
- 建议 Alertmanager webhook timeout=15s，且小于 group_interval；按设计 group_wait=30s、group_interval=5m、repeat_interval=4h，send_resolved=true、max_alerts 不超过 100。
- 发送成功但上游回执丢失，或调用方连接先断开，可能引发后续重复提醒。HTTP 同步处理不能保证感知对端断开的瞬间；不会启动后台补发，不承诺恰好一次。
- 重启会重置内存限流窗口。双实例、滚动重叠和频繁重启均无法保证机器人全局额度，升级应停止旧实例后启动新实例。

## 指标与故障定位

本模块只为固定枚举输出标签，不使用机器人、groupKey、fingerprint、原始 URI 或任何凭据作维度：

- `kb_alert_notifier_requests_total{outcome="success|rejected|failure"}`：接收结果；success 是 204，4xx 是 rejected，503 是 failure。
- `kb_alert_notifier_deliveries_total{outcome}` 与 `kb_alert_notifier_delivery_duration_seconds_{count,sum,max}{outcome}`：真实尝试结果与耗时；outcome 为 `success,remote_http,remote_business,remote_json,timeout,network,cancelled,internal`。
- `kb_alert_notifier_rejections_total{reason}`：`unauthorized,invalid,too_large,media_type,route,method,concurrency,rate,remote_http,remote_business,remote_json,timeout,network,cancelled,internal`。
- `kb_alert_notifier_inflight`：仍占用出站槽位的尝试，包括取消后等待实际退出的尝试。

通用 HTTP server/client 指标与 observation 被禁用，Tomcat access log、请求详情、JDK HTTP 调试日志均关闭。日志只记录 `notification_delivery outcome=<固定枚举>`，没有原始异常堆栈、URI、签名或报文。

排障顺序：

1. 启动失败先核对固定配置名、文件挂载/ACL、编码、双端口及目录，不把凭据打印到终端。
2. 401 核对 Alertmanager 的 Bearer 文件与适配器文件一致，以及轮换后双方已重启；用户 token 无效。
3. 400/413/415 核对实际 Alertmanager 协议版本、分组标签、同源目录和 max_alerts，不能通过放宽身份或任意正文转发来处理。
4. `rate/concurrency` 持续增长时检查分组/重复提醒间隔及真实尝试耗时，不绕过限流；确认只有一个实例。
5. `remote_http/remote_business` 检查机器人启用、安全设置、token/secret 与发送额度；`timeout/network` 检查受控出口、DNS/TLS 和时钟同步；`remote_json` 检查响应契约/中间设备，不记录或转发供应商错误正文。
6. `inflight` 长期为 2 表示底层尝试尚未实际退出，应检查进程/出口。不能靠提前归还槽位继续堆积请求。
7. 整套 Prometheus/Alertmanager/监控主机或钉钉出口失效时，同一链路不能保证通知自身；独立外部探活与 HA 不在首期交付范围。

## 构建、部署说明与离线验证

仅运行本模块的 Maven 命令（PowerShell 7）。仓库本模块所有 `*Test` 均为进程内配置/MockMvc/可控时钟/Mock 或内存传输测试，不启动应用、HTTP 监听、数据库、Redis、容器或真实钉钉，也没有文档内容断言测试：

```powershell
mvn -o -pl kb-alert-notifier package
mvn -o -pl kb-alert-notifier dependency:tree
```

离线模式需要本地 Maven 缓存已有依赖；缺少依赖应明确失败，先由受控构建环境准备，不能跳过测试冒充通过。产物是 `kb-alert-notifier/target/kb-alert-notifier-0.1.0-SNAPSHOT.jar`。

以下仅供**部署期操作说明**，不是本变更执行的验收命令。预先用运维系统设置上表身份、受控根地址及只读文件路径后，可以运行：

```powershell
java -jar .\kb-alert-notifier\target\kb-alert-notifier-0.1.0-SNAPSHOT.jar
```

升级前保留目录、secret 文件权限、监控持久数据及 Alertmanager 配置；先停旧适配器再启新适配器。回退先停止相关通知路由，再停止/替换适配器，不需要修改业务数据；不要删除监控持久卷或把旧指标契约缺失描述为恢复。

2026-11-05 已读取钉钉官方可读正文并静态核对：加签依据为 https://open.dingtalk.com/document/robots/customize-robot-security-settings.md ，其中 Java/Python 示例均使用毫秒 timestamp、secret 作 HMAC-SHA256 key、`timestamp + "\n" + secret` 作消息、Base64 后 URL 编码；签名和查询必须使用同一 timestamp，文档要求与请求时间误差不超过一小时。Markdown 对象依据 https://open.dingtalk.com/document/orgapp/custom-bot-send-message-type.md ，必填 `msgtype=markdown`、嵌套 title/text，与实现一致。已知签名向量由独立 .NET 运算交叉验证，不冒称官方固定向量。

响应契约存在需保留的官方文档差异：https://open.dingtalk.com/document/orgapp/custom-robots-send-group-messages.md 的参数表将 errcode 标为 Number、示例 0，但返回示例写字符串 `"0"`；https://help.aliyun.com/zh/iot-studio/user-guide/dingtalk-chatbot 则提供整数 0 成功示例。本实现按已批准规格严格要求整数 0，字符串零返回 503，不基于矛盾示例放宽成功确认。官方正文静态核对不证明真实机器人返回格式、运行环境时钟、部署网络或群送达，本变更不提供真实群验收入口。
