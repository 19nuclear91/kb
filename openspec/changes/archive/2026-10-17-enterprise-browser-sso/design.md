# Design

## Context

动机与业务范围见 `proposal.md`；行为契约见本变更四份增量规格。

已核对的现状：

- `kb-app/auth/LocalAuthService` 在密码校验和 `IdentityService.resolvePrincipal` 后建立 Sa-Token 会话，返回 `LoginResult`；绑定不能直接调用 `login()`，否则会产生额外会话和本地登录审计。
- `V8__identity_department_model.sql` 已有 `sys_user_identity(provider, subject)` 唯一约束，provider 最长 128、subject 最长 512；现有管理员 API 直接接受原始 provider/subject，只能用于管理操作。
- `IdentityService.bindExternal` 可保持同用户同身份的绑定行，但其审计重放和目标参数面向管理调用，不能直接充当浏览器本人绑定命令入口。
- `V12__local_credentials.sql` 允许空密码哈希，但当前建号接口要求密码并强制改密。本方案沿用已有本地账号，不新增纯 SSO 建号路径。
- 网关及 `SaTokenConfig` 目前仅豁免本地、开发登录。`PasswordChangeRequiredInterceptor` 和 `ConversationIdentityGate` 每请求检查强制改密；新入口必须与这些门禁兼容。
- `AuditSummaryProjector` 按操作域做白名单投影，原外部绑定和登录摘要没有平台枚举规则。`loginUsername` 只匹配原本地登录审计域。
- `kb-common/api/R` 的 body.code 只使用 200/500，旧 `R.fail(int, ...)` 也会折叠为 500；不能把字符串 SSO 错误码或 HTTP 状态写进公共 code 字段。
- 仓库只有后端 Maven 模块，没有业务前端工程。本次交付后端、浏览器调用契约和联调说明，使用方的登录页、账户安全页和固定结果页消费这些接口，不在本变更新建完整前端应用。

## Goals / Non-Goals

**Goals:**

- 三家平台各自实现授权码交换、企业成员证明和安全失败处理，统一成内部身份结果，不把三家接口强行视为同一标准 OIDC 契约。
- 让回调在不携带本平台令牌的顶层导航中完成；在原浏览器的后续 POST 中最终复核并交付结果。
- 复用现有表、权限模型和共享会话，显式处理浏览器切换账号、密码重置、解绑、回调竞争和响应丢失。

**Non-Goals:**

- 不改变资源权限 SQL、部门继承或 AI 服务身份来源；平台角色和部门不写入知识库授权数据。
- 不新增自助解绑、账号注册或自动合并。解绑继续通过现有管理员接口完成。
- 不扩展为多租户 SaaS、统一身份代理或通用 OIDC 客户端；每个平台首期配置一个企业应用连接，可独立关闭。

## Decisions

### D1．连接配置和独立适配器

新增 `kb.auth.sso` 配置与 `auth/sso` 业务包，连接键固定为 `dingtalk`、`wecom`、`feishu`。配置至少包含 enabled、targetEnterpriseId、应用 ID、应用密钥、完整 callbackUri，以及共用的浏览器 publicOrigin 和固定 resultPageUri。企业微信还需 AgentId；需要应用身份查成员的平台配置相应应用凭据。默认全部关闭，启用项逐项校验，不能因一项缺失而放宽企业条件。

`ExternalLoginProvider` 负责构造平台授权请求、解析该平台回调、服务端换码、确认企业归属及有效成员；输出 `VerifiedExternalIdentity(platform, enterpriseId, applicationId, subjectKind, subject)`，只能由服务端产生。编排服务不接受浏览器提交这些字段。授权码字段名由适配器处理，例如钉钉使用 `authCode`，不能统一假定为 `code`。

采用 Spring 提供的 HTTP 客户端，不引入统一身份代理或完整 Spring Security 登录链。供应商地址固定到适配器内的官方 HTTPS 主机；生产不接受请求指定端点，也不跟随跨主机重定向。测试通过注入 HTTP 传输边界提供响应，不需真实供应商服务。

每个调用默认连接超时 2 秒、响应超时 10 秒，整次平台核验上限 30 秒，响应体上限 512 KiB。对换码不做不确定提交后的自动重试；失败后用户发起新事务。应用令牌可按连接和凭据代次在 Redis 中限时缓存，不能串用；用户 access/refresh token 不落库，不跨授权事务保留。成员结果不跨事务缓存。所选官方授权流程支持 PKCE 时采用 S256 并将 verifier 仅保留在本次服务端事务中；不支持时记录该限制，仍必须执行完整浏览器 state 关联，不能假装校验了不存在的 ID token。

替代方案：只接一个通用 OAuth/OIDC SDK 会掩盖企业成员证明及回调字段差异；引入身份代理则增加部署和生命周期管理范围，本期均不采用。

### D2．各平台成员证明与标识选择

| 平台 | 本轮选定路线与证明要求 | 绑定 subject |
| --- | --- | --- |
| 钉钉 | 浏览器授权码换用户访问凭证，取得当前个人标识及组织上下文；使用配置企业的应用身份，把该身份解析为企业成员并核验当前有效状态。只取得个人信息或选择了 corpId 均不足以跳过成员核验。 | 配置企业内的 userId，类型 `corp-user-id` |
| 企业微信 | 采用企业自建应用的浏览器扫码／授权登录，以配置 CorpId、AgentId 和应用凭证获取企业成员身份；非成员结果、个人 OpenId、应用不可见或无法证明有效成员均拒绝。 | 企业成员 UserId，类型 `corp-user-id` |
| 飞书 | 浏览器授权码换 user_access_token 并取用户信息，校验 tenant_key；以配置企业应用身份确认该用户是可读取的有效成员。应用内 open_id 不跨应用使用，不借手机或邮箱匹配内部账号。 | 应用内 open_id，类型 `app-open-id` |

成员接口需要的最小权限、有效／离职／冻结字段和错误语义必须在适配器实施时记录为官方契约证据；不能用仅有个人身份的样例代替。接口缺权限或响应不能证明状态时返回核验不可用的 503，明确非成员或停用返回 403。

本次查阅的官方入口：

- [钉钉浏览器授权码流程](https://open-dingtalk.github.io/developerpedia/docs/develop/permission/token/browser/get_user_app_token_browser/)
- [钉钉根据 unionId 获取企业用户 userId](https://open.dingtalk.com/document/orgapp-server/query-a-user-by-the-union-id)
- [企业微信新版网页登录开发入口](https://developer.work.weixin.qq.com/document/path/98151)
- [腾讯官方企业微信接入配置说明](https://cloud.tencent.cn/document/product/1441/107281)
- [飞书授权码](https://open.feishu.cn/document/common-capabilities/sso/api/obtain-oauth-code)、[user_access_token](https://open.feishu.cn/document/authentication-management/access-token/get-user-access-token)、[用户信息](https://open.feishu.cn/document/server-docs/authentication-management/login-state-management/get)、[成员信息](https://open.feishu.cn/document/server-docs/contact-v3/user/get)、[身份作用域](https://open.feishu.cn/document/platform-overveiw/basic-concepts/user-identity-introduction/introduction)
- [RFC 9700 OAuth 安全实践](https://www.rfc-editor.org/rfc/rfc9700.html)：作为固定回调、浏览器事务关联、凭据隔离及避免 URL 泄露的设计依据。

核验边界：企微开发页在本次工具访问中不可用；部分钉钉和飞书页只取得标题或检索摘要，尚未核验真实响应和应用权限。实施仅补齐可获得的官方字段依据、响应注入单测和代码审阅，不安排三家真实联调或索要企业测试凭据。设计中的接口路线及模拟结果不能被当作真实运行证据。供应商字段说明和部署输入不改变企业限定、身份唯一性及失败拒绝的契约。

### D3．复用外部绑定表并保留历史语义

受管 provider 使用 `sso-v1:<platform>:<scopeDigest>`；scopeDigest 是对有固定字段顺序的规范编码 `(platform, targetEnterpriseId, applicationId, subjectKind)` 计算的完整 SHA-256。企业微信 applicationId 的规范值还包括 AgentId；应用标识组合不能依靠易碰撞的字符串拼接。该 provider 长度小于 128，不含密钥或原始企业 ID。

`subject` 保存平台明确作用域内的原始成员标识，区分大小写，不随意大小写折叠；带不合法空白、空值或超过 512 的值拒绝，不截断。身份作用域摘要不包含密钥和回调域，因此正常密钥轮换或回调域调整不要求重新绑定。另有覆盖认证上下文的 flow fingerprint，包含身份作用域、回调及结果地址、Origin、授权范围和密钥代次的单向摘要，用于使在途旧事务失效；该值只在服务端使用。

新查询只按当前连接计算出的 provider 查找启用绑定，普通 `dingtalk`、`wecom`、`feishu` 或 `oidc` 历史行不会被猜测迁移。企业、应用或主体类型改变会产生新 provider，旧行仍可由现有管理接口查到和解绑，新作用域需重新绑定。暂不增加“每个内部用户每个平台只能一个账号”的约束，保留既有多绑定能力。

在原管理写服务中保留并拒绝以 `sso-v1:` 开头的原始新增请求，避免管理员通过提交 subject 跳过本人验证；限制施加在服务入口，不仅是 Controller。原管理读取与解绑继续沿用 `identity:external:read/write`。交付首次启用前检查旧数据是否占用保留命名空间的操作说明，有歧义时不能自动认领；本变更只人工审阅该步骤，不连接数据库执行检查或处置。部署时的首次检查不能在后续重启时把本方案合法创建的绑定误报为旧数据冲突；正常重启继续读取既有受管绑定。

本设计不需要新增业务表、权限 code 或 Flyway 迁移，沿用 `(provider, subject)` 唯一约束。禁止修改 V1–V19 历史迁移；若实施发现此设计无法保持约束，应先修订规划再增加 DDL，而非随手修改历史表定义。

### D4．浏览器接口与同源部署边界

新增接口契约：

| 方法及路径 | 资格 | 请求／响应 |
| --- | --- | --- |
| `GET /api/auth/sso/providers` | 匿名可读 | 启用连接的 `{connectionId, platform}` 数组，不包含秘密或企业标识 |
| `POST /api/auth/sso/{connectionId}/login` | 原浏览器、可信 Origin | 空 JSON 对象；返回 `{authorizationUrl}` 并设置浏览器事务 Cookie，浏览器顶层导航到该 URL |
| `POST /api/auth/sso/{connectionId}/bindings` | 当前 Sa-Token、主体有效、已改密、本人密码 | `{password}`；验证后返回 `{authorizationUrl}`，创建 BIND 事务，不创建新会话 |
| `GET /api/auth/sso/{connectionId}/callback` | 匿名回调、原浏览器及有效 state | 处理平台 code/error；结果存服务端后 303 到固定 resultPageUri |
| `POST /api/auth/sso/exchange` | 原浏览器、结果 Cookie、可信 Origin；BIND 另验原会话 | 空 JSON 对象；LOGIN 返回 `R<LoginResult>`；BIND 返回 `R<{connectionId, platform, bound:true}>`，不更换登录用户 |
| `GET /api/auth/sso/bindings` | 当前主体有效且已完成改密 | 配置连接的 `{connectionId, platform, connectionEnabled, bound}` 数组 |

全部新增 JSON 请求拒绝未知业务字段、重复字段和不支持的查询参数，不接受客户端 returnUrl、redirectUri、userId、subject、history 或授权声明。授权 URL 由固定平台及连接配置构造，不是开放跳转器。

部署采用浏览器页面与公开网关同源的模式；反向代理可将 `/api` 转到网关，固定结果页由使用方前端提供。publicOrigin、callbackUri 和 resultPageUri 必须形成明确的同源关系，Origin 只按完整 scheme/host/port 精确匹配。开发使用同源代理及显式 loopback 配置；生产必须 HTTPS，禁止把 dev Cookie 或通配 Origin 配置带入生产。

匿名开放上述最小方法／路径集合，未知连接仍返回 404。绑定发起与读取不因 `/api/auth/sso` 前缀而豁免；exchange 的 LOGIN 匿名，BIND 在业务入口强制验证当前令牌。网关和主应用使用一致方法／路径判定，强制改密拦截器为公开握手操作提供必要豁免，但绑定发起、BIND 提交及业务访问继续检查改密标记。修改现有方法时必须补旧端点回归。

错误映射：非法参数、state 或过期凭证为 400；Origin 拒绝为 403；无本地会话或本地密码错误为 401；非成员、内部停用、切换绑定用户及强制改密为 403；未绑定为 409/`SSO_BINDING_REQUIRED`；绑定冲突为 409/`SSO_BINDING_CONFLICT`；节流为 429；平台、状态存储或数据库不可用为 503。新增 SSO 业务错误使用局部异常映射返回 `R<{errorCode, correlationId}>`，保留 body.code=500 和固定安全 message，HTTP 状态照上述映射；成功仍是 body.code=200。网关和既有拦截器的通用 401/403 可继续返回原 `R<Void>`，接入方先识别 HTTP 状态，再读取可选 data.errorCode。既有接口和公共 R 模型不改变。

### D5．有界 Redis 授权事务

浏览器随机值、state 和结果凭证均使用至少 256 位随机熵。生产 Cookie 使用 `Secure; HttpOnly; SameSite=Lax; Path=/` 且无 Domain，可使用 `__Host-` 前缀；Cookie 不存 userId、subject 或平台令牌。浏览器证明与凭证只以摘要作为 Redis 查找键或比较字段，不进入日志。

默认 state TTL 5 分钟、结果 TTL 至多 60 秒且不超过原事务剩余有效期，有限范围配置；不续期、不永久保留。每浏览器同时至多一个当前事务，新发起覆盖当前指针，旧记录依原 TTL 清理。结果 Cookie 名按 flow 隔离，exchange 只选当前 flow 的 Cookie；旧回调响应晚到不会覆盖新 flow 的凭证，清理也只清该 flow。过期 Cookie 数量受短 TTL 和发起频率约束。默认每浏览器每分钟 10 次发起、每连接至多 1000 个活动事务，计数和上限原子维护；活动索引在领取或发起时清理到期记录，不能因未回调而永久占位。限制可按部署容量调整，超限 429。公开发起也需可信 Origin，不能由跨站请求覆盖另一浏览器正在进行的事务。

服务端记录包含 flowId、intent、connectionId、identityScope、configurationFingerprint、browserDigest、创建与到期时间、事务阶段。BIND 额外记录 ownerUserId、发起令牌的摘要及 credentialFingerprint；后者是当前本地密码哈希的单向摘要，不保存密码或可直接用于登录的原始哈希。Redis 数据不允许序列化进 API 或异常。

状态转换：

```text
CREATED --> PROCESSING --> RESULT_READY --> CONSUMED
   |            |               |
   +------------+---------------+--> EXPIRED / SUPERSEDED
```

Lua 原子校验浏览器、当前 flow、连接、阶段和 TTL 后，唯一领取 CREATED 事务为 PROCESSING，再在事务外调用供应商。写入结果时再次比较当前 flow 和阶段，防止被替换的旧请求恢复并覆盖新结果。结果中可有短时内部候选身份，不能有平台令牌。

exchange 先校验 Origin 与浏览器，BIND 再校验当前会话与原令牌摘要；随后原子领取结果，终结其消费资格，处理本地事务或会话签发。失败后不恢复可重复消费的结果，不重新兑换原授权码。Redis 不可用返回 503，不切换内存状态机。进程在领取后崩溃只会造成该 flow 不可继续，用户重新发起，不后台续跑。

### D6．本人绑定的双重证明与提交

登录页扫码发现未绑定时，返回需绑定结果；用户先完成本地登录和强制改密，再在账户安全页明确发起绑定并重新进行平台授权。首扫的 LOGIN 事务不能自动变成 BIND，避免把另一个浏览器或账号的认证结果悄悄绑定进当前用户。

从现有本地凭据逻辑提取无会话副作用的本人密码校验边界，按当前用户取得凭据并复用 BCrypt 和 LoginThrottle。现有登录的失败响应、节流和审计不改变。绑定的原会话可由本地或已绑定 SSO 建立，但本次必须再次验证当前本地密码；“本地验证”不等于只持有旧令牌。

exchange 完成 BIND 时，检查原令牌仍有效且摘要一致、ownerUserId 相同、连接 fingerprint 不变；短数据库事务锁定内部用户，复核启用、有效主部门、must_change_password=false 和 credentialFingerprint。然后按规范 provider/subject 插入或确认现有同用户启用行，成功绑定和 `SSO_BIND` 审计同事务提交。当前角色、部门或密级不从平台写入。

原唯一约束兜底不同用户并发争用；捕获唯一冲突后在新的合法事务边界读取最终所属，再决定同用户已绑定或安全冲突，不能在 PostgreSQL 已中止的事务内继续查询。遇到停用的已有绑定不自动启用，返回冲突，由管理员处置。整个过程不持有数据库锁等待平台 HTTP。

绑定结果响应丢失时，用户查询本人状态；新授权同一身份仍只保留一行。同一 flow 的审计使用服务端生成的 flowId 防重复，不能复用客户端 requestId 或命中另一用户的历史管理回执。本人只读接口不返回 subject、scopeDigest、身份行 ID或其他用户事实。

替代方案：保留首次未绑定的扫码结果再让用户输入任意本地账号，减少一次授权但增加 LOGIN/BIND 意图转换与账号混淆风险，本期采用显式重新绑定流程。

### D7．会话签发与强制改密

LOGIN exchange 重新按当前规范外部身份查启用绑定，调用当前主体解析检查内部用户和主部门，再读改密事实并生成现有 `LoginResult`。此处不读取平台角色作授权，不从 state 复用本地角色快照；来源核验材料只在短结果有效期内使用。

本次外部登录签发独立的新令牌，不复用当前用户已有共享令牌；禁止在成功审计之前自动把该令牌写进 Cookie 或响应头。建立新令牌后写入 `SSO_LOGIN` 成功事件，均成功再返回 JSON；审计失败仅撤销本次尚未交付的令牌，不调用用户级 logout 清除其他合法会话。会话存储和数据库不是分布式事务，响应丢失、进程崩溃可能留下尚未交付的有限期会话，不能宣称 exactly-once 发放；结果防重放和令牌自然到期控制该边界。

已绑定用户改密标记为 true 时，外部登录如原本地登录一样返回受限会话；不能清除标记，业务、绑定和 AI 历史门禁仍按数据库事实拒绝。现有 logout 只退出本平台，不解除绑定、不调用供应商全局登出。

账号停用与令牌签发之间不存在跨系统原子提交。完成前重新校验，停用后的后续资源请求依然实时受内部主体门禁限制，不以刚签发令牌绕过。解绑只影响后续新外部登录，本轮不按外部来源撤销已经建立的全部会话。

### D8．可检索的审计和安全摘要

在 `AuditScope` 增加 `SSO_LOGIN`、`SSO_BIND`，目标类型 `user`，operation 分别为 login、bind。成功 actor/target 是内部用户；无法解析内部用户的失败保持 null，绑定失败可保留已验证的本人 actor。state、浏览器值、结果凭证及平台身份不作为审计请求键，使用服务端 flowId；在本地密码验证前失败的尝试用新的服务端事件 ID。

成功绑定与成功审计同 PostgreSQL 事务。登录成功表示本平台会话已建立，回调拿到个人信息只算中间阶段。只有终结 flow 才写一次终结事件；重放不重复。若进程消失或审计依赖本身不可用，不能保证所有尝试都有事件，文档必须保留这一证据边界，不能伪造审计补全。

摘要目录：platform 仅为 dingtalk/wecom/feishu；resultCode 仅为 `OK`、`ALREADY_BOUND`、`BINDING_REQUIRED`、`NOT_MEMBER`、`USER_DISABLED`、`PASSWORD_CHANGE_REQUIRED`、`CREDENTIAL_REJECTED`、`BINDING_CONFLICT`、`INVALID_FLOW`、`UPSTREAM_UNAVAILABLE`、`STORAGE_UNAVAILABLE`、`CONFIG_CHANGED`；绑定另有 bound 布尔值。投影为既有 changes 数组，非法枚举令 summaryAvailable=false。其余字段均不输出，旧事件投影不扩大。

保持现有 `audit:read`、时间窗口和游标。`loginUsername` 继续只匹配原 AUTH_LOGIN_SUCCESS/FAILURE，本次不补用户名。无须修改角色权限目录或添加权限迁移。

### D9．浏览器凭据与日志

回调拿到 state/code 后由服务端处理，成功或可关联失败均设置短时结果 Cookie，303 到固定 resultPageUri，不复制任何原查询参数。后续 POST exchange 只依赖 HttpOnly 结果 Cookie和浏览器证明，LOGIN 无须把本平台令牌带入顶层导航；BIND 的当前 satoken 仅放在 POST 请求头中。

响应统一 `Cache-Control: no-store` 与 `Referrer-Policy: no-referrer`，结果页不得加载会采集认证信息的第三方资源。所有消费／发起 POST 做完整 Origin 校验与 JSON 内容类型检查；Cookie 属性与 CORS 配置不能代替该检查。缺失或错误浏览器证明的回调直接安全失败，不更换当前事务 Cookie 或清除别的 flow 成功结果。

原始 callback URL 必然含供应商的 code/state，应用和代理日志只能记录路径模板与固定失败类别，禁用相应原始 URI/query/body/header 记录。调试开关也不得记录令牌、密码或原始平台错误。相关代理和结果页要求以对接／运维说明交付，本变更不启动代理、浏览器或结果页进行验收。

### D10．本变更的离线验证边界

- 仅以 Mock、Stub、内存 HTTP 响应注入、standalone MockMvc、直接调用过滤器／拦截器及可控时间进行单元测试，覆盖配置、适配器解析、身份作用域、Cookie/Origin、防重放、本人密码证明、当前账号门禁、审计投影和既有接口兼容。
- 数据库、会话、缓存与供应商客户端均使用替身；不启动应用、监听端口或连接 PostgreSQL、Redis、Docker、代理、供应商及嵌入式替代服务，不安排跨服务集成测试、多实例运行、人工扫码、浏览器或部署验收。
- Lua、SQL 和事务边界人工审阅，模拟唯一冲突、时间到期和依赖异常只证明编排分支，不证明真实 Redis 原子性、数据库锁互斥、回滚或故障恢复已验证。
- Maven 测试必须使用实际存在的离线单测类名白名单，无通配符，不用默认全量发现，排除已有集成测试、PostgreSQL 烟测和会启动服务的测试。离线构建只打包、不运行产物；OpenSpec 命令仅校验规划结构。
- 说明文档继续交付并人工审阅，不为 README、Markdown、注释、文案或示例增加测试，也不执行示例。离线记录明确执行范围，不把代码交付和单测通过标为真实登录、成员门禁或生产可用性已验证。
- 外部组件、企业凭据、测试成员及回调部署均不构成本变更实施、核验或归档的完成条件；任务中不保留等待真实环境的未完成项。

## Risks / Trade-offs

- [平台字段或权限配置与示例不一致] → 以可获得的官方流程说明和字段依据编写适配器，运行时无法证明成员则拒绝；本轮仅有模拟响应单测，不宣称真实接口兼容性已验证。
- [企业 API 暂时不可用导致外部登录失败] → 有界调用与明确 503，本地密码登录作为既有入口独立保留。
- [人员离职而知识库账号仍启用] → 仅拦截新扫码登录；已有会话及本地登录须管理员在本平台停用账号。这是用户确认的生命周期边界，需写入运维步骤。
- [只有后台接口而没有业务前端] → 交付完整接口与结果页契约，不执行浏览器接入验收或宣称已有完整页面。
- [跨 PostgreSQL 与 Redis 的进程崩溃窗口] → 单次结果、防重放、绑定事务审计、独立令牌和有限 TTL；不承诺无法实现的跨系统 exactly-once。
- [密码重置、令牌更换或连接改变时旧证明仍在途] → 消费时复核 credentialFingerprint、原令牌摘要和配置 fingerprint，失败须重新发起。
- [保留原管理接口造成绑定绕过] → 保留命名空间禁止原始新增；普通 provider 不变，管理员仍可按原权限撤销受管绑定。首次升级检查旧数据占用，正常重启不重新认领或清除合法新绑定。

## Migration Plan

以下是交付的部署／回退行为说明，不是本变更的执行或验收任务；本轮不运行服务、操作真实数据或执行生产部署。

1. 构建产物包含三家适配器，连接默认禁用；无新增 DDL，无旧账号、绑定、角色或权限自动回填，保留管理员本地登录入口。
2. 配置说明列出同源 publicOrigin、固定 callback/result 地址、企业及应用凭据、最小权限、企业可见范围和旧命名空间检查步骤。配置秘密由后续部署环境注入，不提交仓库；本轮不要求提供这些值或执行配置。
3. 本变更的完成依据是明确白名单的离线单测、人工代码／SQL 审阅、跳过测试的打包及 OpenSpec 严格校验，验证记录明确缺少真实 SQL、Redis、供应商、浏览器和部署证据，不将这些缺少的证据改列为待完成任务。
4. 文档说明后续使用方可逐家启用连接、完成本地改密后绑定，账号离职由管理员在本平台停用；本轮不登录平台、人工扫码或操作测试／生产部署。
5. 回退方案描述先关闭 SSO 连接、撤销在途事务及停止新握手，再回退 app/gateway；保留绑定和审计。旧二进制缺少新保护，回退期间须关闭外部登录和受管 provider 原始管理新增入口，不混部承接 SSO 流量。该方案仅作为说明交付，不执行回退演练。
6. 企业或应用切换采用新作用域重新绑定；密钥轮换使相关缓存和在途事务失效，不迁移用户。这些行为通过离线分支测试和代码审阅检查，不运行真实环境。

## Open Questions

当前代码实施和离线验收没有需要用户提供外部环境的前置。以下仅为后续实际部署的配置输入，不改变本轮规格、方案或任务拆分：

- 企业／应用标识、凭据、最小权限及企业可见范围；秘密和个人数据不填入规划文件，不要求本轮提供企业测试账号。
- 同源 publicOrigin、供应商接受的 callbackUri、接入方结果页及代理环境；本轮只交付配置和接口说明，不搭建环境。
- 官方接口的字段、状态与限流说明尽量在适配器契约记录中保留来源；无法取得实测材料时如实标明未实测，不能将社区示例或 Mock 响应宣称为真实结果。
