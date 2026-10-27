# Proposal

## Why

后端已提供空间生命周期、空间授权、文档例外授权、文档搬迁和文档密级管理接口，但 `kb-web` 管理区只覆盖账号、部门和角色，日常授权运营仍依赖接口工具。这类操作频率高、影响面大，且资格来自"对具体空间的 manage"而非单一功能权限 code，现有按 code 开放的管理区无法承接；冲突类拒绝目前只返回通用分类，页面无法给出可操作的提示。

## What Changes

- 在管理区新增"知识空间"分组：`/admin/spaces`、`/admin/spaces/:spaceId` 和共享文档管理面板 `/admin/documents/:documentId`，复用既有会话、受保护路由、写入生命周期与游标读取约定。
- 空间入口按资源资格开放：存在可管理空间，或持有 `space:create`、`space:manage-all` 时显示；可管理空间作为与本人权限并列的事实来源，随会话 epoch 隔离并在进入页面、403 和写入成功后重新核对。
- 空间页提供可管理空间目录、新建（`space:create`）、名称与描述修改、归档/取消归档、仅限无文档空间的删除；空间详情提供授权列表、按用户/角色/部门授予与撤销（manage 级另需 `space:grant-manage`），以及空间内文档的 ID 级管理清单。
- 文档管理面板由三个互不依赖的读取组成：管理清单确定所属空间（manage 范围），密级状态（`document:security-level:write`），用户端详情补充标题（view，无权时仅显示文档 ID）。面板提供例外授权、搬迁和密级调整，各区块按自身资格独立开放。
- 用户端文档详情页增加"管理此文档"入口：所属空间可管理或持有 `document:security-level:write` 时显示，仅为交互提示，不代表授权，实际操作由服务端重新判定。
- 授权主体只通过资源上下文候选接口选择，不提供手填内部 ID；搬迁目标限于可管理且未归档的空间；密级调整须填写原因。归档、搬迁、授出 manage 和密级调整在提交前展示后果说明，能由已知事实判断的拒绝在提交前提示。
- 为上述四类管理写入口扩展可选 `reasonCode`：新增 `SPACE_NOT_EMPTY`、`SOURCE_MISMATCH`、`TARGET_ARCHIVED`、`ALREADY_IN_TARGET`；空间编码冲突归入既有 `IDENTIFIER_CONFLICT`，文档密级版本过期归入既有 `INVALID_VERSION`。HTTP 状态、`code/message/data` 字段及成功响应保持不变，前端继续兼容未提供分类的后端。
- 已知限制：管理清单按设计不返回标题；只持有密级权限、不具备文档 view 的操作者，以及归档空间内的文档，无法经用户端入口到达密级调整，首期不提供 ID 手填入口。
- 首期不含：Embedding 与解析死信运维页、全局审计页、外部身份管理、批量授权、文档删除入口。文档授权与搬迁接口以 404/403 先后可区分文档是否存在的问题另行记录，不在本变更处理。

## Capabilities

### New Capabilities

- `admin-web-spaces`：可管理空间目录、空间生命周期、空间授权管理及空间内文档 ID 级清单界面。
- `admin-web-documents`：单文档管理面板，包括例外授权、搬迁和密级调整，以及按资格独立加载的区块与标题补充。

### Modified Capabilities

- `admin-web-access`：管理区由三个固定身份页扩展为包含按资源资格开放的空间分组；增加可管理空间事实来源，403 后同时核对功能权限与可管理空间。
- `identity-management-feedback`：`reasonCode` 投影范围从 `/api/admin/identity` 写入口扩展到空间、空间授权、文档例外授权、搬迁和文档密级写入口，并增加四个冲突分类。
- `user-web-documents`：文档详情页增加按提示开放、不代表授权的管理入口。

`space-authorization`、`document-relocation`、`document-security-level`、`functional-authorization` 的资格、状态码和业务语义不变，不需增量规格。

## Impact

- `kb-web`：新增空间与文档管理页面、可管理空间上下文、路由与管理导航分组、相关 DTO 与写意图、`reasonCode` 白名单扩展，以及用户端详情页入口；沿用现有 React/TypeScript/Vite/Ant Design 依赖。
- `kb-common` / `kb-app`：`IdentityConflictReason` 增加四个值；在空间写服务、搬迁服务和文档密级服务的对应冲突处补原因元数据；`IdentityManagementFeedbackAdvice` 覆盖 `SpaceController`、`DocumentAclController`、`DocumentRelocationController`、`DocumentSecurityLevelController` 的指定写入口。不新增数据库迁移、权限 code、接口路径或基础设施依赖，复用现有 `/api/admin/**` 网关路由。
- 验证范围沿用上一管理变更：内存业务单元测试、模拟 DOM、standalone MockMvc、ArchUnit、前端类型检查及静态构建；不含集成测试、端到端测试或真实数据库、Redis、MinIO、浏览器环境。
