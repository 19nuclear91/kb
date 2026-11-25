# Spec Delta

## ADDED Requirements

### Requirement: 权限诊断必须具有独立精确功能资格

服务端固定权限目录 SHALL 定义 `authorization:diagnose`，用于资源上下文中的指定用户权限解释和只读预演，按当前有效操作者全部角色的持久权限并集精确匹配。空间 manage、space:manage-all、身份读取／写入、审计资格或管理员角色名称 MUST NOT 隐含该资格；该资格不授予资源管理、文档阅读、完整身份目录或实际授权写入能力。

#### Scenario: 独立授予普通角色
- **WHEN** 普通角色被绑定 authorization:diagnose
- **THEN** 其有效持有者获得诊断功能资格，但每个目标仍须另有当前资源管理资格

#### Scenario: 空间管理员未取得诊断资格
- **WHEN** 用户可管理空间而没有 authorization:diagnose
- **THEN** 诊断和预演返回 403，原有授权管理按原规则继续使用

#### Scenario: 全局管理者未取得诊断资格
- **WHEN** 用户持有 space:manage-all 或角色名为 ROLE_ADMIN，但全部角色中无 authorization:diagnose
- **THEN** 新诊断入口拒绝，不由名称或全局管理资格推导新权限

#### Scenario: 下一次请求撤销生效
- **WHEN** 用户所有角色中的诊断权限均被撤销
- **THEN** 下一次诊断、预演和诊断目标搜索返回 403，既有会话不保留资格

### Requirement: 诊断和预演必须同时满足真实资源管理范围

诊断候选、解释及预演 SHALL 在取得 authorization:diagnose 后核对目标空间 manage 或 space:manage-all；文档使用实际当前所属空间。预演包含 manage 授予／撤销时，还 MUST 具备 space:grant-manage。拟议授权不能参与操作者准入，诊断权限不能让操作者预演本来无权执行的授权操作；本规则不修改既有正式授权接口的资格契约。

#### Scenario: 只有诊断资格
- **WHEN** 用户有 authorization:diagnose，但无目标空间 manage 或 space:manage-all
- **THEN** 返回与资源不存在相同的 404，不读取目标用户和授权来源

#### Scenario: 有授出 manage 但无目标管理范围
- **WHEN** 用户有 authorization:diagnose 与 space:grant-manage，但无目标空间管理资格
- **THEN** 预演仍返回 404，不因授出资格扩大诊断范围

#### Scenario: 预演给自己增加 manage
- **WHEN** 操作者试图用预演草稿中的自授 manage 满足当前管理门禁
- **THEN** 准入只按真实授权判定，假设结果不用于鉴权

#### Scenario: 预演撤销本人最后一条管理来源
- **WHEN** 操作者真实具备全部预演资格，草稿将移除其最后一条有效 manage 来源
- **THEN** 可以返回假设失去管理的结果，本次只读请求不撤销实际资格，正式提交另行确认

### Requirement: 诊断权限初始化必须一次性且保留目录兼容约束

新增迁移 SHALL 仅向迁移时已有 ROLE_ADMIN 一次性绑定 authorization:diagnose；空库正式引导新管理员角色时纳入服务端完整目录。其他既有角色不自动获得资格，重启或重复引导不恢复已撤销绑定。新增绑定前 MUST 排空不认识新 code 的旧 app，保留未知权限拒绝启动约束，不能通过忽略新 code 或删除绑定伪装兼容回退。

#### Scenario: 存量首次迁移
- **WHEN** 新权限迁移应用到包含管理员及普通角色的数据库
- **THEN** 仅当时 ROLE_ADMIN 获得新绑定，已有 ACL、用户密级及其他权限不变

#### Scenario: 空库引导
- **WHEN** 迁移时尚无管理员角色，随后正式引导创建首个管理员
- **THEN** 新角色权限集包含 authorization:diagnose，不依赖开发种子

#### Scenario: 撤销后重启
- **WHEN** 管理方撤销诊断权限后重启或重复引导
- **THEN** 既有角色保持撤销，不依据角色 code 补回

#### Scenario: 旧实例接触新权限
- **WHEN** 不认识 authorization:diagnose 的旧 app 在新增绑定后启动
- **THEN** 仍按现有目录校验拒绝启动，发布说明明确禁止该混部或直接回退方式
