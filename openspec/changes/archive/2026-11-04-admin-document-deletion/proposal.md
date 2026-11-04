# Proposal

## Why

空间 manage 不蕴含 edit，用户端 `DELETE /api/documents/{id}` 又只按 edit 判定并排除已归档空间，因此空间管理员和持有 `space:manage-all` 的全局管理员今天都无法直接删除其管理范围内的文档——唯一的办法是先把 edit 授给自己所在部门再走用户端，这是一次被审计记录下来的自我扩权。同时，只持有 `document:security-level:write` 的密级管理员没有任何入口到达文档管理面板，已归档空间或无 view 资格的文档也难以从用户端定位。本变更提供按管理资格判定、可审计、幂等的管理端整文档删除，并补一个按 ID 直达面板的入口。

## What Changes

- 新增 `DELETE /api/admin/documents/{documentId}`：按文档**当前所属空间**的 manage 或 `space:manage-all` 判定资格，不以 view、edit、例外授权或上传者身份替代；已归档空间中的文档可删除。效果与用户端删除一致：软删除整份文档及全部版本、取消未完成解析任务与审核轮次、提交后尽力清理原件；不提供恢复。
- 写请求必须携带 `X-Request-Id`，重复提交按审计幂等规则返回首次回执；新增审计操作域 `DOCUMENT_ADMIN_DELETE`，审计与回执只含文档 ID 与所属空间，不含标题、文件名或正文。不存在、已删除或无权管理统一返回 404；归属在判定后发生变化返回冲突。
- 文档管理面板新增"删除文档"区块，仅在管理文档清单返回该文档时开放；确认说明影响整份文档及全部版本、取消未完成处理与审核、例外授权随之失效、不可恢复。确认与提交期间禁止面板其他写入。成功后重新读取面板事实；结果不确定时只允许按原标识重试或重新读取核对，不自动重发。
- 新增 `/admin/documents` 索引页与管理导航入口"文档管理面板"，在存在可管理空间、持有 `space:create`、`space:manage-all` 或 `document:security-level:write` 时显示；页面只提供文档 ID 输入并跳转到 `/admin/documents/{id}`，不发出管理请求、不保存历史。
- 不在本次范围：批量删除、单版本删除、回收站或恢复、物理擦除、修改用户端删除接口与 `canDelete` 能力、在空间文档清单逐行提供删除、改变管理文档清单的三字段投影。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `document-administration`：新增管理端整文档删除要求，包括按当前所属空间 manage 判定、归档空间可达、幂等回执、单条审计与最小披露。
- `admin-web-documents`：新增文档面板删除区块的确认、互斥、结果核对要求，以及按 ID 到达面板的索引页要求。
- `admin-web-access`：受保护路由清单增加 `/admin/documents`；管理导航增加文档管理面板入口及其显示资格，修正"只有密级调整权限"场景。

## Impact

- 后端：`DocumentManagementService` 抽出不含资格判定的删除主体供两条入口复用，保持既有任务→文档的锁顺序与提交后清理；新增管理端删除控制器与软删除的归属前置条件；`AuditScope` 新增操作域并在 `AuditSummaryProjector` 补充投影。用户端删除、ACL 表、权限目录与数据库结构不变，不新增迁移。
- 前端：`kb-web/src/admin` 的 `DocumentPanelPage.tsx` 新增删除区块、新增索引页组件、`access.tsx` 导航项、路由注册、`spaceApi.ts` 写意图与类型，以及对应单元测试。
- 文档：`docs/admin-space-document-web.md` 的首期限制去掉"文档删除"与"手填文档 ID"，`docs/admin-query-api.md` 补充新操作域，`kb-web/README.md` 同步入口说明。
- 部署顺序为后端先行。新前端遇到旧服务时，删除请求得到 404 后重新读取管理清单：文档仍在清单中则提示当前服务不支持管理端删除，不再出现则按当前不可访问处理；索引页不依赖新接口。
