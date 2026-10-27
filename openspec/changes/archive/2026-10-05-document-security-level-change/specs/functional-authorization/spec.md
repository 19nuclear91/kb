# Spec Delta

## ADDED Requirements

### Requirement: 文档密级调整必须具有独立功能权限

服务端权限目录 SHALL 包含 `document:security-level:write` 及其用途说明，保护文档密级管理查询和调整。该权限 MUST 与
`identity:clearance:write`、空间 manage、edit 及 `space:manage-all` 分开，按既有角色权限并集精确匹配；角色名称或 code 本身
MUST NOT 作为运行时资格依据。

#### Scenario: 权限目录可查询新权限

- **WHEN** 具备角色读取资格的用户查询服务端功能权限目录
- **THEN** 返回 `document:security-level:write` 及文档密级管理用途说明

#### Scenario: 普通角色显式绑定专用权限

- **WHEN** 非 `ROLE_ADMIN` 角色被显式绑定该权限
- **THEN** 其启用持有者获得文档密级管理资格，操作仍受当前密级上限约束
- **AND** 不因此获得用户密级调整或正文阅读资格

#### Scenario: 管理员角色不再绑定专用权限

- **WHEN** 用户虽然持有 `ROLE_ADMIN`，但其全部角色的权限并集中不含该专用权限
- **THEN** 文档密级管理请求返回 `403`

### Requirement: 新增密级权限的初始化不得恢复已撤销授权

升级迁移 SHALL 向迁移时已存在的 `ROLE_ADMIN` 角色一次性绑定文档密级专用权限；首次引导新管理员角色时 SHALL
将该权限纳入服务端完整目录授予。既有角色在迁移后撤销该权限，后续启动或重复引导 MUST NOT 自动恢复；其他角色不得因升级而隐式获得该权限。

#### Scenario: 已有管理员升级

- **WHEN** 已有 `ROLE_ADMIN` 的数据库首次应用本变更迁移
- **THEN** 该角色获得新权限，其他既有绑定保持不变
- **AND** 新权限不修改任何用户的密级

#### Scenario: 空库首次管理员引导

- **WHEN** 迁移时没有管理员角色，后续通过正式引导创建管理员角色
- **THEN** 新角色的权限集中包含文档密级专用权限

#### Scenario: 撤销后重启

- **WHEN** 管理员明确撤销该权限后重启服务或重新运行引导
- **THEN** 既有角色仍不具有该权限，不因角色 code 被自动补齐
