# Design

## Context

动机见 proposal.md 的 Why。以下是影响本设计的既有事实（均已在代码中核对）：

- 会话由 Sa-Token 提供，经 Redis db1 在三服务间共享；网关 `SaReactorFilter`、kb-app `SaInterceptor`、kb-ai `SaReactorFilter`
  三处各自执行 checkLogin，未认证目前统一返回 500。
- `sys_user` 没有任何凭据列；全仓无 spring-security 依赖。`sys_role` 与 `sys_user_role` 结构齐备（主键 `(user_id, role_id)`
  ），但角色行只存在于 V3 种子；`sys_security_level` 字典在 V1，生产安全。
- `resolvePrincipal` 要求启用用户加启用主部门，否则拒绝，因此引导管理员必须同时拥有启用部门。
- 审计表 `sys_identity_audit` 为 append-only，`request_id` 非空且 `UNIQUE (operation_scope, request_id)`
  ；没有客户端请求标识的登录事件需要服务端生成标识。
- `R` 的业务码只有 200 与 500 两值，HTTP 状态码是权威语义；`IdentityException` 自带 httpStatus。
- `IdentityManagementAuthorizer.ROLE_GLOBAL_MANAGER`（值为 ROLE_ADMIN）是唯一角色常量。
- kb-app 的 `SaInterceptor` 目前排除 `/api/auth/dev-login`；logout 位于 `@ConditionalOnProperty(kb.dev-auth.enabled)`
  的控制器内，生产没有登出入口。
- 网关路由 `/api/auth/**` 已指向 kb-app，登录端点无需改路由。

## Goals / Non-Goals

**Goals:**

- 本地账号从创建、登录、改密到管理自举的完整闭环；生产移除 V3 后仍可自举。
- 未认证（401）、无资格（403）、需先改密（403 加专用错误码）三类拒绝在网关与各服务间语义一致、可区分。
- 钉钉接入零返工：会话建立与身份解析复用现有链路，凭据列可空以容纳纯外部身份账号。

**Non-Goals:**

- 钉钉或 OIDC 集成、角色 CRUD、文档密级调整、密码找回、密码历史与过期策略。
- 来源维度（IP）登录节流：需要网关可信 XFF 方案，见 Open Questions。
- kb-ai 对“需先改密”的精确报错文案：本期以通用拒绝呈现，见 Risks。

## Decisions

### D1 凭据哈希：spring-security-crypto 的 BCrypt

kb-app 仅引入 `spring-security-crypto`（Spring Boot BOM 管理版本），使用 `BCryptPasswordEncoder`，不引入完整 Spring
Security。

- 替代方案：完整 `spring-boot-starter-security` 被拒，它带来过滤器链与自动配置，与 Sa-Token 会话体系重叠，收益只有哈希。
- 替代方案：Argon2 被拒，`spring-security-crypto` 的 Argon2 实现需要额外 BouncyCastle 依赖，本场景收益不抵依赖成本；PBKDF2
  可作后续替换点（编码器抽象已隔离）。
- 强度默认 10，`kb.auth.bcrypt-strength` 可配。
- 用户名不存在时也执行一次固定假哈希比对，收敛登录耗时侧信道。

### D2 凭据数据模型（V12）

```sql
ALTER TABLE sys_user
    ADD COLUMN password_hash        TEXT,
    ADD COLUMN password_updated_at  TIMESTAMPTZ,
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
```

- `password_hash` 可空：将来钉钉专属账号可以没有本地密码；登录对空哈希直接拒绝。
- 无回填：开发种子用户保持无密码，dev-login 不受影响。
- 密码写入不提升 `version`：version 语义保持在归属、启停、密级三类身份事实上，密码变更与它们互不冲突；仍更新 `updated_at`
  。代价是密码侧没有乐观锁，由审计幂等兜底（见 Risks）。
- 不新增表：角色分配幂等依赖 `sys_user_role` 主键冲突忽略。

### D3 控制器布局与会话建立

- 现有 `AuthController` 拆分：`DevAuthController` 只保留 `/api/auth/dev-login`（维持 `@ConditionalOnProperty`）；新增无条件的
  `AuthController`，提供 `POST /api/auth/login`、`POST /api/auth/logout`、`GET /api/auth/me`、`POST /api/auth/password`。
- 理由：logout 目前在 dev 开关下，生产没有任何登出入口；login、me、password 也不应受 dev 开关约束。
- `LoginResult` 扩展 `mustChangePassword` 与 `user`（IdentitySummary）；dev-login 复用同一结构（标记恒为 false）。
- 会话建立复用 `resolvePrincipal`（启用用户加启用主部门校验）后 `StpUtil.login(userId)`，与将来钉钉回调走同一入口。

### D4 登录失败语义与错误码

- 凭据失败（含用户名不存在）：HTTP 401、错误码 `UNAUTHORIZED`、固定文案“用户名或密码错误”。
- 凭据正确但用户或主部门停用：HTTP 403、错误码 `DISABLED`。停用状态只在凭据验证通过后才区分，不构成枚举面。
- 节流拒绝：与凭据失败完全一致的 401，不暴露节流状态；审计记录原因。
- kb-common 追加 `IdentityErrorCode.UNAUTHORIZED`、`PASSWORD_CHANGE_REQUIRED` 与 `IdentityException.unauthorized(...)`
  （纯追加，既有错误码语义不变）。

### D5 密码生命周期

- 创建用户必须携带 `initialPassword`（强度校验后哈希），`must_change_password = true`；管理员重置同。
- 自助改密：校验原密码，强度校验，更新哈希与时间戳，清除强制改密标记。
- 强度：`kb.auth.password.min-length` 默认 8；不引入字符类别复杂度要求（可由配置后续收紧）。
- 审计：`USER_PASSWORD_CHANGE`（自助，requestId 客户端可选，缺省由服务端生成）、`USER_PASSWORD_RESET`（管理员，X-Request-Id 幂等）。

### D6 强制改密的执行

- kb-app 新增 `PasswordChangeRequiredInterceptor`，在 `SaTokenConfig` 内注册于 `SaInterceptor` 之后，作用于 `/api/**`，白名单：
  `POST /api/auth/login`、`POST /api/auth/logout`、`GET /api/auth/me`、`POST /api/auth/password`、`/api/auth/dev-login`。
- 每请求一条主键 SELECT 读 `must_change_password`；替代方案（登录时写入会话标记）被拒，管理员重置后旧会话的标记会失真，强制改密必须读库。
- 拒绝：HTTP 403、错误码 `PASSWORD_CHANGE_REQUIRED`。
- 跨服务：kb-ai 不直连库，chat 经 kb-app 的 `/api/auth/quota-context` 间接被拦（403 触发 `QuotaPrincipalRejectedException`
  ）。在 `ChatController.stream()` 补一条对应 `onErrorResume`，输出 SSE 错误事件，把既有的 500
  收敛为可读错误（顺带修复停用用户的既有表现）。文案保持通用，见 Risks。
- `quota-context` 刻意不在白名单：必须改密的会话不应能发起任何业务请求，包括 AI 问答。

### D7 登录节流（Redis）

- 键 `kb:auth:login:fail:<username>`：每次失败 INCR 加 EXPIRE（`kb.auth.login.lock-duration`，默认 15 分钟）；计数达到
  `kb.auth.login.max-failures`（默认 5）即拒绝后续尝试，TTL 到期自动解锁，满足“不得造成需要人工解锁的持久锁定”。
- 登录成功删除计数。
- Redis 异常时拒绝登录（fail-closed）：会话本身存于 Redis，Redis 故障时登录不可能成功，节流不改变这一点。
- 仅按账户维度；来源维度后置（Open Questions）。
- 被节流拒绝的尝试不逐条写审计（防膨胀），计数本身即痕迹；到达凭据校验的失败按 D8 记录，总量被 max-failures 封顶。

### D8 登录审计与新增审计域

- 复用 `sys_identity_audit`，新增 `AuditScope.AUTH_LOGIN_SUCCESS("auth")` 与 `AUTH_LOGIN_FAILURE("auth")`；`request_id`
  由服务端生成（UUID），客户端没有请求标识，而表约束要求非空。
- `target_id` 为已解析的用户（存在时）；失败摘要记录尝试的用户名，不含密码或令牌。
- 管理面新增审计域：`USER_CREATE`、`USER_ROLE_ASSIGN`、`USER_ROLE_REVOKE`、`USER_PASSWORD_RESET`、`USER_PASSWORD_CHANGE`
  （均带实体前缀，受 `AuditScopeTest` 约束）。

### D9 管理面新增接口

- `POST /api/admin/identity/users`：请求体
  `{username, displayName, departmentId, clearanceLevel, initialPassword, requestId?}` 加 `X-Request-Id`；校验部门启用、密级来自
  `SecurityLevelDictionary`（与上传路径同源）、密码强度、用户名非空且长度受限；数据库唯一约束兜底冲突并返回 409；新用户
  enabled 为 true、version 为 0；审计 `USER_CREATE`；重放经 `audit.existing` 取 `targetId` 返回既有用户。
- `GET /api/admin/identity/roles`：返回角色 id、code、name 列表。
- `POST /api/admin/identity/users/{userId}/roles`：请求体 `{roleId, requestId?}`；插入走 `ON CONFLICT DO NOTHING`
  （重复分配视为幂等成功）；审计 `USER_ROLE_ASSIGN`。
- `DELETE /api/admin/identity/users/{userId}/roles/{roleId}` 加 `X-Request-Id`：未绑定返回 404；审计 `USER_ROLE_REVOKE`。
- `PATCH /api/admin/identity/users/{userId}/password`：请求体 `{newPassword, requestId?}`；审计 `USER_PASSWORD_RESET`
  ；重放经审计返回。
- 最后一个管理员保护：对“停用用户”与“解除 ROLE_ADMIN”两条路径统一判定，目标当前是启用 ROLE_ADMIN 持有者且启用持有者数不超过
  1 时返回 409。并发保护：判定与写入前先对 `sys_role` 中 ROLE_ADMIN 角色行加写锁（
  `SELECT ... FROM sys_role WHERE code = 'ROLE_ADMIN' FOR UPDATE`），串行化两名管理员互相并发解除的场景，后到者必须看到前者已提交的结果。
- ROLE_ADMIN 常量继续取自 `IdentityManagementAuthorizer.ROLE_GLOBAL_MANAGER`，不新增副本。

### D10 首个管理员引导

`AdminBootstrapRunner implements ApplicationRunner`（kb-app，Flyway 之后运行），配置见 D12。

1. 未配置用户名或密码：记 INFO 返回（开发态默认，零动作）。
2. 配置了但密码不合强度：抛异常使启动失败，静默启动出一个没有管理员的系统是最坏结局，宁可响亮地崩。
3. 已存在启用 ROLE_ADMIN 持有者：直接返回，零修改。
4. 否则单事务执行：确保角色行（按 code 查，缺则插）；确保部门（按配置 code 查，缺则建根部门，`path = '/<id>/'`
   ）；确保用户（缺则建：启用、字典内密级、bcrypt 密码、`must_change_password = true`；已存在则不动其密码、部门与密级）；确保绑定（
   `ON CONFLICT DO NOTHING`）。全程写审计，actor 为 null 表示系统动作，requestId 服务端生成。
5. 边界：配置的管理员已存在但被停用，只记 WARN，不自动重新启用（不静默翻转停用状态）；多实例并发靠唯一约束与冲突吞并兜底，当前部署形态为单实例。

不复用 `DepartmentService` 与 `IdentityService`：它们假设客户端语义（客户端 requestId、版本冲突、操作者必填），引导是系统上下文，直接用
mapper 加审计更诚实。

### D11 未认证 401 语义

- 网关 `SaTokenGatewayConfig`：`setError` 状态码改为 401；`.addExclude("/api/auth/login")`。
- kb-app：`SaTokenConfig` 的 `SaInterceptor` 增加排除 `/api/auth/login`（否则登录请求本身会先被 checkLogin 拦掉，这是与网关
  exclude 并列的第二个必改点）；`GlobalExceptionHandler.handleAuth` 改为 401。
- kb-ai `SaTokenWebFluxConfig`：`setError` 改为 401。
- 响应体沿用 `R.fail`，业务码 200 与 500 的既有约定不变，HTTP 状态承载语义。
- `QuotaPrincipalClient` 对 kb-app 401 与 403 的分类不变；语义变化在于“未认证”从 500（归入 unavailable）变为 401（归入
  rejected），方向更正确。

### D12 配置项（kb-app application.yml 追加）

```yaml
kb:
  auth:
    password:
      min-length: ${KB_PASSWORD_MIN_LENGTH:8}
    bcrypt-strength: ${KB_BCRYPT_STRENGTH:10}
    login:
      max-failures: ${KB_LOGIN_MAX_FAILURES:5}
      lock-duration: ${KB_LOGIN_LOCK_DURATION:15m}
  bootstrap:
    admin:
      username: ${KB_BOOTSTRAP_ADMIN_USERNAME:}
      password: ${KB_BOOTSTRAP_ADMIN_PASSWORD:}
      display-name: ${KB_BOOTSTRAP_ADMIN_DISPLAY_NAME:系统管理员}
      clearance-level: ${KB_BOOTSTRAP_ADMIN_CLEARANCE_LEVEL:0}
    department:
      code: ${KB_BOOTSTRAP_DEPARTMENT_CODE:HQ}
      name: ${KB_BOOTSTRAP_DEPARTMENT_NAME:总部}
```

## Risks / Trade-offs

- 节流仅按账户维度：攻击者可跨用户名横向尝试；由 bcrypt 成本、登录审计与后续来源维度方案兜底。
- kb-ai 面对强制改密的拦截文案不精确：前端以登录响应的 `mustChangePassword` 为主路径引导；kb-ai 至少把 500
  收敛为错误事件，不静默给空答案。
- 引导管理员默认密级 0 且不得自我提升：配置项可调，与不得自我提权规则一致，后续由第二名管理员调整。
- 密码写入不提升 version：与其他字段并发写互不冲突；代价是密码侧无乐观锁，由审计幂等兜底。
- 登录失败审计量：被节流阈值封顶；节流拒绝不逐条写。
- 引导多实例并发：唯一约束加冲突吞并兜底；当前单实例部署，如需横向扩展再引入锁。
- 401 语义变更：不存在依赖 500 的真实调用方；README 已将其记为待修缺陷，随本变更更新。
- bcrypt 强度与登录延迟：默认 10；若压测不可接受可下调，属配置项。

## Migration Plan

1. kb-app 启动执行 V12：纯加列、无回填、无长锁，回滚只需回滚代码（列保留无害）。
2. 部署顺序：kb-app（V12 加引导配置），然后网关与 kb-ai（401 语义），最后前端跟进。开发环境零配置：dev-auth 与 V3 种子照旧。
3. 生产引导步骤：设置 `KB_BOOTSTRAP_ADMIN_USERNAME` 与 `KB_BOOTSTRAP_ADMIN_PASSWORD`（可选部门与密级配置），启动，管理员首次登录强制改密。
4. 与 V3 移除的关系：V3 照旧在生产移除；移除后由引导建立角色行、部门与管理员，管理端点不再有 403 死角。

## Open Questions

- 来源维度（IP）节流依赖网关可信 X-Forwarded-For 方案，待部署形态确定后设计。
- 初始密码的线下交付流程（工单或口头）由运维约定，不影响接口形态。
- 密码强度最终参数（是否要求字符类别复杂度）待业务确认，当前以最小长度加配置为准。
