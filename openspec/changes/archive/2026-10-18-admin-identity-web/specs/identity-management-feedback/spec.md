# Spec Delta

## Purpose

为账号、部门和角色管理写操作提供可辨认且兼容的错误反馈，使客户端能够说明版本、引用和管理员保护等拒绝原因，同时保持既有状态、响应结构、授权及信息披露边界。

## ADDED Requirements

### Requirement: 指定身份管理写接口必须支持增量错误投影

`/api/admin/identity` 下既有账号创建、部门/状态/密码/密级调整、用户角色绑定操作，部门生命周期写操作及角色生命周期/权限写操作 SHALL 对已识别的领域失败支持可选顶层 `reasonCode`。原 HTTP 状态及 `code/message/data` 字段语义 MUST 保持不变。读取、外部身份写操作、旧 `/api/identity` 别名及其他业务接口不纳入本投影。

#### Scenario: 管理写请求发生版本冲突
- **WHEN** 指定写入口抛出已识别的版本冲突
- **THEN** HTTP 仍为 409，code 仍为既有失败值，data 仍为 null，新增 reasonCode=INVALID_VERSION
- **AND** message 使用安全的固定提示，不携带对象当前字段或底层异常

#### Scenario: 同前缀但不属于本次范围
- **WHEN** 用户读取身份目录或调用外部身份绑定入口
- **THEN** 保持原错误响应契约，不因路径前缀相同被整体加入新投影

### Requirement: 冲突分类必须来自可信业务判断

领域失败 SHALL 支持 `INVALID_VERSION`、`DEPARTMENT_NOT_EMPTY`、`ROLE_IN_USE`、`LAST_MANAGER_REQUIRED`、`IDENTIFIER_CONFLICT`、`INVALID_HIERARCHY`、`INVALID_PARENT` 和通用 `CONFLICT`。分类 MUST 来自实际业务检查，不根据异常文案或客户端声明猜测；无法细分时保留通用反馈，不改变失败状态或触发额外实体查询。

#### Scenario: 多种 409 原因
- **WHEN** 分别发生部门仍有启用依赖、角色仍有引用、会破坏最后管理员不变量和重复用户名/编码
- **THEN** 分别返回 DEPARTMENT_NOT_EMPTY、ROLE_IN_USE、LAST_MANAGER_REQUIRED、IDENTIFIER_CONFLICT
- **AND** 不改变各操作已有拒绝条件、事务效果和审计行为

#### Scenario: 缺少来源分类
- **WHEN** 某个现有冲突尚不能提供可靠的细分原因
- **THEN** 采用通用 CONFLICT 或原错误响应，不从 reason、message、SQL 或自由文本提取分类

### Requirement: 基础身份错误必须保持状态与最小信息

已识别的基础原因 SHALL 仅使用 `INVALID_REQUEST`、`FORBIDDEN`、`DISABLED`、`NOT_FOUND`、`UNAUTHORIZED`、`PASSWORD_CHANGE_REQUIRED`，保持各自已有 HTTP 状态和鉴权先后顺序。错误 MUST 不回显密码、哈希、subject、令牌、原始输入、SQL、堆栈、目标引用数量或远程错误体；未知服务故障不得冒充已识别业务冲突。

#### Scenario: 无资格请求携带不存在目标
- **WHEN** 请求在现有权限门禁处被拒绝
- **THEN** 继续返回原有禁止状态，不为补充错误原因查询目标存在性、引用或其他受保护事实

#### Scenario: 输入或异常带敏感字段
- **WHEN** 管理请求或底层异常包含密码、令牌、恶意名称或数据库细节
- **THEN** 新错误投影只包含固定分类及安全提示，不复制这些值到响应或新增诊断日志

### Requirement: 客户端必须按接口和状态白名单消费错误原因

用户端 SHALL 仅对指定管理写请求消费 reasonCode，并同时验证 HTTP 状态、失败封装和有限分类是否匹配。已知分类使用固定中文文案；缺少、未知、类型不符、状态不匹配、非 JSON 或过大错误响应 SHALL 回退为原 HTTP 提示，不把错误响应当成功数据，也不直接展示服务器任意 message。

#### Scenario: 已知冲突可解释
- **WHEN** 角色删除返回合法 409 及 ROLE_IN_USE
- **THEN** 页面提示先处理用户绑定或空间/文档授权引用，不只要求刷新，也不臆测具体数量

#### Scenario: 错误分类不可信
- **WHEN** 错误响应提供未知 reasonCode、HTML、异常 message，或把 LAST_MANAGER_REQUIRED 与 503 混用
- **THEN** 页面显示对应 HTTP 的安全通用提示，不解释为可靠业务分类

### Requirement: 增量错误反馈必须允许独立升级与回退

新前端 MUST 可连接未提供 reasonCode 的旧后端，新后端 MUST 保留旧前端使用的成功响应、HTTP 状态及失败字段。新投影不改变公共响应类型、数据库、权限目录、角色归属或任何业务请求体，也不得通过错误原因改变 401/403 的既有会话处理。

#### Scenario: 新前端连接旧后端
- **WHEN** 管理请求只返回既有 R 失败响应
- **THEN** 页面仍按原 HTTP 状态显示失败，查询和提交协议继续可用

#### Scenario: 回退前端或使用旧别名
- **WHEN** 部署恢复旧静态产物，或调用方继续使用 `/api/identity` 的既有入口
- **THEN** 原有调用方式和响应消费保持可用，不需要回退数据库或删除权限绑定
