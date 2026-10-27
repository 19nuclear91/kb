## MODIFIED Requirements

### Requirement: 空间授权只能使用已被执行的权限位

系统 SHALL 只接受 view、download、edit、review 作为可委派授予的空间权限位，manage 另受全局管理员限制。review 只表示空间审核人池成员资格，不隐含 view、download、edit 或足够密级；文档例外不接受 review。对系统中没有任何功能执行的权限位，授权请求 MUST 被拒绝，而不得被静默接受。

#### Scenario: 授予受支持的权限位

- **WHEN** 操作者在其可管理的空间上把 view 授予某主体
- **THEN** 授权生效，该主体的可见范围包含该空间

#### Scenario: 授予未被执行的权限位

- **WHEN** 操作者请求授予尚未实现的 comment
- **THEN** 系统返回参数错误并说明该权限位尚未被执行
- **AND** 不写入任何授权记录

#### Scenario: 委派空间审核人池
- **WHEN** 合格空间管理员向用户、角色或部门授予空间 review
- **THEN** 授权按既有主体与部门继承语义生效，实际审批仍须 view、密级、启用状态及非自审检查

#### Scenario: 管理资格不代替审核
- **WHEN** 用户仅具有空间 manage 或 space:manage-all
- **THEN** 不因此进入合格审核人池，不因管理资格直接通过审核

#### Scenario: 文档级审核例外被拒绝
- **WHEN** 管理员尝试为某文档单独授予 review
- **THEN** 返回参数错误，不写入例外或绕过空间审核人池

### Requirement: 归档空间必须退出读取范围但保留管理可达性

系统 SHALL 使已归档空间不出现在 view、download、edit、review 的有效资源范围内，因此其文档不参与检索、列表、下载及审核处理。manage 的可见范围 MUST 仍包含已归档空间，使被委派的管理员能够取消归档。已有审核轮次不因归档自动批准，取消归档后仍按当次资源与版本规则读取。

#### Scenario: 归档后内容退出检索

- **WHEN** 某空间被归档
- **THEN** 其文档不再出现在检索结果、文档列表、详情、下载与预览中
- **AND** 变更对所有读取路径同时生效

#### Scenario: 归档后仍可管理

- **WHEN** 空间管理员在空间被归档后提交取消归档操作
- **THEN** 操作被接受
- **AND** 管理员不会因归档而永久失去对该空间的管理能力

#### Scenario: 取消归档恢复可见

- **WHEN** 已归档空间被取消归档
- **THEN** 其文档按原有 ACL、密级和版本发布规则重新可见
- **AND** 不需要重建任何授权记录

#### Scenario: 归档期间审核
- **WHEN** 有 review 的用户尝试读取待审正文、送审或批准
- **THEN** 按当前归档事实拒绝，旧待办和版本策略不构成例外

## ADDED Requirements

### Requirement: 审核授权必须保留文档例外的封闭范围

本阶段文档例外 SHALL 继续只接受 view、download、edit，不能授予 review、manage 或尚未实现的 comment。空间 review 可由空间管理员按现有资源管理规则授予和撤销，授出仍不可提升密级或自动授予阅读；搬迁继续清除全部文档例外。

#### Scenario: review 与文档 view 组合
- **WHEN** 用户通过文档 view 例外及空间 review 同时满足目标版本的当前条件
- **THEN** 可按审核规则参与该文档审核，不因此获得其他文档阅读权或文档级 review
