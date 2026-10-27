# Tasks

验证仅限进程内单元测试与静态检查，依赖使用 Mock/Stub，不组装跨模块业务链路，不启动应用或外部服务。文档仅交付并人工核对，不编写或执行 README、Markdown、OpenAPI/Javadoc 内容及示例测试。测试按明确的单元测试类/方法白名单运行，打包跳过测试。

## 1. 数据库与公共错误码基础

- [x] 1.1 新增 `V12__local_credentials.sql`：为 `sys_user` 加 `password_hash TEXT`、`password_updated_at TIMESTAMPTZ`、
  `must_change_password BOOLEAN NOT NULL DEFAULT FALSE` 三列，不回填。验证：新增 V12 迁移测试（对照
  `IdentityDepartmentMigrationTest` 的写法）断言三列存在、种子用户 `password_hash` 为空且 `must_change_password` 为 false。
- [x] 1.2 kb-common 追加 `IdentityErrorCode.UNAUTHORIZED` 与 `PASSWORD_CHANGE_REQUIRED`，并为 `IdentityException` 增加
  `unauthorized(...)`（HTTP 401）与 `passwordChangeRequired(...)`（HTTP 403）工厂。验证：单测断言两个工厂的 httpStatus 与
  errorCode，且既有工厂行为不变。

## 2. 本地登录链路

- [x] 2.1 kb-app 引入 `spring-security-crypto` 依赖并注册 `BCryptPasswordEncoder` Bean（强度读 `kb.auth.bcrypt-strength`
  ，默认 10），同时提供未知用户比对用的固定假哈希常量。验证：单测断言编码器强度可配、假哈希 `matches` 恒为 false。
- [x] 2.2 扩展 `IdentityDepartmentMapper` 与对应 XML：按用户名查用户（含 `password_hash`、`must_change_password`
  ）、插入用户、更新密码与 `password_updated_at`、设置与清除 `must_change_password`。验证：映射入参单测，或随 2.5 的登录服务测试经
  mock 断言各语句入参。
- [x] 2.3 登录节流组件（Redis）：键 `kb:auth:login:fail:<username>` 每次失败 INCR 加 EXPIRE（`kb.auth.login.max-failures` 默认
  5、`kb.auth.login.lock-duration` 默认 15m），成功登录 DEL；达到阈值后拒绝且响应与凭据失败完全一致；Redis 异常
  fail-closed。验证：使用 Mock Redis 和可控时钟的单元测试断言键格式、阈值、TTL 到期分支、成功清零与 Redis 异常拒绝路径，不执行 Redis 命令。
- [x] 2.4 登录审计：`AuditScope` 新增 `AUTH_LOGIN_SUCCESS` 与 `AUTH_LOGIN_FAILURE`（targetType 为 `auth`），事件
  `request_id` 由服务端生成 UUID，失败摘要只记尝试的用户名、不含密码或令牌；被节流拒绝的尝试不逐条写。验证：`AuditScopeTest`
  更新通过，单测断言成败各写一条、节流拒绝不写。
- [x] 2.5 登录服务（暂名 `LocalAuthService`）：按用户名取用户；未知用户先做假哈希比对再统一抛 401 `UNAUTHORIZED`
  （固定文案“用户名或密码错误”）；凭据正确但用户或主部门停用抛 403 `DISABLED`；`password_hash` 为空直接拒绝；成功后经
  `resolvePrincipal` 与 `StpUtil.login` 建立会话，返回扩展后的 `LoginResult`（含 `mustChangePassword` 与 `user`
  ）。验证：单测覆盖成功、密码错、未知用户、停用、空哈希五个分支，并断言未知用户与密码错的响应完全一致。
- [x] 2.6 控制器拆分：`DevAuthController` 仅保留 `/api/auth/dev-login`（维持 `@ConditionalOnProperty`）；新增无条件
  `AuthController` 提供 `POST /api/auth/login`、`POST /api/auth/logout`、`GET /api/auth/me`（返回 `user`、`roles`、
  `mustChangePassword`）、`POST /api/auth/password`（业务逻辑在 3.2 完成），全部带 OpenAPI 注解。验证：控制器测试断言 dev-auth
  关闭时 login/logout/me 可用、dev-login 不可用，且 `LoginResult` 新字段序列化正确。
- [x] 2.7 登录路由与拦截排除：网关 `SaTokenGatewayConfig` 增加 `.addExclude("/api/auth/login")`，kb-app `SaTokenConfig` 的
  `SaInterceptor` 同步排除（本组只做排除，401 状态码语义在 6.x）。验证：网关与 kb-app 配置测试断言 `/api/auth/login` 在排除列表且其余
  `/api/**` 仍受保护。

## 3. 密码生命周期与强制改密

- [x] 3.1 密码策略配置：kb-app `application.yml` 增加 `kb.auth.password.min-length`（默认
  8）并绑定属性类。验证：配置绑定测试断言默认值与环境变量覆盖生效。
- [x] 3.2 自助改密：实现 `POST /api/auth/password`——校验原密码、强度校验、更新哈希与时间戳、清除强制改密标记、写
  `USER_PASSWORD_CHANGE` 审计（requestId 客户端可选，缺省服务端生成）。验证：单测覆盖原密码错误被拒且不修改任何状态、强度不足被拒、成功改密后标记清除三分支。
- [x] 3.3 强制改密拦截器：`PasswordChangeRequiredInterceptor` 注册在 `SaTokenConfig` 内、`SaInterceptor` 之后，作用于
  `/api/**`，白名单 `POST /api/auth/login`、`POST /api/auth/logout`、`GET /api/auth/me`、`POST /api/auth/password`、
  `/api/auth/dev-login`；每请求读库 `must_change_password`，为真抛 403 `PASSWORD_CHANGE_REQUIRED`
  。验证：拦截器单测覆盖白名单放行、业务路径拦截、完成改密后放行。

## 4. 管理面：建号、角色与管理员保护

- [x] 4.1 创建用户：`POST /api/admin/identity/users`（服务加 mapper 加控制器）——校验部门启用、密级来自
  `SecurityLevelDictionary`、密码强度、用户名非空且长度受限；唯一冲突返回 409；`USER_CREATE` 审计；重放经 `audit.existing`
  返回既有用户。验证：服务单测覆盖成功、重名、停用部门、字典外密级、重放五个分支。
- [x] 4.2 角色目录：`GET /api/admin/identity/roles` 返回 id、code、name。验证：控制器测试断言列表内容与无资格操作者 403。
- [x] 4.3 角色分配与解除：`POST /api/admin/identity/users/{userId}/roles` 走 `ON CONFLICT DO NOTHING`（重复分配幂等成功）；
  `DELETE /api/admin/identity/users/{userId}/roles/{roleId}` 未绑定返回 404；分别写 `USER_ROLE_ASSIGN` 与
  `USER_ROLE_REVOKE` 审计并支持 X-Request-Id 重放。验证：服务单测覆盖分配、重复分配、解除、解除不存在、重放五个分支。
- [x] 4.4 最后一个管理员保护：对“解除 ROLE_ADMIN”与“停用用户”两条路径统一判定，目标为启用 ROLE_ADMIN 持有者且启用持有者数不超过
  1 时返回 409；判定与写入前对 `sys_role` 中 ROLE_ADMIN 行执行 `SELECT ... FOR UPDATE`
  。验证：单测覆盖解除最后一个被拒、停用最后一个被拒、存在其他持有者放行，并断言判定与写入前先获取 ROLE_ADMIN 角色行锁（mock
  校验调用顺序）。
- [x] 4.5 管理员重置密码：`PATCH /api/admin/identity/users/{userId}/password`——强度校验、置新哈希并设
  `must_change_password = true`、写 `USER_PASSWORD_RESET` 审计、支持重放。验证：单测覆盖重置生效、被重置用户的既有会话随后被
  3.3 拦截、重放返回首次结果。
- [x] 4.6 端点暴露与文档：新端点全部挂在 `/api/admin/identity`（兼容别名同享）并受 `requireManager`；更新既有暴露测试（如
  `IdentityManagementExposureTest`）覆盖新端点，补齐 OpenAPI 注解。验证：独立控制器/路由元数据单元测试覆盖端点暴露与鉴权，注解仅人工核对，不访问 `/doc.html`。

## 5. 首个管理员引导

- [x] 5.1 引导配置：kb-app `application.yml` 增加 `kb.bootstrap.*`（admin 的
  username、password、display-name、clearance-level，department 的 code、name），默认全空。验证：绑定测试断言未配置时为空白、不启用任何引导行为。
- [x] 5.2 `AdminBootstrapRunner`：未配置用户名或密码记 INFO 跳过；配置了但密码不合强度抛异常使启动失败；已存在启用
  ROLE_ADMIN 持有者零动作；否则单事务建角色行、部门（`path = '/<id>/'`）、用户（`must_change_password = true`）、绑定（
  `ON CONFLICT DO NOTHING`），审计 actor 为 null。验证：单测覆盖未配置、弱密码、已有持有者、空库全建四条路径，并断言重复启动零修改、已存在但停用的管理员只记
  WARN 不重新启用。

## 6. 未认证 401 语义与跨服务收敛

- [x] 6.1 网关 `SaTokenGatewayConfig.setError` 改为 401。验证：网关测试断言未认证请求返回 401 且响应体为 `R.fail`。
- [x] 6.2 kb-app `GlobalExceptionHandler` 的 `SaTokenException` 处理改为 401。验证：异常处理测试断言未认证 401、无资格仍
  403。
- [x] 6.3 kb-ai `SaTokenWebFluxConfig.setError` 改为 401。验证：kb-ai 测试断言未认证 401。
- [x] 6.4 kb-ai `ChatController.stream()` 增加 `QuotaPrincipalRejectedException` 的 `onErrorResume`，输出 SSE 错误事件替代当前
  500。验证：`ChatControllerTest` 增加用例断言该异常得到错误事件而非 500。

## 7. 文档同步与离线校验

- [x] 7.1 README 同步：更新能力表（“角色与密级管理”“认证与
  SSO”两行）、第六节硬性检查项（首个管理员引导落地后可勾选）、第八节下一步中已完成的条目，并新增“本地登录与账号管理”小节说明接口用法与配置。验证：与实现逐条核对，README
  中描述的接口、配置项与代码一致，仅人工核对。
- [x] 7.2 按明确的进程内单元测试类/方法白名单运行回归，排除集成、外部组件和文档内容测试；执行 `mvn -B clean package '-DskipTests'`
  与 `openspec validate "account-management-and-local-login"`。验证：记录限定范围的单测、构建与规范校验结果。
