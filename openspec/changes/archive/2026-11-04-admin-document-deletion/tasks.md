# Tasks

测试范围仅限本地隔离单元测试，使用 Mock、Stub、内存路由和模拟 DOM 替代依赖；不包含集成测试、跨服务联调、端到端或浏览器验收，不包含需要启动或连接 PostgreSQL/ParadeDB、Redis、MinIO、Docker、后端服务或前端开发服务器的任务，也不包含对 README、Markdown、注释、规格或文案的内容断言任务。说明文档作为交付物更新，不作为断言对象。真实锁顺序、并发、提交后原件清理与对账保留为未验证限制，不列入本变更待办。

## 1. 后端：操作域与审计投影

- [x] 1.1 在 `AuditScope` 新增 `DOCUMENT_ADMIN_DELETE`（含既有命名风格的操作域 code），并在 `AuditSummaryProjector.rules` 补全对应 case，投影 `spaceId`（ID）与 `spaceArchived`（BOOL）；扩 `AuditSummaryProjectorTest`，验证新操作域只投影定位字段、不出现标题或存储键，且既有操作域投影不变。验证：`mvn -o -pl kb-app -am test '-Dtest=AuditSummaryProjectorTest' '-Dsurefire.failIfNoSpecifiedTests=false'` 通过。
- [x] 1.2 更新 `docs/admin-query-api.md`，说明新操作域的 code、可检索方式与摘要字段。

## 2. 后端：共用删除主体

- [x] 2.1 在 `DocumentMapper` 新增带 `space_id = :expectedSpaceId AND status <> 'deleted'` 前置条件的软删除语句，并保留原 `softDelete`；在 `DocumentManagementService` 把 `findObjectKeys` → `cancelTasks` → 软删除 → `reviewCancellation.cancelDocument` → 注册提交后 `cleanupObjects` 抽为公开方法，接收文档 ID、操作者与预期空间；前置条件更新 0 行时重读文档：已删除抛 404、归属变化抛 409。用户端 `delete` 改为先 edit 判定再调用该方法，对外行为不变。扩 `DocumentManagementServiceTest`：保留既有用户端删除回归（任务取消、审核取消、软删除、提交后清理、非事务分支），新增 `cancelTasks` 先于软删除的调用顺序断言、0 行时的 404/409 分流，以及清理失败不影响已删除状态。

## 3. 后端：管理端删除服务与控制器

- [x] 3.1 在 `space.service` 新建管理端删除服务：请求标识必填；命中 `audit.existing(DOCUMENT_ADMIN_DELETE, requestId)` 时从 `before_state` 取 `spaceId`、重新 `requireSpaceManager` 后返回 `{documentId, spaceId, replayed: true}`；否则以 `MetadataMapper.document(documentId)` 预读归属，不存在或已删除抛 404；用 `SpaceAuthorizer` 对实际归属判 manage 或 `space:manage-all`，无权抛与不存在同形的 404；停用用户或主部门停用拒绝；调用共用删除主体；写入 `before_state={documentId, spaceId, spaceArchived}`、`after_state={}` 的审计事件；回执只含文档 ID、空间 ID 与 replayed。新建对应服务单元测试（Mock mapper、authorizer、audit 与 `DocumentManagementService`），覆盖空间管理员无 edit 可删、仅 edit 得 404、已归档可删、停用拒绝、不存在与无权同形 404、已被用户端删除得 404 且不写审计、共用主体抛 409 时不写审计、相同标识重放不再写入且先核对资格、回执与审计不含标题。
- [x] 3.2 在 `space.web` 新建控制器提供 `DELETE /api/admin/documents/{documentId}`：路径参数非正整数返回 400，缺少 `X-Request-Id` 返回 400，回执 record 带 `@Schema` 字段说明，错误沿用既有管理写入口的投影。新建 standalone MockMvc 控制器单元测试覆盖路由、请求标识缺失、400/404/409 与成功回执形状；确认新增 record 使 `RecordOpenApiSchemaTest` 通过。
- [x] 3.3 更新 `docs/admin-space-document-web.md`：写入错误与回执说明补充管理端删除的 400/404/409 与回执字段，"首期限制"去掉"文档删除"，并明确软删除不释放空间删除条件（`countReferencedDocuments` 计入已删除文档）。

## 4. 前端：面板删除区块与互斥

- [x] 4.1 在 `kb-web/src/admin/spaceApi.ts` 新增 `DeleteDocumentResult` 类型与 `spaceWrites.deleteDocument(documentId)` 写意图（`DELETE`，无请求体，请求标识只在 `X-Request-Id`）。扩 `spaceApi.test.ts`，验证意图路径、方法、无请求体与精确整数回传。
- [x] 4.2 在 `DocumentPanelPage.tsx` 增加以 `session/epoch/documentId` 为键的面板写入协调状态，并新增"删除文档"区块：仅当管理清单返回该文档（`owner`）时显示，仅密级权限不显示；标题不可读时以文档 ID 标识；所属空间已归档仍可删；确认说明整份文档及全部版本、取消未完成处理与审核、例外授权失效、不可恢复；进入确认即禁用例外授予/撤销、搬迁与密级写入，其他写入在途时禁用删除提交，取消确认恢复。扩 `space-pages.test.tsx`，覆盖显示条件三种场景、确认文案、互斥与取消恢复，且不发出请求。
- [x] 4.3 实现删除结果处理：成功后显示"文档已删除"（不含标题）并重读管理清单、标题与各区块，`owner` 为空时显示无可用管理操作；404 时重读管理清单，仍包含该文档提示"当前服务不支持管理端删除"、不再包含按当前不可访问处理，两者都不声称由本次删除；409 提示归属已变化并重读；结果不确定时只提供按原标识重试、重新读取核对或放弃核对。扩 `space-pages.test.tsx`，用请求替身覆盖成功、404 两种分流、409 与结果不确定，并断言不自动重发。

## 5. 前端：索引页与导航入口

- [x] 5.1 新建 `DocumentsIndexPage`：文档 ID 输入与跳转按钮，用 `validTarget` 校验正整数且在 64 位有符号整数范围内，合法时 `navigate('/admin/documents/{id}')`，非法时页内提示且不跳转；页面不发出管理请求、不枚举文档、不保存历史。在 `App.tsx` 于 `/admin/documents/:documentId` 之前注册 `/admin/documents`，经 `AdminGuard` 以"存在可管理空间或持有 `space:create`、`space:manage-all`、`document:security-level:write`"为开放条件。新建索引页单元测试（内存路由与请求替身），覆盖合法跳转且无请求、`abc`/`0`/负数/超范围不跳转、无资格显示无访问资格、仅密级权限可用。
- [x] 5.2 在 `access.tsx` 增加导航项"文档管理面板"，`visible` 为 `canOpenSpaces(access) || access.has(perm.securityLevelWrite)`；扩 `access.test.tsx` 或 `access-spaces.test.tsx`，覆盖仅密级权限显示该入口而不显示知识空间入口、仅 `space:create` 显示、无资格不显示、入口显示不触发任何文档或空间请求，以及 `/admin/documents` 作为登录后站内返回地址重新核对资格。
- [x] 5.3 更新 `kb-web/README.md` 的管理区入口说明与 `docs/admin-space-document-web.md` 的"入口与资格"及"首期限制"（去掉"首期不提供手填文档 ID"），说明索引页只做跳转、资格由面板各区块独立判定。

## 6. 构建与范围检查

- [x] 6.1 执行后端定向单元测试：`mvn -o -pl kb-app -am test '-Dtest=DocumentManagementServiceTest,AuditSummaryProjectorTest,RecordOpenApiSchemaTest,ModuleBoundaryTest,<新建服务测试类>,<新建控制器测试类>' '-Dsurefire.failIfNoSpecifiedTests=false'`（按实际类名替换占位），随后执行 `mvn -o -pl kb-app -am package '-DskipTests'`；记录实际执行的类名与结果；通过 `-Dtest=` 白名单只运行上述类，不运行 `SpaceAuthorizationPostgresSmokeTest`、集成测试或任何需要外部组件的测试，也不为此设置永久排除项。
- [x] 6.2 在 `kb-web` 按实际文件名执行本变更的前端单元测试（预期含 `src/admin/spaceApi.test.ts`、`src/admin/space-pages.test.tsx`、`src/admin/access.test.tsx` 及新建索引页测试），再执行 `npm run typecheck` 与 `npm run build`，记录结果。
- [x] 6.3 执行 `git diff --check`，并确认未新增数据库迁移、权限位、批量删除、单版本删除、回收站或恢复入口，未改变用户端 `DELETE /api/documents/{id}` 与 `canDelete` 的对外契约，未改变管理清单三字段投影；执行 `openspec validate admin-document-deletion --strict` 通过。

## Workflow follow-up

- 实施与上述检查完成后归档本变更，并核对归档结果。
