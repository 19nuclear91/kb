# 认证代次与内部身份检查

## 登录与撤销

本地密码、SSO 和开发登录统一签发随机的独立 token，禁用共享会话，并在 token 专属服务端会话中记录用户 ID、正数认证代次和 LOCAL / SSO / DEV 认证方式。DEV 或 SSO 不能作为应急管理员的本地密码登录证明。签发前后均查询当前用户与部门；中途发生停用、离职或复职时拒绝交付候选 token。失败只注销本次随机候选，不能按用户全量 logout。

app 对受保护请求直接核对 token 的当前代次，gateway 与 AI 每次请求向 app 查询同一事实。任何请求级通过结果都不能跨请求缓存；身份检查不授予角色、空间、文档或引用权限。强制改密只放行原有自助改密必需路径，公开登录与 SSO 路由按方法和路径精确匹配。

Office 继续保存原登录 token。每次激活、心跳、文件读取和消息授权重新检查该 token 的代次及固定文档版本权限；复职后同一用户的新 token 不能替代原预览会话。内部历史与 AI 观测入口同样验证原 token，收尾所需的执行凭据使用独立受限协议。

## 内部认证协议

`POST /internal/auth/check` 是固定内部操作，正文为空，不接受 query。仅允许 `kb-gateway` 和 `kb-ai`；不能由公开网关路由转发。请求包含 `satoken` 和以下签名头：`X-Auth-Caller`、`X-Auth-Timestamp`、`X-Auth-Nonce`、`X-Auth-Signature`。

HMAC-SHA256 的 UTF-8 原文为以下字段以实际换行符 `\n` 连接：`kb-auth-v1`、caller、`POST`、`/internal/auth/check`、`CHECK`、UTC 秒时间戳、nonce、目标 token 的小写十六进制 SHA-256。签名输出小写十六进制。时间窗为前后 30 秒；nonce 使用随机 UUID，验证签名后在独立 Redis 命名空间原子占用 90 秒。替换目标 token、caller、方法或路径都会导致验证失败。

成功响应是最小 JSON 对象 `{ "userId": 7, "authEpoch": 3, "mustChangePassword": false, "checkedAt": "2026-11-06T00:00:00Z" }`，带 `Cache-Control: no-store`，不返回部门、角色、外部员工身份或资源权限。checkedAt 来自主库当前身份查询的 statement_timestamp，不是 app 节点时钟。AI 要求这一时间锚点存在；不接受缺失字段的旧节点响应。调用方单次最多等待 2 秒，无自动重试；响应缓冲最多 4096 字节，不跟随重定向，不携带入站用户声明头或追踪头。

| HTTP 状态 | 含义 |
| --- | --- |
| 401 | token 无效、缺少代次或代次已撤销；须重新登录 |
| 403 | 当前代次的身份/部门禁用、未满足强制改密要求，或内部服务签名无效 |
| 503 | 数据库、Redis、防重放存储或内部检查不可达，返回损坏，配置缺失或超时；身份未知，拒绝访问 |

三个服务共同配置独立的 `KB_AUTH_INTERNAL_SECRET`，至少 32 个 UTF-8 字节；gateway 和 AI 的 `KB_APP_BASE_URL` 必须为固定内部源站。密钥不复用会话、Office 或钉钉秘密，不进入日志。缺失配置使内部检查失败关闭，关闭 `KB_CONVERSATION_ENABLED` 或钉钉订阅不会关闭认证检查。

## 有界清理与恢复

`POST /internal/auth/output-stopped` 是仅允许 kb-ai 的独立观测操作，不携带用户 token，不授予继续生成或账号变更资格。最多 2048 字节的 JSON 绑定 executionId（每次许可独立随机 UUID）、userId、authEpoch、checkedAt、afterCheckMinNanos、afterCheckMaxNanos 和固定 reason。未知字段、畸形/重复字段及 query 均拒绝。同一 executionId 和同一正文重发返回 204；改换任何绑定内容返回 409。

停止签名沿用 X-Auth-* 头和认证密钥，HMAC 原文为实际换行连接的 `kb-auth-output-stop-v1`、`kb-ai`、`POST`、`/internal/auth/output-stopped`、UTC 秒时间戳、nonce、原正文 SHA-256。它与 CHECK 操作不能互换，使用独立 nonce 命名空间和相同 30/90 秒窗口。先验签再解析/持久化，无用户状态查询和平台调用；响应 no-store。

AI 先取消输出和上游订阅，再取单调停止时刻。相对于最近成功检查的响应/请求时刻得到 min/max 时差，网络时间保留为不确定区间；不发送 AI 墙上时钟。后台限 2 个工作线程、64 个排队报告，每次请求 2 秒、503 最多重试两次，每次使用新签名且保持正文与 executionId。排队满、缺失锚点和报告失败只增加固定失败计数，不能延迟停止或恢复输出。进程崩溃前未送出的报告可能丢失，此时不能宣称已获得停止证据。

撤销事务保存 `(user_id, revoked_through_epoch)` 清理意图。Redis 令牌索引按整数代次的补齐文本排序，避免浮点数截断 BIGINT；令牌只保存在可信 Redis，不写入数据库任务或审计。后台每轮最多领取 4 项、每项最多清理 100 个 token，以 30 秒租约支持崩溃恢复；失败以有上限的退避重试。每次注销前再次比较 token 元数据，迟到任务不得注销较新代次或其他用户的 token。

清理不是访问撤销的依据：Redis 清理故障时，旧 token 仍会被数据库代次门禁拒绝。候选 token 清理失败也不能升级为用户全量注销；其有限 TTL 和请求代次校验继续约束访问。索引、调度和持续限制不依赖钉钉接收开关。

## 协调升级

先排空旧 app / gateway / AI 节点及在途生成，部署数据库迁移和认识新权限、代次与内部协议的版本，并在三个服务配置独立密钥及内部地址。没有代次的全部旧会话统一重登，禁止通过读取补写代次“修复”旧 token。钉钉订阅保持默认关闭，完成独立应急管理员准备后再纳管员工。

不能将旧二进制直接回退上线：旧节点不会执行代次门禁，旧 app 还可能拒绝新增权限目录。停止钉钉接收只停止新事件进入，不解除既有身份限制、认证检查、会话清理或 AI 结算。本次交付仅通过进程内替身、编译和静态代码核对验证，没有执行数据库迁移或真实平台联调，也不据此宣称实际跨服务 30 秒时限已被测量证明。
