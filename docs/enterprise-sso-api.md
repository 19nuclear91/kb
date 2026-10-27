# 企业浏览器 SSO 接口与结果页

本文提供既有后端及页面对接契约；`kb-web` 已接入企业登录、个人账户绑定及固定 `/auth/sso/result` 结果页，使用与恢复说明见 [企业 SSO 前端](enterprise-sso-web.md)。工作台免登和自动建号不在范围内。生产页面、网关、回调和固定结果页必须同源。

所有新增响应均 `Cache-Control: no-store`、`Referrer-Policy: no-referrer`。公共包装仍为 `R<T>`，成功 body.code=200、失败
body.code=500；HTTP 状态表达400/401/403/404/409/429/503。SSO 业务错误的 data 为 `{errorCode, correlationId}`
；网关、通用鉴权和强制改密拦截器的旧错误包装保留原结构，使用方不能假设所有401/403均有 data.errorCode。

| 方法与路径                              | 请求及资格                                                     | 成功 data                                                              |
|------------------------------------|-----------------------------------------------------------|----------------------------------------------------------------------|
| GET `/api/auth/sso/providers`      | 匿名，无查询参数                                                  | 启用连接数组 `{connectionId, platform}`                                    |
| POST `/api/auth/sso/{id}/login`    | 可信 Origin，application/json，`{}`                           | `{authorizationUrl}`；设置浏览器 HttpOnly Cookie                           |
| POST `/api/auth/sso/{id}/bindings` | 当前 satoken 请求头，已改密有效主体，本次密码 `{password}`                  | `{authorizationUrl}`；不签发本地会话                                         |
| GET `/api/auth/sso/{id}/callback`  | 原浏览器 Cookie、state 与平台 code/error                          | 303 到配置 resultPageUri，独立结果 Cookie                                    |
| POST `/api/auth/sso/exchange`      | 可信 Origin，`{}`，浏览器及当前 flow 结果 Cookie；BIND 必须携带原 satoken 头 | LOGIN 为原 `LoginResult`；BIND 为 `{connectionId, platform, bound:true}` |
| GET `/api/auth/sso/bindings`       | 当前 satoken、主体有效且完成改密，无查询参数                                | 三家连接数组 `{connectionId, platform, connectionEnabled, bound}`          |

`id` 固定为 dingtalk/wecom/feishu。未知或禁用连接发起返回404。本人状态包括禁用连接；只按当前企业、应用作用域计算启用绑定是否存在，不暴露
subject、摘要、企业标识、用户 ID 或管理员绑定行 ID。关闭连接后保留绑定，重新启用同作用域不要求重绑。

POST 必须携带完整精确 Origin，不能缺失、多个或通配；客户端不传 Origin 自定义替代字段。浏览器 fetch 使用同源凭据携带
Cookie。JSON 须为对象，拒绝未知键、重复键、尾随数据和查询参数。BIND 只接受 password；LOGIN/exchange 只接受空对象。拒绝客户端
returnUrl、redirectUri、userId、subject、企业、平台证明或 requestId。回调只接受 state、平台码、error、error_description，单值且不能同时有
code 和 error；钉钉为 **authCode**，另两家为 **code**。

## 浏览器调用顺序

用户从 `/login` 选择服务端启用连接，或从 `/account` 输入当前本地密码发起本人绑定，均使用当前标签页导航。结果页只读取本标签页最小流程提示，不读取 URL 中的认证字段；没有有效提示时不发送 exchange。未绑定引导的账户目标可经本地登录与强制改密保留，但不会自动提交绑定。

1. 登录页读取 providers，选择连接，POST login 获得 authorizationUrl；用浏览器顶层导航打开该 URL。state
   仅在供应商授权地址及其回调中使用，页面不自行持久保存或拼接认证材料。
2. 平台回调由服务端原子领取事务并核验企业成员。成功或可关联的供应商失败暂存短期结果并设置 HttpOnly 结果
   Cookie，再303到固定结果页。无法关联浏览器/state 的请求直接安全拒绝，不覆盖任何别的 flow。
3. 固定结果页加载后 POST exchange，不能从 query 读取令牌或身份；LOGIN 匿名即可。成功时按原本地登录方式处理
   LoginResult（tokenName/tokenValue/loginId/mustChangePassword/user）。令牌只在 JSON 响应交付，改密标记为 true 时先去改密页。
4. 409 SSO_BINDING_REQUIRED 时提示先登录已有本地账号并完成改密，再到账户安全页主动输入当前密码、POST bindings，重新走一次平台授权。首次
   LOGIN 结果不能转为 BIND。
5. BIND 结果页用发起时同一个 satoken 请求头 POST exchange，不能换用户或换成同一用户的新令牌。绑定成功不切换当前登录身份；响应丢失可
   GET bindings 查状态，或重新授权同一身份，保持既有一行绑定。

结果页不把授权参数、token 或凭据放进 URL、localStorage、遥测、错误报告或第三方资源请求，页面自身也设置
no-store/no-referrer，不加载会收集认证信息的第三方脚本。回调、结果页和认证请求的代理／应用访问日志只记路径模板和固定类别，禁用原始
URI/query/body/header、trace/wiretap 和调试请求明细。correlationId 可用于客户端定位固定错误，不能作为身份或重试凭据。

Cookie：生产 `__Host-kb-sso-browser`，Secure/HttpOnly/SameSite=Lax/Path=/，不设Domain；浏览器证明最多600秒，受信新发起可刷新。结果
Cookie 为 `__Host-kb-sso-result-<flowId>`，最多60秒且不超过 flow 剩余期，只含随机256位证明，按 flow 独立清理。显式 dev HTTP
loopback 使用不带 __Host- 的 Cookie；不能把该例外带到生产。

同浏览器各标签页共享当前 flow，后发起替代前一个。旧回调或旧响应不能覆盖新 flow 的结果 Cookie；只消费当前
flow。重复消费、领取后崩溃或结果丢失均需重新发起，不恢复已消费材料，不自动重试旧授权码。

| HTTP / errorCode                               | 页面处理                         |
|------------------------------------------------|------------------------------|
| 400 INVALID_FLOW / CONFIG_CHANGED              | 事务过期、复用、参数不合法或配置变化，重新发起      |
| 401 CREDENTIAL_REJECTED                        | 本地密码或原会话无效，重新本地登录／验证         |
| 403 NOT_MEMBER                                 | 非指定企业有效成员，不继续绑定或登录           |
| 403 USER_DISABLED / PASSWORD_CHANGE_REQUIRED   | 联系管理员或先完成改密                  |
| 403 CREDENTIAL_REJECTED                        | 用户取消授权，不显示供应商原始错误            |
| 404 CONNECTION_UNAVAILABLE                     | 连接未知或已关闭，刷新目录或使用本地登录         |
| 409 SSO_BINDING_REQUIRED                       | 先本地登录，再主动本人绑定                |
| 409 BINDING_CONFLICT                           | 身份已被占用或绑定停用，不显示对方身份，交管理员处理   |
| 429 RATE_LIMITED                               | 等待后重新发起，不循环自动请求              |
| 503 UPSTREAM_UNAVAILABLE / STORAGE_UNAVAILABLE | 平台、权限或存储不可用，稍后重试新事务；本地入口独立保留 |

登出继续使用原 `POST /api/auth/logout`，只退出本平台，不解除绑定、不调用供应商全局登出。解绑继续使用现有管理员权限接口。

前端对本人绑定密码验证 401 至多核对一次 me：正常时保留原会话并清空密码，401 时清理会话，需改密时进入门禁，故障时遮蔽旧内容。BIND 结果不确定可显式查当前绑定状态；LOGIN 已保存令牌而 me 故障时只重核身份，未取得令牌则重新授权。均不自动重发旧交换。
