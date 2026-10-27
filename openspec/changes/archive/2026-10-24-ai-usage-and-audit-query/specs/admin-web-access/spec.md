# Spec Delta

## ADDED Requirements

### Requirement: AI 管理入口必须分别按用量和审计资格开放

用户端 SHALL 增加 `/admin/ai/usage` 和 `/admin/ai/audits`，分别按当前 ai:usage:read、ai:audit:read 开放导航与数据读取。两条路径沿用现有登录、强制改密、后台恢复和资料清理规则，允许直接访问、刷新及合法站内登录返回；返回地址只接受固定页面路径，不携带筛选。原审计页继续使用 audit:read 及既有管理/登录事件范围。

#### Scenario: 登录后返回 AI 用量页
- **WHEN** 未登录用户打开 `/admin/ai/usage` 并完成登录和必要改密
- **THEN** 可返回该路径，重新核对 ai:usage:read 后才读取统计

#### Scenario: 分别显示导航
- **WHEN** 用户只有 ai:audit:read，没有 ai:usage:read、audit:read 或身份管理权限
- **THEN** 显示 AI 调用审计入口，其他未获授权页面不预取数据，原管理审计入口仍不可用

#### Scenario: 空间目录故障
- **WHEN** 当前 AI 功能权限已确认，可管理空间目录读取失败
- **THEN** AI 导航及页面继续按各自权限开放，不依赖空间管理资格

#### Scenario: 带筛选或外部返回路径
- **WHEN** 登录返回地址为站外 URL、`/admin/ai/audits?callId=...` 或未注册的 AI 子路径
- **THEN** 不将其作为合法返回地址，沿用现有安全回退

#### Scenario: 后台恢复和撤权
- **WHEN** AI 页面从后台恢复，或新权限事实已不包含当前页面资格
- **THEN** 原统计、筛选、游标和详情已清理，只有重新确认资格后才按默认条件读取
