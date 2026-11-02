# 企业浏览器 SSO 配置

三家连接默认关闭，不影响现有本地密码登录。仅在本平台浏览器扫码或授权，不提供 IM 工作台免登、通讯录同步或自动建号。

共用配置：`kb.auth.sso.public-origin`（`KB_SSO_PUBLIC_ORIGIN`）、`result-page-uri`（`KB_SSO_RESULT_PAGE_URI`）。页面与网关采用同源部署，完整 Origin 精确匹配；结果页不能位于 `/api/`。每家 callback 固定为同源 `/api/auth/sso/{connectionId}/callback`，均不能包含 query、fragment 或 userinfo。生产必须 HTTPS。仅显式 `dev` profile 允许 HTTP loopback，同源代理保持浏览器调用一致。

`kb-web` 的固定结果页面路径为 `/auth/sso/result`，因此 `KB_SSO_RESULT_PAGE_URI` 配置为公开 Origin 加该路径。静态托管需让直接访问该路径返回前端页面，并设置 `Cache-Control: no-store`、`Referrer-Policy: no-referrer`；API 请求优先转发到网关，保留全部独立 Set-Cookie、原 Cookie 与 satoken，不将 API 错误回退成 HTML。开发代理保持浏览器 Origin 与配置一致，不能用跨源 Cookie 传输代替同源部署。详情见 [用户端运维](user-web-operations.md) 和 [SSO 前端](enterprise-sso-web.md)。

连接键固定 `dingtalk`、`wecom`、`feishu`。各有 `enabled`、`target-enterprise-id`、`client-id`、`client-secret`、`callback-uri`，企微另有正整数 `agent-id`。环境变量见 application.yml：钉钉 `KB_SSO_DINGTALK_*`、企微 `KB_SSO_WECOM_*`、飞书 `KB_SSO_FEISHU_*`。启用但缺字段或不安全时启动校验失败；秘密从部署环境或秘密管理器注入，禁止提交仓库或输出诊断值。

身份 provider 为 `sso-v1:<platform>:<SHA-256>`，摘要按长度前缀编码企业、应用、类型等字段；企微还隔离 AgentId。密钥轮换不改变绑定，企业或应用改变须重新绑定。认证上下文指纹包含密钥代次、Origin、回调和结果页；轮换会使旧事务不能继续。

HTTP 固定官方 HTTPS 主机，不跟随重定向，连接 2 秒、单次响应 10 秒、整轮 30 秒，响应最多 512 KiB，无换码重试。共享应用令牌按作用域和密钥代次存 Redis，最多 7140 秒且预留 60 秒；用户令牌仅本次核验使用，不持久保存 refresh token。Redis 故障拒绝认证，不以本地内存放行。

整轮30秒由外层截止同时覆盖应用令牌缓存读取和成员核验，平台调用和核验分别使用最多8个工作线程、32个排队项，超时取消、容量不足安全拒绝。新 SSO 会话使用独立256位随机令牌，沿用现有正数 `sa-token.timeout`；若原配置为无限期，则本入口回退到有限30天。签发阶段不写响应 Cookie/header，审计成功后才交付 JSON；中途失败尽力只撤销本次候选令牌，不清除用户其他会话。Redis 与数据库之间没有跨系统原子提交，进程崩溃或响应丢失可能留下未交付会话，不能宣称 exactly-once。

事务默认 state 300秒（允许30–600）、结果60秒（配置1–120，运行时仍硬上限60秒且不超过原事务剩余期），每浏览器60秒窗口最多10次发起（1–100）、每连接1000个活动事务（1–10000）。分别对应 `state-ttl-seconds`、`result-ttl-seconds`、`starts-per-minute`、`max-active-flows`。Lua 在创建时清理到期活动索引、原子计数并检查容量，领取/消费不续期；未回调记录也按原到期时间释放占位。超限429，故障503。Redis 键统一 `kb:{sso}:` hash tag，确保脚本相关键落同一 slot；部署容量须计入这一共享分片。

同浏览器多标签页共用一个当前 flow，新发起替代旧 flow；旧记录在有限 TTL 内清理，可能暂占活动容量。结果 Cookie 按 flow 区分，旧回调响应晚到不会覆盖新结果。已领取、消费、超时或进程中断的授权不恢复，重新开始新事务；不循环调用旧 code。浏览器清除 Cookie 后原事务不能继续。

## 首次启用与历史绑定

以下为交付说明，本变更未连接数据库执行。首次升级前由数据管理人员只读查看 `sys_user_identity` 中 `lower(trim(provider)) LIKE 'sso-v1:%'` 的行及既有数据来源；只看必要管理元数据，避免导出 subject。这个前缀在本版之前不应作为普通 provider 使用，发现历史占用须人工确认来源，通过已有授权管理方式清理歧义后让本人重新绑定；不能自动认领、猜测迁移或按邮箱／手机合并。

首次检查与版本升级记录关联，不在每次正常重启重复判冲突。本版本人流程创建的受管绑定正常读取和继续使用。管理员原始新增服务禁止保留命名空间（含大小写与外围空白归一化），普通历史 dingtalk/wecom/feishu/oidc 行语义不变，不会自动当成新作用域。管理员读取和解绑仍要求 `identity:external:read/write`，不开放自助解绑。

## 启用、回退与离职

先配置公开同源地址、固定结果页、企业应用凭据、官方回调登记、最小权限及可见成员范围，保留本地管理员登录并关闭生产 dev-auth。三家权限依据及未实测边界见 [供应商契约](enterprise-sso-provider-contracts.md)；使用方页面契约见 [API](enterprise-sso-api.md)。可逐家启用；应用 Secret 必须属于声明的企业与应用／AgentId，不允许把不同应用的 Secret 混配。

回退时先关闭全部 SSO 连接，停止新握手并等待有界事务过期／使原配置指纹失效，再回退 app/gateway；绑定和审计保留。本版与旧版不能混部承接 SSO 流量，旧版原始管理新增缺少保留前缀保护，回退期间需关闭该管理入口或加访问限制。无新增 DDL，无旧行回填，不执行删除身份或批量登出。

离开 IM 企业会阻止下一次扫码登录，但不自动撤销已有会话，也不关闭本地密码登录。管理员必须在本平台及时停用内部账号；停用后的新登录与后续受保护请求仍受现有用户／部门门禁约束。未实现目录或离职事件同步。

生产代理与应用日志禁止记录回调 query、认证请求体、Cookie、Authorization/satoken 或平台响应；禁用相关路径的 debug 请求明细、访问日志原始 URI 和 HTTP wiretap。仅保留路径模板、HTTP 状态及固定类别。结果页设置 no-store/no-referrer，避免第三方脚本和遥测收集认证信息。

追踪属性与指标标签遵循相同限制：SSO 回调仅使用路由模板，授权码、state、浏览器证明、结果凭证、外部 subject、令牌和平台原始响应不得进入遥测。客户端目标只使用固定类别，禁止完整 URL、用户或请求 ID 标签；exemplar 仅关联 trace。对 SSO 供应商的 HTTP 调用使用隔离客户端，不发送内部追踪头和 baggage，不采用平台响应中的追踪上下文。网关生成的新 trace 不向浏览器响应头或 CORS 暴露清单回写。
