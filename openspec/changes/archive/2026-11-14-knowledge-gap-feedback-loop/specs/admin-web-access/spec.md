# Spec Delta

## ADDED Requirements

### Requirement: 知识问题受理入口必须按独立读取资格开放

管理导航 SHALL 增加 `/admin/knowledge-gaps`，详情为 `/admin/knowledge-gaps/:gapId`，仅当前有效权限含 `knowledge:gap:read` 时加载页面与数据。不依赖可管理空间或身份目录，不用 assign、handle、close、角色名称或其他管理资格替代。原有懒加载、错误边界、资格恢复和不自动重放写入的规则同样适用。

#### Scenario: 有独立资格而空间目录不可用
- **WHEN** read 已确认，而可管理空间或身份目录不可用
- **THEN** 受理导航及页面仍按自身资格开放，不额外查询这些目录

#### Scenario: 只持写权限或模块已缓存
- **WHEN** 当前只有 assign 或 close，没有 read，即使工作台代码曾加载
- **THEN** 不挂载受理管理页，不读取队列或详情；普通本人问题入口另按自身规则可用

#### Scenario: 页面下载期间撤权
- **WHEN** 新受理页面代码加载期间 read 被撤销
- **THEN** 遮蔽并清理原内容，迟到模块不发起旧问题请求，导航及退出继续可用

### Requirement: 知识问题受理登录返回必须拒绝内容与未注册路径

登录返回 SHALL 只接受固定受理路径及合法正整数 gapId 的详情，不接受问题描述、人员查询、筛选、任意子路径、片段或站外 URL。恢复后重新核对身份和 read；错误地址使用既有文档中心安全回退。新路径不能改变其他已注册业务路径或默认登录落点。

#### Scenario: 合法详情返回
- **WHEN** 未登录用户访问 `/admin/knowledge-gaps/123` 并完成登录和必要改密
- **THEN** 只有当前 read 资格允许时才进入该详情并请求其资料，不因原地址授予访问

#### Scenario: 非法受理返回
- **WHEN** 返回地址含 description、名称查询、筛选，或 gapId 非法、未注册后缀、外部地址
- **THEN** 不恢复该地址，不把输入放入日志或持久化，按既有安全路径回退
