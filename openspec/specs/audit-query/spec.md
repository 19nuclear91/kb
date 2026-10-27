# audit-query Specification

## Purpose

提供独立授权、可跨目标检索且能够稳定分页的管理与登录审计查询，使运维人员可以追溯已有操作和无目标身份的登录失败，同时明确数据源覆盖边界并防止审计载荷成为敏感内容读取通道。

## Requirements

### Requirement: 全局审计查询必须使用独立资格

系统 SHALL 提供 `GET /api/admin/audits` 及 `GET /api/admin/audits/{eventId}`，仅向当前持有 `audit:read` 的启用用户开放。身份审计读取、空间管理、文档密级、外部身份管理、Embedding 管理或 ROLE_ADMIN 名称 MUST NOT 隐含该资格。无会话返回 401，无资格、主体停用或强制改密返回 403，且 MUST 先鉴权再查询事件是否存在。

#### Scenario: 持有旧审计读取资格
- **WHEN** 操作者只有 `identity:audit:read`，没有 `audit:read`
- **THEN** 新全局接口返回 403，原按目标审计接口仍按原规则工作

#### Scenario: 无资格用户猜测事件 ID
- **WHEN** 无资格用户分别查询存在和不存在的合法事件 UUID
- **THEN** 两次请求均返回 403，不区分事件是否存在

#### Scenario: 合格用户查询不存在的事件
- **WHEN** 合格操作者查询合法但不存在的事件 UUID
- **THEN** 返回 404；非法 UUID 返回 400

### Requirement: 查询必须明确覆盖已有持久审计而不伪造完整性

首期查询 SHALL 覆盖现有 `sys_identity_audit` 中的全部已有管理及登录事件，包括成功、失败和 targetId 为空的记录。查询 MUST 保持 append-only，不写回、修补或删除审计。接口说明 MUST 明示不包含 Redis AI 审计、独立 Embedding 操作、尚未被生产者记录的请求及节流期间未记录的尝试。

#### Scenario: 未知用户名登录失败
- **WHEN** 登录失败审计没有解析出目标用户，targetId 为 null
- **THEN** 事件仍可按时间、操作域和登录用户名检索，响应保留 null，不补造用户 ID

#### Scenario: 业务操作没有留下审计
- **WHEN** 查询范围内某个请求未由原生产者写入审计
- **THEN** 查询不生成推测事件，文档不将“未查到记录”解释成“操作从未发生”

### Requirement: 审计必须支持组合条件和明确的时间窗口

列表 SHALL 支持 actorUserId、operationScope、operation、targetType、targetId、status、requestId、loginUsername、from、to，所有已提供条件按 AND 组合。targetId 仅与 targetType 同时提供；单独 targetType 合法。from/to MUST 为带时区时间，采用 `[from,to)`；缺省 to 为首次请求时间、from 为 to 前 7 天，跨度最多 31 天。ID、枚举、长度、空字符串、未知参数或时间范围非法时返回 400。

#### Scenario: 不限定具体目标进行排查
- **WHEN** 合格用户仅指定操作者、FAILED 结果和时间范围
- **THEN** 返回满足条件的跨目标事件，不要求 targetType 或 targetId

#### Scenario: 按登录用户名查询
- **WHEN** 请求提供 loginUsername
- **THEN** 仅在登录成功与失败事件中按已记录用户名精确匹配，不作模糊、全文或正文搜索
- **AND** 与不兼容的 operationScope 组合时返回空页，不忽略过滤条件

#### Scenario: 非法或超长窗口
- **WHEN** from 不早于 to、窗口超过 31 天、时间无时区或 targetId 缺少 targetType
- **THEN** 返回 400，不执行无界扫描或静默放宽条件

### Requirement: 审计分页必须稳定且不得因关联缺失丢失事件

列表 SHALL 按 `(createdAt DESC, id DESC)` 排序，返回 items、hasMore、nextCursor 及实际 from/to；limit 默认 20、范围 1–100。游标 MUST 绑定有效时间范围、过滤条件和最后排序位置，继续请求缺省时间沿用游标范围，不能重新取当前时间。查询 MUST 不依赖用户、角色、部门或文档仍存在的内连接，也不返回无界总数。

#### Scenario: 多条事件具有相同时间
- **WHEN** 页面边界上有多条相同 createdAt 的事件
- **THEN** 使用内部 ID 作为确定性的第二排序键，后续页不跳过或重复边界事件

#### Scenario: 目标实体后来被删除
- **WHEN** 审计目标的角色、空间或其他业务实体已经不存在
- **THEN** 原事件仍按存储的标识和时间返回，不因关联对象缺失被过滤

#### Scenario: 查询条件与游标不一致
- **WHEN** 调用者更换过滤条件、提供损坏游标或在其他查询类型使用游标
- **THEN** 返回 400，不把原游标当作有效查询或授权快照

### Requirement: 审计响应必须使用白名单安全投影

列表和详情 SHALL 返回事件标识、操作域、操作者 ID、目标类型/ID、操作、结果、请求标识和时间；详情另提供按操作域白名单构造的结构化变更摘要，登录事件可返回受长度限制的 attemptedUsername。MUST NOT 原样返回 beforeState、afterState、自由文本 reason，或输出密码、哈希、会话令牌、外部 subject、文档标题/正文、存储键及远程错误体。

#### Scenario: 原事件含额外敏感 JSON 字段
- **WHEN** 事件摘要中存在 subject、password、token、title、content 或未知嵌套字段
- **THEN** 列表与详情均不输出这些字段，不能仅依赖底层实体序列化注解

#### Scenario: 已知操作的前后值
- **WHEN** 查询用户部门/密级、空间归档、角色权限或文档搬迁等已定义操作详情
- **THEN** 仅返回该操作已批准的 ID、布尔值、密级数值、权限 code 及变更方向
- **AND** 不通过关联加载当前名称、正文或其他身份详情

#### Scenario: 历史载荷损坏或操作域未识别
- **WHEN** 原摘要不是预期对象、字段类型错误或没有已注册的摘要规则
- **THEN** 仍返回合法的事件元数据，并标记摘要不可用，不回显原字符串或抛出解析细节

### Requirement: 审计读取必须保持实时撤权和可辨认故障

每次列表与详情请求 SHALL 重新判定当前资格，并返回 `Cache-Control: no-store`。游标、上一页结果与会话中旧角色 MUST NOT 延续已撤销权限。数据库或授权依赖故障 SHALL 返回安全的 503；错误和日志 MUST 不记录查询用户名、请求标识原值或原始审计载荷。审计查询本身 MUST 不递归创建可被同一查询不断追加的新审计事件。

#### Scenario: 翻页前撤销全局审计权限
- **WHEN** 第一次查询后权限被撤销，操作者继续使用原游标或事件 ID
- **THEN** 新请求返回 403，不继续输出历史权限下的记录

#### Scenario: 数据库访问失败
- **WHEN** 审计查询无法可靠完成
- **THEN** 返回 503，不把故障伪装为成功空页，也不输出 SQL、连接信息或原始异常

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
