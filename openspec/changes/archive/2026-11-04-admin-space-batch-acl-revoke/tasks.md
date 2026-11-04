# Tasks

测试范围仅限本地隔离单元测试，使用 Mock、Stub、内存路由和模拟 DOM 替代依赖；不包含集成测试、跨服务联调、端到端或浏览器验收，不包含需要启动或连接 PostgreSQL/ParadeDB、Redis、MinIO、Docker、后端服务或前端开发服务器的任务，也不包含对 README、Markdown、注释、规格或文案的内容断言任务。说明文档作为交付物更新，不作为断言对象。真实锁互斥、事务原子性与并发行为保留为未验证限制，不列入本变更待办。

## 1. 后端：操作域、审计投影与请求锁

- [x] 1.1 在 `AuditScope` 新增 `SPACE_ACL_BATCH_REVOKE("space-acl")`，在 `AuditSummaryProjector.rules` 补全 case，投影 `spaceId`（ID）、`removedCount`、`absentCount`（COUNT），不展开 `items`；在 `AuditSummaryProjectorTest` 的全操作域用例补充该操作域的生产形状，并新增用例验证计数投影、负数计数退化为摘要不可用、既有批量授予投影不变。验证：`mvn -o -pl kb-app -am test '-Dtest=AuditSummaryProjectorTest' '-Dsurefire.failIfNoSpecifiedTests=false'` 通过。
- [x] 1.2 在 `SpaceMapper` 新增 `lockBatchRequest(key)`，写法与 `ParseDeadLetterMapper.lockReplayRequest` 一致（`pg_advisory_xact_lock(hashtextextended(#{key}, 0))`，带同样的 `@Options`）。验证：`mvn -o -pl kb-app -am test-compile '-DskipTests'` 通过；行为由 2.x 与 3.x 的调用顺序断言覆盖。
- [x] 1.3 更新 `docs/admin-query-api.md`：说明 `SPACE_ACL_BATCH_REVOKE` 的 code、targetType、targetId、摘要字段，以及逐条条目只保留在审计快照中。

## 2. 后端：批量授予重放收紧

- [x] 2.1 调整 `SpaceAclService.grantBatch`：资格判定后、`audit.existing` 前调用 `lockBatchRequest("SPACE_ACL_BATCH_GRANT:" + requestId)`；重放时快照解析失败、`spaceId` 不一致、`items` 缺失，或快照条目推导出的主体集合与权限位集合与本次请求不一致，均返回 409「请求标识已用于不同操作」，不返回快照内容；成功路径改用 `appendSuccess`。扩 `SpaceAclServiceTest`：相同内容重放返回首次回执且不写入；仅顺序不同视为相同；主体或权限位不同返回 409 且不调用写入 mapper；快照不可读返回 409；形状校验或资格失败时不加锁；加锁先于审计查询（`InOrder`）；既有批量授予测试保持通过。
- [x] 2.2 更新 `docs/admin-space-document-web.md` 中批量授予的重放说明：同标识不同内容返回 409，核对同样适用于既有快照。

## 3. 后端：批量撤销服务与控制器

- [x] 3.1 新增请求与回执 record：`SpaceAclBatchRevokeRequest(items)`、条目（principalType、principalId、permission）与 `SpaceAclBatchRevokeResult(spaceId, items[Item(..., removed)], removedCount, absentCount, replayed)`，类型级与组件级 `@Schema` 说明齐全。验证：`mvn -o -pl kb-app -am test-compile '-DskipTests'` 通过；请求绑定与回执字段由 3.4 的控制器测试覆盖。
- [x] 3.2 实现 `SpaceAclService.revokeBatch` 的形状校验与资格：条目 1–300、主体类型非空、主体 ID 为正、权限位经 `normalizePermission`、（主体, 权限位）不重复，违规返回 400 并说明原因与上限；`spaceId` 非正或缺少请求标识返回 400；条目含 manage 时 `requireGrantManage`，否则 `requireSpaceManager`；不校验角色存在性。扩 `SpaceAclServiceTest`：空列表、301 条、重复条目、`share`、缺字段均 400 且不加锁、不调用 `revokeAcl`；空间管理员含 manage 被拒且不写入；不含 manage 被接受；停用操作者被拒。
- [x] 3.3 实现写入、回执、审计与重放：加锁（`"SPACE_ACL_BATCH_REVOKE:" + requestId`）→ 重放核对（操作者、空间、快照空间、条目集合；任一不符 409）→ 逐条 `revokeAcl` 以影响行数得出 `removed` → 构建回执 → `appendSuccess` 写入 `{spaceId, items, removedCount, absentCount}`。扩 `SpaceAclServiceTest`：只撤销列出条目（未列出的组合不调用 `revokeAcl`）、部分不存在的计数、已删除角色的条目照常撤销、撤销全部 manage 被接受、审计快照内容、相同内容重放返回首次回执且不调用 `revokeAcl`、条目集合不同或操作者/空间不同返回 409。
- [x] 3.4 在 `SpaceController` 新增 `POST /{spaceId}/acl/batch-revoke`，请求标识只取 `X-Request-Id`，路径参数 `@Positive`，`@Operation` 说明资格、上限、幂等与"撤销不存在条目视为成功"。扩 `SpaceControllerTest`：路由与请求绑定、回执 JSON 含 `items[].removed`、`removedCount`、`absentCount`、`replayed`、缺少请求标识 400、请求体中的 `requestId` 不被采用、非正整数路径 400。
- [x] 3.5 更新 `docs/admin-space-document-web.md` 的写入错误与回执说明：批量撤销的路由、400/403/409 分类、逐条 `removed` 回执与计数、幂等重放语义，并说明单条撤销契约不变、服务端不阻止撤光 manage。

## 4. 前端：写意图与类型

- [x] 4.1 在 `kb-web/src/admin/spaceApi.ts` 新增 `BatchRevokeItem`、`BatchRevokeResult` 与 `spaceWrites.revokeBatch(spaceId, items)`：路径 `/acl/batch-revoke`，按（主体类型, 主体 ID, 权限位）去重，校验 1–300 条与权限位白名单，超限或非法抛出可展示的 400 错误，主体 ID 以精确整数回传。扩 `src/admin/spaceApi.test.ts`：路径与方法、请求体形状、大整数 ID、去重、0 条与 301 条拒绝、非法权限位拒绝。

## 5. 前端：空间授权列表多选与批量撤销

- [x] 5.1 改造 `AclPanel.tsx` 空间模式的授权列表：可撤销行带复选框（manage 行需 `space:grant-manage`），选择键为 `principalType:principalId:permission`，经 `useResourceNotice` 保存；渲染时按当前列表过滤；显示已选条数与批量撤销按钮；单条撤销按钮保留；文档模式不渲染复选框。扩 `src/admin/space-pages.test.tsx`：无 grant-manage 时 manage 行不可选、选择跨资格重核保留、重新读取后消失的条目移出选择、文档面板无多选。
- [x] 5.2 实现批量撤销确认弹窗：scope `spaces:{id}:revoke-batch`，共用 `useResourceWriteBindings()`；摘要显示条目数与 manage 条目数，0 条或超过 300 条禁用提交；按决定 7 的条件显示"本人直接 manage"与"全部直接 manage"提示且不阻止提交；执行函数将 404/405 改写为"当前服务不支持批量撤销"并禁用批量撤销按钮。扩 `space-pages.test.tsx`：摘要计数、提交意图只含所选条目、两类提示的出现与不出现、404/405 后单条撤销与授予仍可用且不逐条补发、400/403 整体拒绝保留选择。
- [x] 5.3 实现回执与结果不确定处理：成功后逐条显示已移除/原本不存在及两类计数，清空选择与批量授予回执，调用 `refresh()` 与 `onChanged()`；结果不确定只提供按原内容重试、重新读取核对或放弃核对，关闭弹窗后重开仍待核对，身份切换清空。扩 `space-pages.test.tsx`：回执渲染与计数、成功后重读列表与资格、重试使用原请求标识与原内容、关闭重开保持待核对、放弃核对后可发起新批次。
- [x] 5.4 更新 `kb-web/README.md` 与 `docs/admin-space-document-web.md` 的面板交互说明：空间授权列表多选与批量撤销、确认提示的识别范围（仅本人 USER 类型直接 manage）、旧服务不可用时的表现、文档例外保持单条。

## 6. 构建与范围检查

- [x] 6.1 执行后端定向单元测试：`mvn -o -pl kb-app -am test '-Dtest=SpaceAclServiceTest,SpaceControllerTest,AuditSummaryProjectorTest,ModuleBoundaryTest' '-Dsurefire.failIfNoSpecifiedTests=false'`，再执行 `mvn -o -pl kb-app -am package '-DskipTests'`，记录实际执行的类名与结果；不运行 PostgreSQL 冒烟测试或集成测试，不设置永久排除项。
- [x] 6.2 在 `kb-web` 执行 `npx vitest run src/admin/spaceApi.test.ts src/admin/space-pages.test.tsx src/admin/hooks.test.tsx`，再执行 `npm run typecheck` 与 `npm run build`，记录结果。
- [x] 6.3 执行 `git diff --check`，确认未新增数据库迁移、权限位、文档例外批量或跨空间批量，单条授予与撤销接口未改动。

## Workflow follow-up

- 实施与上述检查完成后归档本变更，并核对归档结果。
