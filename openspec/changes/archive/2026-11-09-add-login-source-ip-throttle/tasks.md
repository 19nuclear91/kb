# Tasks

测试范围仅限本地隔离单元测试，依赖用 Mock、Stub 和内存实现代替。不包含集成测试、跨服务联调、端到端测试或部署演练，不需要启动或连接 PostgreSQL、Redis、Nginx、后端服务或浏览器，也不对 README、Markdown、注释、规格或文案做内容断言。说明文档作为交付物更新，不作为断言对象。真实的 Nginx 头覆盖、网络访问限制和 Redis 行为属于未验证范围，不列入本变更的待办。

## 1. 来源地址解析

- [x] 1.1 在 kb-app 的 `com.zhishiku.kb.app.auth.web` 包内新增 `LoginSourceAddress`，按 design D2 把 `X-Real-IP` 的全部头值解析为规范化地址或 null：头缺失、出现多次、为空、长度超过 45 时返回 null；先做字符集和 IPv4 四段 0–255、无前导零的严格校验，通过后才调用 `InetAddress.getByName`；统一输出 `getHostAddress()`。新增 `LoginSourceAddressTest`，覆盖以下用例：合法 IPv4 原样返回；`01.2.3.4`、`256.1.1.1`、`1.2`、`1.2.3.4.5` 返回 null；同一 IPv6 地址的大小写写法和压缩写法得到同一结果；`::ffff:1.2.3.4` 得到 `1.2.3.4`；`fe80::1%eth0`、`[::1]`、`1.2.3.4:80`、`1.2.3.4, 5.6.7.8`、空串和超长串返回 null；`localhost` 返回 null（如果发生了 DNS 解析，会得到 127.0.0.1，所以返回 null 即可证明没有解析）；头出现两次返回 null；无头返回 null。验证：`mvn -o -pl kb-app -am test '-Dtest=LoginSourceAddressTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'` 通过。

## 2. 来源节流、失败审计与登录顺序

- [x] 2.1 扩展 `LoginThrottle`：构造器新增 `@Value("${kb.auth.login.source-max-failures:20}") int sourceMaxFailures`，小于 1 时抛出 `IllegalArgumentException`；新增 `isSourceThrottled(source)` 和 `recordSourceFailure(source)`，键为 `kb:auth:login:source-fail:<source>`，复用现有的 INCR、EXPIRE 和补 TTL 逻辑；不提供来源清除方法；现有三个账户方法的签名和行为不变。在 kb-app 的 `application.yml` 的 `kb.auth.login` 下新增 `source-max-failures: ${KB_LOGIN_SOURCE_MAX_FAILURES:20}`，并加注释说明含义及多人共用出口 IP 时的影响。扩展 `LoginThrottleTest`（构造改为新参数）：来源键格式，19 次不节流、20 次节流；记录失败时刷新 TTL；缺少 TTL 时补设；`clear(username)` 不删除来源键；Redis 故障时来源检查和记录都抛出异常；阈值为 0 时构造失败；原有账户用例保持通过。验证：`mvn -o -pl kb-app -am test '-Dtest=LoginThrottleTest,SsoSecurityServiceTest,EmergencyManagementServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'` 通过，后两个类用来证明另外两个入口不受影响。
- [x] 2.2 `LoginAuditService.failure` 新增可为 null 的 `sourceIp` 参数：非 null 时摘要为 `{"username":"...","sourceIp":"..."}`，null 时保持 `{"username":"..."}`，两种情况都经现有 `summary` 转义；`success` 不变。扩展 `LoginAuditServiceTest`：两种摘要的精确值；带来源时仍不出现 password、token、secret、hash；用户名转义用例保持通过。扩展 `AuditSummaryProjectorTest`：含 `sourceIp` 的失败摘要仍能取出 `attemptedUsername`，详情的变更列表中不出现 `sourceIp`。验证：`mvn -o -pl kb-app -am test '-Dtest=LoginAuditServiceTest,AuditSummaryProjectorTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'` 通过。
- [x] 2.3 把 `LocalAuthService.login` 改为 `login(username, password, source)`，按 design D4 的顺序实现：来源已知且被节流时返回统一 401 并直接结束；然后检查账户节流；凭据失败时记录账户失败，来源已知时再记录来源失败，失败审计带来源；身份门禁拒绝时审计带来源，两个计数都不变；成功时只清除账户计数。更新类注释中关于检查顺序的说明。改造 `LocalAuthServiceTest`：现有用例改为三参数，各补一个 null 来源和一个已知来源的版本；新增用例：来源已被节流时返回与凭据失败相同的 401，不调用 mapper、encoder、账户节流、计数和审计；用 `InOrder` 断言来源检查早于账户检查、账户检查早于查凭据；已知来源下未知用户和密码错误都会同时记录两个计数，审计带来源；停用账户的审计带来源，且不调用任何 record 方法；成功时调用 `clear(username)`，不调用 `recordSourceFailure`；来源为 null 时从不调用 `isSourceThrottled` 和 `recordSourceFailure`。验证：`mvn -o -pl kb-app -am test '-Dtest=LocalAuthServiceTest,LocalAuthServicePasswordChangeTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'` 通过。
- [x] 2.4 `AuthController.login` 新增 `HttpServletRequest` 参数，用 `request.getHeaders("X-Real-IP")` 取出头值，交给 `LoginSourceAddress` 解析后传给 `login(username, password, source)`；其他路由不变。改造 `AuthControllerTest`：现有登录用例改为三参数；新增用例：带 `X-Real-IP: 10.0.0.1` 时传入 `"10.0.0.1"`；无头时传 null；出现两个 `X-Real-IP` 时传 null；只有 `X-Forwarded-For` 时传 null；路由反射断言改为 `getDeclaredMethod("login", LoginRequest.class, HttpServletRequest.class)`。验证：`mvn -o -pl kb-app -am test '-Dtest=AuthControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'` 通过。

## 3. 网关透传回归

- [x] 3.1 在 `InboundHeaderSanitizeFilterTest` 新增用例：请求同时带 `X-Real-IP` 和身份类请求头时，身份头被剥离，`X-Real-IP` 的值原样保留。这个用例锁定"网关不处理也不剥离该头"的契约，网关生产代码不改。验证：`mvn -o -pl kb-gateway -am test '-Dtest=InboundHeaderSanitizeFilterTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'` 通过。

## 4. 部署与运维文档

- [x] 4.1 在 `docs/user-web-operations.md` 新增"登录来源与 X-Real-IP"一节，内容包括：Nginx 在 `/api/` 及其所有子 location 中写 `proxy_set_header X-Real-IP $remote_addr;`，并说明 location 块只要自己定义了任何 `proxy_set_header`，就不再继承 server 层同类配置；网关端口只允许 Nginx 访问、app 端口只允许网关访问这条前提；将来在 Nginx 前加负载均衡时必须配置 realip 模块；发布顺序先改 Nginx 再上线 kb-app，回退顺序先回退 kb-app；上线后用伪造头发起一次失败登录，核对审计 `after_state.sourceIp`；`KB_LOGIN_SOURCE_MAX_FAILURES` 的默认值和多人共用出口 IP 时的调整建议；一条按来源和用户名聚合 `AUTH_LOGIN_FAILURE` 的只读示例 SQL，注明本次未实际执行；IP 属于个人信息，保留期限需另行确定。验证：章节已交付。
- [x] 4.2 在 `docs/admin-query-api.md` 现有"不补录节流期间未记录的尝试"处补充：来源节流拒绝的请求同样不记录；审计查询接口不返回 `sourceIp`，返回字段和筛选条件不变。在 README"正式部署要点"中增加一条：入口 Nginx 写入 `X-Real-IP`，网关只允许 Nginx 访问，并链接到 4.1 的章节。验证：两处说明已交付。

## 5. 构建与范围检查

- [x] 5.1 执行后端定向白名单：`mvn -o -pl kb-app,kb-gateway -am test '-Dtest=LoginSourceAddressTest,LoginThrottleTest,LoginAuditServiceTest,LocalAuthServiceTest,LocalAuthServicePasswordChangeTest,AuthControllerTest,SsoSecurityServiceTest,EmergencyManagementServiceTest,AuditSummaryProjectorTest,AuditQueryServiceTest,InboundHeaderSanitizeFilterTest,ModuleBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'`，再执行 `mvn -o -pl kb-app,kb-gateway -am package '-DskipTests'`，记录实际执行的类名和结果。不运行 PostgreSQL 烟测或集成测试。
- [x] 5.2 执行 `git diff --check` 和 `git diff --stat`，确认：没有新增数据库迁移；没有改动前端；`SsoLocalProof`、`EmergencyManagementService` 和网关生产代码未改动；kb-app 没有新增读取 `X-Forwarded-For`、`Forwarded` 或 `getRemoteAddr()` 作为登录来源的代码。
- [x] 5.3 执行 `openspec validate add-login-source-ip-throttle --strict`，确认通过。

## 6. 核验修复

- [x] 6.1 修复核验 W1：旧的按目标审计接口 `GET /api/admin/identity/audits` 会原样返回 `after_state`，能看到已知用户登录失败记录里的 `sourceIp`。保持该接口的输出不变，把"审计查询接口不返回 `sourceIp`"的说法改为只指全局审计查询，并写明旧接口的行为。涉及 `docs/admin-query-api.md`、`docs/user-web-operations.md`、`LoginAuditService` 类注释，以及 proposal 和 design 中的对应说法。同时修复 S1（design 中"Nginx 未配置时行为不变"的表述）和 S2（`LoginSourceAddress.resolve` 注释缺少概述句）。验证：`mvn -o -pl kb-app -am test '-Dtest=LoginSourceAddressTest,LoginAuditServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dstyle.color=never'` 通过，`git diff --check` 和 `openspec validate add-login-source-ip-throttle --strict` 通过。

## Workflow follow-up

- 实施和上述检查完成后，同步主规格并归档本变更，再核对归档结果。
