# Proposal

## Why

后端已提供独立授权、固定时间窗口和安全摘要的审计查询 API，管理端仍缺少可操作的查询入口。增加审计查询页面，使持有 `audit:read` 的人员能够定位管理、登录和企业 SSO 事件，并核对已记录的操作结果。

## What Changes

- 在现有 `kb-web` 管理区增加 `/admin/audits`、审计导航和登录后的安全返回路径，以当前 `audit:read` 独立开放页面。
- 提供常用与高级筛选：时间、结果、操作域、操作、操作者 ID、目标类型与 ID、请求标识、本地登录用户名；保持既有 API 的精确匹配语义。
- 提供固定查询窗口、每次 20/50/100 条的游标继续读取和显式刷新，展示服务端实际时间范围与已加载数量。
- 以事件 UUID 打开详情抽屉，展示安全元数据及字段前后变化，准确区分未知摘要、空变更、空主体及精确整数。
- 延续现有会话与管理权限生命周期，在撤权、退出、后台恢复和切换查询时清理受保护状态并隔离迟到响应。
- 增加 API、分页状态、独立页面、权限组件和导航函数的进程内单元测试，依赖均使用替身，更新管理端使用说明。

## Capabilities

### New Capabilities

- `admin-web-audits`：审计筛选、固定窗口分页、安全详情及查询状态恢复与隔离。

### Modified Capabilities

- `admin-web-access`：将 `/admin/audits` 纳入受保护管理路由、安全登录返回白名单和独立 `audit:read` 导航规则。

## Impact

- 主要涉及 `kb-web/src/admin` 下的新页面、审计 API 适配和专用查询状态，以及 `App.tsx`、`navigation.ts`、`admin/access.tsx` 的入口接入。
- 复用 `GET /api/admin/audits` 与 `GET /api/admin/audits/{eventId}`、`Session`、现有无损 JSON 解码及 Ant Design；后端 `audit-query` 契约、权限目录和数据库结构保持原样。
- 首版沿用上一轮探索建议，仅消费 `sys_identity_audit` 的现有安全投影，覆盖其中已记录的管理、本地登录、SSO 登录与绑定事件；AI Redis 审计和独立 Embedding 操作不并入本次范围。
- 本次不引入依赖、部署单元、导出、审计写入或跨实体名称查询；产品界面明确当前审计覆盖范围。
- 实施验收仅运行明确文件白名单的进程内单元测试、类型检查和静态构建；不包含集成测试、需要启动应用或数据库／Redis 等外部组件的任务，以及针对说明文档、README、注释、规格或示例的内容断言测试。
