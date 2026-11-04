# Tasks

测试范围仅限本地隔离单元测试，使用 Mock、Stub、内存路由和模拟 DOM 替代依赖；不包含集成测试、跨服务联调、端到端或浏览器验收，不包含需要启动或连接 PostgreSQL/ParadeDB、Redis、MinIO、Docker、后端服务或前端开发服务器的任务，也不包含对 README、Markdown、注释、规格或文案的内容断言任务。说明文档作为交付物更新，不作为断言对象。真实事务原子性与并发行为保留为未验证限制，不列入本变更待办。

## 1. 后端：操作域与审计投影

- [x] 1.1 在 `AuditScope` 新增 `SPACE_ACL_BATCH_GRANT`（含既有命名风格的操作域 code），并在 `AuditSummaryProjector.rules` 补全对应 case，投影 `spaceId`、`grantedCount`、`alreadyGrantedCount`、`clearanceBlockedPrincipalCount`；扩 `AuditSummaryProjectorTest`，验证新操作域按计数投影、不展开逐条主体，且既有操作域投影不变。验证：`mvn -o -pl kb-app -am test '-Dtest=AuditSummaryProjectorTest' '-Dsurefire.failIfNoSpecifiedTests=false'` 通过。
- [x] 1.2 更新 `docs/admin-query-api.md`，说明新操作域的 code、可检索方式与摘要字段，并说明逐条主体只留在审计快照中。

## 2. 后端：批量授予服务

- [x] 2.1 在 `SpaceAclService` 实现批量授予的请求形状校验：主体列表 1–50、权限位列表 1–6 且仅限 view/download/edit/review/comment/manage、主体与权限位均不得重复、列表不得为空，违规返回 400 并在消息中说明原因与上限。扩 `SpaceAclServiceTest`，逐条覆盖空列表、51 个主体、重复主体、重复权限位与 `share` 等未执行权限位，并断言校验失败时不调用写入 mapper。
- [x] 2.2 实现整批资格判定与主体校验：权限位含 manage 时整批走 `requireGrantManage`，否则走 `requireSpaceManager`；对 ROLE 主体复用 `requireRoleReference`，且必须在资格判定之后执行；已停用用户或主部门停用的用户被拒绝。扩 `SpaceAclServiceTest`，覆盖含 manage 的批次被空间管理员拒绝且不写入、不含 manage 的批次被接受、角色不存在导致整批拒绝、停用操作者拒绝。
- [x] 2.3 实现写入与回执：对去重后的笛卡尔积逐条 `countAcl` 并在不存在时 `grantAcl`，跳过已存在条目；按主体计算 `clearanceBlockedPrincipalCount`（仅对至少有一条新写入条目的主体按 `countClearanceBlocked` 现有口径判定）；构建含逐条 `alreadyGranted`、`grantedCount`、`alreadyGrantedCount` 的回执；写入一条含 `spaceId`、`items[]`、计数的审计快照。扩 `SpaceAclServiceTest`，覆盖部分已存在、多主体密级受阻计数按主体而非条目、审计快照内容、以及授权不改密级。
- [x] 2.4 实现幂等重放：命中 `audit.existing(SPACE_ACL_BATCH_GRANT, requestId)` 时解析记录的 `afterState` 重建逐条回执与计数并标记 replayed，不重新查询 ACL、不重算受阻数、不再写入。扩 `SpaceAclServiceTest`，覆盖相同标识重放的返回值与首次一致、授权集合与审计数量不变；不回退为单条路径的 `listAcl().get(0)` 实现。

## 3. 后端：控制器与接口契约

- [x] 3.1 新增批量授予请求与回执 record（带 `@Schema` 字段说明），在 `SpaceController` 新增 `POST /api/admin/spaces/{spaceId}/acl/batch`，缺少 `X-Request-Id` 返回 400，路径参数非正整数返回 400。扩 `SpaceControllerTest` 覆盖路由、请求绑定、缺失请求标识与各错误码；确认新增 record 的说明使 `RecordOpenApiSchemaTest` 通过。
- [x] 3.2 更新 `docs/admin-space-document-web.md` 的写入错误与回执说明：补充批量授予的 400/403 分类、逐条回执字段、按主体计的密级受阻数与幂等重放语义，并明确单条授予与撤销契约不变。

## 4. 前端：批量写意图与多选选择器

- [x] 4.1 在 `kb-web/src/admin/spaceApi.ts` 新增 `BatchGrantResult` 类型与 `spaceWrites.grantBatch(spaceId, principals, permissions)`，构造意图时对主体与权限位去重并校验 50/6 上限，超限抛出可展示的错误。扩 `spaceApi.test.ts`，验证意图路径、请求体形状、精确整数回传、去重与上限拒绝。
- [x] 4.2 新增多选主体选择器组件，复用现有候选读取（主体类型、搜索、游标续页），维护独立已选清单：类型或搜索键变化只重置候选列表、保留已选；重复选择不新增；可逐个移除；候选读取失败时保留已选且不从中新增。为该组件补独立单元测试（模拟 DOM 与请求替身），覆盖上述行为与大整数 ID。
- [x] 4.3 改造 `AclPanel.tsx` 的空间模式：接入多选主体与复选权限位（manage 仍受 `space:grant-manage` 控制显示），确认前显示主体数、权限位数与乘积总条目数，主体超 50 或条目为 0 时禁用提交，整批经单次 `useAdminWrite` 提交；按回执逐条标注已存在并显示计数与按主体计的密级受阻数；批量路由返回 404/405 时显示"当前服务不支持批量授予"并保留已选内容，不逐条补发。扩 `space-pages.test.tsx` 覆盖确认摘要、提交意图内容、回执渲染、上限禁用、整体拒绝与结果不确定，以及 404/405 分流。

## 5. 前端：文档面板回归与说明

- [x] 5.1 确认文档例外授权路径不变：`AclPanel` 的 `kind === 'documents'` 继续使用 `PrincipalPicker` 的单选单条流程，不调用批量意图。在 `space-pages.test.tsx` 补回归断言，验证文档例外授予使用单条接口、候选失败仍清空选择、不出现多选确认摘要。
- [x] 5.2 更新 `kb-web/README.md` 与 `docs/admin-space-document-web.md` 的面板交互说明：空间授权改为多主体多权限位、已选跨类型保留、批量确认与逐条回执、单条授予在批量接口不可用时的表现，以及文档例外保持单选。

## 6. 构建与范围检查

- [x] 6.1 执行后端定向单元测试：`mvn -o -pl kb-app -am test '-Dtest=SpaceAclServiceTest,SpaceControllerTest,AuditSummaryProjectorTest,ModuleBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false'`，随后执行 `mvn -o -pl kb-app -am package '-DskipTests'`；记录实际执行的类名与结果；通过 `-Dtest=` 白名单只运行上述类，不运行 `SpaceAuthorizationPostgresSmokeTest`、集成测试或任何需要外部组件的测试，也不为此设置永久排除项。
- [x] 6.2 在 `kb-web` 按实际新增文件名执行本变更的前端单元测试（预期含 `src/admin/spaceApi.test.ts`、`src/admin/space-pages.test.tsx` 及新增多选组件测试），再执行 `npm run typecheck` 与 `npm run build`，记录结果。
- [x] 6.3 执行 `git diff --check`，并确认未新增数据库迁移、权限位、批量撤销、跨空间批量或文档例外批量；执行 `openspec validate admin-space-batch-acl-grant --strict` 通过。

## Workflow follow-up

- 实施与上述检查完成后归档本变更，并核对归档结果。
