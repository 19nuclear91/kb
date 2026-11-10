# Design

## Context

动机见 proposal.md 的 Why。与方案有关的现状：

- `LoginThrottle` 基于 Redis：每次失败执行 INCR，再执行 EXPIRE 刷新 TTL；发现计数没有 TTL 时补设，防止锁死；Redis 出错时拒绝登录（fail-closed）。`LocalAuthService.login`、`SsoLocalProof` 和 `EmergencyManagementService` 共用它，都按用户名计数。
- `LocalAuthService.login` 的检查顺序有安全含义，依次是：节流 → 查凭据 → bcrypt（未知用户用假哈希）→ 身份门禁 → 签发会话 → 清计数与审计。被节流拒绝的请求不写审计，响应与凭据失败相同。
- 登录失败审计写入 `sys_identity_audit`，`after_state` 为 `{"username": ...}`。只有 `kb_audit_login_username()` 和 `AuditSummaryProjector` 读取其中的 `username`。全局审计查询接口 `/api/admin/audits` 不原样返回 `after_state`；旧的按目标审计接口 `GET /api/admin/identity/audits`（要求 `identity:audit:read`）会原样返回。
- 网关是 Spring Cloud Gateway 4.1.5，没有任何 forwarded 相关配置，默认启用 `XForwardedHeadersFilter`。这个版本在 CVE-2025-41235 的影响范围内：它会保留客户端自带的 `X-Forwarded-For` 并追加地址。修复版改成默认关闭这套处理，需要配置 trusted-proxies 才能开启。`InboundHeaderSanitizeFilter` 只剥离身份类请求头。kb-app 没有读取任何转发头。
- 部署拓扑是：浏览器 → Nginx（托管静态资源并代理 `/api/`）→ kb-gateway → kb-app。

## Goals / Non-Goals

**Goals:**

- 来源节流只依赖一个可信头，结果不随 SCG 版本升级而变化。
- 现有 SSO 本人复核和应急登记入口的行为、签名和测试都不变。
- 请求不带 `X-Real-IP` 时，系统行为与当前完全相同。Nginx 不会自动覆盖客户端自带的 `X-Real-IP`，所以新 kb-app 上线前必须先完成 Nginx 配置（见 Risks 第一条）。

**Non-Goals:**

- 不新增 Servlet 过滤器、拦截器或全局的"客户端地址"上下文，只在登录控制器读取来源。
- 不在网关做请求速率限制，不新增指标或告警。
- 不把 INCR 和 EXPIRE 改成原子脚本，沿用现有的补 TTL 方式。

## Decisions

### D1 只信任 `X-Real-IP`

Nginx 用 `proxy_set_header X-Real-IP $remote_addr;` 覆盖这个头。网关的 `XForwardedHeadersFilter` 只处理 `X-Forwarded-*`，`InboundHeaderSanitizeFilter` 的剥离清单里也没有 `X-Real-IP`，所以它会原样到达 kb-app。

备选方案：

- 解析 `X-Forwarded-For` 最右侧的可信跳：这个头的内容随 SCG 版本变化，4.1.5 还会保留伪造的值。等到修复版默认关闭这套处理，app 收到的内容又会变。
- 由网关计算地址并签名：需要新增密钥和签名协议。在单入口拓扑下，它比网络访问限制多提供的保护很少，可以等以后出现多入口时再做。
- 使用 `getRemoteAddr()`：拿到的是网关地址，全体用户会共用一个计数。

可信的前提是 Nginx 是唯一入口：网关端口只允许 Nginx 访问，app 端口只允许网关访问。这条前提写进运维文档（任务 4.1），规格只约束系统行为。

### D2 只接受严格的 IP 字面量，不触发域名解析

新增 kb-app 包内的工具类 `LoginSourceAddress`，输入是 `X-Real-IP` 的全部头值：

1. 头缺失、出现多次、去掉首尾空白后为空、长度超过 45，都返回未知。
2. 字符集校验：IPv4 必须完整匹配四段 0–255 的十进制，不允许前导零；IPv6 只允许十六进制字符、`:` 和 `.`，且必须包含 `:`。含 `%`（scope id）、`[`、`]`、`,`、空格或其他字符时，都返回未知。
3. 只有通过校验的字符串才交给 `InetAddress.getByName`。这时 JDK 走字面量解析，不会查询 DNS。仍然解析失败的返回未知。
4. 规范化：统一取 `getHostAddress()`。IPv6 输出完整的 8 段、小写、各段不带前导零、不压缩，同一地址的不同写法因此得到同一个字符串。IPv4 映射的 IPv6 地址（`::ffff:a.b.c.d`）会被 JDK 转成 `Inet4Address`，也就落到 IPv4 形式。不再额外实现 RFC 5952 压缩，审计里的地址可读性稍差，但规则更简单。

第 2 步是必须的。Java 21 没有 `InetAddress.ofLiteral`，而 `getByName` 收到主机名时会做 DNS 查询，收到 `1.2` 这类旧式写法时会按 IPv4 宽松解析。备选方案是引入 Guava 的 `InetAddresses`，但 kb-app 目前没有这个依赖，为一处解析不值得引入。

### D3 来源计数

- 键为 `kb:auth:login:source-fail:<规范化地址>`。现有账户键是 `kb:auth:login:fail:<username>`，两者前缀从第三段起就不同，任何用户名都写不进来源键的命名空间。
- 阈值使用新配置 `kb.auth.login.source-max-failures`（`KB_LOGIN_SOURCE_MAX_FAILURES`，默认 20）。窗口沿用 `kb.auth.login.lock-duration`，不再新增窗口参数。
- 新阈值小于 1 时，`LoginThrottle` 在构造时抛出异常，阻止启动。现有账户阈值的校验不变，避免已有部署因此启动失败。
- `LoginThrottle` 新增 `isSourceThrottled(source)` 和 `recordSourceFailure(source)`，复用账户键已有的 INCR、EXPIRE 和补 TTL 逻辑。来源计数不提供清除方法。现有三个账户方法的签名和行为不变。

### D4 登录检查顺序

`LocalAuthService.login(username, password, source)` 中，`source` 为 null 表示来源未知：

```
source 已知且 isSourceThrottled  --> 401 统一文案，不写审计
isThrottled(username)            --> 401 统一文案，不写审计
查凭据 + bcrypt
  凭据失败  --> recordFailure(username)；source 已知时再 recordSourceFailure(source)；写失败审计
  身份门禁拒绝 --> 写失败审计，两个计数都不变（与现状一致）
  成功      --> clear(username)，来源计数不动；写成功审计
```

来源检查放在第一步，是为了让轮换用户名的攻击在查库和 bcrypt 之前就被挡住。直接把双参数方法改成三参数，不保留双参数重载，这样就不会有调用方遗漏来源。目前只有 `AuthController` 调用它。

### D5 控制器读取来源

`AuthController.login` 注入 `HttpServletRequest`，调用 `request.getHeaders("X-Real-IP")`，交给 `LoginSourceAddress` 解析后再传给服务。之所以不用 `@RequestHeader String`，是因为它会把多个同名头合并成一个逗号分隔的字符串，无法区分"多次出现"和"单个值里含逗号"。虽然两种情况都按未知处理，但保留原始的多个值，测试可以写得更直接。

### D6 失败审计

`LoginAuditService.failure` 新增 `sourceIp` 参数，可以为 null。来源已知时，摘要为 `{"username":"...","sourceIp":"..."}`；未知时保持 `{"username":"..."}`，不写占位值。地址经 D2 规范化后只含十六进制字符、`.` 和 `:`，但仍走现有的 `summary` 转义，不另写拼接逻辑。成功审计不变。

全局审计查询接口和审计页面都不展示 `sourceIp`。旧的按目标审计接口原样返回 `after_state`，持有 `identity:audit:read` 的人按 `targetType=auth` 和用户 ID 查询时，能看到已知用户登录失败记录里的 `sourceIp`。这符合"追溯登录失败来源"的审计用途，所以本次不改旧接口的输出，只在文档中写明（任务 6.1）。需要按来源聚合时，运维直接对审计表执行只读 SQL，示例写在运维文档里（任务 4.1）。

## Risks / Trade-offs

- [Nginx 漏配 `proxy_set_header`] Nginx 会把客户端自带的 `X-Real-IP` 原样转发。攻击者可以换着伪造地址绕过来源节流，也可以冒用他人地址，让对方被节流。→ 发布顺序先改 Nginx，再上线 app。运维文档要求上线后用伪造头实测，并核对审计里的 `sourceIp`。文档还要指出 Nginx 的继承陷阱：location 块里只要写了任何 `proxy_set_header`，就不会再继承 server 层的同类配置，SSE 等单独定义了请求头的 location 必须重复写这一行。
- [网关或 app 端口可绕过 Nginx 直接访问] 效果与上一条相同。→ 部署前提写入运维文档，由网络策略保证。本次不做代码层的防护。
- [多人共用出口 IP] 同一 NAT 或 VPN 出口下，一个人反复输错可能让同出口的其他人暂时无法登录。→ 默认阈值 20，远高于账户阈值 5，可以调整，到期自动解除。
- [将来在 Nginx 前加负载均衡] 那时 `$remote_addr` 会变成负载均衡的地址，全体用户共用一个来源计数。→ 运维文档写明：加入前置代理时，必须同时配置 Nginx 的 realip 模块，否则会集体误伤。
- [分布式尝试和 IPv6 轮换] 多 IP 喷洒、同一 /64 内轮换地址，都能绕过来源维度。→ 账户维度继续兜底。本次不做按前缀聚合。
- [Redis 键数量] 每个出现过失败的来源产生一个键，键随 TTL 过期，数量受窗口内的来源数约束。
- [IP 属于个人信息] 失败审计开始保存来源 IP。→ 和现有审计用同一张表、同一套保留策略；保留期限由运维另行确定（见 Open Questions）。

## Migration Plan

1. 修改 Nginx：在 `/api/` 及其所有子 location 中设置 `X-Real-IP`，并确认网关端口只允许 Nginx 访问。这一步可以在 kb-app 升级之前完成，旧 kb-app 会忽略这个头。
2. 发布 kb-app。无数据库迁移，网关不需要重新发布。
3. 验证：带伪造的 `X-Real-IP` 发起一次失败登录，核对审计 `after_state.sourceIp` 是真实客户端地址，而不是伪造值或网关地址。
4. 回退：先回退 kb-app，必要时再回退 Nginx。顺序不能反过来：如果先撤掉 Nginx 的头而新 app 还在运行，客户端伪造的头就会被采信。旧 app 忽略审计里新增的键；来源计数键随 TTL 过期，不需要清理。

## Open Questions

- 来源阈值的生产取值取决于公司出口 NAT 的规模，属于部署配置，不影响规格和任务。
- 登录审计里 IP 的保留期限由运维和合规另行确定。本次不新增清理机制。
