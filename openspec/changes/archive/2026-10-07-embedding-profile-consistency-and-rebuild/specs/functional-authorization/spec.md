## ADDED Requirements

### Requirement: embedding 管理必须使用独立功能资格

服务端权限目录 SHALL 定义 `embedding:read` 和 `embedding:rebuild`；前者保护 profile/状态/进度查询，后者保护发起与恢复。两项
MUST 按既有角色权限并集精确匹配，不相互蕴含，也不由角色 code、空间管理、密级管理或死信处置资格代替。管理资格 MUST
不授予正文读取与下载权限。

#### Scenario: 只持读取资格

- **WHEN** 用户只有 embedding:read
- **THEN** 可查询脱敏状态，发起或恢复返回 403

#### Scenario: 只持其他管理资格

- **WHEN** 用户持有空间管理或解析死信重放权限，但没有 embedding:rebuild
- **THEN** 重建请求返回 403，状态、向量与成功审计均不改变

#### Scenario: 资格被撤销后重放请求

- **WHEN** 首次请求成功后操作者资格被撤销，再次提交相同标识
- **THEN** 返回 403，不因匹配幂等记录而返回受保护回执

#### Scenario: 无会话或身份停用

- **WHEN** 未认证用户，或已认证但用户/主部门已停用的主体访问管理接口
- **THEN** 前者返回 401，后者返回 403，不泄露作业是否存在

#### Scenario: 全局运维与正文隔离

- **WHEN** 用户具有 embedding:rebuild 但没有某文档的 view/download
- **THEN** 可发起预配置目标的全局维护，但不能通过管理响应或既有读接口取得该文档正文

### Requirement: embedding 权限初始化不得恢复已撤销绑定

升级迁移 SHALL 向当时已存在的 ROLE_ADMIN 一次性绑定两项 embedding 权限；空库引导新角色时纳入完整权限目录。后续启动、重建或再次引导
MUST NOT 自动补回既有角色已撤销的绑定，其他角色 MUST 不因升级隐式获权。

#### Scenario: 既有管理员升级

- **WHEN** 数据库首次应用本变更迁移
- **THEN** 当时存在的 ROLE_ADMIN 获得两项绑定，其他既有绑定不变

#### Scenario: 撤销后重启

- **WHEN** 角色的 embedding 权限已被明确撤销后服务重启或执行引导
- **THEN** 权限仍保持撤销，不能凭角色 code 执行管理操作

#### Scenario: 新空库管理员引导

- **WHEN** 首次引导创建新的管理员角色
- **THEN** 新角色完整权限集中包含两项 embedding 权限
