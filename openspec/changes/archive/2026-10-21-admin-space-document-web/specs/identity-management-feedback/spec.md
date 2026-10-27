# Spec Delta

## MODIFIED Requirements

### Requirement: 指定身份管理写接口必须支持增量错误投影

`/api/admin/identity` 下既有账号创建、部门/状态/密码/密级调整、用户角色绑定操作，部门生命周期写操作及角色生命周期/权限写操作，以及知识空间创建、修改、归档、取消归档、删除，空间授权授予与撤销，文档例外授权授予与撤销，文档搬迁和文档密级调整写操作 SHALL 对已识别的领域失败支持可选顶层 `reasonCode`。原 HTTP 状态及 `code/message/data` 字段语义 MUST 保持不变。读取（含空间目录、空间授权列表、授权候选、管理文档清单与密级状态）、外部身份写操作、旧 `/api/identity` 别名、死信与向量运维接口及其他业务接口不纳入本投影。

#### Scenario: 管理写请求发生版本冲突
- **WHEN** 指定写入口抛出已识别的版本冲突
- **THEN** HTTP 仍为 409，code 仍为既有失败值，data 仍为 null，新增 reasonCode=INVALID_VERSION
- **AND** message 使用安全的固定提示，不携带对象当前字段或底层异常

#### Scenario: 同前缀但不属于本次范围
- **WHEN** 用户读取身份目录或调用外部身份绑定入口
- **THEN** 保持原错误响应契约，不因路径前缀相同被整体加入新投影

#### Scenario: 删除仍有文档的空间
- **WHEN** 空间删除因空间仍有文档被拒绝
- **THEN** HTTP 仍为 409，data 仍为 null，新增 reasonCode=SPACE_NOT_EMPTY
- **AND** message 使用固定提示，不包含文档数量

#### Scenario: 空间读取不纳入投影
- **WHEN** 空间目录、空间授权列表或授权候选读取失败
- **THEN** 保持原错误响应契约，不新增 reasonCode

### Requirement: 冲突分类必须来自可信业务判断

领域失败 SHALL 支持 `INVALID_VERSION`、`DEPARTMENT_NOT_EMPTY`、`ROLE_IN_USE`、`LAST_MANAGER_REQUIRED`、`IDENTIFIER_CONFLICT`、`INVALID_HIERARCHY`、`INVALID_PARENT`、`SPACE_NOT_EMPTY`、`SOURCE_MISMATCH`、`TARGET_ARCHIVED`、`ALREADY_IN_TARGET` 和通用 `CONFLICT`。分类 MUST 来自实际业务检查，不根据异常文案或客户端声明猜测；无法细分时保留通用反馈，不改变失败状态或触发额外实体查询。

#### Scenario: 多种 409 原因
- **WHEN** 分别发生部门仍有启用依赖、角色仍有引用、会破坏最后管理员不变量和重复用户名/编码
- **THEN** 分别返回 DEPARTMENT_NOT_EMPTY、ROLE_IN_USE、LAST_MANAGER_REQUIRED、IDENTIFIER_CONFLICT
- **AND** 不改变各操作已有拒绝条件、事务效果和审计行为

#### Scenario: 缺少来源分类
- **WHEN** 某个现有冲突尚不能提供可靠的细分原因
- **THEN** 采用通用 CONFLICT 或原错误响应，不从 reason、message、SQL 或自由文本提取分类

#### Scenario: 空间与文档冲突
- **WHEN** 分别发生删除仍有文档的空间、搬迁声明来源与实际归属不符、搬迁目标空间已归档、文档已在目标空间、空间编码重复和密级版本过期
- **THEN** 分别返回 SPACE_NOT_EMPTY、SOURCE_MISMATCH、TARGET_ARCHIVED、ALREADY_IN_TARGET、IDENTIFIER_CONFLICT、INVALID_VERSION
- **AND** 不改变各操作已有拒绝条件、事务效果、幂等重放和审计行为

#### Scenario: 空间与文档冲突无法细分
- **WHEN** 发生密级与当前相同、请求标识复用于不同内容或搬迁期间文档被并发删除
- **THEN** 返回通用 CONFLICT，HTTP 状态保持不变
