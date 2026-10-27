# Design

## Context

动机与交付范围见 `proposal.md`；浏览器行为由 `specs/user-web-sso/spec.md` 和 `specs/user-web-session/spec.md` 约束。

已核对的实现及其影响：

- `kb-app/auth/sso/SsoController` 已提供连接目录、LOGIN/BIND 发起、供应商回调、结果交换和本人绑定状态。发起与交换要求精确 Origin、同源 HttpOnly Cookie、严格 JSON；接口不接受 requestId 或 returnUrl。
- `SsoFacade.exchange` 返回 LoginResult 或 Bound，响应没有统一 intent 字段；BIND 必须使用发起时的原令牌。事务一经消费不能靠重复 exchange 恢复。
- `kb-web/src/session.ts` 的 `send` 固定 `credentials: 'omit'`，保护请求 401 立即清理凭据，403 会先核对身份。`readJson` 丢弃失败载荷，无法区分 SSO 错误类别。
- `Session.refresh()` 会调用 `invalidate()`，递增 epoch 并中止请求；`SessionProvider` 在挂载、聚焦和页面恢复时自动执行重核。直接复用这一机制发送 exchange 会与一次性消费竞争。
- `AppRoutes` 在加载及身份故障时先返回全局状态页，现有路由没有固定结果页。LOGIN 结果页必须能在匿名或旧身份不可确认时运行。
- `session.test.ts`、`lifecycle.test.tsx`、`routing.test.tsx` 已覆盖令牌存储、401 清理、403 有界核对、旧响应及页面恢复；项目已有 Vitest、模拟 DOM 和内存传输设施。
- `enterprise-sso` 后端能力按浏览器维护一个当前 flow。前端没有服务端 flow 标识，无法据本地提示证明结果属于某个标签页；其并发边界必须保留。

## Goals / Non-Goals

**Goals:**

- 在现有前端和后端协议之间建立可测试的浏览器交接层，保证一次性交换、会话接管及资源状态隔离相互协调。
- 将凭据替换与普通资源重核分开识别，使焦点变化不会丢失有效交接，退出及新登录仍能废弃旧结果。
- 把网络不确定、协议拒绝和身份失效转换成具体恢复入口，而不是统一自动重试。

**Non-Goals:**

- 不修改后端 flow、Cookie、成员核验或绑定唯一性语义，不增加跨标签页事务标识、恢复令牌或补发登录结果接口。
- 不重写全站请求层，不给普通业务接口开放 Cookie 模式，不改变文档和管理接口的授权规则。
- 本变更的验证仅包括纯内存单元测试、类型检查与静态构建，排除集成、跨模块联合、端到端及文档类测试，不启动或连接外部组件；部署内容只交付说明。

## Decisions

### D1．沿用同一前端工程和当前标签页授权

登录页及账户页复用 `pages/AuthPages.tsx`，新增 `pages/SsoResultPage.tsx`；SSO 协议、固定提示及流程协调集中在 `src/sso/`。沿用已有组件、样式及依赖，通过可注入导航函数测试顶层跳转，不加载平台 SDK、远程图标或 iframe。

连接显示名来自固定平台字典，是否显示登录入口只由 `/providers` 决定。账户的绑定操作由 `/bindings` 的已确认事实决定；状态读取失败时保留失败状态而不是显示未绑定。登录页的本地与企业发起共用提交互斥，防止两个认证请求竞争接管身份。

选择当前标签页是为了保留现有 sessionStorage 会话与同源 Cookie，避免新增跨窗口令牌传递协议。弹窗及内嵌扫码需要额外生命周期与通信处理，本期不采用。

### D2．为 SSO 定义窄的请求及协议边界

在 Session 内部提供仅能调用明确 SSO 方法和路径的传输入口，复用可注入 transport、请求取消、身份隔离及安全 JSON 编解码；`src/sso/api.ts` 负责 DTO 校验、错误白名单及导航目标校验。令牌仍保持私有，页面不能取得通用的令牌导出方法，也不能给任意 URL 启用 Cookie。

| 调用 | Cookie | satoken | 提交体 |
| --- | --- | --- | --- |
| GET providers | omit | 无 | 无 |
| POST 连接 login | same-origin | 无 | `{}` |
| GET bindings | omit | 当前令牌 | 无 |
| POST 连接 bindings | same-origin | 当前令牌 | `{password}` |
| POST exchange，LOGIN | same-origin | 无 | `{}` |
| POST exchange，BIND | same-origin | 原会话令牌 | `{}` |

全部请求只到固定同源 `/api` 路径，`cache: 'no-store'`；浏览器产生 Origin，不伪造替代头。请求与响应读取共用 15 秒截止时间，失败不自动重发。成功 JSON 最多 32 KiB，错误 JSON 最多 8 KiB，以实际读取字节数限制，不能只相信 Content-Length；超限、非 JSON 或损坏响应均安全结束。凭据发起或交换收到成功状态但无法完整解析时，视为结果不确定。

成功响应验证 `R.code=200`，并按操作校验形状。LOGIN 和 BIND 的结果不能混用；带混合字段或不匹配平台的 Bound 不可接管。令牌校验沿用现有 satoken 名称与值约束，资源整数继续使用既有无损 JSON 规则。

失败只认可 `R.code=500`、HTTP 状态与已知 errorCode 的组合；`INVALID_FLOW` 兼容后端的 400 和 403。correlationId 只接受有界合法格式用于排查，不参与授权。未知字段、类别或旧网关包装使用当前操作的固定通用提示，不展示原始 message、body 或授权地址。429 在 SSO 中表示发起受限，不能套用全站“部门额度不足”文案。

导航校验使用当前后端适配器的固定入口：

| 平台 | HTTPS 主机 | 授权路径 |
| --- | --- | --- |
| dingtalk | login.dingtalk.com | /oauth2/auth |
| wecom | login.work.weixin.qq.com | /wwlogin/sso/login |
| feishu | accounts.feishu.cn | /open-apis/authen/v1/authorize |

只导航到该次响应中的完整地址，拒绝用户信息、非默认端口、fragment、错误主机或路径；不解析后持久保存 state，不重构授权 query。替代方案是全局修改 `send` 和 `readJson`，但会扩大 Cookie 及错误语义的影响面，因此采用窄入口。

### D3．用最小标签页提示承接完整页面跳转

在发起前验证同一 sessionStorage 可用，保存版本化、短期的非敏感提示：本地 attemptKey、intent、platform、phase、expiresAt 和已通过 `safeReturnPath` 校验的 returnPath。记录不包含用户名、用户 ID、令牌副本、密码、授权地址、state 或 Cookie；总大小限制为 2 KiB，存活时间最多 10 分钟。有效期只是 UI 清理上限，后端更短的 TTL 始终优先。

发起请求成功并通过 URL 校验后才将记录置为 ARMED 并导航。密码提交完成立即清空，无论成功与否都不进入提示。发起响应丢失时记录为不确定，不自动重复发起；用户显式选择重新开始才创建新的 attemptKey。站内返回地址缺省为 `/documents`，未绑定引导将其改为 `/account` 并只保留非敏感平台提示；经过强制改密后仍可恢复该站内目标，但不会自动提交绑定。

结果页只消费合法、未过期的本标签页 ARMED 提示；缺失或损坏时提供安全返回入口，不碰浏览器当前 flow。提示是 UI 上下文，不是授权证据。已有流程的意图或平台与响应不符时拒绝接管。

不采用保存授权码、复制 Cookie、额外持久化 token 或解析 URL 参数恢复流程的方案，避免产生第二套凭据来源。

### D4．一次性交换由标签页协调器持有

`src/sso/flow.ts` 以 Session 实例和 attemptKey 关联一次交接，持有唯一在途 Promise 与结果状态。发送 exchange 前同步将提示写成 CONSUMING；若存储写入失败则不发送。组件只订阅状态，重复挂载不重新创建请求，组件清理不单独取消共享交换。

```text
STARTING --> ARMED --> CONSUMING --> CONFIRMED
    |                     |            |
    v                     v            v
 UNCERTAIN            UNCERTAIN      CLEARED
    |                     |
    +--> explicit restart +--> new attempt
```

确定拒绝进入 REJECTED，清理可消费资格，保留固定错误及允许的恢复动作。任何从 CONSUMING 进入的新文档实例都不重新发送 exchange；若当前文档仍持有原 Promise 则继续订阅，否则显示未确认。刷新、历史恢复及返回前进都不能把已消费记录重新改成 ARMED。已接管令牌后优先进入 Session 身份重核或其故障页，不以 CONSUMING 提示阻止合法的 me 恢复。

请求截止、离开结果路由、显式退出及凭据替换会有界结束交接；取消不能保证服务端未消费，因此后续仍按不确定恢复。原始 LoginResult 交给 Session 后不在协调器中额外持有 token，只保留安全结果类型及状态。

### D5．结果页隔离业务内容并协调身份重核

`AppRoutes` 对固定结果路径优先渲染结果页，其他路径继续使用现有加载、故障及受保护布局。`SessionProvider` 能识别该交接路由，在首次挂载重核之前与协调器建立交接状态，避免先核对旧令牌再触发组件重挂载或耗尽结果 TTL。

Session 将凭据代次与现有资源 epoch 分开：每次显式登录接管、退出及凭据清理递增凭据代次；普通 me 重核不视为另一身份的凭据替换。交接进入时停止旧业务读取并清除旧受保护状态，保存私有的原凭据快照与代次。结果接管必须验证代次仍匹配；即使同一用户再次取得同值令牌，新的显式登录也废弃旧操作。

结果页交接期间，挂载、focus、visibilitychange 只记录需要重核，不取消或重发一次性交换。此时没有业务布局或旧正文可见。交接最多持续请求截止时间，并在所有分支 finally 释放；释放后按当前凭据执行一次 me 核对。退出和新登录始终立即使旧代次失效；不能以延迟重核为由恢复旧结果。

普通受保护页面继续按原规则在后台恢复时遮蔽并重核，不能把专用结果页的例外扩展到全站。相较于直接复用 epoch 取消 exchange，这个协调避免本地焦点事件造成服务端已消费、客户端却丢弃结果。

### D6．统一登录接管，绑定保留原会话

从 `Session.login` 提取内部的登录结果验证及接管逻辑，本地与 SSO LOGIN 共用。接管的同步部分依次检查原操作资格、清理旧状态、替换并保存现有 `kb.local-session` 凭据；随后释放交接，再进行 me 核对，防止等待自己延后的 refresh。未确认身份前不进入 ready，强制改密仍先于任何业务预取。

如果合法令牌已保存、me 暂不可用，保留当前凭据并显示现有 unavailable 页面；显式重试只读取 me。me 明确 401 则正常清理。不能以先前 SSO 结果中的 user 摘要替代当前授权事实。

BIND 使用原 Session 私有令牌，不调用 `login`、不颁发或写入新令牌。返回 Bound 后释放交接、重核 me，再读取 `/bindings`；只展示确认的当前状态。原令牌在平台跳转期间被替换时由后端原会话验证拒绝，不能自动取得新令牌补交。

### D7．按操作区分身份拒绝与验证拒绝

既有普通业务 401 和绑定状态读取 401 仍立即执行会话清理。只有本人 BIND 发起 POST 的 401 使用专用恢复：清空密码，遮蔽旧保护内容，至多一次读取 me；身份正常时保留原令牌并提示验证未通过，401 时清理，强制改密时转改密，403/依赖故障则 unavailable。该例外不依赖服务器自由文案，也不自动重试密码。

SSO 403 先消费有界安全错误，再根据操作及类别决定是否需要有界身份核对，避免通用 `handle403` 提前取消尚未读取的错误体。NOT_MEMBER 等业务拒绝不自动销毁一个已确认有效的本地账号；PASSWORD_CHANGE_REQUIRED、USER_DISABLED 或缺少明确类别的保护请求拒绝按既有当前身份门禁处理。

| 状态 | 页面恢复 |
| --- | --- |
| LOGIN 的 SSO_BINDING_REQUIRED | 本地登录，必要时改密，账户页提示主动开始新的 BIND |
| INVALID_FLOW、CONFIG_CHANGED、CONNECTION_UNAVAILABLE | 说明授权不可继续，重读目录或显式重新发起 |
| NOT_MEMBER、BINDING_CONFLICT | 固定拒绝提示；冲突交管理员处理，不展示他人身份 |
| RATE_LIMITED、依赖故障 | 稍后显式重新开始，不循环发送 |
| LOGIN 发起或交换不确定，未取得令牌 | 重新发起或本地登录；不能重放旧交换 |
| BIND 交换不确定 | 原会话有效时显式查当前绑定状态，或重新发起；不声称确认旧请求 |

没有使用 `useAdminWrite`，因为 SSO 禁止 requestId，且不具有管理写接口的幂等重放契约。

### D8．保留浏览器单活动 flow 的真实边界

页面显示同一浏览器一次只进行一个企业授权的提示，当前标签页内防止双击与同页混合提交。来自另一个标签页的新发起仍由后端替代旧 flow，前端收到拒绝时提供重新开始，不做跨标签页接管或恢复。

本地 attemptKey 无法与后端 flow 一一核对。特别是同平台、同意图的两个标签页，前端无法仅凭现有响应判断服务端结果属于哪次发起；结果以浏览器当前 flow 为准，BIND 另受原令牌限制。意图和平台检查只能防明显串用，不能承诺独立标签页事务。实现和测试不得宣称已消除这一协议边界；若未来需要独立并发，须单独变更后端交接契约。

### D9．仅执行纯内存单元验证

沿用 Vitest、可控 fetch、内存 storage、模拟导航及 DOM，分别对 SSO API、流程状态机、Session、Provider 和页面组件执行独立单元测试。被测单元之外的依赖使用替身，移除页面与实际 Session 的联合测试要求，不执行集成或端到端测试。重点验证一次性次数、错类型、错误载荷限制、会话清理与绑定密码 401 区别、过期与刷新不重发、旧代次响应不覆盖、密码清除和安全站内导航。

每个功能任务按明确的单元测试文件名单执行，不使用全量自动发现测试入口。实现完成执行 `npm run typecheck`、`npm run build` 和 OpenSpec strict 验证，记录实际执行的单元测试范围。测试输入的 URL 和响应仅作为进程内夹具，不启动应用、HTTP 服务、浏览器、数据库、Redis、MinIO、容器、网关或供应商服务，也不连接这些外部组件。

文档任务只编写使用、配置和回退说明，不增加文档内容断言、文案测试、配置示例执行或真实环境验收任务。真实 HTTPS、HttpOnly/SameSite Cookie、Origin、网关转发及三家扫码不属于本变更的验证范围，纯内存测试也不提供其运行证明。

## Risks / Trade-offs

- 一次性消费后响应丢失 → LOGIN 重新授权，BIND 查询当前事实；保留可能存在未交付且自然到期的服务端会话边界，不宣称 exactly-once。
- 浏览器共享 Cookie 而标签页凭据独立 → 采用同标签页流程、意图及平台校验和明确的单活动授权提示，接受现有服务端边界。
- 会话重核协调影响原有页面恢复 → 仅专用结果路由允许有界协调，保留普通页面撤权、退出和强制改密回归。
- 标签页存储不可用 → 发起或发送交换前安全停止，不临时改用 URL、localStorage 或认证材料副本恢复。
- 平台授权域名或路径升级 → 与后端适配器在同一变更中审查白名单及契约测试，不接受任意返回地址。
- 状态查询发现 bound=true 不证明某次不确定提交成功 → 文案只展示当前绑定事实，不伪造旧请求回执。

## Migration Plan

以下仅描述部署与回退条件，属于说明文档内容，不安排部署执行、外部联调或验收任务。

1. 先部署现有企业 SSO 后端版本，确认三家连接默认关闭及本地管理员入口可用；本变更无需数据库迁移或权限回填。
2. 发布前端并配置同源静态托管，固定结果地址为 `https://<公开主机>/auth/sso/result`；API 路由优先转发至网关，SPA 回退仅作用于页面路径。
3. 结果页响应由静态托管层设置 `Cache-Control: no-store`、`Referrer-Policy: no-referrer`，保留现有 CSP 与认证头保护；关闭相关路径的原始 URI/query、body、Cookie、令牌日志及遥测。
4. 将 `KB_SSO_PUBLIC_ORIGIN`、`KB_SSO_RESULT_PAGE_URI` 和已登记的后端 callback 配置到同一 Origin；开发同源代理保持 Origin 与 Cookie 行为，生产使用 HTTPS。
5. 回退前关闭 SSO 连接并处理有界在途流程，再回退前端静态产物。本地登录继续可用，绑定及审计数据保留，不自动解绑或批量登出。
