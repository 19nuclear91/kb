## ADDED Requirements

### Requirement: 运营报表入口必须按独立读取资格开放

用户端 SHALL 增加 `/admin/reports/operations`，仅在当前本人功能权限包含 operations:reports:read 时显示导航并加载概览。该入口沿用既有登录、强制改密、直接访问、刷新及资料清理规则，不依赖可管理空间、身份目录或 AI 开关。角色名称、AI／审计读取及任意管理写权限 MUST 不代替报表读取资格。

#### Scenario: 只有报表读取资格
- **WHEN** 用户只有 operations:reports:read，且空间目录查询失败
- **THEN** 报表导航及页面仍按自身资格开放，不要求或预取用户、部门、角色、空间和文档目录

#### Scenario: 没有报表资格直接访问
- **WHEN** 用户只有空间管理、ai:usage:read 或 ROLE_ADMIN 名称而当前权限不含报表 code
- **THEN** 报表导航不显示，直接访问显示无资格且不请求概览数据；已有独立 AI 入口不受影响

#### Scenario: 权限未确认或失败
- **WHEN** 本人权限仍加载或返回 503
- **THEN** 报表入口不按旧权限开放，页面区分加载与可重试故障，不把未知解释为成功空权限

### Requirement: 报表登录返回和恢复必须重新核对资格

报表登录返回 SHALL 只接受固定 `/admin/reports/operations`，不接受筛选、任意子路径、外部 URL 或目标身份。后台恢复、本人权限变化或 403 后 MUST 清除统计和筛选并重新核对当前资格；只有资格确认后按默认条件读取。有效会话仍可访问其他已授权页面，旧请求不能恢复已撤权报表。

#### Scenario: 登录后返回报表
- **WHEN** 未登录用户直接访问固定报表路径并完成登录及必要改密
- **THEN** 可返回该页，重新确认 operations:reports:read 后才读取统计，改密期间不预取

#### Scenario: 非法登录返回
- **WHEN** 返回值为 `/admin/reports/operations?from=...`、未注册子路径或站外 URL
- **THEN** 不将其作为合法报表返回地址，使用既有文档中心安全回退

#### Scenario: 恢复或权限撤销
- **WHEN** 报表页由后台恢复，或本人权限新事实已无报表资格
- **THEN** 原数据和筛选已清理，未重新获权前不请求概览，迟到响应不恢复原内容
