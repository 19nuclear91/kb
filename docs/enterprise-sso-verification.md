# 企业浏览器 SSO 离线验证记录

日期：2026-10-17，Windows PowerShell，JDK21 / Maven3.9.9。变更 `enterprise-browser-sso`。本记录只证明限定离线编排、序列化、鉴权分支与构建结果，不表示真实供应商登录或生产验收。

## 执行范围与结果

| 检查 | 结果 |
| --- | --- |
| 明确类名白名单单元测试 | 30个类、129项，0失败、0错误、0跳过 |
| kb-app | 29个类、125项通过 |
| kb-gateway | 1个类、4项通过 |
| kb-common | 参与依赖编译，本次白名单没有该模块测试 |
| 离线跳过测试的 package | parent/common/app/gateway 成功，未运行产物 |
| OpenSpec strict | `Change 'enterprise-browser-sso' is valid` |

执行前核对每个类名实际存在，没有通配符、全量发现或默认测试入口。最终执行的 PowerShell 变量与命令如下；这些是执行记录，不要求读者执行文档示例：

```powershell
$ssoUnitTests = 'SsoSettingsTest,SsoScopeTest,SsoHttpTest,SsoProviderTest,SsoFlowsTest,SsoSecurityServiceTest,SsoFacadeTest,SsoAuditTest,SsoControllerTest,SsoVerificationTest,SsoAppGuardTest,LocalAuthServiceTest,LocalAuthServicePasswordChangeTest,LoginThrottleTest,LoginAuditServiceTest,IdentityServiceTest,IdentityServicePasswordResetTest,IdentityAuditServiceTest,AuditScopeTest,AuditSummaryProjectorTest,AuditQuerySourceTest,AuditQueryServiceTest,AuthControllerTest,SaTokenConfigTest,SaTokenGatewayConfigTest,PasswordChangeRequiredInterceptorTest,ConversationIdentityAndPrivacyTest,IdentityQueryServiceTest,IdentityManagementAuthorizerTest,ModuleBoundaryTest'
mvn -o -pl kb-app,kb-gateway -am test "-Dtest=$ssoUnitTests" '-Dsurefire.failIfNoSpecifiedTests=false'
mvn -o -pl kb-app,kb-gateway -am package '-DskipTests'
openspec validate enterprise-browser-sso --strict
```

本地证据：`target/enterprise-sso-unit-allowlist.txt`、`target/enterprise-sso-unit-tests.log`、`target/enterprise-sso-unit-summary.json`、`target/enterprise-sso-package.log`，各模块 `target/surefire-reports`。这些构建目录会被 clean 清除，结论以本记录的固定范围为准。

## 测试覆盖

- 配置默认关闭与部分启用、企业／应用／标识类型隔离、完整 SHA-256 及长度前缀编码、大小写和标识长度、密钥轮换只改变事务指纹。
- 注入 HTTP 字节响应验证三家授权／换码／成员状态分支；明确非成员、停用、错误企业、缺权限、未知字段、取消和损坏响应安全失败。未使用手机号／邮箱建号或匹配。
- 官方主机限制、拒绝重定向、512KiB 上限、单次与整轮预算、外层超时取消、应用令牌缓存作用域／凭据代次和 Redis 异常。用户令牌只在本次核验中使用。
- 受控状态机替身及 Mock Redis 返回值验证跨浏览器／连接、意图混用、重复领取、多标签替代、晚到旧结果、过期、429、重复消费、领取后不可恢复等编排分支；结果 Cookie 按 flow 独立。
- 当前本人密码、节流、强制改密、主体失效、密码哈希变化导致证明失效；原用户换令牌或切换用户不能完成绑定。本人复核不调用本地 login，不新增本地登录审计。
- 唯一冲突后读取最终归属、同用户重复、停用绑定拒绝、审计异常传播；受管命名空间新增阻断，普通历史 provider 和管理员读／解绑权限及审计保持兼容。
- standalone MockMvc 验证严格 JSON、重复／未知字段、Origin、媒体类型、固定303、Cookie、安全响应头、原 LoginResult 及 R.code=200/500；模拟 HTTP400/401/403/404/409/429/503。未启动 HTTP 服务。
- 独立令牌、成功审计后交付、失败只清理本次候选令牌、强制改密受限会话；直接调用主应用／网关鉴权策略，绑定仍受保护，本地接口与 AI 历史门禁回归。
- SSO 终结审计幂等、未知用户 null 目标、固定枚举投影、敏感字段不回显、旧 loginUsername、当前 audit:read、窗口／游标及旧事件摘要兼容。审计枚举登记原来仅覆盖旧前缀，回归发现后补充 SSO 与 user 目标的明确例外，重跑全白名单通过。

## 人工代码与 SQL／Lua 审阅

- `RedisSsoFlowStore`：相关键共用 `{sso}` slot；创建原子维护限额和当前指针；领取比较浏览器、连接、state、阶段、到期时间和当前指针，先转换 PROCESSING 再调用平台；结果写入再次比较当前 flow 和原阶段；消费比较结果摘要、意图和指纹后删除唯一资格。过期索引按 score 清理，所有值有限 TTL，无故障内存放行。
- `SsoBindings` / `SsoBindingMapper`：平台 HTTP 已结束才进入短 READ_COMMITTED 事务；锁定内部用户、复核当前凭据和主体，V8 `(provider, subject)` 唯一约束兜底。`ON CONFLICT DO NOTHING` 避免进入 PostgreSQL aborted 状态，后续独立语句读取最终归属；成功审计加入同一事务，异常向外传播。无新增 DDL，未修改历史迁移。
- `SsoSession`：人工查看当前依赖 Sa-Token1.46.0 字节码，`createLoginSession` 建立服务端映射，响应写入由另外入口负责；本实现使用前者且显式独立令牌、不共享、不自动响应 header。已知随机候选令牌便于部分失败清理，未使用用户级 logout。
- 人工审阅配置、API、供应商依据、README、回退及手工离职说明。未为文档、注释、文本、样例或 README 新增测试，也未执行其中示例。供应商公开说明仅只读查阅，部分页面仅取得标题或无法访问，权限及真实响应核对边界见供应商契约。

## 明确未执行

未启动应用、网关或产物，未监听端口；未运行或连接 PostgreSQL、Redis、Docker、MinIO、代理、嵌入式数据库／缓存或供应商接口。未执行集成测试、已有 PostgreSQL 烟测、联网测试、浏览器验收、人工扫码、部署或回退演练；未运行文档／源码字符串断言测试。

Mock 和受控 Stub 不能证明真实 Redis 原子性、Lua 执行兼容性、数据库锁互斥、并发竞争、唯一冲突等待、事务回滚或跨系统故障恢复。未验证真实成员 API 权限及状态字段、浏览器 Cookie 行为、代理日志或前端结果页。进程在领取后崩溃、审计依赖不可用或响应丢失存在有限状态／会话窗口，不承诺跨 PostgreSQL 与 Redis 的 exactly-once，也不伪造缺失审计。

连接默认关闭；真实配置和外部环境不构成本轮代码交付的待完成前置。本变更不执行 OpenSpec 实现核验、归档或部署，后续由用户另行发起。
