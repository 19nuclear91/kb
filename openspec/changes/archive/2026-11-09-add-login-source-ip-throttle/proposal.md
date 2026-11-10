# Proposal

## Why

本地登录节流目前只按用户名计数（`kb:auth:login:fail:<username>`）。同一来源轮换用户名尝试时不受限制，每次请求都会执行一次 bcrypt。主规格要求登录失败"允许按来源与用户名聚合"，但失败审计只记录用户名。2026-10-04 的账号变更把来源维度留作待定问题，原因是缺少可信的客户端地址方案。现在部署形态已明确为"浏览器 → Nginx → kb-gateway → kb-app"，可以补上这一维度。

## What Changes

- 登录来源：kb-app 只采用入口 Nginx 写入的 `X-Real-IP` 作为登录来源。该值必须是单个合法的 IPv4 或 IPv6 字面量，并做规范化；缺失、多值或非法时视为来源未知。不读取 `X-Forwarded-For`、`Forwarded`，也不使用连接地址（连接地址是网关地址）。
- 来源节流：`POST /api/auth/login` 新增按来源计数的失败节流。来源计数与账户计数使用不同的键，阈值单独配置（默认 20 次），窗口沿用现有 `lock-duration`（默认 15 分钟），到期自动解除。来源检查先于账户检查、数据库查询和 bcrypt。凭据失败时两个计数都加一；登录成功只清除账户计数，不清除来源计数。来源未知时只执行现有的账户节流。
- 拒绝响应：被来源节流拒绝时，沿用现有的 401 和"用户名或密码错误"，不写审计，前端不改。
- 失败审计：来源已知时，登录失败审计的摘要增加 `sourceIp`。全局审计查询的返回字段和筛选条件不变；旧的按目标审计接口原样返回摘要，持有 `identity:audit:read` 的人能看到已知用户登录失败记录的来源。
- 部署：Nginx 的 `/api/` 代理必须设置 `proxy_set_header X-Real-IP $remote_addr;`；网关只允许 Nginx 访问，kb-app 只允许网关访问。同步更新部署说明。
- 不在本次范围：改密时的原密码节流；SSO 本人复核和应急资格登记两个入口的来源维度；Spring Cloud Gateway 升级；管理端按来源筛选审计；IPv6 按前缀聚合；登录成功审计记录来源。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `user-authentication`：修改"登录尝试必须被节流"，在账户维度之外增加来源维度，规定检查顺序、清除规则和来源未知时的行为；修改"登录成败必须可审计"，要求来源已知时失败记录保留来源；新增"登录来源必须取自入口代理写入的客户端地址"。

## Impact

- kb-app：`LoginThrottle` 新增来源计数方法；`LocalAuthService.login` 增加来源参数并调整检查顺序；`AuthController.login` 读取 `X-Real-IP`；新增客户端地址解析组件；`LoginAuditService.failure` 的摘要增加 `sourceIp`；`application.yml` 新增 `kb.auth.login.source-max-failures`（`KB_LOGIN_SOURCE_MAX_FAILURES`）。SSO 本人复核和应急资格登记继续调用现有的账户节流方法，行为不变。
- kb-gateway：无生产代码改动，`X-Real-IP` 原样透传；新增一项回归测试，锁定入站请求头净化不剥离该头。
- 数据：无数据库迁移。`after_state` 新增可选键 `sourceIp`；`kb_audit_login_username` 和全局审计查询只读取 `username`，不受影响。旧的按目标审计接口原样返回 `after_state`，返回结构不变，但已知用户的登录失败记录里会多出 `sourceIp`。Redis 新增键前缀 `kb:auth:login:source-fail:`。
- 文档：`docs/user-web-operations.md`（Nginx 配置和端口访问前提）、`docs/admin-query-api.md`（节流拒绝不记审计、新旧审计接口对 `sourceIp` 的返回差异）、README 部署要点。
- 兼容与回退：先修改 Nginx，再发布 kb-app。顺序反过来，会出现客户端自带的 `X-Real-IP` 被原样转发并被采信的窗口；旧 kb-app 会忽略这个头。回退时先回退 kb-app，再视情况回退 Nginx。来源计数键随 TTL 自然过期。
