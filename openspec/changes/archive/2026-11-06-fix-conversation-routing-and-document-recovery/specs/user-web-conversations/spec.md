# Spec Delta

## ADDED Requirements

### Requirement: 开发与生产网关必须保持会话入口路由一致

网关在显式 dev 和 prod 配置下 SHALL 将 `/api/conversations` 及其子路径转发到业务应用，将 `/api/ai/**` 转发到 AI 服务。两种配置 MUST 保留内部 `/internal/**` 不对浏览器转发的边界，以及流式路径独立于普通业务响应超时和缓冲的配置。路由存在不代表会话功能已启用，不改变既有认证、功能开关或服务端权限判定。

#### Scenario: 开发环境启用会话
- **WHEN** 开发环境完成既有会话配置，浏览器访问会话列表、创建、详情、历史或删除入口
- **THEN** 对应 `/api/conversations` 及其子路径可匹配到业务应用路由，不因开发配置覆盖路由列表而丢失
- **AND** 路由不替代下游鉴权，不改变功能关闭时的既有行为

#### Scenario: 多轮生成仍走流式路由
- **WHEN** dev 或 prod 收到 `/api/ai/conversations/9/turns` 请求
- **THEN** 匹配 AI 路由，不匹配会话管理业务路由
- **AND** 保留独立流式响应超时及禁缓冲响应头配置

#### Scenario: 内部轮次路径不能经公开网关访问
- **WHEN** 浏览器在 dev 或 prod 请求 `/internal/conversations/finish`
- **THEN** 不匹配任何公开业务或 AI 路由，不向下游内部轮次接口转发
