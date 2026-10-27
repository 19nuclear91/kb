# Spec Delta

## ADDED Requirements

### Requirement: 企业登录与本人绑定必须进入既有全局审计范围

系统 SHALL 把企业登录和本人绑定的终结事件分别记录为 sys_identity_audit 中的 SSO_LOGIN 和 SSO_BIND，支持现有全局列表及详情查询。事件使用服务端生成的关联身份，保留成功、失败及未解析内部用户的 null 目标；同一授权事务不得重复记录终结事件。读取仍要求 audit:read，不新增普通用户审计读取资格。

#### Scenario: 已绑定用户完成外部登录
- **WHEN** 企业认证、内部身份检查和会话建立均成功
- **THEN** 现有审计接口可查到 SSO_LOGIN 成功事件，actor 与 target 指向内部用户
- **AND** 事件不包含内部令牌、平台令牌或外部 subject

#### Scenario: 未绑定或非企业成员
- **WHEN** 授权事务因未绑定、成员门禁或供应商故障终止，且审计存储可用
- **THEN** 记录失败终结事件，未知内部用户保持 null，不猜测用户名或用户 ID

#### Scenario: 授权事务重放
- **WHEN** 已记录终结事件的回调或结果被重复提交
- **THEN** 请求按防重放规则拒绝，不重复写入该事务的登录或绑定终结事件

### Requirement: 企业认证审计摘要必须使用有限安全投影

新增 SSO_LOGIN 与 SSO_BIND 摘要 SHALL 仅允许平台枚举 dingtalk、wecom、feishu，固定目录中的 resultCode，以及绑定事件的布尔 bound 字段。摘要 MUST 不返回企业 ID、应用 ID、连接作用域键、外部 subject 及其摘要、用户名、远程错误或任何认证凭证。字段非法时沿用摘要不可用的安全降级，不回显原始 JSON。

#### Scenario: 合法平台与失败类别
- **WHEN** 合格审计用户读取一个平台为 feishu、结果为 NOT_MEMBER 的合法失败事件
- **THEN** 仅展示批准的平台和固定结果码及既有元数据，不加载企业或用户当前名称

#### Scenario: 摘要混入额外凭据
- **WHEN** 原事件摘要包含合法平台字段及额外 token、subject、corpId 或用户信息
- **THEN** 额外字段不进入查询结果，原始载荷不被返回

#### Scenario: 伪造平台或结果值
- **WHEN** 原事件的 platform 或 resultCode 不在固定目录中
- **THEN** 保留安全事件元数据，摘要标记不可用，不输出伪造字符串

### Requirement: 新审计域必须保留已有查询协议

新增企业认证事件 MUST 沿用现有状态、时间窗口、稳定游标、当前权限、no-store 和依赖故障语义，不增加新数据源拼接。loginUsername SHALL 继续仅匹配 AUTH_LOGIN_SUCCESS 与 AUTH_LOGIN_FAILURE 的已记录用户名；SSO 事件不补造 username，也不因此扩大旧登录审计摘要。

#### Scenario: 使用旧登录用户名过滤
- **WHEN** 操作者提供 loginUsername，或同时限定 SSO_LOGIN 域与 loginUsername
- **THEN** 前者仍仅匹配旧本地登录域，后者返回空页，不忽略过滤条件

#### Scenario: 查询 SSO 事件前撤权
- **WHEN** 操作者首次读取 SSO 事件后被撤销 audit:read
- **THEN** 后续页和详情均返回 403，旧游标不延续原读取资格
